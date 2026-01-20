package org.epos.api.core.distributions;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.epos.api.beans.AvailableContactPoints.AvailableContactPointsBuilder;
import org.epos.api.beans.DataServiceProvider;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.Distribution;
import org.epos.api.beans.ServiceParameter;
import org.epos.api.beans.SpatialInformation;
import org.epos.api.beans.TemporalCoverage;
import org.epos.api.core.AvailableFormatsGeneration;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.PreFetchedEntities;
import org.epos.api.enums.ProviderType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.FacetsNodeTree;
import org.epos.eposdatamodel.Category;
import org.epos.eposdatamodel.DataProduct;
import org.epos.eposdatamodel.Documentation;
import org.epos.eposdatamodel.Identifier;
import org.epos.eposdatamodel.LinkedEntity;
import org.epos.eposdatamodel.Location;
import org.epos.eposdatamodel.Mapping;
import org.epos.eposdatamodel.Operation;
import org.epos.eposdatamodel.Organization;
import org.epos.eposdatamodel.PeriodOfTime;
import org.epos.eposdatamodel.WebService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;

public class DistributionDetailsGenerationJPA {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributionDetailsGenerationJPA.class);

    private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/resources/details/";
    private static final String EMAIL_SENDER = EnvironmentVariables.API_CONTEXT + "/sender/send-email?id=";

    public static Distribution generate(Map<String, Object> parameters) {
        return generate(parameters, Facets.Type.DATA);
    }

    public static Distribution generate(Map<String, Object> parameters, Facets.Type facetsType) {
        long startTime = System.currentTimeMillis();
        LOGGER.info("Generating distribution details (OPTIMIZED) for parameters: {}", parameters);

        // Retrieve main distribution
        long retrievalStart = System.currentTimeMillis();
        org.epos.eposdatamodel.Distribution distributionSelected = (org.epos.eposdatamodel.Distribution)
                AbstractAPI.retrieveAPI(EntityNames.DISTRIBUTION.name()).retrieve(parameters.get("id").toString());

        if (distributionSelected == null) {
            LOGGER.warn("Distribution not found for id: {}", parameters.get("id"));
            return null;
        }
        LOGGER.info("[PERF] Distribution retrieval: {} ms", System.currentTimeMillis() - retrievalStart);

        // Retrieve DataProduct
        DataProduct dp = getDataProduct(distributionSelected);
        if (dp == null) {
            LOGGER.warn("DataProduct not found for distribution: {}", distributionSelected.getInstanceId());
            return null;
        }

        //  Retrieve WebService
        WebService ws = getWebService(distributionSelected);
        if (ws == null && distributionSelected.getAccessService() != null) {
            LOGGER.warn("WebService not found for distribution: {}", distributionSelected.getInstanceId());
            return null;
        }

        // Retrieve Operation
        Operation op = getOperation(ws, distributionSelected);

        // Pre-fetch ALL linked entities
        long prefetchStart = System.currentTimeMillis();
        PreFetchedEntities preFetched = preFetchLinkedEntities(dp, ws, op);
        LOGGER.info("[PERF] Pre-fetching entities: {} ms", System.currentTimeMillis() - prefetchStart);

        // Build distribution object
        long buildStart = System.currentTimeMillis();
        Distribution distribution = buildDistribution(distributionSelected, dp, ws, op, preFetched, facetsType);
        LOGGER.info("[PERF] Building distribution: {} ms", System.currentTimeMillis() - buildStart);

        long endTime = System.currentTimeMillis();
        LOGGER.info("[PERF] TOTAL: {} ms", endTime - startTime);

        return distribution;
    }

    /**
     * Get DataProduct from distribution
     */
    private static DataProduct getDataProduct(org.epos.eposdatamodel.Distribution distributionSelected) {
        if (distributionSelected.getDataProduct() != null && !distributionSelected.getDataProduct().isEmpty()) {
            return (DataProduct) LinkedEntityAPI.retrieveFromLinkedEntity(distributionSelected.getDataProduct().get(0));
        }
        return null;
    }

    /**
     * Get WebService from distribution
     */
    private static WebService getWebService(org.epos.eposdatamodel.Distribution distributionSelected) {
        if (distributionSelected.getAccessService() != null && !distributionSelected.getAccessService().isEmpty()) {
            return (WebService) LinkedEntityAPI.retrieveFromLinkedEntity(distributionSelected.getAccessService().get(0));
        }
        return null;
    }

    /**
     * Get Operation from WebService
     */
    private static Operation getOperation(WebService ws, org.epos.eposdatamodel.Distribution distributionSelected) {
        if (ws != null && distributionSelected.getSupportedOperation() != null) {
            List<Operation> opList = distributionSelected.getSupportedOperation().parallelStream()
                    .map(linkedEntity -> (Operation) LinkedEntityAPI.retrieveFromLinkedEntity(linkedEntity))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            return !opList.isEmpty() ? opList.get(0) : null;
        }
        return null;
    }

    /**
     * Pre-fetch ALL linked entities to eliminate N+1 query problem
     */
    private static PreFetchedEntities preFetchLinkedEntities(DataProduct dp, WebService ws, Operation op) {
        PreFetchedEntities entities = new PreFetchedEntities();

        // Collect all IDs
        Set<String> identifierIds = new HashSet<>();
        Set<String> locationIds = new HashSet<>();
        Set<String> temporalIds = new HashSet<>();
        Set<String> organizationIds = new HashSet<>();
        Set<String> categoryIds = new HashSet<>();
        Set<String> documentationIds = new HashSet<>();
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
            if (ws.getDocumentation() != null) {
                ws.getDocumentation().forEach(le -> documentationIds.add(le.getInstanceId()));
            }
        }

        // From Operation
        if (op != null && op.getMapping() != null) {
            op.getMapping().forEach(le -> mappingIds.add(le.getInstanceId()));
        }

        LOGGER.info("Pre-fetching: {} identifiers, {} locations, {} temporal, {} organizations, {} categories, {} documentations, {} mappings",
                identifierIds.size(), locationIds.size(), temporalIds.size(),
                organizationIds.size(), categoryIds.size(), documentationIds.size(), mappingIds.size());

        // Batch fetch all entities
        entities.identifiers = fetchEntityBatch(EntityNames.IDENTIFIER, identifierIds);
        entities.locations = fetchEntityBatch(EntityNames.LOCATION, locationIds);
        entities.temporals = fetchEntityBatch(EntityNames.PERIODOFTIME, temporalIds);
        entities.organizations = fetchEntityBatch(EntityNames.ORGANIZATION, organizationIds);
        entities.categories = fetchEntityBatch(EntityNames.CATEGORY, categoryIds);
        entities.documentations = fetchEntityBatch(EntityNames.DOCUMENTATION, documentationIds);
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
     * Build the complete Distribution object using pre-fetched entities
     */
    private static Distribution buildDistribution(org.epos.eposdatamodel.Distribution distributionSelected,
                                                  DataProduct dp, WebService ws, Operation op,
                                                  PreFetchedEntities preFetched, Facets.Type facetsType) {
        Distribution distribution = new Distribution();

        // Basic fields
        setBasicFields(distribution, distributionSelected, dp);

        // Dataset info with pre-fetched identifiers
        setDatasetInfo(distribution, dp, preFetched);

        // Keywords
        setKeywords(distribution, dp, ws);

        // Spatial information with pre-fetched locations
        setSpatialInfo(distribution, dp, preFetched);

        // Temporal coverage with pre-fetched temporal entities
        setTemporalCoverage(distribution, dp, preFetched);

        // Data providers with pre-fetched organizations
        setDataProviders(distribution, dp, preFetched);

        // Science domains with pre-fetched categories
        setScienceDomains(distribution, dp, preFetched);

        // Quality assurance
        distribution.setQualityAssurance(dp.getQualityAssurance());

        // WebService info
        if (ws != null) {
            setWebServiceInfo(distribution, ws, preFetched);
        }

        // Contact points
        setContactPoints(distribution, dp, ws);

        // Parameters with pre-fetched mappings
        setParameters(distribution, op, preFetched);

        // Available formats
        distribution.setAvailableFormats(AvailableFormatsGeneration.generate(distributionSelected));

        // Categories (facets)
        setCategories(distribution, distributionSelected, dp, ws, preFetched, facetsType);

        return distribution;
    }

    /**
     * Set basic distribution fields
     */
    private static void setBasicFields(Distribution distribution,
                                       org.epos.eposdatamodel.Distribution distributionSelected,
                                       DataProduct dp) {
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
        distribution.setMetaId(distributionSelected.getMetaId());
        distribution.setVersioningStatus(distributionSelected.getStatus());
        distribution.setEditorId(distributionSelected.getEditorId());

        if (distributionSelected.getDownloadURL() != null) {
            distribution.setDownloadURL(Optional.of(String.join(".", distributionSelected.getDownloadURL())).orElse(null));
        }

        if (distributionSelected.getAccessURL() != null) {
            distribution.setEndpoint(Optional.of(String.join(".", distributionSelected.getAccessURL())).orElse(null));
        }

        distribution.setLicense(distributionSelected.getLicence());
        distribution.setFrequencyUpdate(dp.getAccrualPeriodicity());
        distribution.setHref(EnvironmentVariables.API_HOST + API_PATH_DETAILS + distributionSelected.getInstanceId());
        distribution.setHrefExtended(EnvironmentVariables.API_HOST + API_PATH_DETAILS +
                distributionSelected.getInstanceId() + "?extended=true");
    }

    /**
     * Set dataset info using pre-fetched identifiers
     */
    private static void setDatasetInfo(Distribution distribution, DataProduct dp, PreFetchedEntities preFetched) {
        ArrayList<String> internalIDs = new ArrayList<>();
        List<String> doi = new ArrayList<>();

        if (dp.getIdentifier() != null) {
            dp.getIdentifier().forEach(identifierLe -> {
                Identifier identifier = (Identifier) preFetched.identifiers.get(identifierLe.getInstanceId());
                if (identifier != null) {
                    if ("DOI".equals(identifier.getType())) {
                        doi.add(identifier.getIdentifier());
                        distribution.setDOI(doi);
                    }
                    if ("DDSS-ID".equals(identifier.getType())) {
                        String ddss = identifier.getIdentifier();
                        if (ddss != null) {
                            internalIDs.add(ddss);
                        }
                    }
                }
            });
        }

        distribution.setInternalID(internalIDs);
    }

    /**
     * Set keywords from DataProduct and WebService
     */
    private static void setKeywords(Distribution distribution, DataProduct dp, WebService ws) {
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
     * Set spatial information using pre-fetched locations
     */
    private static void setSpatialInfo(Distribution distribution, DataProduct dp, PreFetchedEntities preFetched) {
        if (dp.getSpatialExtent() != null) {
            dp.getSpatialExtent().forEach(sLe -> {
                Location s = (Location) preFetched.locations.get(sLe.getInstanceId());
                if (s != null && s.getLocation() != null && !s.getLocation().isEmpty()) {
                    distribution.getSpatial().addPaths(
                            SpatialInformation.doSpatial(s.getLocation()),
                            SpatialInformation.checkPoint(s.getLocation())
                    );
                }
            });
        }
    }

    /**
     * Set temporal coverage using pre-fetched temporal entities
     */
    private static void setTemporalCoverage(Distribution distribution, DataProduct dp, PreFetchedEntities preFetched) {
        TemporalCoverage tc = new TemporalCoverage();

        if (dp.getTemporalExtent() != null && !dp.getTemporalExtent().isEmpty()) {
            PeriodOfTime periodOfTime = (PeriodOfTime) preFetched.temporals.get(
                    dp.getTemporalExtent().get(0).getInstanceId()
            );

            if (periodOfTime != null) {
                String startDate = null;
                String endDate = null;

                if (periodOfTime.getStartDate() != null) {
                    startDate = Timestamp.valueOf(periodOfTime.getStartDate())
                            .toString().replace(".0", "Z").replace(" ", "T");
                    if (!startDate.contains("Z")) startDate = startDate + "Z";
                }

                if (periodOfTime.getEndDate() != null) {
                    endDate = Timestamp.valueOf(periodOfTime.getEndDate())
                            .toString().replace(".0", "Z").replace(" ", "T");
                    if (!endDate.contains("Z")) endDate = endDate + "Z";
                }

                tc.setStartDate(startDate);
                tc.setEndDate(endDate);
            }
        }

        distribution.setTemporalCoverage(tc);
    }

    /**
     * Set data providers using pre-fetched organizations
     */
    private static void setDataProviders(Distribution distribution, DataProduct dp, PreFetchedEntities preFetched) {
        if (dp.getPublisher() != null) {
            List<Organization> organizations = dp.getPublisher().stream()
                    .map(linkedEntity -> (Organization) preFetched.organizations.get(linkedEntity.getInstanceId()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            List<DataServiceProvider> dataProviders = DataServiceProviderGeneration.getProviders(organizations);
            distribution.setDataProvider(dataProviders);
        }
    }

    /**
     * Set science domains using pre-fetched categories
     */
    private static void setScienceDomains(Distribution distribution, DataProduct dp, PreFetchedEntities preFetched) {
        if (dp.getCategory() != null) {
            List<String> scienceDomains = dp.getCategory().stream()
                    .map(linkedEntity -> (Category) preFetched.categories.get(linkedEntity.getInstanceId()))
                    .filter(Objects::nonNull)
                    .map(Category::getName)
                    .collect(Collectors.toList());
            distribution.setScienceDomain(scienceDomains);
        }
    }

    /**
     * Set WebService information using pre-fetched entities
     */
    private static void setWebServiceInfo(Distribution distribution, WebService ws, PreFetchedEntities preFetched) {
        distribution.setServiceDescription(ws.getDescription());

        // Documentation
        if (ws.getDocumentation() != null) {
            String documentation = ws.getDocumentation().stream()
                    .map(linkedEntity -> (Documentation) preFetched.documentations.get(linkedEntity.getInstanceId()))
                    .filter(Objects::nonNull)
                    .map(Documentation::getUri)
                    .collect(Collectors.joining("."));
            distribution.setServiceDocumentation(documentation);
        }

        distribution.setServiceName(ws.getName());

        // Provider
        if (ws.getProvider() != null) {
            Organization provider = (Organization) preFetched.organizations.get(ws.getProvider().getInstanceId());
            if (provider != null) {
                List<DataServiceProvider> serviceProviders = DataServiceProviderGeneration.getProviders(List.of(provider));
                if (!serviceProviders.isEmpty()) {
                    distribution.setServiceProvider(serviceProviders.get(0));
                }
            }
        }

        // Spatial extent
        if (ws.getSpatialExtent() != null && !ws.getSpatialExtent().isEmpty()) {
            ws.getSpatialExtent().forEach(sLe -> {
                Location s = (Location) preFetched.locations.get(sLe.getInstanceId());
                if (s != null) {
                    distribution.getServiceSpatial().addPaths(
                            SpatialInformation.doSpatial(s.getLocation()),
                            SpatialInformation.checkPoint(s.getLocation())
                    );
                }
            });
        }

        // Temporal coverage
        TemporalCoverage tcws = new TemporalCoverage();
        if (ws.getTemporalExtent() != null && !ws.getTemporalExtent().isEmpty()) {
            PeriodOfTime periodOfTime = (PeriodOfTime) preFetched.temporals.get(
                    ws.getTemporalExtent().get(0).getInstanceId()
            );

            if (periodOfTime != null) {
                String startDate = null;
                String endDate = null;

                if (periodOfTime.getStartDate() != null) {
                    startDate = Timestamp.valueOf(periodOfTime.getStartDate())
                            .toString().replace(".0", "Z").replace(" ", "T");
                    if (!startDate.contains("Z")) startDate = startDate + "Z";
                }

                if (periodOfTime.getEndDate() != null) {
                    endDate = Timestamp.valueOf(periodOfTime.getEndDate())
                            .toString().replace(".0", "Z").replace(" ", "T");
                    if (!endDate.contains("Z")) endDate = endDate + "Z";
                }

                tcws.setStartDate(startDate);
                tcws.setEndDate(endDate);
            }
        }
        distribution.setServiceTemporalCoverage(tcws);

        // Service types
        if (ws.getCategory() != null) {
            List<String> serviceTypes = ws.getCategory().stream()
                    .map(linkedEntity -> (Category) preFetched.categories.get(linkedEntity.getInstanceId()))
                    .filter(Objects::nonNull)
                    .map(Category::getName)
                    .collect(Collectors.toList());
            distribution.setServiceType(serviceTypes);
        }
    }

    /**
     * Set contact points
     */
    private static void setContactPoints(Distribution distribution, DataProduct dp, WebService ws) {
        if (ws != null && ws.getContactPoint() != null && !ws.getContactPoint().isEmpty()) {
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

        if ((ws != null && ws.getContactPoint() != null && !ws.getContactPoint().isEmpty() &&
                dp.getContactPoint() != null && !dp.getContactPoint().isEmpty())) {
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
     * Set parameters using pre-fetched mappings
     */
    private static void setParameters(Distribution distribution, Operation op, PreFetchedEntities preFetched) {
        distribution.setParameters(new ArrayList<>());

        if (op != null) {
            distribution.setEndpoint(op.getTemplate());
            if (op.getTemplate() != null) {
                distribution.setServiceEndpoint(op.getTemplate().split("\\{")[0]);
            }
            distribution.setOperationid(op.getUid());

            if (op.getMapping() != null && !op.getMapping().isEmpty()) {
                op.getMapping().forEach(mpLe -> {
                    Mapping mp = (Mapping) preFetched.mappings.get(mpLe.getInstanceId());
                    if (mp != null) {
                        ServiceParameter sp = getServiceParameter(mp);
                        distribution.getParameters().add(sp);
                    }
                });
            }
        }
    }

    /**
     * Set categories (facets) using pre-fetched entities
     */
    private static void setCategories(Distribution distribution,
                                      org.epos.eposdatamodel.Distribution distributionSelected,
                                      DataProduct dp, WebService ws, PreFetchedEntities preFetched,
                                      Facets.Type facetsType) {
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

        List<String> categoryList = new ArrayList<>();
        if (dp.getCategory() != null) {
            categoryList = dp.getCategory().stream()
                    .map(linkedEntity -> (Category) preFetched.categories.get(linkedEntity.getInstanceId()))
                    .filter(Objects::nonNull)
                    .map(Category::getUid)
                    .filter(uid -> uid.contains("category:"))
                    .collect(Collectors.toList());
        }

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
                .serviceProvider(facetsServiceProviders)
                .categories(categoryList.isEmpty() ? null : categoryList)
                .versioningStatus(distribution.getVersioningStatus().name())
                .editorId(distribution.getEditorId())
                .build());

        FacetsNodeTree categories = FacetsGeneration.generateResponseUsingCategories(discoveryList, facetsType);
        categories.getNodes().forEach(node -> node.setDistributions(null));
        distribution.setCategories(categories.getFacets());
    }

    /**
     * Create ServiceParameter from Mapping
     */
    private static ServiceParameter getServiceParameter(Mapping mp) {
        ServiceParameter sp = new ServiceParameter();
        sp.setDefaultValue(mp.getDefaultValue());
        sp.setEnumValue(mp.getParamValue() != null ? mp.getParamValue() : new ArrayList<>());
        sp.setName(mp.getVariable());
        sp.setMaxValue(mp.getMaxValue());
        sp.setMinValue(mp.getMinValue());
        sp.setLabel(mp.getLabel() != null ? mp.getLabel().replaceAll("@en", "") : null);
        sp.setProperty(mp.getProperty());
        sp.setRequired(mp.getRequired() != null ? Boolean.parseBoolean(mp.getRequired()) : null);
        sp.setType(mp.getRange() != null ? mp.getRange().replace("xsd:", "") : null);
        sp.setValue(null);
        sp.setValuePattern(mp.getValuePattern());
        sp.setVersion(null);
        sp.setReadOnlyValue(Boolean.parseBoolean(mp.getReadOnlyValue()) ? mp.getReadOnlyValue() : null);
        sp.setMultipleValue(Boolean.parseBoolean(mp.getMultipleValues()) ? mp.getMultipleValues() : null);
        return sp;
    }
}
