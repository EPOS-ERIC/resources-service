package org.epos.api.core.distributions;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.apache.commons.codec.digest.DigestUtils;
import org.epos.api.beans.AvailableContactPoints.AvailableContactPointsBuilder;
import org.epos.api.beans.DataServiceProvider;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.Distribution;
import org.epos.api.beans.ServiceParameter;
import org.epos.api.beans.SpatialInformation;
import org.epos.api.beans.TemporalCoverage;
import org.epos.api.core.AvailableFormatsGeneration;
import org.epos.api.core.AvailableFormatsGenerationSQL;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.DataServiceProviderGenerationSQL;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.enums.ProviderType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.FacetsNodeTree;
import org.epos.eposdatamodel.Organization;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for retrieving distribution details.
 * This replaces the JPA-based DistributionDetailsGenerationJPA for improved performance.
 */
public class DistributionDetailsGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributionDetailsGenerationSQL.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/resources/details/";
    private static final String EMAIL_SENDER = EnvironmentVariables.API_CONTEXT + "/sender/send-email?id=";

    private static final int SQL_BUILDER_INITIAL_CAPACITY = 8192;

    public static Distribution generate(Map<String, Object> parameters) {
        return generate(parameters, Facets.Type.DATA);
    }

    public static Distribution generate(Map<String, Object> parameters, Facets.Type facetsType) {
        final long startTime = System.nanoTime();
        LOGGER.info("Generating distribution details (SQL) for parameters: {}", parameters);

        String distributionId = parameters.get("id").toString();

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            String sql = buildDistributionDetailsSQL();
            Query query = em.createNativeQuery(sql);
            query.setParameter(1, distributionId);

            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();

            if (results.isEmpty()) {
                LOGGER.warn("Distribution not found for id: {}", distributionId);
                return null;
            }

            Object[] row = results.get(0);
            Distribution distribution = mapRowToDistribution(row, facetsType);

            LOGGER.info("Distribution details generated (SQL) in {} ms",
                    (System.nanoTime() - startTime) / 1_000_000);

            return distribution;

        } catch (Exception e) {
            LOGGER.error("Failed to generate distribution details", e);
            throw new RuntimeException("Failed to generate distribution details", e);
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    /**
     * Builds the SQL query for retrieving distribution details with all related data.
     */
    private static String buildDistributionDetailsSQL() {
        StringBuilder sql = new StringBuilder(SQL_BUILDER_INITIAL_CAPACITY);

        sql.append("WITH distribution_base AS ( ");
        sql.append("  SELECT d.instance_id, d.meta_id, d.uid, d.type, d.format, d.license, d.version_id, ");
        sql.append("         v.status AS versioning_status, v.editor_id ");
        sql.append("  FROM metadata_catalogue.distribution d ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON d.version_id = v.version_id ");
        sql.append("  WHERE d.instance_id = ?1 ");
        sql.append("), ");

        // Distribution titles
        sql.append("dist_titles AS ( ");
        sql.append("  SELECT dt.distribution_instance_id, STRING_AGG(dt.title, '.' ORDER BY dt.lang) AS title ");
        sql.append("  FROM metadata_catalogue.distribution_title dt ");
        sql.append("  WHERE dt.distribution_instance_id = ?1 ");
        sql.append("  GROUP BY dt.distribution_instance_id ");
        sql.append("), ");

        // Distribution descriptions
        sql.append("dist_descriptions AS ( ");
        sql.append("  SELECT dd.distribution_instance_id, STRING_AGG(dd.description, '.' ORDER BY dd.lang) AS description ");
        sql.append("  FROM metadata_catalogue.distribution_description dd ");
        sql.append("  WHERE dd.distribution_instance_id = ?1 ");
        sql.append("  GROUP BY dd.distribution_instance_id ");
        sql.append("), ");

        // Download URLs
        sql.append("dist_download_urls AS ( ");
        sql.append("  SELECT de.distribution_instance_id, STRING_AGG(e.value, '.') AS download_urls ");
        sql.append("  FROM metadata_catalogue.distribution_element de ");
        sql.append("  JOIN metadata_catalogue.element e ON de.element_instance_id = e.instance_id ");
        sql.append("  WHERE de.distribution_instance_id = ?1 AND e.type = 'DOWNLOADURL' ");
        sql.append("  GROUP BY de.distribution_instance_id ");
        sql.append("), ");

        // Access URLs
        sql.append("dist_access_urls AS ( ");
        sql.append("  SELECT de.distribution_instance_id, STRING_AGG(e.value, '.') AS access_urls ");
        sql.append("  FROM metadata_catalogue.distribution_element de ");
        sql.append("  JOIN metadata_catalogue.element e ON de.element_instance_id = e.instance_id ");
        sql.append("  WHERE de.distribution_instance_id = ?1 AND e.type = 'ACCESSURL' ");
        sql.append("  GROUP BY de.distribution_instance_id ");
        sql.append("), ");

        // DataProduct info
        sql.append("dataproduct_info AS ( ");
        sql.append("  SELECT ddp.distribution_instance_id, dp.instance_id AS dataproduct_id, ");
        sql.append("         dp.keywords, dp.accrualperiodicity, dp.qualityassurance ");
        sql.append("  FROM metadata_catalogue.distribution_dataproduct ddp ");
        sql.append("  JOIN metadata_catalogue.dataproduct dp ON ddp.dataproduct_instance_id = dp.instance_id ");
        sql.append("  WHERE ddp.distribution_instance_id = ?1 ");
        sql.append("  LIMIT 1 ");
        sql.append("), ");

        // DataProduct identifiers (DOI, DDSS-ID)
        sql.append("dp_identifiers AS ( ");
        sql.append("  SELECT di.dataproduct_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT('type', i.type, 'value', i.value)) AS identifiers ");
        sql.append("  FROM dataproduct_info di ");
        sql.append("  JOIN metadata_catalogue.dataproduct_identifier dpi ON di.dataproduct_id = dpi.dataproduct_instance_id ");
        sql.append("  JOIN metadata_catalogue.identifier i ON dpi.identifier_instance_id = i.instance_id ");
        sql.append("  GROUP BY di.dataproduct_id ");
        sql.append("), ");

        // DataProduct spatial
        sql.append("dp_spatial AS ( ");
        sql.append("  SELECT di.dataproduct_id, STRING_AGG(s.location, ' #EPOS# ') AS locations ");
        sql.append("  FROM dataproduct_info di ");
        sql.append("  JOIN metadata_catalogue.dataproduct_spatial dps ON di.dataproduct_id = dps.dataproduct_instance_id ");
        sql.append("  JOIN metadata_catalogue.spatial s ON dps.spatial_instance_id = s.instance_id ");
        sql.append("  GROUP BY di.dataproduct_id ");
        sql.append("), ");

        // DataProduct temporal
        sql.append("dp_temporal AS ( ");
        sql.append("  SELECT di.dataproduct_id, t.startdate, t.enddate ");
        sql.append("  FROM dataproduct_info di ");
        sql.append("  JOIN metadata_catalogue.dataproduct_temporal dpt ON di.dataproduct_id = dpt.dataproduct_instance_id ");
        sql.append("  JOIN metadata_catalogue.temporal t ON dpt.temporal_instance_id = t.instance_id ");
        sql.append("  LIMIT 1 ");
        sql.append("), ");

        // DataProduct publishers (data providers)
        sql.append("dp_publishers AS ( ");
        sql.append("  SELECT di.dataproduct_id, ");
        sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ");
        sql.append("           'instance_id', o.instance_id, 'legal_name', o.legalname, ");
        sql.append("           'acronym', o.acronym, 'url', o.url, 'logo', o.logo ");
        sql.append("         )) AS publishers ");
        sql.append("  FROM dataproduct_info di ");
        sql.append("  JOIN metadata_catalogue.dataproduct_publisher dpp ON di.dataproduct_id = dpp.dataproduct_instance_id ");
        sql.append("  JOIN metadata_catalogue.organization o ON dpp.organization_instance_id = o.instance_id ");
        sql.append("  GROUP BY di.dataproduct_id ");
        sql.append("), ");

        // DataProduct categories (science domains)
        sql.append("dp_categories AS ( ");
        sql.append("  SELECT di.dataproduct_id, ");
        sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('uid', c.uid, 'name', c.name, 'instance_id', c.instance_id)) AS categories ");
        sql.append("  FROM dataproduct_info di ");
        sql.append("  JOIN metadata_catalogue.dataproduct_category dpc ON di.dataproduct_id = dpc.dataproduct_instance_id ");
        sql.append("  JOIN metadata_catalogue.category c ON dpc.category_instance_id = c.instance_id ");
        sql.append("  GROUP BY di.dataproduct_id ");
        sql.append("), ");

        // DataProduct contact points
        sql.append("dp_contactpoints AS ( ");
        sql.append("  SELECT di.dataproduct_id, COUNT(*) AS contact_count ");
        sql.append("  FROM dataproduct_info di ");
        sql.append("  JOIN metadata_catalogue.dataproduct_contactpoint dcp ON di.dataproduct_id = dcp.dataproduct_instance_id ");
        sql.append("  GROUP BY di.dataproduct_id ");
        sql.append("), ");

        // WebService info
        sql.append("webservice_info AS ( ");
        sql.append("  SELECT wd.distribution_instance_id, ws.instance_id AS webservice_id, ");
        sql.append("         ws.name, ws.description AS ws_description, ws.provider AS provider_id, ws.keywords AS ws_keywords ");
        sql.append("  FROM metadata_catalogue.webservice_distribution wd ");
        sql.append("  JOIN metadata_catalogue.webservice ws ON wd.webservice_instance_id = ws.instance_id ");
        sql.append("  WHERE wd.distribution_instance_id = ?1 ");
        sql.append("  LIMIT 1 ");
        sql.append("), ");

        // WebService documentation
        sql.append("ws_documentation AS ( ");
        sql.append("  SELECT wi.webservice_id, STRING_AGG(e.value, '.') AS documentation ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.webservice_element we ON wi.webservice_id = we.webservice_instance_id ");
        sql.append("  JOIN metadata_catalogue.element e ON we.element_instance_id = e.instance_id ");
        sql.append("  WHERE e.type = 'DOCUMENTATION' ");
        sql.append("  GROUP BY wi.webservice_id ");
        sql.append("), ");

        // WebService provider
        sql.append("ws_provider AS ( ");
        sql.append("  SELECT wi.webservice_id, ");
        sql.append("         JSONB_BUILD_OBJECT( ");
        sql.append("           'instance_id', o.instance_id, 'legal_name', o.legalname, ");
        sql.append("           'acronym', o.acronym, 'url', o.url, 'logo', o.logo ");
        sql.append("         ) AS provider ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.organization o ON wi.provider_id = o.instance_id ");
        sql.append("), ");

        // WebService spatial
        sql.append("ws_spatial AS ( ");
        sql.append("  SELECT wi.webservice_id, STRING_AGG(s.location, ' #EPOS# ') AS locations ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.webservice_spatial wss ON wi.webservice_id = wss.webservice_instance_id ");
        sql.append("  JOIN metadata_catalogue.spatial s ON wss.spatial_instance_id = s.instance_id ");
        sql.append("  GROUP BY wi.webservice_id ");
        sql.append("), ");

        // WebService temporal
        sql.append("ws_temporal AS ( ");
        sql.append("  SELECT wi.webservice_id, t.startdate, t.enddate ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.webservice_temporal wst ON wi.webservice_id = wst.webservice_instance_id ");
        sql.append("  JOIN metadata_catalogue.temporal t ON wst.temporal_instance_id = t.instance_id ");
        sql.append("  LIMIT 1 ");
        sql.append("), ");

        // WebService categories (service types)
        sql.append("ws_categories AS ( ");
        sql.append("  SELECT wi.webservice_id, ");
        sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('uid', c.uid, 'name', c.name, 'instance_id', c.instance_id)) AS service_types ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.webservice_category wc ON wi.webservice_id = wc.webservice_instance_id ");
        sql.append("  JOIN metadata_catalogue.category c ON wc.category_instance_id = c.instance_id ");
        sql.append("  GROUP BY wi.webservice_id ");
        sql.append("), ");

        // WebService contact points
        sql.append("ws_contactpoints AS ( ");
        sql.append("  SELECT wi.webservice_id, COUNT(*) AS contact_count ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.webservice_contactpoint wcp ON wi.webservice_id = wcp.webservice_instance_id ");
        sql.append("  GROUP BY wi.webservice_id ");
        sql.append("), ");

        // Operation info
        sql.append("operation_info AS ( ");
        sql.append("  SELECT od.distribution_instance_id, op.instance_id AS operation_id, op.template, op.uid AS operation_uid ");
        sql.append("  FROM metadata_catalogue.operation_distribution od ");
        sql.append("  JOIN metadata_catalogue.operation op ON od.operation_instance_id = op.instance_id ");
        sql.append("  WHERE od.distribution_instance_id = ?1 ");
        sql.append("  LIMIT 1 ");
        sql.append("), ");

        // Operation mappings (parameters)
        sql.append("op_mappings AS ( ");
        sql.append("  SELECT oi.operation_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT( ");
        sql.append("           'variable', m.variable, 'label', m.label, 'required', m.required, ");
        sql.append("           'range', m.range, 'defaultvalue', m.defaultvalue, 'minvalue', m.minvalue, ");
        sql.append("           'maxvalue', m.maxvalue, 'property', m.property, 'valuepattern', m.valuepattern, ");
        sql.append("           'readonly', m.read_only_value, 'multiple', m.multiple_values, ");
        sql.append("           'paramvalues', (SELECT ARRAY_AGG(e.value) FROM metadata_catalogue.mapping_element me ");
        sql.append("                           JOIN metadata_catalogue.element e ON me.element_instance_id = e.instance_id ");
        sql.append("                           WHERE me.mapping_instance_id = m.instance_id AND e.type = 'PARAMVALUE') ");
        sql.append("         )) AS mappings ");
        sql.append("  FROM operation_info oi ");
        sql.append("  JOIN metadata_catalogue.operation_mapping om ON oi.operation_id = om.operation_instance_id ");
        sql.append("  JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ");
        sql.append("  GROUP BY oi.operation_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  db.instance_id, db.meta_id, db.uid, db.type, db.format, db.license, ");
        sql.append("  db.versioning_status, db.editor_id, ");
        sql.append("  COALESCE(dt.title, '') AS title, ");
        sql.append("  COALESCE(dd.description, '') AS description, ");
        sql.append("  COALESCE(ddu.download_urls, '') AS download_urls, ");
        sql.append("  COALESCE(dau.access_urls, '') AS access_urls, ");
        sql.append("  di.dataproduct_id, di.keywords, di.accrualperiodicity, di.qualityassurance, ");
        sql.append("  COALESCE(CAST(dpi.identifiers AS text), '[]') AS dp_identifiers, ");
        sql.append("  COALESCE(dps.locations, '') AS dp_spatial, ");
        sql.append("  dpt.startdate AS dp_start_date, dpt.enddate AS dp_end_date, ");
        sql.append("  COALESCE(CAST(dpp.publishers AS text), '[]') AS dp_publishers, ");
        sql.append("  COALESCE(CAST(dpc.categories AS text), '[]') AS dp_categories, ");
        sql.append("  COALESCE(dpcp.contact_count, 0) AS dp_contact_count, ");
        sql.append("  wi.webservice_id, wi.name AS ws_name, wi.ws_description, wi.ws_keywords, ");
        sql.append("  COALESCE(wsd.documentation, '') AS ws_documentation, ");
        sql.append("  COALESCE(CAST(wsp.provider AS text), '{}') AS ws_provider, ");
        sql.append("  COALESCE(wss.locations, '') AS ws_spatial, ");
        sql.append("  wst.startdate AS ws_start_date, wst.enddate AS ws_end_date, ");
        sql.append("  COALESCE(CAST(wsc.service_types AS text), '[]') AS ws_service_types, ");
        sql.append("  COALESCE(wscp.contact_count, 0) AS ws_contact_count, ");
        sql.append("  oi.operation_id, oi.template, oi.operation_uid, ");
        sql.append("  COALESCE(CAST(opm.mappings AS text), '[]') AS op_mappings ");
        sql.append("FROM distribution_base db ");
        sql.append("LEFT JOIN dist_titles dt ON db.instance_id = dt.distribution_instance_id ");
        sql.append("LEFT JOIN dist_descriptions dd ON db.instance_id = dd.distribution_instance_id ");
        sql.append("LEFT JOIN dist_download_urls ddu ON db.instance_id = ddu.distribution_instance_id ");
        sql.append("LEFT JOIN dist_access_urls dau ON db.instance_id = dau.distribution_instance_id ");
        sql.append("LEFT JOIN dataproduct_info di ON db.instance_id = di.distribution_instance_id ");
        sql.append("LEFT JOIN dp_identifiers dpi ON di.dataproduct_id = dpi.dataproduct_id ");
        sql.append("LEFT JOIN dp_spatial dps ON di.dataproduct_id = dps.dataproduct_id ");
        sql.append("LEFT JOIN dp_temporal dpt ON di.dataproduct_id = dpt.dataproduct_id ");
        sql.append("LEFT JOIN dp_publishers dpp ON di.dataproduct_id = dpp.dataproduct_id ");
        sql.append("LEFT JOIN dp_categories dpc ON di.dataproduct_id = dpc.dataproduct_id ");
        sql.append("LEFT JOIN dp_contactpoints dpcp ON di.dataproduct_id = dpcp.dataproduct_id ");
        sql.append("LEFT JOIN webservice_info wi ON db.instance_id = wi.distribution_instance_id ");
        sql.append("LEFT JOIN ws_documentation wsd ON wi.webservice_id = wsd.webservice_id ");
        sql.append("LEFT JOIN ws_provider wsp ON wi.webservice_id = wsp.webservice_id ");
        sql.append("LEFT JOIN ws_spatial wss ON wi.webservice_id = wss.webservice_id ");
        sql.append("LEFT JOIN ws_temporal wst ON wi.webservice_id = wst.webservice_id ");
        sql.append("LEFT JOIN ws_categories wsc ON wi.webservice_id = wsc.webservice_id ");
        sql.append("LEFT JOIN ws_contactpoints wscp ON wi.webservice_id = wscp.webservice_id ");
        sql.append("LEFT JOIN operation_info oi ON db.instance_id = oi.distribution_instance_id ");
        sql.append("LEFT JOIN op_mappings opm ON oi.operation_id = opm.operation_id ");

        return sql.toString();
    }

    /**
     * Maps a database result row to a Distribution object.
     */
    private static Distribution mapRowToDistribution(Object[] row, Facets.Type facetsType) {
        int i = 0;

        // Distribution base fields
        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String type = (String) row[i++];
        String format = (String) row[i++];
        String license = (String) row[i++];
        String versioningStatus = (String) row[i++];
        String editorId = (String) row[i++];
        String title = (String) row[i++];
        String description = (String) row[i++];
        String downloadUrls = (String) row[i++];
        String accessUrls = (String) row[i++];

        // DataProduct fields
        String dataproductId = (String) row[i++];
        String keywords = (String) row[i++];
        String accrualPeriodicity = (String) row[i++];
        String qualityAssurance = (String) row[i++];
        String dpIdentifiersJson = (String) row[i++];
        String dpSpatial = (String) row[i++];
        Timestamp dpStartDate = (Timestamp) row[i++];
        Timestamp dpEndDate = (Timestamp) row[i++];
        String dpPublishersJson = (String) row[i++];
        String dpCategoriesJson = (String) row[i++];
        Long dpContactCount = ((Number) row[i++]).longValue();

        // WebService fields
        String webserviceId = (String) row[i++];
        String wsName = (String) row[i++];
        String wsDescription = (String) row[i++];
        String wsKeywords = (String) row[i++];
        String wsDocumentation = (String) row[i++];
        String wsProviderJson = (String) row[i++];
        String wsSpatial = (String) row[i++];
        Timestamp wsStartDate = (Timestamp) row[i++];
        Timestamp wsEndDate = (Timestamp) row[i++];
        String wsServiceTypesJson = (String) row[i++];
        Long wsContactCount = ((Number) row[i++]).longValue();

        // Operation fields
        String operationId = (String) row[i++];
        String template = (String) row[i++];
        String operationUid = (String) row[i++];
        String opMappingsJson = (String) row[i++];

        // Build Distribution object
        Distribution distribution = new Distribution();

        // Basic fields
        distribution.setId(instanceId);
        distribution.setUid(uid);
        distribution.setMetaId(metaId);
        distribution.setTitle(title);
        distribution.setDescription(description);

        if (type != null) {
            String[] typeParts = type.split("/");
            distribution.setType(typeParts[typeParts.length - 1]);
        }

        distribution.setLicense(license);
        distribution.setVersioningStatus(model.StatusType.valueOf(versioningStatus));
        distribution.setEditorId(editorId);

        if (downloadUrls != null && !downloadUrls.isEmpty()) {
            distribution.setDownloadURL(downloadUrls);
        }

        if (accessUrls != null && !accessUrls.isEmpty()) {
            distribution.setEndpoint(accessUrls);
        }

        distribution.setFrequencyUpdate(accrualPeriodicity);
        distribution.setQualityAssurance(qualityAssurance);

        distribution.setHref(EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId);
        distribution.setHrefExtended(EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId + "?extended=true");

        // Identifiers (DOI, DDSS-ID)
        parseIdentifiers(distribution, dpIdentifiersJson);

        // Keywords
        parseKeywords(distribution, keywords, wsKeywords);

        // Spatial information
        parseSpatialInfo(distribution, dpSpatial);

        // Temporal coverage
        parseTemporalCoverage(distribution, dpStartDate, dpEndDate);

        // Data providers
        List<Organization> publishers = parsePublishers(dpPublishersJson);
        if (!publishers.isEmpty()) {
            distribution.setDataProvider(DataServiceProviderGenerationSQL.getProviders(publishers));
        }

        // Science domains
        parseScienceDomains(distribution, dpCategoriesJson);

        // WebService info
        if (webserviceId != null) {
            distribution.setServiceName(wsName);
            distribution.setServiceDescription(wsDescription);
            distribution.setServiceDocumentation(extractDocumentationUri(wsDocumentation));

            // Service provider
            parseServiceProvider(distribution, wsProviderJson);

            // Service spatial
            parseServiceSpatial(distribution, wsSpatial);

            // Service temporal
            parseServiceTemporal(distribution, wsStartDate, wsEndDate);

            // Service types
            parseServiceTypes(distribution, wsServiceTypesJson);
        }

        // Contact points
        parseContactPoints(distribution, instanceId, dpContactCount, wsContactCount);

        // Operation/Parameters
        if (operationId != null) {
            distribution.setEndpoint(template);
            if (template != null) {
                distribution.setServiceEndpoint(template.split("\\{")[0]);
            }
            distribution.setOperationid(operationUid);
            parseParameters(distribution, opMappingsJson);
        }

        // Available formats
        distribution.setAvailableFormats(AvailableFormatsGenerationSQL.generate(instanceId));

        // Categories (facets)
        buildCategories(distribution, instanceId, uid, metaId, title, description,
                dpPublishersJson, wsProviderJson, dpCategoriesJson, facetsType);

        return distribution;
    }

    private static void parseIdentifiers(Distribution distribution, String identifiersJson) {
        if (isEmptyJson(identifiersJson)) {
            distribution.setInternalID(new ArrayList<>());
            return;
        }

        List<String> doi = new ArrayList<>();
        List<String> internalIds = new ArrayList<>();

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(identifiersJson);
            for (JsonNode node : arrayNode) {
                String type = getTextOrNull(node, "type");
                String value = getTextOrNull(node, "value");
                if ("DOI".equals(type) && value != null) {
                    doi.add(value);
                } else if ("DDSS-ID".equals(type) && value != null) {
                    internalIds.add(value);
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse identifiers JSON: {}", e.getMessage());
        }

        distribution.setDOI(doi.isEmpty() ? null : doi);
        distribution.setInternalID(internalIds);
    }

    private static void parseKeywords(Distribution distribution, String dpKeywords, String wsKeywords) {
        Set<String> keywords = new HashSet<>();

        if (dpKeywords != null && !dpKeywords.isEmpty()) {
            Arrays.stream(dpKeywords.split(",\t"))
                    .map(String::toLowerCase)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(keywords::add);
        }

        if (wsKeywords != null && !wsKeywords.isEmpty()) {
            Arrays.stream(wsKeywords.split(",\t"))
                    .map(String::toLowerCase)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(keywords::add);
        }

        keywords.removeAll(Collections.singleton(null));
        keywords.removeAll(Collections.singleton(""));
        distribution.setKeywords(new ArrayList<>(keywords));
    }

    private static void parseSpatialInfo(Distribution distribution, String spatialStr) {
        if (spatialStr == null || spatialStr.isEmpty()) {
            return;
        }

        String[] locations = spatialStr.split("#EPOS#");
        for (String location : locations) {
            String trimmed = location.trim();
            if (!trimmed.isEmpty()) {
                distribution.getSpatial().addPaths(
                        SpatialInformation.doSpatial(trimmed),
                        SpatialInformation.checkPoint(trimmed)
                );
            }
        }
    }

    private static void parseTemporalCoverage(Distribution distribution, Timestamp startDate, Timestamp endDate) {
        TemporalCoverage tc = new TemporalCoverage();

        if (startDate != null) {
            String start = startDate.toString().replace(".0", "Z").replace(" ", "T");
            if (!start.contains("Z")) start = start + "Z";
            tc.setStartDate(start);
        }

        if (endDate != null) {
            String end = endDate.toString().replace(".0", "Z").replace(" ", "T");
            if (!end.contains("Z")) end = end + "Z";
            tc.setEndDate(end);
        }

        distribution.setTemporalCoverage(tc);
    }

    private static List<Organization> parsePublishers(String publishersJson) {
        List<Organization> organizations = new ArrayList<>();

        if (isEmptyJson(publishersJson)) {
            return organizations;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(publishersJson);
            for (JsonNode node : arrayNode) {
                Organization org = new Organization();
                org.setInstanceId(getTextOrNull(node, "instance_id"));
                String legalName = getTextOrNull(node, "legal_name");
                if (legalName != null) {
                    org.setLegalName(Collections.singletonList(legalName));
                }
                org.setURL(getTextOrNull(node, "url"));
                org.setLogo(getTextOrNull(node, "logo"));
                organizations.add(org);
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse publishers JSON: {}", e.getMessage());
        }

        return organizations;
    }

    private static void parseScienceDomains(Distribution distribution, String categoriesJson) {
        if (isEmptyJson(categoriesJson)) {
            return;
        }

        List<String> scienceDomains = new ArrayList<>();
        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(categoriesJson);
            for (JsonNode node : arrayNode) {
                String name = getTextOrNull(node, "name");
                if (name != null) {
                    scienceDomains.add(name);
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse categories JSON: {}", e.getMessage());
        }

        distribution.setScienceDomain(scienceDomains);
    }

    private static void parseServiceProvider(Distribution distribution, String providerJson) {
        if (isEmptyJson(providerJson) || "{}".equals(providerJson)) {
            return;
        }

        try {
            JsonNode node = OBJECT_MAPPER.readTree(providerJson);
            Organization org = new Organization();
            org.setInstanceId(getTextOrNull(node, "instance_id"));
            String legalName = getTextOrNull(node, "legal_name");
            if (legalName != null) {
                org.setLegalName(Collections.singletonList(legalName));
            }
            org.setURL(getTextOrNull(node, "url"));
            org.setLogo(getTextOrNull(node, "logo"));

            List<DataServiceProvider> providers = DataServiceProviderGenerationSQL.getProviders(List.of(org));
            if (!providers.isEmpty()) {
                distribution.setServiceProvider(providers.get(0));
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse provider JSON: {}", e.getMessage());
        }
    }

    private static void parseServiceSpatial(Distribution distribution, String spatialStr) {
        if (spatialStr == null || spatialStr.isEmpty()) {
            return;
        }

        String[] locations = spatialStr.split("#EPOS#");
        for (String location : locations) {
            String trimmed = location.trim();
            if (!trimmed.isEmpty()) {
                distribution.getServiceSpatial().addPaths(
                        SpatialInformation.doSpatial(trimmed),
                        SpatialInformation.checkPoint(trimmed)
                );
            }
        }
    }

    private static void parseServiceTemporal(Distribution distribution, Timestamp startDate, Timestamp endDate) {
        TemporalCoverage tc = new TemporalCoverage();

        if (startDate != null) {
            String start = startDate.toString().replace(".0", "Z").replace(" ", "T");
            if (!start.contains("Z")) start = start + "Z";
            tc.setStartDate(start);
        }

        if (endDate != null) {
            String end = endDate.toString().replace(".0", "Z").replace(" ", "T");
            if (!end.contains("Z")) end = end + "Z";
            tc.setEndDate(end);
        }

        distribution.setServiceTemporalCoverage(tc);
    }

    private static void parseServiceTypes(Distribution distribution, String serviceTypesJson) {
        if (isEmptyJson(serviceTypesJson)) {
            return;
        }

        List<String> serviceTypes = new ArrayList<>();
        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(serviceTypesJson);
            for (JsonNode node : arrayNode) {
                String name = getTextOrNull(node, "name");
                if (name != null) {
                    serviceTypes.add(name);
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse service types JSON: {}", e.getMessage());
        }

        distribution.setServiceType(serviceTypes);
    }

    private static void parseContactPoints(Distribution distribution, String instanceId,
                                           Long dpContactCount, Long wsContactCount) {
        if (wsContactCount > 0) {
            distribution.getAvailableContactPoints().add(
                    new AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + instanceId +
                                    "&contactType=" + ProviderType.SERVICEPROVIDERS)
                            .type(ProviderType.SERVICEPROVIDERS)
                            .build()
            );
        }

        if (dpContactCount > 0) {
            distribution.getAvailableContactPoints().add(
                    new AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + instanceId +
                                    "&contactType=" + ProviderType.DATAPROVIDERS)
                            .type(ProviderType.DATAPROVIDERS)
                            .build()
            );
        }

        if (wsContactCount > 0 && dpContactCount > 0) {
            distribution.getAvailableContactPoints().add(
                    new AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + instanceId +
                                    "&contactType=" + ProviderType.ALL)
                            .type(ProviderType.ALL)
                            .build()
            );
        }
    }

    private static void parseParameters(Distribution distribution, String mappingsJson) {
        distribution.setParameters(new ArrayList<>());

        if (isEmptyJson(mappingsJson)) {
            return;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(mappingsJson);
            for (JsonNode node : arrayNode) {
                ServiceParameter sp = new ServiceParameter();
                sp.setName(getTextOrNull(node, "variable"));
                sp.setLabel(getTextOrNull(node, "label") != null ?
                        getTextOrNull(node, "label").replaceAll("@en", "") : null);
                sp.setRequired(node.has("required") && !node.get("required").isNull() ?
                        Boolean.parseBoolean(node.get("required").asText()) : null);
                sp.setType(getTextOrNull(node, "range") != null ?
                        getTextOrNull(node, "range").replace("xsd:", "") : null);
                sp.setDefaultValue(getTextOrNull(node, "defaultvalue"));
                sp.setMinValue(getTextOrNull(node, "minvalue"));
                sp.setMaxValue(getTextOrNull(node, "maxvalue"));
                sp.setProperty(getTextOrNull(node, "property"));
                sp.setValuePattern(getTextOrNull(node, "valuepattern"));
                sp.setReadOnlyValue(getTextOrNull(node, "readonly"));
                sp.setMultipleValue(getTextOrNull(node, "multiple"));

                // Parse enum values
                if (node.has("paramvalues") && !node.get("paramvalues").isNull()) {
                    List<String> enumValues = new ArrayList<>();
                    for (JsonNode valNode : node.get("paramvalues")) {
                        if (!valNode.isNull()) {
                            enumValues.add(valNode.asText());
                        }
                    }
                    sp.setEnumValue(enumValues);
                } else {
                    sp.setEnumValue(new ArrayList<>());
                }

                distribution.getParameters().add(sp);
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse mappings JSON: {}", e.getMessage());
        }
    }

    private static void buildCategories(Distribution distribution, String instanceId, String uid,
                                        String metaId, String title, String description,
                                        String publishersJson, String providerJson,
                                        String categoriesJson, Facets.Type facetsType) {
        ArrayList<DiscoveryItem> discoveryList = new ArrayList<>();

        Set<String> facetsDataProviders = new HashSet<>();
        if (!isEmptyJson(publishersJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(publishersJson);
                for (JsonNode node : arrayNode) {
                    String legalName = getTextOrNull(node, "legal_name");
                    if (legalName != null) {
                        facetsDataProviders.add(legalName);
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse publishers for facets: {}", e.getMessage());
            }
        }

        Set<String> facetsServiceProviders = new HashSet<>();
        if (!isEmptyJson(providerJson) && !"{}".equals(providerJson)) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(providerJson);
                String legalName = getTextOrNull(node, "legal_name");
                if (legalName != null) {
                    facetsServiceProviders.add(legalName);
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse provider for facets: {}", e.getMessage());
            }
        }

        List<String> categoryList = new ArrayList<>();
        if (!isEmptyJson(categoriesJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(categoriesJson);
                for (JsonNode node : arrayNode) {
                    String catUid = getTextOrNull(node, "uid");
                    if (catUid != null && catUid.contains("category:")) {
                        categoryList.add(catUid);
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse categories for facets: {}", e.getMessage());
            }
        }

        discoveryList.add(new DiscoveryItem.DiscoveryItemBuilder(
                instanceId,
                EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId,
                EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId + "?extended=true")
                .uid(uid)
                .metaId(metaId)
                .title(title)
                .description(description)
                .availableFormats(AvailableFormatsGenerationSQL.generate(instanceId))
                .sha256id(uid != null ? DigestUtils.sha256Hex(uid) : "")
                .dataProvider(facetsDataProviders)
                .serviceProvider(facetsServiceProviders)
                .categories(categoryList.isEmpty() ? null : categoryList)
                .versioningStatus(distribution.getVersioningStatus() != null ?
                        distribution.getVersioningStatus().name() : null)
                .editorId(distribution.getEditorId())
                .build());

        FacetsNodeTree categories = FacetsGeneration.generateResponseUsingCategories(discoveryList, facetsType);
        categories.getNodes().forEach(node -> node.setDistributions(null));
        distribution.setCategories(categories.getFacets());
    }

    /**
     * Extracts the "Uri" value from a documentation JSON object.
     * Example input: {"Title":"ahead-restful-bibliography documentation","Description":"...","Uri":"https://..."}
     * Returns only the Uri value, or null if not found or invalid JSON.
     */
    private static String extractDocumentationUri(String documentationJson) {
        if (isEmptyJson(documentationJson) || "{}".equals(documentationJson)) {
            return null;
        }

        try {
            JsonNode node = OBJECT_MAPPER.readTree(documentationJson);
            return getTextOrNull(node, "Uri");
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse documentation JSON: {}", e.getMessage());
            return documentationJson; // Return as-is if not valid JSON (backwards compatibility)
        }
    }

    private static boolean isEmptyJson(String json) {
        return json == null || json.isEmpty() || "[]".equals(json) || "null".equals(json);
    }

    private static String getTextOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText(null);
    }
}
