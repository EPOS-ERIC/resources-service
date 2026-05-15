package org.epos.api.core.filtersearch;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;
import org.epos.api.core.PreFetchedEntities;
import org.epos.api.core.search.SearchQueryProcessor;
import org.epos.api.core.search.SearchSynonyms;
import org.epos.api.core.search.TextSimilarityScorer;
import org.epos.api.routines.DatabaseConnections;
import org.epos.eposdatamodel.Address;
import org.epos.eposdatamodel.Identifier;
import org.epos.eposdatamodel.LinkedEntity;
import org.epos.eposdatamodel.Organization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optimized version of OrganizationFilterSearch with:
 * - Pre-fetching of all linked entities (eliminates N+1 queries)
 * - Parallel processing for large datasets
 * - Reduced memory allocations
 * - Better code organization
 */
public class OrganizationFilterSearch {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrganizationFilterSearch.class);

    public static List<Organization> doFilters(List<Organization> organisationsList, Map<String, Object> parameters) {
        SearchSynonyms.initialize();
        
        // Pre-fetch all linked entities at once
        PreFetchedEntities preFetched = preFetchLinkedEntities(organisationsList);

        organisationsList = filterOrganisationsByFullText(organisationsList, parameters, preFetched);
        organisationsList = filterOrganisationsByCountry(organisationsList, parameters, preFetched);

        return organisationsList;
    }

    /**
     * Pre-fetch all linked entities to eliminate N+1 query problem
     */
    private static PreFetchedEntities preFetchLinkedEntities(List<Organization> organisationsList) {
        PreFetchedEntities entities = new PreFetchedEntities();

        Set<String> identifierIds = ConcurrentHashMap.newKeySet();
        Set<String> addressIds = ConcurrentHashMap.newKeySet();

        organisationsList.parallelStream().forEach(org -> {
            if (org.getIdentifier() != null) {
                org.getIdentifier().forEach(le -> identifierIds.add(le.getInstanceId()));
            }
            if (org.getAddress() != null) {
                addressIds.add(org.getAddress().getInstanceId());
            }
        });

        // Batch fetch identifiers
        if (!identifierIds.isEmpty()) {
            LOGGER.info("Pre-fetching: {} identifiers", identifierIds.size());
            List<Identifier> identifiers = (List<Identifier>) AbstractAPI
                    .retrieveAPI(EntityNames.IDENTIFIER.name())
                    .retrieveBunch(new ArrayList<>(identifierIds));

            if (identifiers != null) {
                entities.identifiers = identifiers.parallelStream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toConcurrentMap(Identifier::getInstanceId, i -> i, (a, b) -> a));
            }
        }

        // Batch fetch addresses
        if (!addressIds.isEmpty()) {
            LOGGER.info("Pre-fetching: {} addresses", addressIds.size());
            List<Address> addresses = (List<Address>) AbstractAPI
                    .retrieveAPI(EntityNames.ADDRESS.name())
                    .retrieveBunch(new ArrayList<>(addressIds));

            if (addresses != null) {
                entities.addresses = addresses.parallelStream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toConcurrentMap(Address::getInstanceId, a -> a, (a, b) -> a));
            }
        }

        return entities;
    }

    /**
     * Filter organizations by full text search - OPTIMIZED with pre-fetched data
     */
    private static List<Organization> filterOrganisationsByFullText(List<Organization> organisationsList,
                                                                    Map<String, Object> parameters,
                                                                    PreFetchedEntities preFetched) {
        if (!parameters.containsKey("q")) {
            return organisationsList;
        }

        String originalQuery = parameters.get("q").toString();
        List<String> searchTerms = SearchQueryProcessor.processQueryWithSynonyms(originalQuery, new SearchSynonyms());
        
        if (searchTerms.isEmpty()) {
            return organisationsList;
        }

        Set<Organization> tempDatasetList = ConcurrentHashMap.newKeySet();
        boolean useParallel = organisationsList.size() > 100;

        (useParallel ? organisationsList.parallelStream() : organisationsList.stream()).forEach(edmOrganisation -> {
            Map<String, Boolean> qSMap = searchTerms.stream()
                    .collect(Collectors.toMap(key -> key, value -> Boolean.FALSE));

            if (edmOrganisation.getLegalName() != null && !edmOrganisation.getLegalName().isEmpty()) {
                edmOrganisation.getLegalName().forEach(title -> {
                    qSMap.keySet().forEach(q -> {
                        if (title.toLowerCase().contains(q)) {
                            qSMap.put(q, Boolean.TRUE);
                        }
                    });
                });
            }

            if (edmOrganisation.getIdentifier() != null) {
                edmOrganisation.getIdentifier().forEach(identifierLe -> {
                    Identifier identifier = (Identifier) preFetched.identifiers.get(identifierLe.getInstanceId());
                    if (identifier != null) {
                        qSMap.keySet().forEach(q -> {
                            if (identifier.getIdentifier() != null && identifier.getIdentifier().toLowerCase().contains(q)) {
                                qSMap.put(q, Boolean.TRUE);
                            }
                            if (identifier.getType() != null && identifier.getType().toLowerCase().contains(q)) {
                                qSMap.put(q, Boolean.TRUE);
                            }
                            if (identifier.getType() != null && identifier.getIdentifier() != null) {
                                String combined = (identifier.getType() + identifier.getIdentifier()).toLowerCase();
                                if (combined.contains(q)) {
                                    qSMap.put(q, Boolean.TRUE);
                                }
                            }
                        });
                    }
                });
            }

            if (edmOrganisation.getUid() != null) {
                qSMap.keySet().forEach(q -> {
                    if (edmOrganisation.getUid().toLowerCase().contains(q)) {
                        qSMap.put(q, Boolean.TRUE);
                    }
                });
            }

            if (qSMap.values().stream().allMatch(b -> b)) {
                tempDatasetList.add(edmOrganisation);
            }
        });

        return new ArrayList<>(tempDatasetList);
    }

    /**
     * Filter organizations by country - OPTIMIZED with pre-fetched data
     */
    private static List<Organization> filterOrganisationsByCountry(List<Organization> organisationsList,
                                                                   Map<String, Object> parameters,
                                                                   PreFetchedEntities preFetched) {
        if (!parameters.containsKey("country")) {
            return organisationsList;
        }

        Set<String> countries = Arrays.stream(parameters.get("country").toString().toLowerCase().split(","))
                .collect(Collectors.toSet());

        boolean useParallel = organisationsList.size() > 100;

        return (useParallel ? organisationsList.parallelStream() : organisationsList.stream())
                .filter(edmOrganisation -> {
                    if (edmOrganisation.getAddress() == null) {
                        return false;
                    }

                    Address address = (Address) preFetched.addresses.get(edmOrganisation.getAddress().getInstanceId());
                    if (address == null || address.getCountry() == null) {
                        return false;
                    }

                    return countries.contains(address.getCountry().toLowerCase());
                })
                .collect(Collectors.toList());
    }
}
