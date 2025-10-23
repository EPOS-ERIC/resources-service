package org.epos.api.core.distributions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;
import org.apache.commons.codec.digest.DigestUtils;
import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.DataServiceProvider;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.DiscoveryItem.DiscoveryItemBuilder;
import org.epos.api.beans.NodeFilters;
import org.epos.api.beans.SearchResponse;
import org.epos.api.core.AvailableFormatsGeneration;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.ZabbixExecutor;
import org.epos.api.core.filtersearch.DistributionFilterSearch;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.Node;
import org.epos.api.routines.DatabaseConnections;
import org.epos.eposdatamodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import model.StatusType;

public class DistributionSearchGenerationJPA {

	private static final Logger LOGGER = LoggerFactory.getLogger(DistributionSearchGenerationJPA.class);
	private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/resources/details/";
	private static final String PARAMETER__SCIENCE_DOMAIN = "sciencedomains";
	private static final String PARAMETER__SERVICE_TYPE = "servicetypes";

	public static SearchResponse generate(Map<String, Object> parameters, User user) {
		LOGGER.info("Requests start - JPA method");
		long startTime = System.currentTimeMillis();

		// user will be not null only if the user is a member of the backoffice
		boolean isBackofficeUser = user != null;

		List<StatusType> versions = new ArrayList<>();

		if (isBackofficeUser && parameters.containsKey("versioningStatus")) {
			Arrays.stream(parameters.get("versioningStatus").toString().split(",")).forEach(version -> {
				versions.add(StatusType.valueOf(version));
			});
		} else {
			versions.add(StatusType.PUBLISHED);
		}

		List<DataProduct> dataproducts = new ArrayList<>();
		Map<String, User> userMap = DatabaseConnections.retrieveUserMap();
		//Map<String, List<AvailableFormat>> formats = AvailableFormatsGeneration.getFormats();

		//for (DataProduct dataProduct : DatabaseConnections.getInstance().getDataproducts()) {
		for (DataProduct dataProduct : (List<DataProduct>) AbstractAPI.retrieveAPI(EntityNames.DATAPRODUCT.name()).retrieveAll()){
			if (dataProduct != null) {
				if (versions.contains(StatusType.PUBLISHED) && dataProduct.getStatus().equals(StatusType.PUBLISHED)) {
					dataproducts.add(dataProduct);
					continue;
				}
				// If the user exists and in the query parameters there is the status of the
				// current dataProduct
				if (isBackofficeUser && user != null && versions.contains(dataProduct.getStatus())) {
					// If the user is an admin or the editor of this dataproducts
					if (user.getIsAdmin() || dataProduct.getEditorId().equals(user.getAuthIdentifier())) {
						LOGGER.debug("[VersioningStatus] Found dataproduct {} {} {} {}", dataProduct.getEditorId(),
								user.getAuthIdentifier(), dataProduct.getStatus(), dataProduct.getInstanceId());
						// show it
						dataproducts.add(dataProduct);
					}
				}
			}
		}

		//List<Category> categoryList1 = (List<Category>) AbstractAPI.retrieveAPI(EntityNames.CATEGORY.name()).retrieveAll();

		LOGGER.info("Apply filter using input parameters: {}", parameters.toString());
		// Apply filtering
		dataproducts = DistributionFilterSearch.doFilters(dataproducts, parameters);

		Set<DiscoveryItem> discoveryMap = new HashSet<DiscoveryItem>();

		LOGGER.info("Start for each");

		Set<String> keywords = new HashSet<>();
		Set<Category> scienceDomains = new HashSet<>();
		Set<Category> serviceTypes = new HashSet<>();
		Set<Organization> organizationsEntityIds = new HashSet<>();

		for (var dataproduct : dataproducts) {
			Set<String> facetsDataProviders = new HashSet<>();
			List<String> categoryList = new ArrayList<>();

			// DATA PRODUCT
			if (dataproduct.getCategory() != null) {
				for (var linkedEntity : dataproduct.getCategory()) {
					Category category = (Category) LinkedEntityAPI.retrieveFromLinkedEntity(linkedEntity);
					if (Objects.nonNull(category)) {
						if (category.getUid().contains("category:"))
							categoryList.add(category.getUid());
						else {
							scienceDomains.add(category);
						}
					}
				}
			}

			if (dataproduct.getPublisher() != null) {
				for (var linkedEntity : dataproduct.getPublisher()) {
					Organization organization = (Organization) LinkedEntityAPI.retrieveFromLinkedEntity(linkedEntity);
					if (Objects.nonNull(organization)) {
						if (organization.getLegalName() != null) {
							facetsDataProviders.add(String.join(",", organization.getLegalName()));
						}
						organizationsEntityIds.add(organization);
					}
				}
			}

			// Keywords
			keywords.addAll(Arrays.stream(Optional.ofNullable(dataproduct.getKeywords())
					.orElse("").split(",\t"))
					.map(String::toLowerCase)
					.map(String::trim)
					.collect(Collectors.toSet()));

			if (dataproduct.getDistribution() == null) {
				continue;
			}

			for (var linkedEntity : dataproduct.getDistribution()) {
				Distribution distribution = (Distribution) LinkedEntityAPI.retrieveFromLinkedEntity(linkedEntity);
				if (Objects.isNull(distribution)) {
					continue;
				}

				Set<String> facetsServiceProviders = new HashSet<>();

				// AVAILABLE FORMATS
				List<AvailableFormat> availableFormats = AvailableFormatsGeneration.generate(distribution);//formats.get(distribution.getInstanceId());//AvailableFormatsGeneration.generate(distribution);
                List<DataServiceProvider> dataServiceProviderList = new ArrayList<>();

				if (distribution.getAccessService() != null) {
					distribution.getAccessService().forEach(linkedEntity1 -> {
						WebService webService = (WebService) LinkedEntityAPI.retrieveFromLinkedEntity(linkedEntity1);
						if (Objects.nonNull(webService)) {
							if (webService.getProvider() != null) {
								Organization organization = (Organization) LinkedEntityAPI.retrieveFromLinkedEntity(webService.getProvider());
								if (Objects.nonNull(organization)) {
									if (organization.getLegalName() != null)
										facetsServiceProviders
												.add(String.join(",", organization.getLegalName()));
									organizationsEntityIds.add(organization);
								}
                                List<DataServiceProvider> serviceProviders = DataServiceProviderGeneration.getProviders(List.of(organization));
                                if (!serviceProviders.isEmpty()){
                                    dataServiceProviderList.add(serviceProviders.getFirst());
                                }
							}
							// Service Types
							if (webService.getCategory() != null) {
								webService.getCategory()
										.forEach(linkedEntity2 -> {
											Category category = (Category) LinkedEntityAPI.retrieveFromLinkedEntity(linkedEntity2);
											if(Objects.nonNull(category)) serviceTypes.add(category);
										});
							}
						}
					});
				}


				DiscoveryItemBuilder discoveryItemBuilder = new DiscoveryItemBuilder(distribution.getInstanceId(),
						EnvironmentVariables.API_HOST + API_PATH_DETAILS + distribution.getInstanceId(),
						EnvironmentVariables.API_HOST + API_PATH_DETAILS + distribution.getInstanceId()
								+ "?extended=true")
						.uid(distribution.getUid())
						.metaId(distribution.getMetaId())
						.title(distribution.getTitle() != null
								? String.join(";", distribution.getTitle())
								: null)
						.description(distribution.getDescription() != null
								? String.join(";", distribution.getDescription())
								: null)
                        .dataServiceProvider(dataServiceProviderList.isEmpty()?null:dataServiceProviderList.get(0))
                        .availableFormats(availableFormats)
						.sha256id(distribution.getUid() != null
								? DigestUtils.sha256Hex(distribution.getUid())
								: "")
						.dataProvider(facetsDataProviders)
						.serviceProvider(facetsServiceProviders)
						.categories(categoryList.isEmpty()
								? null
								: categoryList);

				// if the user is part of the backoffice & it is a query for the versions
				if (isBackofficeUser && parameters.containsKey("versioningStatus")) {
					var editorId = distribution.getEditorId();
					// if the editor is the ingestor
					if (editorId.equals("ingestor")) {
						discoveryItemBuilder.editorFullName("Ingestor");
					} else { // if the editor is an user
						var editor = userMap.get(distribution.getEditorId());
						if (editor != null) { // unlikely but technically possible
							discoveryItemBuilder.editorFullName(editor.getFirstName() + " " + editor.getLastName());
						}
					}
					discoveryItemBuilder.editorId(editorId)
							.changeDate(distribution.getChangeTimestamp())
							.versioningStatus(dataproduct.getStatus().name());
				}

				var discoveryItem = discoveryItemBuilder.build();

				if (EnvironmentVariables.MONITORING != null && EnvironmentVariables.MONITORING.equals("true")) {
					discoveryItem
							.setStatus(ZabbixExecutor.getInstance().getStatusInfoFromSha(discoveryItem.getSha256id()));
					discoveryItem.setStatusTimestamp(
							ZabbixExecutor.getInstance().getStatusTimestampInfoFromSha(discoveryItem.getSha256id()));
				}

				discoveryMap.add(discoveryItem);
			}
		}

		LOGGER.info("Final number of results: {}", discoveryMap.size());

		Node results = new Node("results");
		if (parameters.containsKey("facets") && parameters.get("facets").toString().equals("true")) {
			switch (parameters.get("facetstype").toString()) {
				case "categories":
					results.addChild(FacetsGeneration.generateResponseUsingCategories(discoveryMap, Facets.Type.DATA).getFacets());
					break;
				case "dataproviders":
					results.addChild(FacetsGeneration.generateResponseUsingDataproviders(discoveryMap).getFacets());
					break;
				case "serviceproviders":
					results.addChild(FacetsGeneration.generateResponseUsingServiceproviders(discoveryMap).getFacets());
					break;
				default:
					break;
			}

		} else {
			Node child = new Node();
			child.setDistributions(discoveryMap);
			results.addChild(child);
		}

		LOGGER.info("Number of organizations retrieved {}", organizationsEntityIds.size());

		List<String> keywordsCollection = keywords.stream()
				.filter(Objects::nonNull)
				.sorted()
				.collect(Collectors.toList());

		NodeFilters keywordsNodes = new NodeFilters("keywords");

		keywordsCollection.forEach(r -> {
			NodeFilters node = new NodeFilters(r);
			node.setId(Base64.getEncoder().encodeToString(r.getBytes()));
			keywordsNodes.addChild(node);
		});

		List<DataServiceProvider> collection = DataServiceProviderGeneration
				.getProviders(new ArrayList<>(organizationsEntityIds));

		NodeFilters organisationsNodes = new NodeFilters("organisations");

		collection.forEach(resource -> {
			NodeFilters node = new NodeFilters(resource.getDataProviderLegalName());
			node.setId(resource.getInstanceid());
			organisationsNodes.addChild(node);

			resource.getRelatedDataProvider().forEach(relatedData -> {
				NodeFilters relatedNodeDataProvider = new NodeFilters(relatedData.getDataProviderLegalName());
				relatedNodeDataProvider.setId(relatedData.getInstanceid());
				organisationsNodes.addChild(relatedNodeDataProvider);
			});
			resource.getRelatedDataServiceProvider().forEach(relatedDataService -> {
				NodeFilters relatedNodeDataServiceProvider = new NodeFilters(
						relatedDataService.getDataProviderLegalName());
				relatedNodeDataServiceProvider.setId(relatedDataService.getInstanceid());
				organisationsNodes.addChild(relatedNodeDataServiceProvider);
			});
		});

		NodeFilters scienceDomainsNodes = new NodeFilters(
				PARAMETER__SCIENCE_DOMAIN);
		scienceDomains.forEach(r -> scienceDomainsNodes.addChild(new NodeFilters(r.getInstanceId(), r.getName())));

		NodeFilters serviceTypesNodes = new NodeFilters(
				PARAMETER__SERVICE_TYPE);
		serviceTypes.forEach(r -> serviceTypesNodes.addChild(new NodeFilters(r.getInstanceId(), r.getName())));

		ArrayList<NodeFilters> filters = new ArrayList<NodeFilters>();
		filters.add(keywordsNodes);
		filters.add(organisationsNodes);
		filters.add(scienceDomainsNodes);
		filters.add(serviceTypesNodes);

		SearchResponse response = new SearchResponse(results, filters);

		long endTime = System.currentTimeMillis();
		long duration = (endTime - startTime);

		LOGGER.info("Result done in ms: {}", duration);

		return response;
	}
}
