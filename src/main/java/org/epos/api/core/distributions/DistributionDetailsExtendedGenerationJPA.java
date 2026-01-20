package org.epos.api.core.distributions;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.epos.api.beans.AvailableContactPoints.AvailableContactPointsBuilder;
import org.epos.api.beans.DataProduct;
import org.epos.api.beans.DataServiceProvider;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.DistributionExtended;
import org.epos.api.beans.Operation;
import org.epos.api.beans.ServiceParameter;
import org.epos.api.beans.SpatialInformation;
import org.epos.api.beans.TemporalCoverage;
import org.epos.api.beans.Webservice;
import org.epos.api.core.AvailableFormatsGeneration;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.PreFetchedEntities;
import org.epos.api.enums.ProviderType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.FacetsNodeTree;
import org.epos.eposdatamodel.Category;
import org.epos.eposdatamodel.ContactPoint;
import org.epos.eposdatamodel.Distribution;
import org.epos.eposdatamodel.Documentation;
import org.epos.eposdatamodel.Identifier;
import org.epos.eposdatamodel.LinkedEntity;
import org.epos.eposdatamodel.Location;
import org.epos.eposdatamodel.Mapping;
import org.epos.eposdatamodel.Organization;
import org.epos.eposdatamodel.PeriodOfTime;
import org.epos.eposdatamodel.Person;
import org.epos.eposdatamodel.WebService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;

public class DistributionDetailsExtendedGenerationJPA {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributionDetailsExtendedGenerationJPA.class);

    private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/resources/details/";
    private static final String EMAIL_SENDER = EnvironmentVariables.API_CONTEXT + "/sender/send-email?id=";

    public static DistributionExtended generate(Map<String, Object> parameters) {
        long startTime = System.currentTimeMillis();
        LOGGER.info("Generating extended distribution details (OPTIMIZED) for parameters: {}", parameters);

        //  Retrieve main distribution
        long retrievalStart = System.currentTimeMillis();
        Distribution distributionSelected = (Distribution) AbstractAPI
                .retrieveAPI(EntityNames.DISTRIBUTION.name())
                .retrieve(parameters.get("id").toString());

        if (distributionSelected == null) {
            LOGGER.warn("Distribution not found for id: {}", parameters.get("id"));
            return null;
        }
        LOGGER.info("[PERF] Distribution retrieval: {} ms", System.currentTimeMillis() - retrievalStart);

        // Get operations related to distribution
        List<String> operationsIdRelatedToDistribution = null;
        if (distributionSelected.getSupportedOperation() != null) {
            operationsIdRelatedToDistribution = distributionSelected.getSupportedOperation().stream()
                    .map(LinkedEntity::getInstanceId)
                    .collect(Collectors.toList());
        }

        // Retrieve DataProduct
        org.epos.eposdatamodel.DataProduct dp = getDataProduct(distributionSelected);
        if (dp == null) {
            LOGGER.warn("DataProduct not found for distribution: {}", distributionSelected.getInstanceId());
            return null;
        }

        // Retrieve WebService
        WebService ws = getWebService(distributionSelected);
        if (ws == null && distributionSelected.getAccessService() != null) {
            LOGGER.warn("WebService not found for distribution: {}", distributionSelected.getInstanceId());
            return null;
        }

        // Pre-fetch ALL linked entities
        long prefetchStart = System.currentTimeMillis();
        PreFetchedEntities preFetched = preFetchLinkedEntities(dp, ws, operationsIdRelatedToDistribution);
        LOGGER.info("[PERF] Pre-fetching entities: {} ms", System.currentTimeMillis() - prefetchStart);

        // Build extended distribution object
        long buildStart = System.currentTimeMillis();
        DistributionExtended distribution = buildDistributionExtended(
                distributionSelected, dp, ws, preFetched, operationsIdRelatedToDistribution
        );
        LOGGER.info("[PERF] Building extended distribution: {} ms", System.currentTimeMillis() - buildStart);

        long endTime = System.currentTimeMillis();
        LOGGER.info("[PERF] TOTAL: {} ms", endTime - startTime);

        return distribution;
    }

    /**
     * Get DataProduct from distribution
     */
    private static org.epos.eposdatamodel.DataProduct getDataProduct(Distribution distributionSelected) {
        if (distributionSelected.getDataProduct() != null && !distributionSelected.getDataProduct().isEmpty()) {
            return (org.epos.eposdatamodel.DataProduct) LinkedEntityAPI
                    .retrieveFromLinkedEntity(distributionSelected.getDataProduct().get(0));
        }
        return null;
    }

    /**
     * Get WebService from distribution
     */
    private static WebService getWebService(Distribution distributionSelected) {
        if (distributionSelected.getAccessService() != null && !distributionSelected.getAccessService().isEmpty()) {
            return (WebService) LinkedEntityAPI
                    .retrieveFromLinkedEntity(distributionSelected.getAccessService().get(0));
        }
        return null;
    }

    /**
     * Pre-fetch ALL linked entities to eliminate N+1 query problem
     */
    private static PreFetchedEntities preFetchLinkedEntities(org.epos.eposdatamodel.DataProduct dp,
                                                             WebService ws,
                                                             List<String> operationsIdRelatedToDistribution) {
        PreFetchedEntities entities = new PreFetchedEntities();

        // Collect all IDs
        Set<String> identifierIds = new HashSet<>();
        Set<String> locationIds = new HashSet<>();
        Set<String> temporalIds = new HashSet<>();
        Set<String> organizationIds = new HashSet<>();
        Set<String> categoryIds = new HashSet<>();
        Set<String> contactPointIds = new HashSet<>();
        Set<String> personIds = new HashSet<>();
        Set<String> documentationIds = new HashSet<>();
        Set<String> operationIds = new HashSet<>();
        Set<String> mappingIds = new HashSet<>();

        // From DataProduct
        if (dp.getIdentifier() != null) {
            dp.getIdentifier().forEach(le -> identifierIds.add(le.getInstanceId()));
        }
        if (dp.getSpatialExtent() != null) {
            dp.getSpatialExtent().forEach(le -> locationIds.add(le.getInstanceId()));
        }
        if (dp.getTemporalExtent() != null) {
            dp.getTemporalExtent().forEach(le -> temporalIds.add(le.getInstanceId()));
        }
        if (dp.getPublisher() != null) {
            dp.getPublisher().forEach(le -> organizationIds.add(le.getInstanceId()));
        }
        if (dp.getCategory() != null) {
            dp.getCategory().forEach(le -> categoryIds.add(le.getInstanceId()));
        }
        if (dp.getContactPoint() != null) {
            dp.getContactPoint().forEach(le -> contactPointIds.add(le.getInstanceId()));
        }

        // From WebService
        if (ws != null) {
            if (ws.getSpatialExtent() != null) {
                ws.getSpatialExtent().forEach(le -> locationIds.add(le.getInstanceId()));
            }
            if (ws.getTemporalExtent() != null) {
                ws.getTemporalExtent().forEach(le -> temporalIds.add(le.getInstanceId()));
            }
            if (ws.getProvider() != null) {
                organizationIds.add(ws.getProvider().getInstanceId());
            }
            if (ws.getCategory() != null) {
                ws.getCategory().forEach(le -> categoryIds.add(le.getInstanceId()));
            }
            if (ws.getContactPoint() != null) {
                ws.getContactPoint().forEach(le -> contactPointIds.add(le.getInstanceId()));
            }
            if (ws.getDocumentation() != null) {
                ws.getDocumentation().forEach(le -> documentationIds.add(le.getInstanceId()));
            }
            if (ws.getSupportedOperation() != null) {
                ws.getSupportedOperation().forEach(le -> operationIds.add(le.getInstanceId()));
            }
        }

        LOGGER.info("Pre-fetching Phase 1: {} identifiers, {} locations, {} temporal, {} organizations, {} categories, {} contacts, {} documentations, {} operations",
                identifierIds.size(), locationIds.size(), temporalIds.size(),
                organizationIds.size(), categoryIds.size(), contactPointIds.size(),
                documentationIds.size(), operationIds.size());

        // Batch fetch Phase 1 entities
        entities.identifiers = fetchEntityBatch(EntityNames.IDENTIFIER, identifierIds);
        entities.locations = fetchEntityBatch(EntityNames.LOCATION, locationIds);
        entities.temporals = fetchEntityBatch(EntityNames.PERIODOFTIME, temporalIds);
        entities.organizations = fetchEntityBatch(EntityNames.ORGANIZATION, organizationIds);
        entities.categories = fetchEntityBatch(EntityNames.CATEGORY, categoryIds);
        entities.contactPoints = fetchEntityBatch(EntityNames.CONTACTPOINT, contactPointIds);
        entities.documentations = fetchEntityBatch(EntityNames.DOCUMENTATION, documentationIds);
        entities.operations = fetchEntityBatch(EntityNames.OPERATION, operationIds);

        // Phase 2: Collect person IDs from contact points
        entities.contactPoints.values().stream()
                .filter(obj -> obj instanceof ContactPoint)
                .map(obj -> (ContactPoint) obj)
                .filter(cp -> cp.getPerson() != null)
                .forEach(cp -> personIds.add(cp.getPerson().getInstanceId()));

        // Phase 2: Collect mapping IDs from operations
        entities.operations.values().stream()
                .filter(obj -> obj instanceof org.epos.eposdatamodel.Operation)
                .map(obj -> (org.epos.eposdatamodel.Operation) obj)
                .filter(op -> op.getMapping() != null)
                .forEach(op -> op.getMapping().forEach(le -> mappingIds.add(le.getInstanceId())));

        LOGGER.info("Pre-fetching Phase 2: {} persons, {} mappings", personIds.size(), mappingIds.size());

        // Batch fetch Phase 2 entities
        entities.persons = fetchEntityBatch(EntityNames.PERSON, personIds);
        entities.mappings = fetchEntityBatch(EntityNames.MAPPING, mappingIds);

        return entities;
    }

    /**
     * Batch fetch helper
     */
    private static Map<String, Object> fetchEntityBatch(EntityNames entityName, Set<String> ids) {
        if (ids.isEmpty()) {
            return new HashMap<>();
        }

        List<String> idList = new ArrayList<>(ids);
        List<?> results = (List<?>) AbstractAPI.retrieveAPI(entityName.name()).retrieveBunch(idList);

        Map<String, Object> map = new HashMap<>();
        if (results != null) {
            results.stream()
                    .filter(Objects::nonNull)
                    .forEach(obj -> {
                        if (obj instanceof org.epos.eposdatamodel.EPOSDataModelEntity) {
                            org.epos.eposdatamodel.EPOSDataModelEntity entity =
                                    (org.epos.eposdatamodel.EPOSDataModelEntity) obj;
                            map.put(entity.getInstanceId(), entity);
                        }
                    });
        }
        return map;
    }

    /**
     * Build the complete DistributionExtended object using pre-fetched entities
     */
    private static DistributionExtended buildDistributionExtended(Distribution distributionSelected,
                                                                  org.epos.eposdatamodel.DataProduct dp,
                                                                  WebService ws,
                                                                  PreFetchedEntities preFetched,
                                                                  List<String> operationsIdRelatedToDistribution) {
        DistributionExtended distribution = new DistributionExtended();

        // Distribution bean population
        populateDistribution(distribution, distributionSelected, dp, ws);

        // DataProduct bean population
        DataProduct dataproduct = buildDataProduct(dp, preFetched);
        distribution.getRelatedDataProducts().add(dataproduct);

        // WebService bean population
        if (ws != null) {
            Webservice webservice = buildWebService(ws, preFetched, operationsIdRelatedToDistribution);
            distribution.getRelatedWebservice().add(webservice);
        }

        // Contact Points
        populateContactPoints(distribution, dp, ws);

        // Available formats
        distribution.setAvailableFormats(AvailableFormatsGeneration.generate(distributionSelected));

        // Categories (facets)
        populateCategories(distribution, distributionSelected, dp, ws, preFetched);

        return distribution;
    }

    /**
     * Populate basic distribution fields
     */
    private static void populateDistribution(DistributionExtended distribution,
                                             Distribution distributionSelected,
                                             org.epos.eposdatamodel.DataProduct dp,
                                             WebService ws) {
        if (distributionSelected.getTitle() != null) {
            distribution.setTitle(Optional.of(String.join(".", distributionSelected.getTitle())).orElse(null));
        }

        if (distributionSelected.getType() != null) {
            String[] type = distributionSelected.getType().split("/");
            distribution.setType(type[type.length - 1]);
        }

        if (distributionSelected.getDescription() != null) {
            distribution.setDescription(Optional.of(String.join(".", distributionSelected.getDescription())).orElse(null));
        }

        distribution.setId(distributionSelected.getInstanceId());
        distribution.setUid(distributionSelected.getUid());
        distribution.setVersioningStatus(distributionSelected.getVersion());

        if (distributionSelected.getDownloadURL() != null) {
            distribution.setDownloadURL(Optional.of(String.join(".", distributionSelected.getDownloadURL())).orElse(null));
        }

        distribution.setLicense(distributionSelected.getLicence());
        distribution.setFrequencyUpdate(dp.getAccrualPeriodicity());
        distribution.setHref(EnvironmentVariables.API_HOST + API_PATH_DETAILS + distributionSelected.getInstanceId());
        distribution.setHrefExtended(EnvironmentVariables.API_HOST + API_PATH_DETAILS +
                distributionSelected.getInstanceId() + "?extended=true");

        // Keywords
        Set<String> keywords = new HashSet<>(
                Arrays.stream(Optional.ofNullable(dp.getKeywords()).orElse("").split(",\t"))
                        .map(String::toLowerCase)
                        .map(String::trim)
                        .collect(Collectors.toList())
        );

        if (ws != null) {
            keywords.addAll(Arrays.stream(Optional.ofNullable(ws.getKeywords()).orElse("").split(",\t"))
                    .map(String::toLowerCase)
                    .map(String::trim)
                    .collect(Collectors.toList()));
        }

        keywords.removeAll(Collections.singleton(null));
        keywords.removeAll(Collections.singleton(""));
        distribution.setKeywords(new ArrayList<>(keywords));
    }

    /**
     * Build DataProduct bean using pre-fetched entities
     */
    private static DataProduct buildDataProduct(org.epos.eposdatamodel.DataProduct dp,
                                                PreFetchedEntities preFetched) {
        DataProduct dataproduct = new DataProduct();
        dataproduct.setType(dp.getType());
        dataproduct.setVersion(dp.getVersionInfo());
        dataproduct.setUid(dp.getUid());
        dataproduct.setId(dp.getInstanceId());

        // Identifiers
        if (dp.getIdentifier() == null || dp.getIdentifier().isEmpty()) {
            HashMap<String, String> singleIdentifier = new HashMap<>();
            singleIdentifier.put("type", "plain");
            singleIdentifier.put("value", dp.getUid());
            dataproduct.getIdentifiers().add(singleIdentifier);
        } else {
            dp.getIdentifier().forEach(identifierLe -> {
                Identifier identifier = (Identifier) preFetched.identifiers.get(identifierLe.getInstanceId());
                if (identifier != null) {
                    HashMap<String, String> singleIdentifier = new HashMap<>();
                    singleIdentifier.put("type", identifier.getType());
                    singleIdentifier.put("value", identifier.getIdentifier());
                    dataproduct.getIdentifiers().add(singleIdentifier);
                }
            });
        }

        // Spatial extent
        if (dp.getSpatialExtent() != null) {
            dp.getSpatialExtent().forEach(le -> {
                Location s = (Location) preFetched.locations.get(le.getInstanceId());
                if (s != null) {
                    dataproduct.getSpatial().addPaths(
                            SpatialInformation.doSpatial(s.getLocation()),
                            SpatialInformation.checkPoint(s.getLocation())
                    );
                }
            });
        }

        // Temporal coverage
        TemporalCoverage tc = new TemporalCoverage();
        if (dp.getTemporalExtent() != null && !dp.getTemporalExtent().isEmpty()) {
            PeriodOfTime periodOfTime = (PeriodOfTime) preFetched.temporals.get(
                    dp.getTemporalExtent().get(0).getInstanceId()
            );

            if (periodOfTime != null) {
                String startDate = formatDate(periodOfTime.getStartDate());
                String endDate = formatDate(periodOfTime.getEndDate());
                tc.setStartDate(startDate);
                tc.setEndDate(endDate);
            }
        }

        // Data providers
        if (dp.getPublisher() != null) {
            List<DataServiceProvider> dataProviders = new ArrayList<>();
            dp.getPublisher().forEach(publisherLe -> {
                Organization publisher = (Organization) preFetched.organizations.get(publisherLe.getInstanceId());
                if (publisher != null) {
                    dataProviders.addAll(DataServiceProviderGeneration.getProviders(List.of(publisher)));
                }
            });
            dataproduct.setDataProvider(dataProviders);
        }

        dataproduct.setTemporalCoverage(List.of(tc));

        // Contact points
        if (dp.getContactPoint() != null && !dp.getContactPoint().isEmpty()) {
            dp.getContactPoint().forEach(contactsLe -> {
                ContactPoint contactpoint = (ContactPoint) preFetched.contactPoints.get(contactsLe.getInstanceId());
                if (contactpoint != null) {
                    HashMap<String, Object> contact = new HashMap<>();
                    contact.put("id", contactpoint.getInstanceId());
                    contact.put("metaid", contactpoint.getMetaId());
                    contact.put("uid", contactpoint.getUid());

                    if (contactpoint.getPerson() != null) {
                        Person person = (Person) preFetched.persons.get(contactpoint.getPerson().getInstanceId());
                        if (person != null) {
                            HashMap<String, Object> relatedPerson = new HashMap<>();
                            relatedPerson.put("id", person.getInstanceId());
                            relatedPerson.put("metaid", person.getMetaId());
                            relatedPerson.put("uid", person.getUid());
                            contact.put("person", relatedPerson);
                        }
                    }
                    dataproduct.getContactPoints().add(contact);
                }
            });
        }

        // Science domains
        if (dp.getCategory() != null && !dp.getCategory().isEmpty()) {
            List<String> scienceDomains = dp.getCategory().stream()
                    .map(linkedEntity -> (Category) preFetched.categories.get(linkedEntity.getInstanceId()))
                    .filter(Objects::nonNull)
                    .map(Category::getName)
                    .collect(Collectors.toList());
            dataproduct.setScienceDomain(scienceDomains);
        }

        dataproduct.setAccessRights(dp.getAccessRight());

        // Provenance
        if (dp.getProvenance() != null && !dp.getProvenance().isEmpty()) {
            dp.getProvenance().forEach(instance -> {
                HashMap<String, String> prov = new HashMap<>();
                prov.put("provenance", instance);
                dataproduct.getProvenance().add(prov);
            });
        }

        return dataproduct;
    }

    /**
     * Build WebService bean using pre-fetched entities
     */
    private static Webservice buildWebService(WebService ws,
                                              PreFetchedEntities preFetched,
                                              List<String> operationsIdRelatedToDistribution) {
        Webservice webservice = new Webservice();

        // Contact points
        if (ws.getContactPoint() != null) {
            ws.getContactPoint().forEach(contacts -> {
                ContactPoint contactpoint = (ContactPoint) preFetched.contactPoints.get(contacts.getInstanceId());
                if (contactpoint != null) {
                    HashMap<String, Object> contact = new HashMap<>();
                    contact.put("id", contactpoint.getInstanceId());
                    contact.put("metaid", contactpoint.getMetaId());
                    contact.put("uid", contactpoint.getUid());

                    if (contactpoint.getPerson() != null) {
                        Person person = (Person) preFetched.persons.get(contactpoint.getPerson().getInstanceId());
                        if (person != null) {
                            HashMap<String, String> relatedPerson = new HashMap<>();
                            relatedPerson.put("id", person.getInstanceId());
                            relatedPerson.put("metaid", person.getMetaId());
                            relatedPerson.put("uid", person.getUid());
                            contact.put("person", relatedPerson);
                        }
                    }
                    webservice.getContactPoints().add(contact);
                }
            });
        }

        webservice.setDescription(ws.getDescription());

        // Documentation
        if (ws.getDocumentation() != null) {
            String documentation = ws.getDocumentation().stream()
                    .map(linkedEntity -> (Documentation) preFetched.documentations.get(linkedEntity.getInstanceId()))
                    .filter(Objects::nonNull)
                    .map(Documentation::getUri)
                    .collect(Collectors.joining("."));
            webservice.setDocumentation(documentation);
        }

        webservice.setName(Optional.ofNullable(ws.getName()).orElse(null));

        // Provider
        if (ws.getProvider() != null) {
            Organization provider = (Organization) preFetched.organizations.get(ws.getProvider().getInstanceId());
            if (provider != null) {
                List<DataServiceProvider> serviceProviders = DataServiceProviderGeneration.getProviders(List.of(provider));
                if (!serviceProviders.isEmpty()) {
                    webservice.setProvider(serviceProviders.get(0));
                }
            }
        }

        // Spatial extent
        if (ws.getSpatialExtent() != null && !ws.getSpatialExtent().isEmpty()) {
            ws.getSpatialExtent().forEach(sLe -> {
                Location s = (Location) preFetched.locations.get(sLe.getInstanceId());
                if (s != null) {
                    webservice.getSpatial().addPaths(
                            SpatialInformation.doSpatial(s.getLocation()),
                            SpatialInformation.checkPoint(s.getLocation())
                    );
                }
            });
        }

        // Temporal coverage
        if (ws.getTemporalExtent() != null && !ws.getTemporalExtent().isEmpty()) {
            ws.getTemporalExtent().forEach(temporalLe -> {
                PeriodOfTime temporal = (PeriodOfTime) preFetched.temporals.get(temporalLe.getInstanceId());
                if (temporal != null) {
                    TemporalCoverage tcws = new TemporalCoverage();
                    String startDate = formatDate(temporal.getStartDate());
                    String endDate = formatDate(temporal.getEndDate());
                    tcws.setStartDate(startDate);
                    tcws.setEndDate(endDate);
                    webservice.getTemporalCoverage().add(tcws);
                }
            });
        }

        // Service types
        if (ws.getCategory() != null) {
            List<String> types = ws.getCategory().stream()
                    .map(linkedEntity -> (Category) preFetched.categories.get(linkedEntity.getInstanceId()))
                    .filter(Objects::nonNull)
                    .map(Category::getName)
                    .collect(Collectors.toList());
            webservice.setType(types);
        }

        // Operations
        if (ws.getSupportedOperation() != null) {
            List<org.epos.eposdatamodel.Operation> operations = ws.getSupportedOperation().stream()
                    .map(linkedEntity -> (org.epos.eposdatamodel.Operation) preFetched.operations.get(linkedEntity.getInstanceId()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            operations.forEach(op -> {
                if (operationsIdRelatedToDistribution != null &&
                        operationsIdRelatedToDistribution.contains(op.getInstanceId())) {
                    Operation operation = new Operation();
                    operation.setMethod(op.getMethod());
                    operation.setEndpoint(op.getTemplate());
                    operation.setUid(op.getUid());

                    // Parameters
                    if (op.getMapping() != null && !op.getMapping().isEmpty()) {
                        op.getMapping().forEach(mpLe -> {
                            Mapping mp = (Mapping) preFetched.mappings.get(mpLe.getInstanceId());
                            if (mp != null) {
                                ServiceParameter sp = new ServiceParameter();
                                sp.setDefaultValue(mp.getDefaultValue());
                                sp.setEnumValue(mp.getParamValue() != null ? mp.getParamValue() : new ArrayList<>());
                                sp.setName(mp.getVariable());
                                sp.setMaxValue(mp.getMaxValue());
                                sp.setMinValue(mp.getMinValue());
                                sp.setLabel(mp.getLabel() != null ? mp.getLabel().replaceAll("@en", "") : null);
                                sp.setProperty(mp.getProperty());
                                sp.setRequired(Boolean.parseBoolean(mp.getRequired()));
                                sp.setType(mp.getRange() != null ? mp.getRange().replace("xsd:", "") : null);
                                sp.setValue(null);
                                sp.setValuePattern(mp.getValuePattern());
                                sp.setVersion(null);
                                sp.setReadOnlyValue(mp.getReadOnlyValue());
                                sp.setMultipleValue(mp.getMultipleValues());
                                operation.getServiceParameters().add(sp);
                            }
                        });
                    }
                    webservice.getOperations().add(operation);
                }
            });
        }

        return webservice;
    }

    /**
     * Populate contact points
     */
    private static void populateContactPoints(DistributionExtended distribution,
                                              org.epos.eposdatamodel.DataProduct dp,
                                              WebService ws) {
        if (ws != null && !ws.getContactPoint().isEmpty()) {
            distribution.getAvailableContactPoints().add(
                    new AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + distribution.getId() +
                                    "&contactType=" + ProviderType.SERVICEPROVIDERS)
                            .type(ProviderType.SERVICEPROVIDERS)
                            .build()
            );
        }

        if (dp.getContactPoint() != null && !dp.getContactPoint().isEmpty()) {
            distribution.getAvailableContactPoints().add(
                    new AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + distribution.getId() +
                                    "&contactType=" + ProviderType.DATAPROVIDERS)
                            .type(ProviderType.DATAPROVIDERS)
                            .build()
            );
        }

        if ((ws != null && !ws.getContactPoint().isEmpty() && !dp.getContactPoint().isEmpty())) {
            distribution.getAvailableContactPoints().add(
                    new AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + distribution.getId() +
                                    "&contactType=" + ProviderType.ALL)
                            .type(ProviderType.ALL)
                            .build()
            );
        }
    }

    /**
     * Populate categories (facets)
     */
    private static void populateCategories(DistributionExtended distribution,
                                           Distribution distributionSelected,
                                           org.epos.eposdatamodel.DataProduct dp,
                                           WebService ws,
                                           PreFetchedEntities preFetched) {
        ArrayList<DiscoveryItem> discoveryList = new ArrayList<>();

        Set<String> facetsDataProviders = new HashSet<>();
        if (dp.getPublisher() != null) {
            dp.getPublisher().forEach(edmMetaIdLe -> {
                Organization edmMetaId = (Organization) preFetched.organizations.get(edmMetaIdLe.getInstanceId());
                if (edmMetaId != null && edmMetaId.getLegalName() != null && !edmMetaId.getLegalName().isEmpty()) {
                    facetsDataProviders.add(String.join(",", edmMetaId.getLegalName()));
                }
            });
        }

        Set<String> facetsServiceProviders = new HashSet<>();
        if (ws != null && ws.getProvider() != null) {
            Organization edmMetaId = (Organization) preFetched.organizations.get(ws.getProvider().getInstanceId());
            if (edmMetaId != null && edmMetaId.getLegalName() != null && !edmMetaId.getLegalName().isEmpty()) {
                facetsServiceProviders.add(String.join(",", edmMetaId.getLegalName()));
            }
        }

        List<String> categoryList = dp.getCategory().stream()
                .map(linkedEntity -> (Category) preFetched.categories.get(linkedEntity.getInstanceId()))
                .filter(Objects::nonNull)
                .map(Category::getUid)
                .filter(uid -> uid.contains("category:"))
                .collect(Collectors.toList());

        discoveryList.add(new DiscoveryItem.DiscoveryItemBuilder(
                distributionSelected.getInstanceId(),
                EnvironmentVariables.API_HOST + API_PATH_DETAILS + distributionSelected.getInstanceId(),
                EnvironmentVariables.API_HOST + API_PATH_DETAILS + distributionSelected.getInstanceId() + "?extended=true")
                .uid(distribution.getUid())
                .metaId(distribution.getMetaId())
                .title(distribution.getTitle() != null ? String.join(";", distribution.getTitle()) : null)
                .description(distribution.getDescription() != null ? String.join(";", distribution.getDescription()) : null)
                .availableFormats(AvailableFormatsGeneration.generate(distributionSelected))
                .sha256id(DigestUtils.sha256Hex(distribution.getUid()))
                .dataProvider(facetsDataProviders)
                .versioningStatus(distribution.getVersioningStatus())
                .serviceProvider(facetsServiceProviders)
                .categories(categoryList.isEmpty() ? null : categoryList)
                .editorId(distribution.getEditorId())
                .build());

        FacetsNodeTree categories = FacetsGeneration.generateResponseUsingCategories(discoveryList, Facets.Type.DATA);
        categories.getNodes().forEach(node -> node.setDistributions(null));
        distribution.setCategories(categories.getFacets());
    }

    /**
     * Format date to ISO format
     */
    private static String formatDate(java.time.LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        String formatted = Timestamp.valueOf(dateTime).toString().replace(".0", "Z").replace(" ", "T");
        if (!formatted.contains("Z")) {
            formatted = formatted + "Z";
        }
        return formatted;
    }
}