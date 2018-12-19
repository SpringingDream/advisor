package com.springingdream.adviser.service;

import com.springingdream.adviser.model.Cluster;
import com.springingdream.adviser.model.Product;
import com.springingdream.adviser.model.UserPreferences;
import com.springingdream.adviser.payload.ApiResponse;
import com.springingdream.adviser.payload.PagedResponse;
import com.springingdream.adviser.repository.ClusterRepository;
import com.springingdream.adviser.repository.UserPreferencesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AdviserService {

    @Autowired
    UserPreferencesRepository userPreferencesRepository;

    @Autowired
    private ClusterRepository clusterRepository;

    // Just for now it's static, but should be calculated dynamically for each cluster
    private int clusterSize = 25;
    List<Cluster> clusters = new ArrayList<>();

    public AdviserService() {

    }

    public boolean init() {
        List<UserPreferences> userPreferences = getPreferences();
        for (UserPreferences user : userPreferences) {
            Cluster userCluser = user.getCluster();
            if (!clusters.contains(userCluser)) {
                clusters.add(userCluser);
            }
        }
//        System.out.println(clusters);
//        for (Cluster cluster : clusters) {
//            cluster.calcCentroid();
//            clusterRepository.save(cluster);
//        }
//        cluster(); //???
        return true;
    }

    /**
     * Gives recommendations to user based on ratings made by ALL other users.
     * Reasonable to use when user amount is not big enough to cluster them.
     * @param userId - user that receives recommendations
     * @param page - number of the current page of a recommendations list
     * @param size - amount of recommendations on each page
     */
    public ApiResponse getGeneralRecommendations(int userId, int page, int size) {
        List<UserPreferences> preferences = getPreferences();
        return getGeneralRecommendations(userId, page, size, preferences);
    }

    /**
     * Gives recommendations to user based on ratings made by other users IN USER'S CLUSTER.
     * @param userId - user that receives recommendations
     * @param page - number of the current page of a recommendations list
     * @param size - amount of recommendations on each page
     */
    public ApiResponse getGeneralRecommendationsByCluster(int userId, int page, int size) {
        List<UserPreferences> preferences = getPreferencesForCluster(userId);
        return getGeneralRecommendations(userId, page, size, preferences);
    }

    public ApiResponse getSimilar(Product product, long userId, int page, int size) {
        //TODO collaboration filtering
        return null;
    }

    public ApiResponse getSimilar(Product product, int page, int size) {
        //TODO one of content based methods.. or not?
        return null;
    }

    public ApiResponse getRelated(Product product, int page, int size) {
        //TODO one of content based methods.. or not?
        return null;
    }

    /**
     * Cluster or recluster users.
     * Users with no rated products remain in their previous cluster.
     */
    public ApiResponse cluster() {

        boolean changed = true;

        while (changed) {
            changed = false;

            List<List<UserPreferences>> clusterUsers = new ArrayList<>();

            for (Cluster cluster: clusters) {
                clusterUsers.add(new ArrayList<>(cluster.getUsers()));
            }

            for (int i = 0; i < clusters.size(); i++) {
                for (UserPreferences user: clusterUsers.get(i)) {
                    int minDistanceCluster = i;
                    double minDistance = clusters.get(minDistanceCluster).calcDistance(user);

                    for (int j = 0; j < clusters.size(); j++) {
                        if (j == i) continue;
                        double distance = clusters.get(j).calcDistance(user);
                        System.out.println("distance: " + distance);
                        if (distance < minDistance) {
                            minDistance = distance;
                            minDistanceCluster = j;
                        }
                    }

                    if (minDistanceCluster != i) {
                        clusters.get(i).remove(user);
                        Cluster newCluster = clusters.get(minDistanceCluster);
                        newCluster.add(user);

                        user.setCluster(newCluster);
                        clusterRepository.save(newCluster);
                        userPreferencesRepository.save(user);
                    }

                }
            }

        }

        return new ApiResponse<>(true, "Users are successfully clustered.");
    }

    public void addUserToCluster(int userId) {
        getPreference(userId).ifPresent(this::addUserToCluster);
    }

    /**
     * A silly way to add a user to cluster.
     */
    void addUserToCluster(UserPreferences user) {

        boolean added = false;
        for (Cluster cluster: clusters) {
            if (cluster.getSize() < clusterSize) {
                cluster.add(user);
                userPreferencesRepository.setCluster(cluster.getId(), user.getOwnerId());
                added = true;
                break;
            }
        }

        if (!added) {
            Cluster newCluster = new Cluster();
            newCluster.setId(clusters.size());
            newCluster.add(user);
            userPreferencesRepository.setCluster(newCluster.getId(), user.getOwnerId());
            clusters.add(newCluster);
        }

        cluster();

    }

    private ApiResponse getGeneralRecommendations(int userId, int page, int size,
                                                                     List<UserPreferences> preferences) {
        List<Long> recommendations = recommend(userId, preferences);

//        List<Product> products = ProductsAPI.getProductsByIdIn(recommendations);

        if (recommendations == null) {
            return new ApiResponse<>(false, "Nothing to recommend");
        }

        int totalElements = recommendations.size();
        int totalPages = totalElements / size + 1;
        boolean last = false;

//        List<ProductResponse> productResponses = products.stream()
//                .map(ModelMapper::mapProductToProductResponse).collect(Collectors.toList());

        if ((page + 1) * size > totalElements) {
            recommendations = recommendations.subList(page * size, totalElements);
            last = true;
        } else {
            recommendations = recommendations.subList(page * size, (page + 1) * size);
        }

        return new ApiResponse<>(true,
                new PagedResponse<>(recommendations, page, size, totalElements, totalPages, last));
    }

    /**
     * Collaboration filtering algorithm
     * TODO Java doc for this
     */
    List<Long> recommend(int userId, List<UserPreferences> preferences) {

        UserPreferences userPreferences = null;
        for (UserPreferences user : preferences) {
            if (user.getOwnerId() == userId) {
                userPreferences = user;
                break;
            }
        }
        if (userPreferences == null) {
            throw new IllegalArgumentException("User with id " + userId + " not found in given preferences");
        }


        Map<Long, Double> rank = new HashMap<>();

        for (UserPreferences otherUserPref : preferences) {
            if (otherUserPref.getOwnerId() != userId) {
                double similarity = calcSimilarity(userPreferences, otherUserPref);

                if (similarity == 0.0) {
                    continue;
                }

                for (long productId : otherUserPref.getPreferences().keySet()) {
                    if (!userPreferences.contains(productId)) {
                        if (!rank.containsKey(productId)) {
                            rank.put(productId, 0.0);
                        }
                        double newValue = rank.get(productId) + similarity * otherUserPref.getProductRating(productId);
                        rank.put(productId, newValue);
                    }

                }
            }
        }

        List<Long> result = new ArrayList<>();

        rank.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).forEach((entry) -> result.add(entry.getKey()));

        Collections.reverse(result);
        return result;
    }

    /**
     * Similarity function for two users.
     * The order of values in `preferences` and `otherPreferences` doesn't matter. The result will be the same for the same pair.
     * @param preferences - one of the users whose similarity we want to calculate.
     * @param otherPreferences - another user  whose similarity we want to calculate.
     * @return - the bigger return value is - the more similar user are.
     */
    double calcSimilarity(UserPreferences preferences, UserPreferences otherPreferences) {
        double result = 1.0; // To prevent division by zero in case of 100% similarity

        List<Long> commonProducts = new ArrayList<>();

        for (long product : preferences.getPreferences().keySet()) {
            for (long otherProduct : otherPreferences.getPreferences().keySet()) {
                if (product == otherProduct) {
                    commonProducts.add(product);
                    break;
                }
            }
        }

        for (long product : commonProducts) {
            result += Math.abs(preferences.getProductRating(product) - otherPreferences.getProductRating(product));
        }

        if (!commonProducts.isEmpty()) {
            result /= commonProducts.size();
        }

        return 1 / result;
    }

    void setClusterSize(int clusterSize) {
        this.clusterSize = clusterSize;
    }

    /**
     * Get preferences of all users in the same cluster with given.
     * @param userId - id of the user whose cluster preferences will be returned
     * @return - list of UserPreferences in the cluster
     */
    private List<UserPreferences> getPreferencesForCluster(int userId) {
        long clusterId = userPreferencesRepository.getUsersClusterId(userId);
        return userPreferencesRepository.getClusterPreferences(clusterId);
    }

    private List<UserPreferences> getPreferences() {
        return userPreferencesRepository.findAll();
    }

    private Optional<UserPreferences> getPreference(int userId) {
        return userPreferencesRepository.findById(userId);
    }

}
