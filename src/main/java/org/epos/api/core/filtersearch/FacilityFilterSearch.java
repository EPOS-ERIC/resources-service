package org.epos.api.core.filtersearch;

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
import org.epos.api.utility.BBoxToPolygon;
import org.epos.eposdatamodel.*;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optimized version of FacilityFilterSearch with:
 * - Pre-fetching of all linked entities (eliminates N+1 queries)
 * - Parallel processing for large datasets
 * - Reduced memory allocations
 * - Better code organization
 */
public class FacilityFilterSearch {

    private static final Logger LOGGER = LoggerFactory.getLogger(FacilityFilterSearch.class);

    private static final String NORTHEN_LAT = "epos:northernmostLatitude";
    private static final String SOUTHERN_LAT = "epos:southernmostLatitude";
    private static final String WESTERN_LON = "epos:westernmostLongitude";
    private static final String EASTERN_LON = "epos:easternmostLongitude";

    private static final String PARAMETER_FACILITY_TYPES = "facilitytypes";
    private static final String PARAMETER_EQUIPMENT_TYPES = "equipmenttypes";

    public static List<Facility> doFilters(List<Facility> facilityList, Map<String, Object> parameters,
                                           List<Category> categories, List<Organization> organizationForOwners) {

        SearchSynonyms.initialize();
        
        // Pre-fetch all linked entities at once
        PreFetchedEntities preFetched = preFetchLinkedEntities(facilityList);

        facilityList = filterFacilityByFullText(facilityList, parameters);
        facilityList = filterFacilityByKeywords(facilityList, parameters);
        facilityList = filterFacilityByOrganizations(facilityList, parameters, organizationForOwners);
        facilityList = filterFacilityByBoundingBox(facilityList, parameters, preFetched);
        facilityList = filterByFacilityType(facilityList, parameters, categories);
        facilityList = filterByEquipmentType(facilityList, parameters, categories);

        return facilityList;
    }

    /**
     * Pre-fetch all linked entities to eliminate N+1 query problem
     */
    private static PreFetchedEntities preFetchLinkedEntities(List<Facility> facilityList) {
        PreFetchedEntities entities = new PreFetchedEntities();

        Set<String> locationIds = ConcurrentHashMap.newKeySet();

        facilityList.parallelStream()
                .filter(f -> f.getSpatialExtent() != null)
                .forEach(f -> f.getSpatialExtent().forEach(le -> locationIds.add(le.getInstanceId())));

        if (!locationIds.isEmpty()) {
            LOGGER.info("Pre-fetching: {} locations", locationIds.size());
            List<Location> locations = (List<Location>) AbstractAPI
                    .retrieveAPI(EntityNames.LOCATION.name())
                    .retrieveBunch(new ArrayList<>(locationIds));

            if (locations != null) {
                entities.locations = locations.parallelStream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toConcurrentMap(Location::getInstanceId, l -> l, (a, b) -> a));
            }
        }

        return entities;
    }

    /**
     * Filter by facility type
     */
    private static List<Facility> filterByFacilityType(List<Facility> facilityList, Map<String, Object> parameters,
                                                       List<Category> categories) {
        if (!parameters.containsKey(PARAMETER_FACILITY_TYPES)) {
            return facilityList;
        }

        Set<String> requestedTypes = new HashSet<>(
                Arrays.asList(parameters.get(PARAMETER_FACILITY_TYPES).toString().split(",")));

        // Build UID to instance ID mapping
        Map<String, String> uidToInstanceId = categories.stream()
                .filter(cat -> cat.getUid() != null && cat.getInstanceId() != null)
                .collect(Collectors.toMap(Category::getUid, Category::getInstanceId));

        boolean useParallel = facilityList.size() > 100;

        return (useParallel ? facilityList.parallelStream() : facilityList.stream())
                .filter(facility -> {
                    if (facility.getType() == null) {
                        return false;
                    }
                    String instanceId = uidToInstanceId.get(facility.getType());
                    return instanceId != null && requestedTypes.contains(instanceId);
                })
                .collect(Collectors.toList());
    }

    /**
     * Filter by equipment type - TODO: implement when relationship is clarified
     */
    private static List<Facility> filterByEquipmentType(List<Facility> facilityList, Map<String, Object> parameters,
                                                        List<Category> categories) {
        if (!parameters.containsKey(PARAMETER_EQUIPMENT_TYPES)) {
            return facilityList;
        }

        // TODO: Implement when equipment relationship is clarified
        LOGGER.warn("Equipment type filtering not yet implemented");
        return facilityList;
    }

    /**
     * Filter by bounding box - OPTIMIZED with pre-fetched data and parallel processing
     */
    private static List<Facility> filterFacilityByBoundingBox(List<Facility> facilityList,
                                                              Map<String, Object> parameters,
                                                              PreFetchedEntities preFetched) {
        if (!parameters.containsKey(NORTHEN_LAT) || !parameters.containsKey(SOUTHERN_LAT)
                || !parameters.containsKey(WESTERN_LON) || !parameters.containsKey(EASTERN_LON)) {
            return facilityList;
        }

        GeometryFactory geometryFactory = new GeometryFactory();
        WKTReader reader = new WKTReader(geometryFactory);

        try {
            final Geometry inputGeometry = reader.read(BBoxToPolygon.transform(parameters));
            if (inputGeometry == null) {
                return facilityList;
            }

            boolean useParallel = facilityList.size() > 100;

            List<Facility> filtered = (useParallel ? facilityList.parallelStream() : facilityList.stream())
                    .filter(fac -> {
                        if (fac.getSpatialExtent() == null) {
                            return false;
                        }

                        return fac.getSpatialExtent().stream()
                                .map(le -> preFetched.locations.get(le.getInstanceId()))
                                .filter(Objects::nonNull)
                                .anyMatch(location -> {
                                    try {
                                        Geometry dsGeometry = reader.read(((Location)location).getLocation());
                                        return inputGeometry.intersects(dsGeometry);
                                    } catch (ParseException e) {
                                        LOGGER.error("Error parsing geometry for facility {}", fac.getMetaId(), e);
                                        return false;
                                    }
                                });
                    })
                    .collect(Collectors.toList());

            return filtered;

        } catch (org.locationtech.jts.io.ParseException e) {
            LOGGER.error("Error occurs during BBOX input parsing", e);
            return facilityList;
        }
    }

    /**
     * Filter by organizations - optimized
     */
    private static List<Facility> filterFacilityByOrganizations(List<Facility> facilityList,
                                                                Map<String, Object> parameters,
                                                                List<Organization> organizationForOwners) {
        if (!parameters.containsKey("organisations")) {
            return facilityList;
        }

        Set<String> organisations = new HashSet<>(
                Arrays.asList(parameters.get("organisations").toString().split(",")));

        // Build provider IDs for requested organizations
        Set<String> validProviderIds = organizationForOwners.stream()
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
                .filter(organisations::contains)
                .collect(Collectors.toSet());

        boolean useParallel = facilityList.size() > 100;
        Set<Facility> tempFacilityList = ConcurrentHashMap.newKeySet();

        (useParallel ? facilityList.parallelStream() : facilityList.stream()).forEach(fac -> {
            // TODO: Implement proper organization matching when relationship is clarified
            // For now, keep all facilities (placeholder implementation)
            tempFacilityList.add(fac);
        });

        return new ArrayList<>(tempFacilityList);
    }

    /**
     * Filter by keywords - optimized
     */
    private static List<Facility> filterFacilityByKeywords(List<Facility> facilityList, Map<String, Object> parameters) {
        if (!parameters.containsKey("keywords")) {
            return facilityList;
        }

        Set<String> keywordSet = Arrays.stream(parameters.get("keywords").toString().split(","))
                .map(String::toLowerCase)
                .map(String::trim)
                .collect(Collectors.toSet());

        boolean useParallel = facilityList.size() > 100;

        return (useParallel ? facilityList.parallelStream() : facilityList.stream())
                .filter(fac -> {
                    if (fac.getKeywords() == null) {
                        return false;
                    }

                    Set<String> facKeywords = new HashSet<>(fac.getKeywords());

                    return !Collections.disjoint(facKeywords, keywordSet);
                })
                .collect(Collectors.toList());
    }

    /**
     * Filter by full text search - optimized with parallel processing
     */
    private static List<Facility> filterFacilityByFullText(List<Facility> facilityList, Map<String, Object> parameters) {
        if (!parameters.containsKey("q")) {
            return facilityList;
        }

        String originalQuery = parameters.get("q").toString();
        List<String> searchTerms = SearchQueryProcessor.processQueryWithSynonyms(originalQuery, new SearchSynonyms());
        
        if (searchTerms.isEmpty()) {
            return facilityList;
        }

        Set<Facility> tempDatasetList = ConcurrentHashMap.newKeySet();
        boolean useParallel = facilityList.size() > 100;

        (useParallel ? facilityList.parallelStream() : facilityList.stream()).forEach(edmFacility -> {
            Map<String, Boolean> qSMap = searchTerms.stream()
                    .collect(Collectors.toMap(key -> key, value -> Boolean.FALSE));

            if (edmFacility.getKeywords() != null) {
                List<String> dataproductKeywords = edmFacility.getKeywords();

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

            if (edmFacility.getTitle() != null) {
                qSMap.keySet().forEach(q -> {
                    if (edmFacility.getTitle().toLowerCase().contains(q)) {
                        qSMap.put(q, Boolean.TRUE);
                    }
                });
            }

            if (edmFacility.getDescription() != null) {
                qSMap.keySet().forEach(q -> {
                    if (edmFacility.getDescription().toLowerCase().contains(q)) {
                        qSMap.put(q, Boolean.TRUE);
                    }
                });
            }

            if (edmFacility.getUid() != null) {
                qSMap.keySet().forEach(q -> {
                    if (edmFacility.getUid().toLowerCase().contains(q)) {
                        qSMap.put(q, Boolean.TRUE);
                    }
                });
            }

            if (qSMap.values().stream().anyMatch(b -> b)) {
                tempDatasetList.add(edmFacility);
            }
        });

        return new ArrayList<>(tempDatasetList);
    }
}