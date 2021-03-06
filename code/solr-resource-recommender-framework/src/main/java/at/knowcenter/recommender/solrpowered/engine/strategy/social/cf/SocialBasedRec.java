package at.knowcenter.recommender.solrpowered.engine.strategy.social.cf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.FacetField.Count;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;

import at.knowcenter.recommender.solrpowered.engine.filtering.ContentFilter;
import at.knowcenter.recommender.solrpowered.engine.filtering.FriendsEvaluation;
import at.knowcenter.recommender.solrpowered.engine.strategy.RecommendStrategy;
import at.knowcenter.recommender.solrpowered.engine.strategy.StrategyType;
import at.knowcenter.recommender.solrpowered.engine.strategy.marketplace.cf.ReviewBasedRec;
import at.knowcenter.recommender.solrpowered.engine.utils.CFQueryBuilder;
import at.knowcenter.recommender.solrpowered.engine.utils.RecommendationQueryUtils;
import at.knowcenter.recommender.solrpowered.model.CustomerAction;
import at.knowcenter.recommender.solrpowered.model.SocialAction;
import at.knowcenter.recommender.solrpowered.services.SolrServiceContainer;
import at.knowcenter.recommender.solrpowered.services.impl.actions.RecommendQuery;
import at.knowcenter.recommender.solrpowered.services.impl.actions.RecommendResponse;
import at.knowcenter.recommender.solrpowered.services.impl.item.ItemQuery;

/**
 * Collaborative Filtering Recommender strategy
 * @author elacic
 *
 */
public class SocialBasedRec implements RecommendStrategy {

	public static int MAX_USER_OCCURENCE_COUNT = CFQueryBuilder.MAX_USER_OCCURENCE_COUNT;
	private List<String> alreadyBoughtProducts;
	private ContentFilter contentFilter;

	@Override
	public RecommendResponse recommend(RecommendQuery query, Integer maxReuslts){
		ModifiableSolrParams solrParams = new ModifiableSolrParams();
		QueryResponse response = null;
		RecommendResponse searchResponse = new RecommendResponse();
		
		long step0ElapsedTime = 0;
		long step1ElapsedTime;
		List<String> recommendations = new ArrayList<String>();

		try {
			// STEP 0 - get products from a user
			if (query.getUser() != null ) {
				if (query.getProductIds() == null || query.getProductIds().size() == 0) {
					if (alreadyBoughtProducts != null) {
						query.setProductIds(alreadyBoughtProducts);
					} else {
					}
				}
			}
			
			solrParams = getSTEP1Params(query.getUser());

			response = SolrServiceContainer.getInstance().getSocialActionService().getSolrServer().query(solrParams);
			step1ElapsedTime = response.getElapsedTime();
			
			List<String> users = new ArrayList<String>();
			List<Double> boostings = new ArrayList<Double>();
			
			List<SocialAction> socialUsers = response.getBeans(SocialAction.class);
			
			if (socialUsers.size() == 0) {
				searchResponse.setNumFound(0);
				searchResponse.setResultItems(recommendations);
				searchResponse.setElapsedTime(-1);
				return searchResponse;
			}
			
			SocialAction firstUser = socialUsers.get(0);
			if (firstUser.getUserId().equals(query.getUser())){
				socialUsers.remove(0);
			} else {
				firstUser = null;
			}
			
			for (SocialAction socialUser : socialUsers) {
				if (firstUser != null) {
					double jaccardSimilarity = calcJaccardSimilarity(firstUser, socialUser);
					boostings.add(jaccardSimilarity);
				} else {
					boostings.add(1.0);
				}
				
				users.add(socialUser.getUserId());
			}
			
			
			solrParams = getSTEP2Params(query, maxReuslts, users, boostings);
			// TODO Facet for confidence value
			response = SolrServiceContainer.getInstance().getRecommendService().getSolrServer().query(solrParams);
			// fill response object
			List<CustomerAction> beans = response.getBeans(CustomerAction.class);
			searchResponse.setResultItems(RecommendationQueryUtils.extractRecommendationIds(beans));
			searchResponse.setElapsedTime(step0ElapsedTime + step1ElapsedTime + response.getElapsedTime());

			SolrDocumentList docResults = response.getResults();
			searchResponse.setNumFound(docResults.getNumFound());
		} catch (SolrServerException e) {
			e.printStackTrace();
			searchResponse.setNumFound(0);
			searchResponse.setResultItems(recommendations);
			searchResponse.setElapsedTime(-1);
		}
		
		return searchResponse;
	}

	private double calcJaccardSimilarity(SocialAction firstUser,
			SocialAction socialUser) {
		List<String> firstUsersThatLikedMe = getList(firstUser.getUsersThatLikedMe());
		List<String> likedUsersIntersection = new ArrayList<String>(firstUsersThatLikedMe);
		List<String> otherUsersThatLikedMe = getList(socialUser.getUsersThatLikedMe());
		likedUsersIntersection.retainAll(otherUsersThatLikedMe);
		
		List<String> firstUsersThatCommentedOnMyPost = getList(firstUser.getUsersThatCommentedOnMyPost());
		List<String> commentedUsersIntersection = new ArrayList<String>(firstUsersThatCommentedOnMyPost);
		List<String> usersThatCommentedOnMyPost = getList(socialUser.getUsersThatCommentedOnMyPost());
		commentedUsersIntersection.retainAll(usersThatCommentedOnMyPost);
		
		double jaccardSimilarity = likedUsersIntersection.size() + commentedUsersIntersection.size();
		
		Set<String> uniqueUserUnion = new HashSet<String>(firstUsersThatLikedMe);
		uniqueUserUnion.addAll(otherUsersThatLikedMe);
		uniqueUserUnion.addAll(firstUsersThatCommentedOnMyPost);
		uniqueUserUnion.addAll(usersThatCommentedOnMyPost);
		
		jaccardSimilarity = jaccardSimilarity / uniqueUserUnion.size();
		return jaccardSimilarity;
	}

	private List<String> getList(List<String> usersThatCommentedOnMyPost) {
		if (usersThatCommentedOnMyPost == null) {
			usersThatCommentedOnMyPost = new ArrayList<String>();
		}
		return usersThatCommentedOnMyPost;
	}

	private ModifiableSolrParams getSTEP2Params(
			RecommendQuery query, Integer maxReuslts, List<String> users, List<Double> boostings) {
		ModifiableSolrParams solrParams = new ModifiableSolrParams();
		
		String queryString = createQueryToFindProdLikedBySimilarSocialUsers(users, boostings, contentFilter, MAX_USER_OCCURENCE_COUNT);
		
		String filterQueryString = 
				RecommendationQueryUtils.buildFilterForContentBasedFiltering(contentFilter);
		
		if (alreadyBoughtProducts != null && alreadyBoughtProducts.size() > 0) {
			if (filterQueryString.trim().length() > 0) {
				filterQueryString += " OR ";
			}
			filterQueryString += RecommendationQueryUtils.buildFilterForAlreadyBoughtProducts(alreadyBoughtProducts);
		}
		solrParams.set("q", queryString);
		solrParams.set("fq", filterQueryString);
		solrParams.set("fl", "id");
		solrParams.set("rows", maxReuslts);
		return solrParams;
	}
	
	public static String createQueryToFindProdLikedBySimilarSocialUsers(
			List<String> users, List<Double> boostings, ContentFilter contentFilter, int maxUserOccurence) {
		String query = createQueryToFindProdLikedBySimilarUsers(
				users, boostings, contentFilter, ReviewBasedRec.USERS_RATED_5_FIELD, maxUserOccurence, 1.0);
		query += " OR " + createQueryToFindProdLikedBySimilarUsers(
				users, boostings, contentFilter, ReviewBasedRec.USERS_RATED_4_FIELD, maxUserOccurence, 2.0);
		query += " OR " + createQueryToFindProdLikedBySimilarUsers(
				users, boostings, contentFilter, ReviewBasedRec.USERS_RATED_3_FIELD, maxUserOccurence, 3.0);
		query += " OR " + createQueryToFindProdLikedBySimilarUsers(
				users, boostings, contentFilter, ReviewBasedRec.USERS_RATED_2_FIELD, maxUserOccurence, 4.0);
		query += " OR " + createQueryToFindProdLikedBySimilarUsers(
				users, boostings, contentFilter, ReviewBasedRec.USERS_RATED_1_FIELD, maxUserOccurence, 5.0);
		
		return query;
	}
	
	public static String createQueryToFindProdLikedBySimilarUsers(
			List<String> users, 
			List<Double> boostings, 
			ContentFilter contentFilter,
			String usersFieldName,
			int maxUserOccurence,
			double weightDividor) {
		StringBuilder queryBuilder = new StringBuilder();

		queryBuilder.append(usersFieldName + ":(");
		
		if (weightDividor <= 0.0) {
			queryBuilder.append("\"\")");
			return queryBuilder.toString();
		}
		//  max users
		int userOccurenceCount = 0;
		
		for (String user : users) {
			if (userOccurenceCount >= maxUserOccurence) { break; }
			Double boosting = boostings.get(userOccurenceCount);

			queryBuilder.append("\"" + user + "\"^" + (boosting / weightDividor ) + " OR ");
			userOccurenceCount++;
		}
		
		if (queryBuilder.length() > (usersFieldName + ":(").length()){
			queryBuilder.replace(queryBuilder.length() - 3, queryBuilder.length(), "");
		} else {
			queryBuilder.append("\"\"");
		}
		
		queryBuilder.append(")");
		return queryBuilder.toString();
	}

	private ModifiableSolrParams getSTEP1Params(String user) {
		ModifiableSolrParams solrParams = new ModifiableSolrParams();
		String queryString = "id:(\"" + user + "\"^2) OR users_that_liked_me:(\"" + user + 
								"\") OR users_that_commented_on_my_post:(\"" + user + "\")";
		
		solrParams.set("q", queryString);
		return solrParams;
	}
	
	

	@Override
	public void setAlreadyPurchasedResources(List<String> alreadyBoughtProducts) {
		this.alreadyBoughtProducts = alreadyBoughtProducts;
	}

	@Override
	public List<String> getAlreadyBoughtProducts() {
		return alreadyBoughtProducts;
	}
	
	@Override
	public void setContentFiltering(ContentFilter contentFilter) {
		this.contentFilter = contentFilter;
	}
	
	@Override
	public StrategyType getStrategyType() {
		return StrategyType.CollaborativeFiltering;
	}

}
