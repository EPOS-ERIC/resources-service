package org.epos.api.core.filtersearch;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;
import org.epos.api.beans.DataServiceProvider;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.PreFetchedEntities;
import org.epos.api.core.search.SearchQueryProcessor;
import org.epos.api.core.search.SearchReranker;
import org.epos.api.core.search.SearchSynonyms;
import org.epos.api.core.search.TextSimilarityScorer;
import org.epos.api.routines.DatabaseConnections;
import org.epos.api.utility.BBoxToPolygon;
import org.epos.eposdatamodel.*;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optimized version of DistributionFilterSearch with:
 * - Pre-fetching of all linked entities (eliminates N+1 queries)
 * - Parallel processing for large datasets
 * - Reduced redundant computations
 * - Better memory efficiency
 */
public class DistributionFilterSearch {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributionFilterSearch.class);

    private static final String NORTHEN_LAT = "epos:northernmostLatitude";
    private static final String SOUTHERN_LAT = "epos:southernmostLatitude";
    private static final String WESTERN_LON = "epos:westernmostLongitude";
    private static final String EASTERN_LON = "epos:easternmostLongitude";

    private static final String PARAMETER__SCIENCE_DOMAIN = "sciencedomains";
    private static final String PARAMETER__SERVICE_TYPE = "servicetypes";

    public static List<DataProduct> doFilters(List<DataProduct> datasetList, Map<String, Object> parameters) {
        SearchSynonyms.initialize();
        
        // Pre-fetch all linked entities at once to avoid N+1 queries
        PreFetchedEntities preFetched = preFetchLinkedEntities(datasetList, parameters);

        // Apply filters in sequence
        datasetList = filterByFullText(datasetList, parameters, preFetched);
        datasetList = filterByKeywords(datasetList, parameters);
        datasetList = filterByOrganizations(datasetList, parameters, preFetched);
        datasetList = filterByDateRange(datasetList, checkTemporalExtent(parameters));
        datasetList = filterByBoundingBox(datasetList, parameters, preFetched);
        datasetList = filterByScienceDomain(datasetList, parameters, preFetched);
        datasetList = filterByServiceType(datasetList, parameters, preFetched);

        return datasetList;
    }

    /**
     * Pre-fetch all linked entities to eliminate N+1 query problem
     */
    private static PreFetchedEntities preFetchLinkedEntities(List<DataProduct> datasetList,
                                                             Map<String, Object> parameters) {
        PreFetchedEntities entities = new PreFetchedEntities();

        // Collect all IDs
        Set<String> distributionIds = ConcurrentHashMap.newKeySet();
        Set<String> categoryIds = ConcurrentHashMap.newKeySet();
        Set<String> identifierIds = ConcurrentHashMap.newKeySet();
        Set<String> organizationIds = ConcurrentHashMap.newKeySet();

        datasetList.parallelStream().forEach(dp -> {
            if (dp.getDistribution() != null) {
                dp.getDistribution().forEach(le -> distributionIds.add(le.getInstanceId()));
            }
            if (dp.getCategory() != null) {
                dp.getCategory().forEach(le -> categoryIds.add(le.getInstanceId()));
            }
            if (dp.getIdentifier() != null) {
                dp.getIdentifier().forEach(le -> identifierIds.add(le.getInstanceId()));
            }
            if (dp.getPublisher() != null) {
                dp.getPublisher().forEach(le -> organizationIds.add(le.getInstanceId()));
            }
        });

        LOGGER.info("Pre-fetching: {} distributions, {} categories, {} identifiers, {} organizations",
                distributionIds.size(), categoryIds.size(), identifierIds.size(), organizationIds.size());

        // Batch fetch all entities
        entities.distributions = fetchEntityBatch(EntityNames.DISTRIBUTION, distributionIds);
        entities.categories = fetchEntityBatch(EntityNames.CATEGORY, categoryIds);
        entities.identifiers = fetchEntityBatch(EntityNames.IDENTIFIER, identifierIds);
        entities.organizations = fetchEntityBatch(EntityNames.ORGANIZATION, organizationIds);

        // Collect web service and location IDs from distributions
        Set<String> webServiceIds = ConcurrentHashMap.newKeySet();
        Set<String> locationIds = ConcurrentHashMap.newKeySet();

        entities.distributions.values().parallelStream()
                .filter(obj -> obj instanceof Distribution)
                .map(obj -> (Distribution) obj)
                .forEach(dist -> {
                    if (dist.getAccessService() != null) {
                        dist.getAccessService().forEach(le -> webServiceIds.add(le.getInstanceId()));
                    }
                });

        if (!webServiceIds.isEmpty()) {
            LOGGER.info("Pre-fetching: {} web services", webServiceIds.size());
            entities.webServices = fetchEntityBatch(EntityNames.WEBSERVICE, webServiceIds);

            // Collect locations and additional organizations from web services
            entities.webServices.values().parallelStream()
                    .filter(obj -> obj instanceof WebService)
                    .map(obj -> (WebService) obj)
                    .forEach(ws -> {
                        if (ws.getSpatialExtent() != null) {
                            ws.getSpatialExtent().forEach(le -> locationIds.add(le.getInstanceId()));
                        }
                        if (ws.getProvider() != null) {
                            organizationIds.add(ws.getProvider().getInstanceId());
                        }
                        if (ws.getCategory() != null) {
                            ws.getCategory().forEach(le -> categoryIds.add(le.getInstanceId()));
                        }
                    });
        }

        // Collect locations from dataproducts
        datasetList.parallelStream()
                .filter(dp -> dp.getSpatialExtent() != null)
                .forEach(dp -> dp.getSpatialExtent().forEach(le -> locationIds.add(le.getInstanceId())));

        if (!locationIds.isEmpty()) {
            LOGGER.info("Pre-fetching: {} locations", locationIds.size());
            entities.locations = fetchEntityBatch(EntityNames.LOCATION, locationIds);
        }

        // Fetch any additional organizations and categories
        Set<String> newOrgIds = new HashSet<>(organizationIds);
        newOrgIds.removeAll(entities.organizations.keySet());
        if (!newOrgIds.isEmpty()) {
            entities.organizations.putAll(fetchEntityBatch(EntityNames.ORGANIZATION, newOrgIds));
        }

        Set<String> newCatIds = new HashSet<>(categoryIds);
        newCatIds.removeAll(entities.categories.keySet());
        if (!newCatIds.isEmpty()) {
            entities.categories.putAll(fetchEntityBatch(EntityNames.CATEGORY, newCatIds));
        }

        return entities;
    }

    /**
     * Batch fetch helper
     */
    private static Map<String, Object> fetchEntityBatch(EntityNames entityName, Set<String> ids) {
        if (ids.isEmpty()) {
            return new ConcurrentHashMap<>();
        }

        List<String> idList = new ArrayList<>(ids);
        List<?> results = (List<?>) AbstractAPI.retrieveAPI(entityName.name()).retrieveBunch(idList);

        Map<String, Object> map = new ConcurrentHashMap<>();
        if (results != null) {
            results.parallelStream()
                    .filter(Objects::nonNull)
                    .filter(obj -> obj instanceof EPOSDataModelEntity)
                    .forEach(obj -> {
                        EPOSDataModelEntity entity = (EPOSDataModelEntity) obj;
                        map.put(entity.getInstanceId(), entity);
                    });
        }
        return map;
    }

    /**
     * Filter by science domain - OPTIMIZED with pre-fetched data
     */
    private static List<DataProduct> filterByScienceDomain(List<DataProduct> datasetList,
                                                           Map<String, Object> parameters,
                                                           PreFetchedEntities preFetched) {
        if (!parameters.containsKey(PARAMETER__SCIENCE_DOMAIN)) {
            return datasetList;
        }

        Set<String> scienceDomainsParameters = new HashSet<>(
                Arrays.asList(parameters.get(PARAMETER__SCIENCE_DOMAIN).toString().split(",")));

        boolean useParallel = datasetList.size() > 100;

        return (useParallel ? datasetList.parallelStream() : datasetList.stream())
                .filter(dataproduct -> {
                    if (dataproduct.getCategory() == null || dataproduct.getCategory().isEmpty()) {
                        return false;
                    }

                    List<String> scienceDomainOfDataproduct = dataproduct.getCategory().stream()
                            .map(le -> preFetched.categories.get(le.getInstanceId()))
                            .filter(Objects::nonNull)
                            .filter(obj -> obj instanceof Category)
                            .map(obj -> ((Category) obj).getName())
                            .collect(Collectors.toList());

                    return !Collections.disjoint(scienceDomainOfDataproduct, scienceDomainsParameters);
                })
                .collect(Collectors.toList());
    }

    /**
     * Filter by service type - OPTIMIZED with pre-fetched data
     */
    private static List<DataProduct> filterByServiceType(List<DataProduct> datasetList,
                                                         Map<String, Object> parameters,
                                                         PreFetchedEntities preFetched) {
        if (!parameters.containsKey(PARAMETER__SERVICE_TYPE)) {
            return datasetList;
        }

        Set<String> serviceTypesParameters = new HashSet<>(
                Arrays.asList(parameters.get(PARAMETER__SERVICE_TYPE).toString().split(",")));

        Set<DataProduct> tempDatasetList = ConcurrentHashMap.newKeySet();

        boolean useParallel = datasetList.size() > 100;

        (useParallel ? datasetList.parallelStream() : datasetList.stream()).forEach(dataproduct -> {
            if (dataproduct.getDistribution() == null) {
                return;
            }

            boolean matches = dataproduct.getDistribution().stream()
                    .map(le -> preFetched.distributions.get(le.getInstanceId()))
                    .filter(Objects::nonNull)
                    .filter(obj -> obj instanceof Distribution)
                    .map(obj -> (Distribution) obj)
                    .filter(dist -> dist.getAccessService() != null)
                    .flatMap(dist -> dist.getAccessService().stream())
                    .map(le -> preFetched.webServices.get(le.getInstanceId()))
                    .filter(Objects::nonNull)
                    .filter(obj -> obj instanceof WebService)
                    .map(obj -> (WebService) obj)
                    .filter(ws -> ws.getCategory() != null)
                    .flatMap(ws -> ws.getCategory().stream())
                    .map(le -> preFetched.categories.get(le.getInstanceId()))
                    .filter(Objects::nonNull)
                    .filter(obj -> obj instanceof Category)
                    .map(obj -> ((Category) obj).getName())
                    .anyMatch(serviceTypesParameters::contains);

            if (matches) {
                tempDatasetList.add(dataproduct);
            }
        });

        return new ArrayList<>(tempDatasetList);
    }

    /**
     * Filter by bounding box - OPTIMIZED with pre-fetched data and parallel processing
     */
    private static List<DataProduct> filterByBoundingBox(List<DataProduct> datasetList,
                                                         Map<String, Object> parameters,
                                                         PreFetchedEntities preFetched) {
        if (!parameters.containsKey(NORTHEN_LAT) || !parameters.containsKey(SOUTHERN_LAT)
                || !parameters.containsKey(WESTERN_LON) || !parameters.containsKey(EASTERN_LON)) {
            return datasetList;
        }

        GeometryFactory geometryFactory = new GeometryFactory();
        WKTReader reader = new WKTReader(geometryFactory);

        try {
            final Geometry inputGeometry = reader.read(BBoxToPolygon.transform(parameters));
            if (inputGeometry == null) {
                return datasetList;
            }

            Set<String> matchedUids = ConcurrentHashMap.newKeySet();
            boolean useParallel = datasetList.size() > 100;

            (useParallel ? datasetList.parallelStream() : datasetList.stream()).forEach(ds -> {
                if (matchedUids.contains(ds.getMetaId())) {
                    return;
                }

                // Check distributions
                if (ds.getDistribution() != null) {
                    boolean found = ds.getDistribution().stream()
                            .map(le -> preFetched.distributions.get(le.getInstanceId()))
                            .filter(Objects::nonNull)
                            .filter(obj -> obj instanceof Distribution)
                            .map(obj -> (Distribution) obj)
                            .filter(dist -> dist.getAccessService() != null)
                            .flatMap(dist -> dist.getAccessService().stream())
                            .map(le -> preFetched.webServices.get(le.getInstanceId()))
                            .filter(Objects::nonNull)
                            .filter(obj -> obj instanceof WebService)
                            .map(obj -> (WebService) obj)
                            .filter(ws -> ws.getSpatialExtent() != null)
                            .flatMap(ws -> ws.getSpatialExtent().stream())
                            .map(le -> preFetched.locations.get(le.getInstanceId()))
                            .filter(Objects::nonNull)
                            .filter(obj -> obj instanceof Location)
                            .map(obj -> (Location) obj)
                            .anyMatch(location -> {
                                try {
                                    Geometry dsGeometry = reader.read(location.getLocation());
                                    return inputGeometry.intersects(dsGeometry);
                                } catch (Exception e) {
                                    return false;
                                }
                            });

                    if (found) {
                        matchedUids.add(ds.getMetaId());
                        return;
                    }
                }

                // Check dataproduct spatial extent
                if (ds.getSpatialExtent() != null) {
                    boolean found = ds.getSpatialExtent().stream()
                            .map(le -> preFetched.locations.get(le.getInstanceId()))
                            .filter(Objects::nonNull)
                            .filter(obj -> obj instanceof Location)
                            .map(obj -> (Location) obj)
                            .anyMatch(location -> {
                                try {
                                    Geometry dsGeometry = reader.read(location.getLocation());
                                    return inputGeometry.intersects(dsGeometry);
                                } catch (Exception e) {
                                    return false;
                                }
                            });

                    if (found) {
                        matchedUids.add(ds.getMetaId());
                    }
                }
            });

            return datasetList.stream()
                    .filter(ds -> matchedUids.contains(ds.getMetaId()))
                    .collect(Collectors.toList());

        } catch (org.locationtech.jts.io.ParseException e) {
            LOGGER.error("Error occurs during BBOX input parsing", e);
            return datasetList;
        }
    }

    /**
     * Filter by date range
     */
    private static List<DataProduct> filterByDateRange(List<DataProduct> datasetList, PeriodOfTime temporal) {
        if (temporal.getStartDate() == null && temporal.getEndDate() == null) {
            return datasetList;
        }

        boolean useParallel = datasetList.size() > 100;

        return (useParallel ? datasetList.parallelStream() : datasetList.stream())
                .filter(ds -> {
                    if (ds.getTemporalExtent() == null || ds.getTemporalExtent().isEmpty()) {
                        return false;
                    }

                    return ds.getTemporalExtent().stream()
                            .anyMatch(period -> isWithinDateRange((PeriodOfTime) LinkedEntityAPI.retrieveFromLinkedEntity(period), temporal));
                })
                .collect(Collectors.toList());
    }

    /**
     * Check if period is within date range
     */
    private static boolean isWithinDateRange(PeriodOfTime period, PeriodOfTime temporal) {
        /*if (temporal.getStartDate() != null) {
            if (period.getEndDate() == null || period.getEndDate().isBefore(temporal.getStartDate())) {
                return false;
            }
        }
        if (temporal.getEndDate() != null) {
            if (period.getStartDate() == null || period.getStartDate().isAfter(temporal.getEndDate())) {
                return false;
            }
        }*/
        return (period.getStartDate() == null || temporal.getEndDate() == null || period.getStartDate().isBefore(temporal.getEndDate()))
                && (temporal.getStartDate() == null || period.getEndDate() == null || temporal.getStartDate().isBefore(period.getEndDate()))
                && (period.getStartDate() == null || period.getEndDate() == null || period.getStartDate().isBefore(period.getEndDate()))
                && (temporal.getStartDate() == null || temporal.getEndDate() == null || temporal.getStartDate().isBefore(temporal.getEndDate()));
    }

    /**
     * Filter by organizations - OPTIMIZED with pre-fetched data
     */
    private static List<DataProduct> filterByOrganizations(List<DataProduct> datasetList,
                                                           Map<String, Object> parameters,
                                                           PreFetchedEntities preFetched) {
        if (!parameters.containsKey("organisations")) {
            return datasetList;
        }

        Set<String> organisations = new HashSet<>(
                Arrays.asList(parameters.get("organisations").toString().split(",")));

        // Build organization provider IDs
        Set<String> validProviderIds = buildProviderIds(preFetched.organizations.values(), organisations);

        boolean useParallel = datasetList.size() > 100;

        Set<DataProduct> tempDatasetList = ConcurrentHashMap.newKeySet();

        (useParallel ? datasetList.parallelStream() : datasetList.stream()).forEach(ds -> {
            List<Organization> organizationsEntityIds = new ArrayList<>();

            // Get organizations from publishers
            if (ds.getPublisher() != null) {
                ds.getPublisher().stream()
                        .map(le -> preFetched.organizations.get(le.getInstanceId()))
                        .filter(Objects::nonNull)
                        .filter(obj -> obj instanceof Organization)
                        .map(obj -> (Organization) obj)
                        .forEach(organizationsEntityIds::add);
            }

            // Get organizations from distribution/webservice providers
            if (ds.getDistribution() != null) {
                ds.getDistribution().stream()
                        .map(le -> preFetched.distributions.get(le.getInstanceId()))
                        .filter(Objects::nonNull)
                        .filter(obj -> obj instanceof Distribution)
                        .map(obj -> (Distribution) obj)
                        .filter(dist -> dist.getAccessService() != null)
                        .flatMap(dist -> dist.getAccessService().stream())
                        .map(le -> preFetched.webServices.get(le.getInstanceId()))
                        .filter(Objects::nonNull)
                        .filter(obj -> obj instanceof WebService)
                        .map(obj -> (WebService) obj)
                        .filter(ws -> ws.getProvider() != null)
                        .map(ws -> preFetched.organizations.get(ws.getProvider().getInstanceId()))
                        .filter(Objects::nonNull)
                        .filter(obj -> obj instanceof Organization)
                        .map(obj -> (Organization) obj)
                        .forEach(organizationsEntityIds::add);
            }

            Set<String> dsProviderIds = buildProviderIds(organizationsEntityIds, null);

            if (!Collections.disjoint(organisations, dsProviderIds)) {
                tempDatasetList.add(ds);
            }
        });

        return new ArrayList<>(tempDatasetList);
    }

    /**
     * Build provider IDs from organizations
     */
    private static Set<String> buildProviderIds(Collection<?> organizations, Set<String> filter) {
        return organizations.stream()
                .filter(obj -> obj instanceof Organization)
                .map(obj -> (Organization) obj)
                .map(org -> {
                    List<DataServiceProvider> providers = DataServiceProviderGeneration.getProviders(List.of(org));
                    return providers.stream().flatMap(p ->
                            java.util.stream.Stream.concat(
                                    java.util.stream.Stream.of(p.getInstanceid()),
                                    java.util.stream.Stream.concat(
                                            p.getRelatedDataProvider().stream().map(DataServiceProvider::getInstanceid),
                                            p.getRelatedDataServiceProvider().stream().map(DataServiceProvider::getInstanceid)
                                    )
                            )
                    );
                })
                .flatMap(s -> s)
                .filter(id -> filter == null || filter.contains(id))
                .collect(Collectors.toSet());
    }

    /**
     * Filter by keywords - optimized
     */
    private static List<DataProduct> filterByKeywords(List<DataProduct> datasetList, Map<String, Object> parameters) {
        if (!parameters.containsKey("keywords")) {
            return datasetList;
        }

        Set<String> keywords = Arrays.stream(parameters.get("keywords").toString().split(","))
                .map(String::toLowerCase)
                .map(String::trim)
                .collect(Collectors.toSet());

        boolean useParallel = datasetList.size() > 100;

        return (useParallel ? datasetList.parallelStream() : datasetList.stream())
                .filter(ds -> ds.getKeywords() != null)
                .filter(ds -> {
                    Set<String> dataproductKeywords = Arrays.stream(ds.getKeywords().split(","))
                            .map(String::toLowerCase)
                            .map(String::trim)
                            .collect(Collectors.toSet());
                    return !Collections.disjoint(dataproductKeywords, keywords);
                })
                .collect(Collectors.toList());
    }

    /**
     * Filter by full text search - OPTIMIZED with pre-fetched data and parallel processing
     */
    private static List<DataProduct> filterByFullText(List<DataProduct> datasetList,
                                                       Map<String, Object> parameters,
                                                       PreFetchedEntities preFetched) {
        if (!parameters.containsKey("q")) {
            return datasetList;
        }

        String originalQuery = parameters.get("q").toString();
        List<String> searchTerms = SearchQueryProcessor.processQueryWithSynonyms(originalQuery, new SearchSynonyms());
        
        if (searchTerms.isEmpty()) {
            return datasetList;
        }

        Set<DataProduct> tempDatasetList = ConcurrentHashMap.newKeySet();
        boolean useParallel = datasetList.size() > 100;

        (useParallel ? datasetList.parallelStream() : datasetList.stream()).forEach(edmDataproduct -> {
            Map<String, Boolean> qSMap = searchTerms.stream()
                    .collect(Collectors.toMap(key -> key, value -> Boolean.FALSE));

            if (edmDataproduct.getKeywords() != null) {
                List<String> dataproductKeywords = Arrays.stream(edmDataproduct.getKeywords().split(","))
                        .map(String::toLowerCase)
                        .map(String::trim)
                        .collect(Collectors.toList());

                qSMap.keySet().forEach(q -> {
                    if (dataproductKeywords.contains(q)) {
                        qSMap.put(q, Boolean.TRUE);
                    } else {
                        for (String keyword : dataproductKeywords) {
                            if (TextSimilarityScorer.calculateSimilarity(q, keyword) > 0.0) {
                                qSMap.put(q, Boolean.TRUE);
                                break;
                            }
                        }
                    }
                });
            }

            if (edmDataproduct.getUid() != null) {
                qSMap.keySet().forEach(q -> {
                    if (edmDataproduct.getUid().toLowerCase().contains(q)) {
                        qSMap.put(q, Boolean.TRUE);
                    }
                });
            }

            if (edmDataproduct.getIdentifier() != null && !edmDataproduct.getIdentifier().isEmpty()) {
                edmDataproduct.getIdentifier().forEach(linkedEntity -> {
                    Identifier edmIdentifier = (Identifier) preFetched.identifiers.get(linkedEntity.getInstanceId());
                    if (edmIdentifier != null && edmIdentifier.getIdentifier() != null && edmIdentifier.getType() != null) {
                        qSMap.keySet().forEach(q -> {
                            if (edmIdentifier.getIdentifier().toLowerCase().contains(q)
                                    || edmIdentifier.getType().toLowerCase().contains(q)
                                    || (edmIdentifier.getType().toLowerCase() + edmIdentifier.getIdentifier().toLowerCase()).contains(q)) {
                                qSMap.put(q, Boolean.TRUE);
                            }
                        });
                    }
                });
            }

            if (edmDataproduct.getDistribution() != null && !edmDataproduct.getDistribution().isEmpty()) {
                edmDataproduct.getDistribution().forEach(edmDistributionLe -> {
                    Distribution edmDistribution = (Distribution) preFetched.distributions.get(edmDistributionLe.getInstanceId());
                    if (edmDistribution != null) {
                        if (edmDistribution.getTitle() != null) {
                            edmDistribution.getTitle().forEach(title -> {
                                qSMap.keySet().forEach(q -> {
                                    if (title.toLowerCase().contains(q)) {
                                        qSMap.put(q, Boolean.TRUE);
                                    }
                                });
                            });
                        }

                        if (edmDistribution.getUid() != null) {
                            qSMap.keySet().forEach(q -> {
                                if (edmDistribution.getUid().toLowerCase().contains(q)) {
                                    qSMap.put(q, Boolean.TRUE);
                                }
                            });
                        }

                        if (edmDistribution.getDescription() != null) {
                            edmDistribution.getDescription().forEach(description -> {
                                qSMap.keySet().forEach(q -> {
                                    if (description.toLowerCase().contains(q)) {
                                        qSMap.put(q, Boolean.TRUE);
                                    }
                                });
                            });
                        }

                        if (edmDistribution.getAccessService() != null) {
                            edmDistribution.getAccessService().forEach(accessService -> {
                                WebService edmWebservice = (WebService) preFetched.webServices.get(accessService.getInstanceId());
                                if (edmWebservice != null) {
                                    checkWebServiceFullText(edmWebservice, qSMap);
                                }
                            });
                        }
                    }
                });
            }

            if (qSMap.values().stream().allMatch(b -> b)) {
                tempDatasetList.add(edmDataproduct);
            }
        });

        return new ArrayList<>(tempDatasetList);
    }

    /**
     * Check webservice fields for full text search
     */
    private static void checkWebServiceFullText(WebService edmWebservice, Map<String, Boolean> qSMap) {
        // UID
        if (edmWebservice.getUid() != null) {
            qSMap.keySet().forEach(q -> {
                if (edmWebservice.getUid().toLowerCase().contains(q)) {
                    qSMap.put(q, Boolean.TRUE);
                }
            });
        }

        // Name
        if (edmWebservice.getName() != null) {
            qSMap.keySet().forEach(q -> {
                if (edmWebservice.getName().toLowerCase().contains(q)) {
                    qSMap.put(q, Boolean.TRUE);
                }
            });
        }

        // Description
        if (edmWebservice.getDescription() != null) {
            qSMap.keySet().forEach(q -> {
                if (edmWebservice.getDescription().toLowerCase().contains(q)) {
                    qSMap.put(q, Boolean.TRUE);
                }
            });
        }

        // Keywords
        if (edmWebservice.getKeywords() != null) {
            List<String> webserviceKeywords = Arrays.stream(edmWebservice.getKeywords().split(","))
                    .map(String::toLowerCase)
                    .map(String::trim)
                    .collect(Collectors.toList());

            qSMap.keySet().forEach(q -> {
                if (webserviceKeywords.contains(q)) {
                    qSMap.put(q, Boolean.TRUE);
                }
            });
        }
    }

    /**
     * Check temporal extent from parameters
     */
    public static PeriodOfTime checkTemporalExtent(Map<String, Object> parameters) {
        PeriodOfTime temporal = new PeriodOfTime();
        try {
            if (parameters.containsKey("schema:startDate")) {
                temporal.setStartDate(convertToLocalDateTimeViaSqlTimestamp(
                        new SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
                                .parse(parameters.get("schema:startDate").toString().replace("T", " ").replace("Z", ""))));
            }
            if (parameters.containsKey("schema:endDate")) {
                temporal.setEndDate(convertToLocalDateTimeViaSqlTimestamp(
                        new SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
                                .parse(parameters.get("schema:endDate").toString().replace("T", " ").replace("Z", ""))));
            }
        } catch (java.text.ParseException e) {
            LOGGER.error("Error occurs during search caused by Date parsing", e);
        }
        return temporal;
    }

    /**
     * Convert Date to LocalDateTime
     */
    public static LocalDateTime convertToLocalDateTimeViaSqlTimestamp(Date dateToConvert) {
        return new java.sql.Timestamp(dateToConvert.getTime()).toLocalDateTime();
    }

}