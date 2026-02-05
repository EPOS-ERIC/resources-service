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
 * SQL-based implementation for retrieving extended distribution details.
 * This replaces the JPA-based DistributionDetailsExtendedGenerationJPA for improved performance.
 */
public class DistributionDetailsExtendedGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributionDetailsExtendedGenerationSQL.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/resources/details/";
    private static final String EMAIL_SENDER = EnvironmentVariables.API_CONTEXT + "/sender/send-email?id=";

    private static final int SQL_BUILDER_INITIAL_CAPACITY = 12288;

    public static DistributionExtended generate(Map<String, Object> parameters) {
        final long startTime = System.nanoTime();
        LOGGER.info("Generating extended distribution details (SQL) for parameters: {}", parameters);

        String distributionId = parameters.get("id").toString();

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            String sql = buildExtendedDistributionSQL();
            Query query = em.createNativeQuery(sql);
            query.setParameter(1, distributionId);

            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();

            if (results.isEmpty()) {
                LOGGER.warn("Distribution not found for id: {}", distributionId);
                return null;
            }

            Object[] row = results.get(0);
            DistributionExtended distribution = mapRowToDistributionExtended(row);

            LOGGER.info("Extended distribution details generated (SQL) in {} ms",
                    (System.nanoTime() - startTime) / 1_000_000);

            return distribution;

        } catch (Exception e) {
            LOGGER.error("Failed to generate extended distribution details", e);
            throw new RuntimeException("Failed to generate extended distribution details", e);
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    /**
     * Builds the SQL query for retrieving extended distribution details with all related data.
     */
    private static String buildExtendedDistributionSQL() {
        StringBuilder sql = new StringBuilder(SQL_BUILDER_INITIAL_CAPACITY);

        sql.append("WITH distribution_base AS ( ");
        sql.append("  SELECT d.instance_id, d.meta_id, d.uid, d.type, d.format, d.license, d.version_id, ");
        sql.append("         v.status AS versioning_status, v.editor_id, v.version AS version_info ");
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

        // Operations related to distribution
        sql.append("dist_operations AS ( ");
        sql.append("  SELECT od.distribution_instance_id, ARRAY_AGG(od.operation_instance_id) AS operation_ids ");
        sql.append("  FROM metadata_catalogue.operation_distribution od ");
        sql.append("  WHERE od.distribution_instance_id = ?1 ");
        sql.append("  GROUP BY od.distribution_instance_id ");
        sql.append("), ");

        // DataProduct info (full)
        sql.append("dataproduct_info AS ( ");
        sql.append("  SELECT ddp.distribution_instance_id, dp.instance_id AS dataproduct_id, dp.meta_id AS dp_meta_id, ");
        sql.append("         dp.uid AS dp_uid, dp.type AS dp_type, dp.keywords, dp.accrualperiodicity, ");
        sql.append("         dp.accessright, v.version AS dp_version ");
        sql.append("  FROM metadata_catalogue.distribution_dataproduct ddp ");
        sql.append("  JOIN metadata_catalogue.dataproduct dp ON ddp.dataproduct_instance_id = dp.instance_id ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON dp.version_id = v.version_id ");
        sql.append("  WHERE ddp.distribution_instance_id = ?1 ");
        sql.append("  LIMIT 1 ");
        sql.append("), ");

        // DataProduct identifiers
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

        // DataProduct contact points with person info
        sql.append("dp_contactpoints AS ( ");
        sql.append("  SELECT di.dataproduct_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT( ");
        sql.append("           'id', cp.instance_id, 'metaid', cp.meta_id, 'uid', cp.uid, ");
        sql.append("           'person', CASE WHEN p.instance_id IS NOT NULL THEN ");
        sql.append("             JSONB_BUILD_OBJECT('id', p.instance_id, 'metaid', p.meta_id, 'uid', p.uid) ");
        sql.append("           ELSE NULL END ");
        sql.append("         )) AS contactpoints ");
        sql.append("  FROM dataproduct_info di ");
        sql.append("  JOIN metadata_catalogue.dataproduct_contactpoint dcp ON di.dataproduct_id = dcp.dataproduct_instance_id ");
        sql.append("  JOIN metadata_catalogue.contactpoint cp ON dcp.contactpoint_instance_id = cp.instance_id ");
        sql.append("  LEFT JOIN metadata_catalogue.person p ON cp.instance_id = ( ");
        sql.append("    SELECT pcp.contactpoint_instance_id FROM metadata_catalogue.person_contactpoint pcp ");
        sql.append("    WHERE pcp.contactpoint_instance_id = cp.instance_id LIMIT 1 ");
        sql.append("  ) ");
        sql.append("  GROUP BY di.dataproduct_id ");
        sql.append("), ");

        // DataProduct provenance
        sql.append("dp_provenance AS ( ");
        sql.append("  SELECT di.dataproduct_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT('provenance', dprov.provenance)) AS provenance ");
        sql.append("  FROM dataproduct_info di ");
        sql.append("  JOIN metadata_catalogue.dataproduct_provenance dprov ON di.dataproduct_id = dprov.dataproduct_instance_id ");
        sql.append("  GROUP BY di.dataproduct_id ");
        sql.append("), ");

        // WebService info (full)
        sql.append("webservice_info AS ( ");
        sql.append("  SELECT wd.distribution_instance_id, ws.instance_id AS webservice_id, ws.meta_id AS ws_meta_id, ");
        sql.append("         ws.uid AS ws_uid, ws.name, ws.description AS ws_description, ");
        sql.append("         ws.provider AS provider_id, ws.keywords AS ws_keywords ");
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
        sql.append("  SELECT wi.webservice_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT('startdate', t.startdate, 'enddate', t.enddate)) AS temporals ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.webservice_temporal wst ON wi.webservice_id = wst.webservice_instance_id ");
        sql.append("  JOIN metadata_catalogue.temporal t ON wst.temporal_instance_id = t.instance_id ");
        sql.append("  GROUP BY wi.webservice_id ");
        sql.append("), ");

        // WebService categories (service types)
        sql.append("ws_categories AS ( ");
        sql.append("  SELECT wi.webservice_id, ");
        sql.append("         JSONB_AGG(DISTINCT c.name) AS service_types ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.webservice_category wc ON wi.webservice_id = wc.webservice_instance_id ");
        sql.append("  JOIN metadata_catalogue.category c ON wc.category_instance_id = c.instance_id ");
        sql.append("  GROUP BY wi.webservice_id ");
        sql.append("), ");

        // WebService contact points with person info
        sql.append("ws_contactpoints AS ( ");
        sql.append("  SELECT wi.webservice_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT( ");
        sql.append("           'id', cp.instance_id, 'metaid', cp.meta_id, 'uid', cp.uid, ");
        sql.append("           'person', CASE WHEN p.instance_id IS NOT NULL THEN ");
        sql.append("             JSONB_BUILD_OBJECT('id', p.instance_id, 'metaid', p.meta_id, 'uid', p.uid) ");
        sql.append("           ELSE NULL END ");
        sql.append("         )) AS contactpoints ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.webservice_contactpoint wcp ON wi.webservice_id = wcp.webservice_instance_id ");
        sql.append("  JOIN metadata_catalogue.contactpoint cp ON wcp.contactpoint_instance_id = cp.instance_id ");
        sql.append("  LEFT JOIN metadata_catalogue.person p ON cp.instance_id = ( ");
        sql.append("    SELECT pcp.contactpoint_instance_id FROM metadata_catalogue.person_contactpoint pcp ");
        sql.append("    WHERE pcp.contactpoint_instance_id = cp.instance_id LIMIT 1 ");
        sql.append("  ) ");
        sql.append("  GROUP BY wi.webservice_id ");
        sql.append("), ");

        // WebService operations with mappings
        sql.append("ws_operations AS ( ");
        sql.append("  SELECT wi.webservice_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT( ");
        sql.append("           'instance_id', op.instance_id, 'uid', op.uid, 'method', op.method, 'template', op.template, ");
        sql.append("           'mappings', ( ");
        sql.append("             SELECT JSONB_AGG(JSONB_BUILD_OBJECT( ");
        sql.append("               'variable', m.variable, 'label', m.label, 'required', m.required, ");
        sql.append("               'range', m.range, 'defaultvalue', m.defaultvalue, 'minvalue', m.minvalue, ");
        sql.append("               'maxvalue', m.maxvalue, 'property', m.property, 'valuepattern', m.valuepattern, ");
        sql.append("               'readonly', m.read_only_value, 'multiple', m.multiple_values, ");
        sql.append("               'paramvalues', (SELECT ARRAY_AGG(e.value) FROM metadata_catalogue.mapping_element me ");
        sql.append("                               JOIN metadata_catalogue.element e ON me.element_instance_id = e.instance_id ");
        sql.append("                               WHERE me.mapping_instance_id = m.instance_id AND e.type = 'PARAMVALUE') ");
        sql.append("             )) ");
        sql.append("             FROM metadata_catalogue.operation_mapping om ");
        sql.append("             JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ");
        sql.append("             WHERE om.operation_instance_id = op.instance_id ");
        sql.append("           ) ");
        sql.append("         )) AS operations ");
        sql.append("  FROM webservice_info wi ");
        sql.append("  JOIN metadata_catalogue.operation_webservice ow ON wi.webservice_id = ow.webservice_instance_id ");
        sql.append("  JOIN metadata_catalogue.operation op ON ow.operation_instance_id = op.instance_id ");
        sql.append("  GROUP BY wi.webservice_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  db.instance_id, db.meta_id, db.uid, db.type, db.format, db.license, ");
        sql.append("  db.versioning_status, db.editor_id, db.version_info, ");
        sql.append("  COALESCE(dt.title, '') AS title, ");
        sql.append("  COALESCE(dd.description, '') AS description, ");
        sql.append("  COALESCE(ddu.download_urls, '') AS download_urls, ");
        sql.append("  COALESCE(CAST(TO_JSON(dops.operation_ids) AS text), '[]') AS dist_operation_ids, ");
        sql.append("  di.dataproduct_id, di.dp_meta_id, di.dp_uid, di.dp_type, di.keywords, ");
        sql.append("  di.accrualperiodicity, di.accessright, di.dp_version, ");
        sql.append("  COALESCE(CAST(dpi.identifiers AS text), '[]') AS dp_identifiers, ");
        sql.append("  COALESCE(dps.locations, '') AS dp_spatial, ");
        sql.append("  dpt.startdate AS dp_start_date, dpt.enddate AS dp_end_date, ");
        sql.append("  COALESCE(CAST(dpp.publishers AS text), '[]') AS dp_publishers, ");
        sql.append("  COALESCE(CAST(dpc.categories AS text), '[]') AS dp_categories, ");
        sql.append("  COALESCE(CAST(dpcp.contactpoints AS text), '[]') AS dp_contactpoints, ");
        sql.append("  COALESCE(CAST(dpprov.provenance AS text), '[]') AS dp_provenance, ");
        sql.append("  wi.webservice_id, wi.ws_meta_id, wi.ws_uid, wi.name AS ws_name, wi.ws_description, wi.ws_keywords, ");
        sql.append("  COALESCE(wsd.documentation, '') AS ws_documentation, ");
        sql.append("  COALESCE(CAST(wsp.provider AS text), '{}') AS ws_provider, ");
        sql.append("  COALESCE(wss.locations, '') AS ws_spatial, ");
        sql.append("  COALESCE(CAST(wst.temporals AS text), '[]') AS ws_temporals, ");
        sql.append("  COALESCE(CAST(wsc.service_types AS text), '[]') AS ws_service_types, ");
        sql.append("  COALESCE(CAST(wscp.contactpoints AS text), '[]') AS ws_contactpoints, ");
        sql.append("  COALESCE(CAST(wsops.operations AS text), '[]') AS ws_operations ");
        sql.append("FROM distribution_base db ");
        sql.append("LEFT JOIN dist_titles dt ON db.instance_id = dt.distribution_instance_id ");
        sql.append("LEFT JOIN dist_descriptions dd ON db.instance_id = dd.distribution_instance_id ");
        sql.append("LEFT JOIN dist_download_urls ddu ON db.instance_id = ddu.distribution_instance_id ");
        sql.append("LEFT JOIN dist_operations dops ON db.instance_id = dops.distribution_instance_id ");
        sql.append("LEFT JOIN dataproduct_info di ON db.instance_id = di.distribution_instance_id ");
        sql.append("LEFT JOIN dp_identifiers dpi ON di.dataproduct_id = dpi.dataproduct_id ");
        sql.append("LEFT JOIN dp_spatial dps ON di.dataproduct_id = dps.dataproduct_id ");
        sql.append("LEFT JOIN dp_temporal dpt ON di.dataproduct_id = dpt.dataproduct_id ");
        sql.append("LEFT JOIN dp_publishers dpp ON di.dataproduct_id = dpp.dataproduct_id ");
        sql.append("LEFT JOIN dp_categories dpc ON di.dataproduct_id = dpc.dataproduct_id ");
        sql.append("LEFT JOIN dp_contactpoints dpcp ON di.dataproduct_id = dpcp.dataproduct_id ");
        sql.append("LEFT JOIN dp_provenance dpprov ON di.dataproduct_id = dpprov.dataproduct_id ");
        sql.append("LEFT JOIN webservice_info wi ON db.instance_id = wi.distribution_instance_id ");
        sql.append("LEFT JOIN ws_documentation wsd ON wi.webservice_id = wsd.webservice_id ");
        sql.append("LEFT JOIN ws_provider wsp ON wi.webservice_id = wsp.webservice_id ");
        sql.append("LEFT JOIN ws_spatial wss ON wi.webservice_id = wss.webservice_id ");
        sql.append("LEFT JOIN ws_temporal wst ON wi.webservice_id = wst.webservice_id ");
        sql.append("LEFT JOIN ws_categories wsc ON wi.webservice_id = wsc.webservice_id ");
        sql.append("LEFT JOIN ws_contactpoints wscp ON wi.webservice_id = wscp.webservice_id ");
        sql.append("LEFT JOIN ws_operations wsops ON wi.webservice_id = wsops.webservice_id ");

        return sql.toString();
    }

    /**
     * Maps a database result row to a DistributionExtended object.
     */
    private static DistributionExtended mapRowToDistributionExtended(Object[] row) {
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
        String versionInfo = (String) row[i++];
        String title = (String) row[i++];
        String description = (String) row[i++];
        String downloadUrls = (String) row[i++];
        String distOperationIdsJson = (String) row[i++];

        // DataProduct fields
        String dataproductId = (String) row[i++];
        String dpMetaId = (String) row[i++];
        String dpUid = (String) row[i++];
        String dpType = (String) row[i++];
        String keywords = (String) row[i++];
        String accrualPeriodicity = (String) row[i++];
        String accessRight = (String) row[i++];
        String dpVersion = (String) row[i++];
        String dpIdentifiersJson = (String) row[i++];
        String dpSpatial = (String) row[i++];
        Timestamp dpStartDate = (Timestamp) row[i++];
        Timestamp dpEndDate = (Timestamp) row[i++];
        String dpPublishersJson = (String) row[i++];
        String dpCategoriesJson = (String) row[i++];
        String dpContactpointsJson = (String) row[i++];
        String dpProvenanceJson = (String) row[i++];

        // WebService fields
        String webserviceId = (String) row[i++];
        String wsMetaId = (String) row[i++];
        String wsUid = (String) row[i++];
        String wsName = (String) row[i++];
        String wsDescription = (String) row[i++];
        String wsKeywords = (String) row[i++];
        String wsDocumentation = (String) row[i++];
        String wsProviderJson = (String) row[i++];
        String wsSpatial = (String) row[i++];
        String wsTemporalsJson = (String) row[i++];
        String wsServiceTypesJson = (String) row[i++];
        String wsContactpointsJson = (String) row[i++];
        String wsOperationsJson = (String) row[i++];

        // Parse operation IDs related to this distribution
        List<String> distOperationIds = parseOperationIds(distOperationIdsJson);

        // Build DistributionExtended object
        DistributionExtended distribution = new DistributionExtended();

        // Basic distribution fields
        distribution.setId(instanceId);
        distribution.setUid(uid);
        distribution.setMetaId(metaId);
        distribution.setTitle(title);
        distribution.setDescription(description);
        distribution.setVersioningStatus(versionInfo);
        distribution.setEditorId(editorId);

        if (type != null) {
            String[] typeParts = type.split("/");
            distribution.setType(typeParts[typeParts.length - 1]);
        }

        if (downloadUrls != null && !downloadUrls.isEmpty()) {
            distribution.setDownloadURL(downloadUrls);
        }

        distribution.setLicense(license);
        distribution.setFrequencyUpdate(accrualPeriodicity);
        distribution.setHref(EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId);
        distribution.setHrefExtended(EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId + "?extended=true");

        // Keywords
        parseKeywords(distribution, keywords, wsKeywords);

        // Build DataProduct bean
        if (dataproductId != null) {
            DataProduct dataproduct = buildDataProduct(dataproductId, dpMetaId, dpUid, dpType, dpVersion,
                    dpIdentifiersJson, dpSpatial, dpStartDate, dpEndDate, dpPublishersJson,
                    dpCategoriesJson, dpContactpointsJson, dpProvenanceJson, accessRight);
            distribution.getRelatedDataProducts().add(dataproduct);
        }

        // Build WebService bean
        if (webserviceId != null) {
            Webservice webservice = buildWebservice(wsName, wsDescription, wsDocumentation,
                    wsProviderJson, wsSpatial, wsTemporalsJson, wsServiceTypesJson,
                    wsContactpointsJson, wsOperationsJson, distOperationIds);
            distribution.getRelatedWebservice().add(webservice);
        }

        // Contact points
        boolean hasWsContacts = !isEmptyJson(wsContactpointsJson) && !"[]".equals(wsContactpointsJson);
        boolean hasDpContacts = !isEmptyJson(dpContactpointsJson) && !"[]".equals(dpContactpointsJson);

        if (hasWsContacts) {
            distribution.getAvailableContactPoints().add(
                    new AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + instanceId +
                                    "&contactType=" + ProviderType.SERVICEPROVIDERS)
                            .type(ProviderType.SERVICEPROVIDERS)
                            .build()
            );
        }

        if (hasDpContacts) {
            distribution.getAvailableContactPoints().add(
                    new AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + instanceId +
                                    "&contactType=" + ProviderType.DATAPROVIDERS)
                            .type(ProviderType.DATAPROVIDERS)
                            .build()
            );
        }

        if (hasWsContacts && hasDpContacts) {
            distribution.getAvailableContactPoints().add(
                    new AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + instanceId +
                                    "&contactType=" + ProviderType.ALL)
                            .type(ProviderType.ALL)
                            .build()
            );
        }

        // Available formats
        distribution.setAvailableFormats(new ArrayList<>());

        // Categories (facets)
        buildCategories(distribution, instanceId, uid, metaId, title, description,
                dpPublishersJson, wsProviderJson, dpCategoriesJson);

        return distribution;
    }

    private static List<String> parseOperationIds(String json) {
        if (isEmptyJson(json)) {
            return Collections.emptyList();
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(json);
            List<String> ids = new ArrayList<>();
            for (JsonNode node : arrayNode) {
                if (!node.isNull()) {
                    ids.add(node.asText());
                }
            }
            return ids;
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse operation IDs: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static DataProduct buildDataProduct(String id, String metaId, String uid, String type, String version,
                                                String identifiersJson, String spatial, Timestamp startDate, Timestamp endDate,
                                                String publishersJson, String categoriesJson, String contactpointsJson,
                                                String provenanceJson, String accessRight) {
        DataProduct dataproduct = new DataProduct();
        dataproduct.setId(id);
        dataproduct.setUid(uid);
        dataproduct.setType(type);
        dataproduct.setVersion(version);
        dataproduct.setAccessRights(accessRight);

        // Identifiers
        if (!isEmptyJson(identifiersJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(identifiersJson);
                if (arrayNode.size() == 0) {
                    HashMap<String, String> singleIdentifier = new HashMap<>();
                    singleIdentifier.put("type", "plain");
                    singleIdentifier.put("value", uid);
                    dataproduct.getIdentifiers().add(singleIdentifier);
                } else {
                    for (JsonNode node : arrayNode) {
                        HashMap<String, String> identifier = new HashMap<>();
                        identifier.put("type", getTextOrNull(node, "type"));
                        identifier.put("value", getTextOrNull(node, "value"));
                        dataproduct.getIdentifiers().add(identifier);
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse identifiers: {}", e.getMessage());
            }
        } else {
            HashMap<String, String> singleIdentifier = new HashMap<>();
            singleIdentifier.put("type", "plain");
            singleIdentifier.put("value", uid);
            dataproduct.getIdentifiers().add(singleIdentifier);
        }

        // Spatial
        if (spatial != null && !spatial.isEmpty()) {
            String[] locations = spatial.split("#EPOS#");
            for (String location : locations) {
                String trimmed = location.trim();
                if (!trimmed.isEmpty()) {
                    dataproduct.getSpatial().addPaths(
                            SpatialInformation.doSpatial(trimmed),
                            SpatialInformation.checkPoint(trimmed)
                    );
                }
            }
        }

        // Temporal
        TemporalCoverage tc = new TemporalCoverage();
        if (startDate != null) {
            tc.setStartDate(formatTimestamp(startDate));
        }
        if (endDate != null) {
            tc.setEndDate(formatTimestamp(endDate));
        }
        dataproduct.setTemporalCoverage(List.of(tc));

        // Data providers
        List<Organization> publishers = parsePublishers(publishersJson);
        if (!publishers.isEmpty()) {
            dataproduct.setDataProvider(DataServiceProviderGenerationSQL.getProviders(publishers));
        }

        // Contact points
        if (!isEmptyJson(contactpointsJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(contactpointsJson);
                for (JsonNode node : arrayNode) {
                    HashMap<String, Object> contact = new HashMap<>();
                    contact.put("id", getTextOrNull(node, "id"));
                    contact.put("metaid", getTextOrNull(node, "metaid"));
                    contact.put("uid", getTextOrNull(node, "uid"));

                    if (node.has("person") && !node.get("person").isNull()) {
                        JsonNode personNode = node.get("person");
                        HashMap<String, Object> person = new HashMap<>();
                        person.put("id", getTextOrNull(personNode, "id"));
                        person.put("metaid", getTextOrNull(personNode, "metaid"));
                        person.put("uid", getTextOrNull(personNode, "uid"));
                        contact.put("person", person);
                    }
                    dataproduct.getContactPoints().add(contact);
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse contact points: {}", e.getMessage());
            }
        }

        // Science domains
        if (!isEmptyJson(categoriesJson)) {
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
                LOGGER.warn("Failed to parse categories: {}", e.getMessage());
            }
            dataproduct.setScienceDomain(scienceDomains);
        }

        // Provenance
        if (!isEmptyJson(provenanceJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(provenanceJson);
                for (JsonNode node : arrayNode) {
                    HashMap<String, String> prov = new HashMap<>();
                    prov.put("provenance", getTextOrNull(node, "provenance"));
                    dataproduct.getProvenance().add(prov);
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse provenance: {}", e.getMessage());
            }
        }

        return dataproduct;
    }

    private static Webservice buildWebservice(String name, String description, String documentation,
                                              String providerJson, String spatial, String temporalsJson,
                                              String serviceTypesJson, String contactpointsJson,
                                              String operationsJson, List<String> distOperationIds) {
        Webservice webservice = new Webservice();
        webservice.setName(name);
        webservice.setDescription(description);
        webservice.setDocumentation(extractDocumentationUri(documentation));

        // Provider
        if (!isEmptyJson(providerJson) && !"{}".equals(providerJson)) {
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
                    webservice.setProvider(providers.get(0));
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse provider: {}", e.getMessage());
            }
        }

        // Spatial
        if (spatial != null && !spatial.isEmpty()) {
            String[] locations = spatial.split("#EPOS#");
            for (String location : locations) {
                String trimmed = location.trim();
                if (!trimmed.isEmpty()) {
                    webservice.getSpatial().addPaths(
                            SpatialInformation.doSpatial(trimmed),
                            SpatialInformation.checkPoint(trimmed)
                    );
                }
            }
        }

        // Temporal
        if (!isEmptyJson(temporalsJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(temporalsJson);
                for (JsonNode node : arrayNode) {
                    TemporalCoverage tc = new TemporalCoverage();
                    if (node.has("startdate") && !node.get("startdate").isNull()) {
                        tc.setStartDate(node.get("startdate").asText());
                    }
                    if (node.has("enddate") && !node.get("enddate").isNull()) {
                        tc.setEndDate(node.get("enddate").asText());
                    }
                    webservice.getTemporalCoverage().add(tc);
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse temporals: {}", e.getMessage());
            }
        }

        // Service types
        if (!isEmptyJson(serviceTypesJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(serviceTypesJson);
                List<String> types = new ArrayList<>();
                for (JsonNode node : arrayNode) {
                    if (!node.isNull()) {
                        types.add(node.asText());
                    }
                }
                webservice.setType(types);
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse service types: {}", e.getMessage());
            }
        }

        // Contact points
        if (!isEmptyJson(contactpointsJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(contactpointsJson);
                for (JsonNode node : arrayNode) {
                    HashMap<String, Object> contact = new HashMap<>();
                    contact.put("id", getTextOrNull(node, "id"));
                    contact.put("metaid", getTextOrNull(node, "metaid"));
                    contact.put("uid", getTextOrNull(node, "uid"));

                    if (node.has("person") && !node.get("person").isNull()) {
                        JsonNode personNode = node.get("person");
                        HashMap<String, String> person = new HashMap<>();
                        person.put("id", getTextOrNull(personNode, "id"));
                        person.put("metaid", getTextOrNull(personNode, "metaid"));
                        person.put("uid", getTextOrNull(personNode, "uid"));
                        contact.put("person", person);
                    }
                    webservice.getContactPoints().add(contact);
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse contact points: {}", e.getMessage());
            }
        }

        // Operations (only those related to this distribution)
        if (!isEmptyJson(operationsJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(operationsJson);
                for (JsonNode opNode : arrayNode) {
                    String opId = getTextOrNull(opNode, "instance_id");
                    // Only include operations that are related to this distribution
                    if (opId != null && distOperationIds.contains(opId)) {
                        Operation operation = new Operation();
                        operation.setUid(getTextOrNull(opNode, "uid"));
                        operation.setMethod(getTextOrNull(opNode, "method"));
                        operation.setEndpoint(getTextOrNull(opNode, "template"));

                        // Parameters (mappings)
                        if (opNode.has("mappings") && !opNode.get("mappings").isNull()) {
                            for (JsonNode mpNode : opNode.get("mappings")) {
                                ServiceParameter sp = new ServiceParameter();
                                sp.setName(getTextOrNull(mpNode, "variable"));
                                sp.setLabel(getTextOrNull(mpNode, "label") != null ?
                                        getTextOrNull(mpNode, "label").replaceAll("@en", "") : null);
                                sp.setRequired(mpNode.has("required") && !mpNode.get("required").isNull() ?
                                        Boolean.parseBoolean(mpNode.get("required").asText()) : null);
                                sp.setType(getTextOrNull(mpNode, "range") != null ?
                                        getTextOrNull(mpNode, "range").replace("xsd:", "") : null);
                                sp.setDefaultValue(getTextOrNull(mpNode, "defaultvalue"));
                                sp.setMinValue(getTextOrNull(mpNode, "minvalue"));
                                sp.setMaxValue(getTextOrNull(mpNode, "maxvalue"));
                                sp.setProperty(getTextOrNull(mpNode, "property"));
                                sp.setValuePattern(getTextOrNull(mpNode, "valuepattern"));
                                sp.setReadOnlyValue(getTextOrNull(mpNode, "readonly"));
                                sp.setMultipleValue(getTextOrNull(mpNode, "multiple"));

                                // Enum values
                                if (mpNode.has("paramvalues") && !mpNode.get("paramvalues").isNull()) {
                                    List<String> enumValues = new ArrayList<>();
                                    for (JsonNode valNode : mpNode.get("paramvalues")) {
                                        if (!valNode.isNull()) {
                                            enumValues.add(valNode.asText());
                                        }
                                    }
                                    sp.setEnumValue(enumValues);
                                } else {
                                    sp.setEnumValue(new ArrayList<>());
                                }

                                operation.getServiceParameters().add(sp);
                            }
                        }

                        webservice.getOperations().add(operation);
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse operations: {}", e.getMessage());
            }
        }

        return webservice;
    }

    private static void parseKeywords(DistributionExtended distribution, String dpKeywords, String wsKeywords) {
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
            LOGGER.warn("Failed to parse publishers: {}", e.getMessage());
        }

        return organizations;
    }

    private static void buildCategories(DistributionExtended distribution, String instanceId, String uid,
                                        String metaId, String title, String description,
                                        String publishersJson, String providerJson, String categoriesJson) {
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
                .availableFormats(new ArrayList<>())
                .sha256id(uid != null ? DigestUtils.sha256Hex(uid) : "")
                .dataProvider(facetsDataProviders)
                .serviceProvider(facetsServiceProviders)
                .categories(categoryList.isEmpty() ? null : categoryList)
                .versioningStatus(distribution.getVersioningStatus())
                .editorId(distribution.getEditorId())
                .build());

        FacetsNodeTree categories = FacetsGeneration.generateResponseUsingCategories(discoveryList, Facets.Type.DATA);
        categories.getNodes().forEach(node -> node.setDistributions(null));
        distribution.setCategories(categories.getFacets());
    }

    private static String formatTimestamp(Timestamp ts) {
        if (ts == null) return null;
        String formatted = ts.toString().replace(".0", "Z").replace(" ", "T");
        if (!formatted.contains("Z")) {
            formatted = formatted + "Z";
        }
        return formatted;
    }

    /**
     * Extracts the "Uri" value from a documentation JSON object.
     * Example input: {"Title":"...","Description":"...","Uri":"https://..."}
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
