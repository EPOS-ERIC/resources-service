package org.epos.api.core.filtersearch;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import abstractapis.AbstractAPI;
import metadataapis.EntityNames;
import org.epos.api.beans.DataServiceProvider;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.routines.DatabaseConnections;
import org.epos.api.utility.BBoxToPolygon;
import org.epos.eposdatamodel.*;
import org.epos.eposdatamodel.DataProduct;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DistributionFilterSearch {

	private static final Logger LOGGER = LoggerFactory.getLogger(DistributionFilterSearch.class); 


	private static final String NORTHEN_LAT  = "epos:northernmostLatitude";
	private static final String SOUTHERN_LAT  = "epos:southernmostLatitude";
	private static final String WESTERN_LON  = "epos:westernmostLongitude";
	private static final String EASTERN_LON  = "epos:easternmostLongitude";

	private static final String PARAMETER__EXV = "exvs";
	private static final String PARAMETER__SCIENCE_DOMAIN = "sciencedomains";
	private static final String PARAMETER__SERVICE_TYPE = "servicetypes";


	public static List<DataProduct> doFilters(List<DataProduct> datasetList, Map<String,Object> parameters) {

		datasetList = filterByFullText(datasetList, parameters);
		datasetList = filterByKeywords(datasetList, parameters);
		datasetList = filterByOrganizations(datasetList, parameters);
		datasetList = filterByDateRange(datasetList, checkTemporalExtent(parameters));
		datasetList = filterByBoundingBox(datasetList, parameters);
		datasetList = filterByScienceDomain(datasetList, parameters);
		datasetList = filterByServiceType(datasetList, parameters);
		datasetList = filterByEXV(datasetList, parameters);

		return datasetList;
	}

	private static List<DataProduct> filterByScienceDomain(List<DataProduct> datasetList, Map<String,Object> parameters) {
		if(parameters.containsKey(PARAMETER__SCIENCE_DOMAIN)) {
			ArrayList<DataProduct> tempDatasetList = new ArrayList<>();
			List<String> scienceDomainsParameters = List.of(parameters.get(PARAMETER__SCIENCE_DOMAIN).toString().split(","));
			datasetList.forEach(dataproduct -> {
				if (Objects.nonNull(dataproduct.getCategory()) && !dataproduct.getCategory().isEmpty()){
					List<String> scienceDomainOfDataproduct = new ArrayList<>();
					for(LinkedEntity item : dataproduct.getCategory()){
						Category category = (Category) AbstractAPI.retrieveAPI(EntityNames.CATEGORY.name()).retrieve(item.getInstanceId());
						if(Objects.nonNull(category)) scienceDomainOfDataproduct.add(category.getName());
					}

					if(!Collections.disjoint(scienceDomainOfDataproduct, scienceDomainsParameters)){
						tempDatasetList.add(dataproduct);
					}
				}
			});
			datasetList = tempDatasetList;
		}
		return datasetList;
	}

	private static List<DataProduct> filterByEXV(List<DataProduct> datasetList, Map<String,Object> parameters) {
		if(parameters.containsKey(PARAMETER__EXV)) {
			ArrayList<DataProduct> tempDatasetList = new ArrayList<>();
			List<String> exvParameters = List.of(parameters.get(PARAMETER__EXV).toString().split(","));
			datasetList.forEach(dataproduct -> {
				if (Objects.nonNull(dataproduct.getVariableMeasured()) && !dataproduct.getVariableMeasured().isEmpty()){
					List<String> exv = new ArrayList<>();
					for(String item : dataproduct.getVariableMeasured()){
						if(exvParameters.contains(item)) exv.add(item);
					}

					if(!Collections.disjoint(exv, exvParameters)){
						tempDatasetList.add(dataproduct);
					}
				}
			});
			datasetList = tempDatasetList;
		}
		return datasetList;
	}


	private static List<DataProduct> filterByServiceType(List<DataProduct> datasetList, Map<String,Object> parameters) {
		if (parameters.containsKey(PARAMETER__SERVICE_TYPE)) {
			ArrayList<DataProduct> tempDatasetList = new ArrayList<>();
			List<String> serviceTypesParameters = List.of(parameters.get(PARAMETER__SERVICE_TYPE).toString().split(","));

			datasetList.forEach(dataproduct -> {
				for (LinkedEntity distribution : dataproduct.getDistribution()) {
					Distribution distribution1 =  (Distribution) AbstractAPI.retrieveAPI(EntityNames.DISTRIBUTION.name()).retrieve(distribution.getInstanceId());
					if(Objects.nonNull(distribution1)){
						for (LinkedEntity accessService : distribution1.getAccessService()) {
							WebService webservice = (WebService) AbstractAPI.retrieveAPI(EntityNames.WEBSERVICE.name()).retrieve(accessService.getInstanceId());
							if(Objects.nonNull(webservice)){
								if (Objects.nonNull(webservice.getCategory()) && !webservice.getCategory().isEmpty()){
									List<String> serviceTypesOfWebservice = new ArrayList<>();
									List<Category> categories = (List<Category>) AbstractAPI.retrieveAPI(EntityNames.CATEGORY.name()).retrieveBunch(dataproduct.getCategory().stream().map(LinkedEntity::getInstanceId).collect(Collectors.toList()));
									for(Category item : categories){
										//Optional<Category> category = DatabaseConnections.getInstance().getCategoryList().stream().filter(obj -> obj.getInstanceId().equals(item.getInstanceId())).findFirst();
										//category.ifPresent(value -> serviceTypesOfWebservice.add(value.getName()));
										serviceTypesOfWebservice.add(item.getName());
									}
									if(!Collections.disjoint(serviceTypesOfWebservice, serviceTypesParameters)){
										tempDatasetList.add(dataproduct);
									}
								}
							}
						}
					}
				}
			});
			datasetList = tempDatasetList;
		}
		return datasetList;
	}


	private static List<DataProduct> filterByBoundingBox(List<DataProduct> datasetList, Map<String,Object> parameters) {
		//check if the bbox passed inside the parameters is complete, if not exit and return the whole list of dataproduct
		if (!parameters.containsKey(NORTHEN_LAT)
				|| !parameters.containsKey(SOUTHERN_LAT)
				|| !parameters.containsKey(WESTERN_LON)
				|| !parameters.containsKey(EASTERN_LON))
			return datasetList;
		GeometryFactory geometryFactory = new GeometryFactory();
		WKTReader reader = new WKTReader( geometryFactory );
		try {
			//try to parse the bbox
			final Geometry inputGeometry = reader.read(BBoxToPolygon.transform(parameters));
			//check if the bbox is parsed
			if(inputGeometry!=null) {
				//temporary dataproduct list, it will collect the dataproduct which are, even in part, contained inside the bbox
				ArrayList<DataProduct> tempDatasetList = new ArrayList<>();
				//utility set to contain the all the uid of the selected dataproduct to avoid duplicates
				Set<String> uidSet = new HashSet<>();
				//iterate over every dataproduct
				for (DataProduct ds : datasetList) {

					//if the uid belong to an already selected dataproduct just skip the iteration
					if(uidSet.contains(ds.getMetaId())) continue;

					if (ds.getDistribution() == null) {
                        LOGGER.error("[Spatial Filter] Error on current Dataset: {} skip", ds);
                        continue;
                    }

					//iterate over every distribution related to the dataproduct taken into account
					for (LinkedEntity distribution : ds.getDistribution()) {
						Distribution distribution1 = (Distribution) AbstractAPI.retrieveAPI(EntityNames.DISTRIBUTION.name()).retrieve(distribution.getInstanceId());

						// if no distribution was found, skip it
						if (Objects.isNull(distribution1))
							continue;
						// if the distribution does not have an accessService, skip it
						if (Objects.isNull(distribution1.getAccessService()))
							continue;

						for (LinkedEntity accessService : distribution1.getAccessService()) {
							WebService ws = (WebService) AbstractAPI.retrieveAPI(EntityNames.WEBSERVICE.name()).retrieve(accessService.getInstanceId());

							// if this distribution does not have a spatial extent, don't add it
							if (Objects.isNull(ws) || Objects.isNull(ws.getSpatialExtent()))
								continue;

							for (LinkedEntity wsSpatialLe : ws.getSpatialExtent()) {
								Location wsSpatial = (Location) AbstractAPI.retrieveAPI(EntityNames.LOCATION.name()).retrieve(wsSpatialLe.getInstanceId());

								// if not present, skip it
								if (Objects.isNull(wsSpatial))
									continue;

								try {
									// parse the spatial of the webservice
									Geometry dsGeometry = reader.read(wsSpatial.getLocation());
									// if the dataproduct hasn't been selected yet and the spatial of the webservice
									// intersect with the bbox, the dataproduct is selected.
									if (!uidSet.contains(ds.getMetaId()) && inputGeometry.intersects(dsGeometry)) {
										tempDatasetList.add(ds);
										uidSet.add(ds.getMetaId());
									}
								} catch (Exception e) {
									LOGGER.warn("Error occurs during BBOX dataproduct parsing for distribution with uid {} and spatial {}",
											distribution1.getUid(), wsSpatial, e);
									continue;
								}
							}
						}
					}
					//if the uid belong to an already selected dataproduct just skip the iteration
					if(uidSet.contains(ds.getMetaId())) continue;
					//same operation done before for the webservice's spatial but now for the dataprodut spatial
					if(ds.getSpatialExtent() != null){
						for (LinkedEntity wsSpatialLe : ds.getSpatialExtent()) {
							Location wsSpatial = (Location) AbstractAPI.retrieveAPI(EntityNames.LOCATION.name()).retrieve(wsSpatialLe.getInstanceId());
							if(Objects.nonNull(wsSpatial)){
								try {
									//parse the spatial of the webservice
									Geometry dsGeometry = reader.read(wsSpatial.getLocation());
									//if the dataproduct hasn't been selected yet and the spatial of the webservice
									//intersect with the bbox, the dataproduct is selected.
									if (!uidSet.contains(ds.getMetaId()) && inputGeometry.intersects(dsGeometry)) {
										tempDatasetList.add(ds);
										uidSet.add(ds.getMetaId());
									}
								} catch (Exception e) {
									LOGGER.warn("Error occurs during BBOX dataproduct parsing for dataset with uid {} and spatial {}",
											ds.getUid(), wsSpatial, e);
									continue;
								}
							}
						}
					}
					//if the uid belong to an already selected dataproduct just skip the iteration
					if(uidSet.contains(ds.getMetaId())) continue;
					//if the dataproduct doesn't have any spatial information it is taken
					if ( ds.getSpatialExtent() == null || ds.getSpatialExtent().isEmpty() ) {
						tempDatasetList.add(ds);
						uidSet.add(ds.getMetaId());
					}
				}
				//replace the old dataset list with the new temporary filtered dataproduct list
				datasetList = tempDatasetList;
			}
		} catch (org.locationtech.jts.io.ParseException e) {
			LOGGER.error("Error occurs during BBOX input parsing ",e);
		}
		return datasetList;
	}

	private static List<DataProduct> filterByDateRange(List<DataProduct> datasetList, PeriodOfTime temporal) {
		if(temporal.getStartDate()!=null && temporal.getEndDate()!=null) {
			ArrayList<DataProduct> tempDatasetList = new ArrayList<>();
			datasetList.forEach(ds -> {
				if(ds.getTemporalExtent()!=null){
					for(LinkedEntity linkedEntity : ds.getTemporalExtent()){
						PeriodOfTime dsTemporalItem = (PeriodOfTime) AbstractAPI.retrieveAPI(EntityNames.PERIODOFTIME.name()).retrieve(linkedEntity.getInstanceId());
						if(Objects.nonNull(dsTemporalItem)){
							if((dsTemporalItem.getStartDate() == null || temporal.getEndDate() == null || dsTemporalItem.getStartDate().isBefore(temporal.getEndDate()))
									&& (temporal.getStartDate() == null || dsTemporalItem.getEndDate() == null || temporal.getStartDate().isBefore(dsTemporalItem.getEndDate()))
									&& (dsTemporalItem.getStartDate() == null || dsTemporalItem.getEndDate() == null || dsTemporalItem.getStartDate().isBefore(dsTemporalItem.getEndDate()))
									&& (temporal.getStartDate() == null || temporal.getEndDate() == null || temporal.getStartDate().isBefore(temporal.getEndDate())))
								tempDatasetList.add(ds);
						}
					}
				}
			});
			datasetList = tempDatasetList;
		}
		return datasetList;
	}

	private static List<DataProduct> filterByOrganizations(List<DataProduct> datasetList, Map<String,Object> parameters) {
		if(parameters.containsKey("organisations")) {
			List<String> organisations = Arrays.asList(parameters.get("organisations").toString().split(","));

			HashSet<DataProduct> tempDatasetList = new HashSet<>();
			for (DataProduct ds: datasetList) {
				List<Organization> organizations = new ArrayList<>();
				if (ds.getPublisher() == null) {
					continue;
				}
				for(LinkedEntity linkedEntity : ds.getPublisher()){
					Organization provider = (Organization) AbstractAPI.retrieveAPI(EntityNames.ORGANIZATION.name()).retrieve(linkedEntity.getInstanceId());
                    if(Objects.nonNull(provider)) organizations.add(provider);
				}
				List<DataServiceProvider> providers = new ArrayList<DataServiceProvider>(DataServiceProviderGeneration.getProviders(organizations));

				List<Organization> organisationList = new ArrayList<>();
                if(ds.getDistribution() == null && ds.getDistribution().isEmpty())
                    LOGGER.error("[Organization Filter] Error on current Dataset: {} skip", ds);
				if(ds.getDistribution() != null && !ds.getDistribution().isEmpty()){
					for (LinkedEntity distribution : ds.getDistribution()) {
						Distribution distribution1 = (Distribution) AbstractAPI.retrieveAPI(EntityNames.DISTRIBUTION.name()).retrieve(distribution.getInstanceId());
						if(Objects.nonNull(distribution1) && distribution1.getAccessService() != null){
							for (LinkedEntity accessService : distribution1.getAccessService()) {
								WebService ws = (WebService) AbstractAPI.retrieveAPI(EntityNames.WEBSERVICE.name()).retrieve(accessService.getInstanceId());
								if(Objects.nonNull(ws) && Objects.nonNull(ws.getProvider())){
									Organization organization = (Organization) AbstractAPI.retrieveAPI(EntityNames.ORGANIZATION.name()).retrieve(ws.getProvider().getInstanceId());
									if(Objects.nonNull(organization)) organisationList.add(organization);
								}
							}
						}
					}
				}

				providers.addAll(DataServiceProviderGeneration.getProviders(organisationList));

				List<String> value = new ArrayList<>();

				providers.forEach(resource->{
					value.add(resource.getInstanceid());
					//LOGGER.info("Number of related retrieved "+resource.getRelatedDataProvider().size());
					resource.getRelatedDataProvider().forEach(related ->{
						value.add(related.getInstanceid());
					});
					//LOGGER.info("Number of related retrieved "+resource.getRelatedDataServiceProvider().size());
					resource.getRelatedDataServiceProvider().forEach(related ->{
						value.add(related.getInstanceid());
					});
				});

				if(!Collections.disjoint(organisations, value)){
					tempDatasetList.add(ds);
				}

			}
			datasetList = new ArrayList<>(tempDatasetList);
		}
		return datasetList;
	}

	private static List<DataProduct> filterByKeywords(List<DataProduct> datasetList, Map<String,Object> parameters) {
		if(parameters.containsKey("keywords")) {
			ArrayList<DataProduct> tempDatasetList = new ArrayList<>();
			String[] keywords = parameters.get("keywords").toString().split(",");
			Set<String> keywordsSet = new HashSet<String>(Arrays.asList(keywords));
			datasetList.forEach(ds -> {
				if(!Collections.disjoint(Arrays.stream(ds.getKeywords().split(",")).map(String::toLowerCase).map(String::trim).collect(Collectors.toList()), keywordsSet)){
					tempDatasetList.add(ds);
				}
			});
			datasetList = tempDatasetList;
		}
		return datasetList;
	}

	private static List<DataProduct> filterByFullText(List<DataProduct> datasetList, Map<String,Object> parameters) {
		if(parameters.containsKey("q")) {
			HashSet<DataProduct> tempDatasetList = new HashSet<>();
			String[] qs = parameters.get("q").toString().toLowerCase().split(",");

			Set<String> qsSet = new HashSet<String>(Arrays.asList(qs));

			for (DataProduct edmDataproduct : datasetList) {
				Map<String, Boolean> qSMap = qsSet.stream()
						.collect(Collectors.toMap(
								key -> key, value -> Boolean.FALSE
								));

				if(Objects.nonNull(edmDataproduct.getKeywords())){
					List<String> dataproductKeywords = Arrays.stream(edmDataproduct.getKeywords().split(","))
							.map(String::toLowerCase)
							.map(String::trim)
							.collect(Collectors.toList());

					for (String q : qSMap.keySet()) {
						if (dataproductKeywords.contains(q)) qSMap.put(q, Boolean.TRUE);
					}
				}
				if(Objects.nonNull(edmDataproduct.getUid())){
					for (String q : qSMap.keySet()) {
						if (edmDataproduct.getUid().toLowerCase().contains(q)) qSMap.put(q, Boolean.TRUE);
					}
				}
				
				//identifier
				if (edmDataproduct.getIdentifier()!=null && !edmDataproduct.getIdentifier().isEmpty()) {
					for (LinkedEntity linkedEntity : edmDataproduct.getIdentifier()) {
						Identifier edmIdentifier = (Identifier) AbstractAPI.retrieveAPI(EntityNames.IDENTIFIER.name()).retrieve(linkedEntity.getInstanceId());
						if(Objects.nonNull(edmIdentifier)){
							for (String q : qSMap.keySet()) {
								if (edmIdentifier.getIdentifier().toLowerCase().contains(q)) qSMap.put(q, Boolean.TRUE);
								if (edmIdentifier.getType().toLowerCase().contains(q)) qSMap.put(q, Boolean.TRUE);
								if ((edmIdentifier.getType().toLowerCase()+edmIdentifier.getIdentifier().toLowerCase()).contains(q)) qSMap.put(q, Boolean.TRUE);
							}
						}
					}
				}


				if (edmDataproduct.getDistribution() != null && !edmDataproduct.getDistribution().isEmpty()) {
					for (LinkedEntity edmDistributionLe : edmDataproduct.getDistribution()) {
						Distribution edmDistribution = (Distribution) AbstractAPI.retrieveAPI(EntityNames.DISTRIBUTION.name()).retrieve(edmDistributionLe.getInstanceId());
						if(Objects.nonNull(edmDistribution)){
							//distribution title
							if (edmDistribution.getTitle() != null) {
								edmDistribution.getTitle()
										.forEach(title ->
										{
											for (String q : qSMap.keySet()) {
												if (title.toLowerCase().contains(q)) {
													qSMap.put(q, Boolean.TRUE);
												}
											}
										});
							}

							if(Objects.nonNull(edmDistribution.getUid())){
								for (String q : qSMap.keySet()) {
									if (edmDistribution.getUid().toLowerCase().contains(q)) qSMap.put(q, Boolean.TRUE);
								}
							}

							//distribution description
							if (edmDistribution.getDescription() != null) {
								edmDistribution.getDescription()
										.forEach(description ->
												{
													for (String q : qSMap.keySet()) {
														if (description.toLowerCase().contains(q)) {
															qSMap.put(q, Boolean.TRUE);
														}
													}
												}
										);
							}

							//webservice
							if (edmDistribution.getAccessService() != null) {
								for(LinkedEntity accessService : edmDistribution.getAccessService()){
									WebService edmWebservice = (WebService) AbstractAPI.retrieveAPI(EntityNames.WEBSERVICE.name()).retrieve(accessService.getInstanceId());
									if(Objects.nonNull(edmWebservice)){

										if(Objects.nonNull(edmWebservice.getUid())){
											for (String q : qSMap.keySet()) {
												if (edmWebservice.getUid().toLowerCase().contains(q)) qSMap.put(q, Boolean.TRUE);
											}
										}
										//webservice title
										if (edmWebservice.getName() != null) {
											for (String q : qSMap.keySet()) {
												if (edmWebservice.getName().toLowerCase().contains(q)) {
													qSMap.put(q, Boolean.TRUE);
												}
											}
										}

										//webservice description
										if (edmWebservice.getDescription() != null) {
											for (String q : qSMap.keySet()) {
												if (edmWebservice.getDescription().toLowerCase().contains(q)) {
													qSMap.put(q, Boolean.TRUE);
												}
											}
										}

										if(Objects.nonNull(edmWebservice.getKeywords())){
											List<String> webserviceKeywords = Arrays.stream(edmWebservice.getKeywords().split(","))
													.map(String::toLowerCase)
													.map(String::trim)
													.collect(Collectors.toList());

											for (String q : qSMap.keySet()) {
												if (webserviceKeywords.contains(q)) qSMap.put(q, Boolean.TRUE);
											}
										}

									}
								}
							}
						}
					}
				}

				//take a dataproduct onlly if every element of the freetext search is satisfied
				if (qSMap.values().stream().allMatch(b -> b)) tempDatasetList.add(edmDataproduct);
			}

			datasetList = new ArrayList<>(tempDatasetList);
		}

		return datasetList;

	}


	public static PeriodOfTime checkTemporalExtent(Map<String,Object> parameters) {
		PeriodOfTime temporal = new PeriodOfTime();
		try {
			if(parameters.containsKey("schema:startDate")) temporal.setStartDate(convertToLocalDateTimeViaSqlTimestamp(new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").parse(parameters.get("schema:startDate").toString().replace("T", " ").replace("Z", ""))));
			if(parameters.containsKey("schema:endDate")) temporal.setEndDate(convertToLocalDateTimeViaSqlTimestamp(new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").parse(parameters.get("schema:endDate").toString().replace("T", " ").replace("Z", ""))));
		} catch (java.text.ParseException e) {
			LOGGER.error("Error occurs during search caused by Date parsing {}",e);
		}
		return temporal;
	}

	public static LocalDateTime convertToLocalDateTimeViaSqlTimestamp(Date dateToConvert) {
		return new java.sql.Timestamp(
				dateToConvert.getTime()).toLocalDateTime();
	}

}
