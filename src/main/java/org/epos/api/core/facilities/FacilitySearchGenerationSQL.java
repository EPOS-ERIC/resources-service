package org.epos.api.core.facilities;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.apache.commons.codec.digest.DigestUtils;
import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.DataServiceProvider;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.DiscoveryItem.DiscoveryItemBuilder;
import org.epos.api.beans.NodeFilters;
import org.epos.api.beans.SearchResponse;
import org.epos.api.core.AvailableFormatsBuilder;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.routines.DatabaseConnections;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.Node;
import org.epos.api.utility.BBoxToPolygon;
import org.epos.eposdatamodel.Organization;
import org.epos.eposdatamodel.User;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for facility search.
 * This replaces the JPA-based FacilitySearchGenerationJPA for improved performance.
 */
public class FacilitySearchGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(FacilitySearchGenerationSQL.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/facilities/details/";
    private static final String API_PATH_EXECUTE_EQUIPMENTS = EnvironmentVariables.API_CONTEXT + "/equipments/";
    private static final String API_FORMAT = "?format=";

    private static final String PARAMETER_FACILITY_TYPES = "facilitytypes";
    private static final String PARAMETER_EQUIPMENT_TYPES = "equipmenttypes";

    private static final String SPATIAL_SEPARATOR = " #EPOS# ";
    private static final String SPATIAL_SEPARATOR_TRIMMED = "#EPOS#";

    private static final int SQL_BUILDER_INITIAL_CAPACITY = 8192;

    /**
     * Aggregates filter metadata collected during result processing.
     */
    private static final class FilterData {
        final Set<String> keywords = ConcurrentHashMap.newKeySet();
        final Set<CategoryInfo> facilityTypes = ConcurrentHashMap.newKeySet();
        final Set<CategoryInfo> equipmentTypes = ConcurrentHashMap.newKeySet();
        final Set<OrganizationInfo> organizations = ConcurrentHashMap.newKeySet();
    }

    private static final class CategoryInfo {
        final String instanceId;
        final String name;

        CategoryInfo(String instanceId, String name) {
            this.instanceId = instanceId;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CategoryInfo)) return false;
            return Objects.equals(instanceId, ((CategoryInfo) o).instanceId);
        }

        @Override
        public int hashCode() {
            return instanceId != null ? instanceId.hashCode() : 0;
        }
    }

    private static final class OrganizationInfo {
        final String instanceId;
        final String legalName;

        OrganizationInfo(String instanceId, String legalName) {
            this.instanceId = instanceId;
            this.legalName = legalName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OrganizationInfo)) return false;
            return Objects.equals(instanceId, ((OrganizationInfo) o).instanceId);
        }

        @Override
        public int hashCode() {
            return instanceId != null ? instanceId.hashCode() : 0;
        }
    }

    public static SearchResponse generate(Map<String, Object> parameters, User user) {
        final long startTime = System.nanoTime();
        LOGGER.info("Initiating facility search (SQL) with parameters: {}", parameters);

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            FilterData filterData = new FilterData();

            // Get status list based on user permissions
            List<String> statuses = new ArrayList<>(getStatusList(parameters, user));


            // Build and execute query
            QueryContext ctx = buildFacilitySearchSQL(parameters, user, statuses);
            
            LOGGER.info("Generated SQL: {}", ctx.sql.toString());
            LOGGER.info("SQL Parameters: {}", ctx.params);
            
            Query query = em.createNativeQuery(ctx.sql.toString());

            for (Map.Entry<Integer, Object> entry : ctx.params.entrySet()) {
                query.setParameter(entry.getKey(), entry.getValue());
            }

            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();

            LOGGER.info("SQL query returned {} rows", results.size());

            // Prepare spatial filtering if needed
            Geometry inputGeometry = null;
            WKTReader wktReader = null;
            if (hasSpatialParams(parameters)) {
                try {
                    GeometryFactory geometryFactory = new GeometryFactory();
                    wktReader = new WKTReader(geometryFactory);
                    inputGeometry = wktReader.read(BBoxToPolygon.transform(parameters));
                } catch (Exception e) {
                    LOGGER.error("Failed to parse bounding box parameters", e);
                }
            }

            // Map results to discovery items
            Set<DiscoveryItem> discoveryItems = new HashSet<>();
            for (Object[] row : results) {
                DiscoveryItem item = mapRowToDiscoveryItem(row, parameters, user, filterData, inputGeometry, wktReader);
                if (item != null) {
                    discoveryItems.add(item);
                }
            }

            // Also fetch facility-related distributions
            Set<DiscoveryItem> distributionItems = fetchFacilityDistributions(em, parameters, user, statuses, inputGeometry, wktReader);
            discoveryItems.addAll(distributionItems);

            LOGGER.info("Facility search completed (SQL) in {} ms with {} items",
                    (System.nanoTime() - startTime) / 1_000_000, discoveryItems.size());

            return buildResponse(discoveryItems, parameters, filterData);

        } catch (Exception e) {
            LOGGER.error("Facility search failed", e);
            throw new RuntimeException("Facility search failed", e);
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    /**
     * Query context for building dynamic SQL
     */
    private static final class QueryContext {
        final StringBuilder sql;
        final Map<Integer, Object> params;
        int paramIndex;

        QueryContext() {
            this.sql = new StringBuilder(SQL_BUILDER_INITIAL_CAPACITY);
            this.params = new HashMap<>(16);
            this.paramIndex = 1;
        }
    }

    private static String nextParam(QueryContext ctx, Object value) {
        int idx = ctx.paramIndex++;
        ctx.params.put(idx, value);
        return "?" + idx;
    }

    private static String nextListParam(QueryContext ctx, List<String> values) {
        if (values == null || values.isEmpty()) {
            return "(NULL)";
        }
        StringBuilder sb = new StringBuilder(values.size() * 8);
        sb.append('(');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(nextParam(ctx, values.get(i)));
        }
        sb.append(')');
        return sb.toString();
    }

    private static QueryContext buildFacilitySearchSQL(Map<String, Object> parameters, User user, List<String> statuses) {
        QueryContext ctx = new QueryContext();

        List<String> facilityTypes = getListParam(parameters, PARAMETER_FACILITY_TYPES);
        List<String> equipmentTypes = getListParam(parameters, PARAMETER_EQUIPMENT_TYPES);
        List<String> keywords = getListParam(parameters, "keywords");
        String freeTextQuery = (String) parameters.get("q");

        ctx.sql.append("WITH ");

        // CTE 1: Published facilities
        ctx.sql.append("published_facilities AS ( ");
        ctx.sql.append("  SELECT f.instance_id, f.meta_id, f.uid, f.title, f.description, f.type, f.keywords, ");
        ctx.sql.append("         v.status AS versioning_status, v.change_timestamp, v.editor_id ");
        ctx.sql.append("  FROM metadata_catalogue.facility f ");
        ctx.sql.append("  JOIN metadata_catalogue.versioningstatus v ON f.version_id = v.version_id ");
        ctx.sql.append("  WHERE ");
        buildVersioningStatusFilter(ctx, user, parameters, statuses, "v");
        ctx.sql.append("), ");

        // CTE 2: Facility categories
        ctx.sql.append("facility_categories_agg AS ( ");
        ctx.sql.append("  SELECT fc.facility_instance_id, ");
        ctx.sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('uid', c.uid, 'name', c.name, 'instance_id', c.instance_id)) AS categories ");
        ctx.sql.append("  FROM metadata_catalogue.facility_category fc ");
        ctx.sql.append("  JOIN metadata_catalogue.category c ON fc.category_instance_id = c.instance_id ");
        ctx.sql.append("  WHERE fc.facility_instance_id IN (SELECT instance_id FROM published_facilities) ");
        ctx.sql.append("  GROUP BY fc.facility_instance_id ");
        ctx.sql.append("), ");

        // CTE 3: Facility spatial
        ctx.sql.append("facility_spatial_agg AS ( ");
        ctx.sql.append("  SELECT fs.facility_instance_id, STRING_AGG(s.location, '").append(SPATIAL_SEPARATOR).append("') AS locations ");
        ctx.sql.append("  FROM metadata_catalogue.facility_spatial fs ");
        ctx.sql.append("  JOIN metadata_catalogue.spatial s ON fs.spatial_instance_id = s.instance_id ");
        ctx.sql.append("  WHERE fs.facility_instance_id IN (SELECT instance_id FROM published_facilities) ");
        ctx.sql.append("  GROUP BY fs.facility_instance_id ");
        ctx.sql.append("), ");

        // CTE 4: Facility owners (organizations)
        ctx.sql.append("facility_owners_agg AS ( ");
        ctx.sql.append("  SELECT oo.entity_instance_id AS facility_instance_id, ");
        ctx.sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('instance_id', o.instance_id, 'legal_name', o.legalname)) AS owners ");
        ctx.sql.append("  FROM metadata_catalogue.organization_owns oo ");
        ctx.sql.append("  JOIN metadata_catalogue.organization o ON oo.organization_instance_id = o.instance_id ");
        ctx.sql.append("  WHERE oo.resource_entity = 'FACILITY' ");
        ctx.sql.append("    AND oo.entity_instance_id IN (SELECT instance_id FROM published_facilities) ");
        ctx.sql.append("  GROUP BY oo.entity_instance_id ");
        ctx.sql.append("), ");

        // CTE 5: Facility types (from category table based on facility.type)
        ctx.sql.append("facility_type_names AS ( ");
        ctx.sql.append("  SELECT pf.instance_id AS facility_instance_id, c.instance_id AS type_instance_id, c.name AS type_name ");
        ctx.sql.append("  FROM published_facilities pf ");
        ctx.sql.append("  JOIN metadata_catalogue.category c ON TRIM(c.uid) = TRIM(pf.type) ");
        ctx.sql.append("), ");

        // CTE 6: Equipment types for each facility
        // Note: Equipment status is always PUBLISHED for this CTE (equipment visibility is tied to facility visibility)
        ctx.sql.append("equipment_types_agg AS ( ");
        ctx.sql.append("  SELECT ei.entity_instance_id AS facility_instance_id, ");
        ctx.sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('instance_id', c.instance_id, 'name', c.name)) AS equipment_types ");
        ctx.sql.append("  FROM metadata_catalogue.equipment_ispartof ei ");
        ctx.sql.append("  JOIN metadata_catalogue.equipment e ON ei.equipment_instance_id = e.instance_id ");
        ctx.sql.append("  JOIN metadata_catalogue.versioningstatus v ON e.version_id = v.version_id ");
        ctx.sql.append("  JOIN metadata_catalogue.category c ON TRIM(c.uid) = TRIM(e.type) ");
        ctx.sql.append("  WHERE ei.resource_entity = 'FACILITY' ");
        ctx.sql.append("    AND ei.entity_instance_id IN (SELECT instance_id FROM published_facilities) ");
        ctx.sql.append("    AND ");
        buildVersioningStatusFilter(ctx, user, parameters, statuses, "v");
        ctx.sql.append("  GROUP BY ei.entity_instance_id ");
        ctx.sql.append("), ");

        // CTE 7: Filtered facilities
        ctx.sql.append("filtered_facilities AS ( ");
        ctx.sql.append("  SELECT pf.instance_id FROM published_facilities pf ");

        boolean hasFilters = !facilityTypes.isEmpty() || !equipmentTypes.isEmpty() || !keywords.isEmpty() ||
                (freeTextQuery != null && !freeTextQuery.trim().isEmpty());

        if (!facilityTypes.isEmpty()) {
            String ftParams = nextListParam(ctx, facilityTypes);
            ctx.sql.append("  JOIN facility_type_names ftn ON pf.instance_id = ftn.facility_instance_id ");
            ctx.sql.append("    AND (ftn.type_name IN ").append(ftParams)
                    .append(" OR ftn.type_instance_id IN ").append(ftParams).append(") ");
        }

        if (!equipmentTypes.isEmpty()) {
            String etParams = nextListParam(ctx, equipmentTypes);
            ctx.sql.append("  JOIN equipment_types_agg eta ON pf.instance_id = eta.facility_instance_id ");
        }

        if (hasFilters) {
            ctx.sql.append("  WHERE 1=1 ");

            if (!keywords.isEmpty()) {
                ctx.sql.append(" AND ( ");
                for (int i = 0; i < keywords.size(); i++) {
                    if (i > 0) ctx.sql.append(" OR ");
                    ctx.sql.append("pf.keywords ILIKE ").append(nextParam(ctx, "%" + keywords.get(i) + "%"));
                }
                ctx.sql.append(" ) ");
            }

            if (freeTextQuery != null && !freeTextQuery.trim().isEmpty()) {
                String[] tokens = freeTextQuery.split("[\\s,;]+");
                for (String token : tokens) {
                    if (!token.trim().isEmpty()) {
                        String tokenParam = nextParam(ctx, "%" + token.trim() + "%");
                        ctx.sql.append(" AND (pf.title ILIKE ").append(tokenParam)
                                .append(" OR pf.description ILIKE ").append(tokenParam)
                                .append(" OR pf.keywords ILIKE ").append(tokenParam).append(") ");
                    }
                }
            }

            if (!equipmentTypes.isEmpty()) {
                String etParams = nextListParam(ctx, equipmentTypes);
                ctx.sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements(eta.equipment_types) et ");
                ctx.sql.append("   WHERE et->>'name' IN ").append(etParams)
                        .append(" OR et->>'instance_id' IN ").append(etParams).append(") ");
            }
        }

        ctx.sql.append(") ");

        // Main SELECT
        ctx.sql.append("SELECT ");
        ctx.sql.append("  pf.instance_id, pf.meta_id, pf.uid, pf.title, pf.description, pf.type, pf.keywords, ");
        ctx.sql.append("  pf.versioning_status, pf.change_timestamp, pf.editor_id, ");
        ctx.sql.append("  COALESCE(CAST(fca.categories AS text), '[]') AS categories, ");
        ctx.sql.append("  COALESCE(fsa.locations, '') AS spatial_locations, ");
        ctx.sql.append("  COALESCE(CAST(foa.owners AS text), '[]') AS owners, ");
        ctx.sql.append("  ftn.type_instance_id, ftn.type_name, ");
        ctx.sql.append("  COALESCE(CAST(eta.equipment_types AS text), '[]') AS equipment_types ");
        ctx.sql.append("FROM published_facilities pf ");
        ctx.sql.append("JOIN filtered_facilities ff ON pf.instance_id = ff.instance_id ");
        ctx.sql.append("LEFT JOIN facility_categories_agg fca ON pf.instance_id = fca.facility_instance_id ");
        ctx.sql.append("LEFT JOIN facility_spatial_agg fsa ON pf.instance_id = fsa.facility_instance_id ");
        ctx.sql.append("LEFT JOIN facility_owners_agg foa ON pf.instance_id = foa.facility_instance_id ");
        ctx.sql.append("LEFT JOIN facility_type_names ftn ON pf.instance_id = ftn.facility_instance_id ");
        ctx.sql.append("LEFT JOIN equipment_types_agg eta ON pf.instance_id = eta.facility_instance_id ");
        ctx.sql.append("ORDER BY pf.instance_id ");

        return ctx;
    }

    /**
     * Fetches distributions that have facility-related categories.
     * A category is of FACILITY type when its scheme has a topConcept with UID 'category:facets/facility-theme'.
     * 
     * This method uses pure SQL to fetch all required data including format information,
     * avoiding JPA calls that would cause performance issues.
     */
    private static Set<DiscoveryItem> fetchFacilityDistributions(EntityManager em, Map<String, Object> parameters,
                                                                  User user, List<String> statuses,
                                                                  Geometry inputGeometry, WKTReader wktReader) {
        Set<DiscoveryItem> items = new HashSet<>();

        StringBuilder sql = new StringBuilder(4096);
        Map<Integer, Object> params = new HashMap<>();
        int paramIndex = 1;

        // CTE 1: Base facility distributions
        sql.append("WITH facility_distributions AS ( ");
        sql.append("  SELECT DISTINCT d.instance_id, d.meta_id, d.uid, d.format AS original_format, ");
        sql.append("         v.status AS versioning_status, v.change_timestamp, v.editor_id, c.uid AS category_uid ");
        sql.append("  FROM metadata_catalogue.distribution d ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON d.version_id = v.version_id ");
        sql.append("  JOIN metadata_catalogue.distribution_dataproduct ddp ON d.instance_id = ddp.distribution_instance_id ");
        sql.append("  JOIN metadata_catalogue.dataproduct dp ON ddp.dataproduct_instance_id = dp.instance_id ");
        sql.append("  JOIN metadata_catalogue.versioningstatus vdp ON dp.version_id = vdp.version_id ");
        sql.append("  JOIN metadata_catalogue.dataproduct_category dpc ON dp.instance_id = dpc.dataproduct_instance_id ");
        sql.append("  JOIN metadata_catalogue.category c ON dpc.category_instance_id = c.instance_id ");
        sql.append("  JOIN metadata_catalogue.category_scheme cs ON c.in_scheme = cs.instance_id ");
        sql.append("  JOIN metadata_catalogue.category_hastopconcept chtc ON cs.instance_id = chtc.category_scheme_instance_id ");
        sql.append("  JOIN metadata_catalogue.category tc ON chtc.category_instance_id = tc.instance_id ");
        sql.append("  WHERE tc.uid = 'category:facets/facility-theme' ");
        sql.append("    AND (");
        paramIndex = buildVersioningStatusFilterSimple(sql, params, paramIndex, user, parameters, statuses, "v");
        sql.append(") AND (");
        paramIndex = buildVersioningStatusFilterSimple(sql, params, paramIndex, user, parameters, statuses, "vdp");
        sql.append(")");
        sql.append("), ");

        // CTE 2: Distribution titles
        sql.append("dist_titles AS ( ");
        sql.append("  SELECT dt.distribution_instance_id, STRING_AGG(dt.title, ';' ORDER BY dt.lang) AS title ");
        sql.append("  FROM metadata_catalogue.distribution_title dt ");
        sql.append("  WHERE dt.distribution_instance_id IN (SELECT instance_id FROM facility_distributions) ");
        sql.append("  GROUP BY dt.distribution_instance_id ");
        sql.append("), ");

        // CTE 3: Distribution descriptions
        sql.append("dist_descriptions AS ( ");
        sql.append("  SELECT dd.distribution_instance_id, STRING_AGG(dd.description, ';' ORDER BY dd.lang) AS description ");
        sql.append("  FROM metadata_catalogue.distribution_description dd ");
        sql.append("  WHERE dd.distribution_instance_id IN (SELECT instance_id FROM facility_distributions) ");
        sql.append("  GROUP BY dd.distribution_instance_id ");
        sql.append("), ");

        // CTE 4: Download URLs
        sql.append("dist_download_urls AS ( ");
        sql.append("  SELECT de.distribution_instance_id, ARRAY_AGG(e.value) AS download_urls ");
        sql.append("  FROM metadata_catalogue.distribution_element de ");
        sql.append("  JOIN metadata_catalogue.element e ON de.element_instance_id = e.instance_id ");
        sql.append("  WHERE de.distribution_instance_id IN (SELECT instance_id FROM facility_distributions) ");
        sql.append("    AND e.type = 'DOWNLOADURL' ");
        sql.append("  GROUP BY de.distribution_instance_id ");
        sql.append("), ");

        // CTE 5: Operation info
        sql.append("operation_info AS ( ");
        sql.append("  SELECT od.distribution_instance_id, op.instance_id AS operation_id, op.template ");
        sql.append("  FROM metadata_catalogue.operation_distribution od ");
        sql.append("  JOIN metadata_catalogue.operation op ON od.operation_instance_id = op.instance_id ");
        sql.append("  WHERE od.distribution_instance_id IN (SELECT instance_id FROM facility_distributions) ");
        sql.append("), ");

        // CTE 6: Operation returns
        sql.append("operation_returns AS ( ");
        sql.append("  SELECT oi.distribution_instance_id, ARRAY_AGG(DISTINCT e.value) AS returns ");
        sql.append("  FROM operation_info oi ");
        sql.append("  JOIN metadata_catalogue.operation_element oe ON oi.operation_id = oe.operation_instance_id ");
        sql.append("  JOIN metadata_catalogue.element e ON oe.element_instance_id = e.instance_id ");
        sql.append("  WHERE e.type = 'RETURNS' ");
        sql.append("  GROUP BY oi.distribution_instance_id ");
        sql.append("), ");

        // CTE 7: Operation services (WMS, WFS, WMTS detection)
        sql.append("operation_services AS ( ");
        sql.append("  SELECT od.distribution_instance_id, STRING_AGG(DISTINCT UPPER(COALESCE(e.value, m.defaultvalue)), ',') AS service_values ");
        sql.append("  FROM metadata_catalogue.operation_distribution od ");
        sql.append("  JOIN metadata_catalogue.operation_mapping om ON od.operation_instance_id = om.operation_instance_id ");
        sql.append("  JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ");
        sql.append("  LEFT JOIN metadata_catalogue.mapping_element me ON m.instance_id = me.mapping_instance_id ");
        sql.append("  LEFT JOIN metadata_catalogue.element e ON me.element_instance_id = e.instance_id AND e.type = 'PARAMVALUE' ");
        sql.append("  WHERE m.variable ILIKE 'service' ");
        sql.append("    AND od.distribution_instance_id IN (SELECT instance_id FROM facility_distributions) ");
        sql.append("  GROUP BY od.distribution_instance_id ");
        sql.append("), ");

        // CTE 8: Encoding formats
        sql.append("encoding_formats AS ( ");
        sql.append("  SELECT oi.distribution_instance_id, oi.template, m.variable, m.defaultvalue, ");
        sql.append("         ARRAY_AGG(DISTINCT e.value) FILTER (WHERE e.value IS NOT NULL) AS param_values ");
        sql.append("  FROM operation_info oi ");
        sql.append("  JOIN metadata_catalogue.operation_mapping om ON oi.operation_id = om.operation_instance_id ");
        sql.append("  JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ");
        sql.append("  LEFT JOIN metadata_catalogue.mapping_element me ON m.instance_id = me.mapping_instance_id ");
        sql.append("  LEFT JOIN metadata_catalogue.element e ON me.element_instance_id = e.instance_id AND e.type = 'PARAMVALUE' ");
        sql.append("  WHERE m.property LIKE '%encodingFormat%' ");
        sql.append("  GROUP BY oi.distribution_instance_id, oi.template, m.variable, m.defaultvalue ");
        sql.append("), ");

        // CTE 9: Available formats aggregation
        sql.append("available_formats_agg AS ( ");
        sql.append("  SELECT distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ");
        sql.append("    'format', pv.format_value, 'template', template, 'variable', variable, 'default_value', defaultvalue ");
        sql.append("  )) AS available_formats_data ");
        sql.append("  FROM encoding_formats ef, LATERAL UNNEST(ef.param_values) AS pv(format_value) ");
        sql.append("  WHERE ef.param_values IS NOT NULL ");
        sql.append("  GROUP BY distribution_instance_id ");
        sql.append("), ");

        // CTE 10: Has Access Service - check if distribution has a linked webservice
        sql.append("has_access_service AS ( ");
        sql.append("  SELECT wd.distribution_instance_id, TRUE AS has_ws ");
        sql.append("  FROM metadata_catalogue.webservice_distribution wd ");
        sql.append("  WHERE wd.distribution_instance_id IN (SELECT instance_id FROM facility_distributions) ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  fd.instance_id, fd.meta_id, fd.uid, fd.original_format, ");
        sql.append("  fd.versioning_status, fd.change_timestamp, fd.editor_id, fd.category_uid, ");
        sql.append("  COALESCE(dt.title, '') AS title, ");
        sql.append("  COALESCE(dd.description, '') AS description, ");
        sql.append("  COALESCE(CAST(TO_JSON(ddu.download_urls) AS text), '[]') AS download_urls, ");
        sql.append("  COALESCE(CAST(TO_JSON(oret.returns) AS text), '[]') AS operation_returns, ");
        sql.append("  COALESCE(CAST(afa.available_formats_data AS text), '[]') AS available_formats_raw, ");
        sql.append("  COALESCE(os.service_values, '') AS service_values, ");
        sql.append("  COALESCE(has.has_ws, FALSE) AS has_access_service ");
        sql.append("FROM facility_distributions fd ");
        sql.append("LEFT JOIN dist_titles dt ON fd.instance_id = dt.distribution_instance_id ");
        sql.append("LEFT JOIN dist_descriptions dd ON fd.instance_id = dd.distribution_instance_id ");
        sql.append("LEFT JOIN dist_download_urls ddu ON fd.instance_id = ddu.distribution_instance_id ");
        sql.append("LEFT JOIN operation_returns oret ON fd.instance_id = oret.distribution_instance_id ");
        sql.append("LEFT JOIN available_formats_agg afa ON fd.instance_id = afa.distribution_instance_id ");
        sql.append("LEFT JOIN operation_services os ON fd.instance_id = os.distribution_instance_id ");
        sql.append("LEFT JOIN has_access_service has ON fd.instance_id = has.distribution_instance_id ");

        LOGGER.debug("Facility distributions SQL: {}", sql.toString());

        Query query = em.createNativeQuery(sql.toString());
        for (Map.Entry<Integer, Object> entry : params.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        LOGGER.debug("Facility distributions query returned {} rows", results.size());

        for (Object[] row : results) {
            int i = 0;
            String instanceId = (String) row[i++];
            String metaId = (String) row[i++];
            String uid = (String) row[i++];
            String originalFormat = (String) row[i++];
            String versioningStatus = (String) row[i++];
            Timestamp changeTimestamp = (Timestamp) row[i++];
            String editorId = (String) row[i++];
            String categoryUid = (String) row[i++];
            String title = (String) row[i++];
            String description = (String) row[i++];
            String downloadUrlsJson = (String) row[i++];
            String operationReturnsJson = (String) row[i++];
            String availableFormatsJson = (String) row[i++];
            String serviceValues = (String) row[i++];
            Boolean hasAccessService = (Boolean) row[i++];

            // Build available formats from SQL data using shared builder
            String[] downloadUrls = parseJsonArray(downloadUrlsJson);
            String[] operationReturns = parseJsonArray(operationReturnsJson);
            List<AvailableFormat> availableFormats = AvailableFormatsBuilder.buildFromSearchData(
                    instanceId, downloadUrls, originalFormat, operationReturns, availableFormatsJson, serviceValues,
                    hasAccessService != null && hasAccessService);

            DiscoveryItemBuilder item = new DiscoveryItemBuilder(
                    instanceId,
                    EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId,
                    EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId + "?extended=true")
                    .uid(uid)
                    .metaId(metaId)
                    .title(title)
                    .description(description)
                    .availableFormats(availableFormats)
                    .categories(Arrays.asList(categoryUid));

            // Add versioning metadata for backoffice users
            if (user != null && parameters.containsKey("versioningStatus")) {
                item.versioningStatus(versioningStatus)
                    .editorId(editorId);
                if (changeTimestamp != null) {
                    item.changeDate(changeTimestamp.toLocalDateTime());
                }
                if ("ingestor".equals(editorId)) {
                    item.editorFullName("Ingestor");
                } else {
                    User editor = DatabaseConnections.retrieveUserMap().get(editorId);
                    if (editor != null) {
                        item.editorFullName(editor.getFirstName() + " " + editor.getLastName());
                    }
                }
            }

            items.add(item.build());
        }

        return items;
    }

    /**
     * Parses a JSON array string to a String array.
     */
    private static String[] parseJsonArray(String json) {
        if (isEmptyJson(json)) {
            return null;
        }
        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(json);
            if (arrayNode.isArray()) {
                String[] result = new String[arrayNode.size()];
                for (int i = 0; i < arrayNode.size(); i++) {
                    result[i] = arrayNode.get(i).asText();
                }
                return result;
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse JSON array: {}", e.getMessage());
        }
        return null;
    }

    private static DiscoveryItem mapRowToDiscoveryItem(Object[] row, Map<String, Object> parameters, User user,
                                                       FilterData filterData, Geometry inputGeometry, WKTReader wktReader) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String title = (String) row[i++];
        String description = (String) row[i++];
        String type = (String) row[i++];
        String keywords = (String) row[i++];
        String versioningStatus = (String) row[i++];
        Timestamp changeTimestamp = (Timestamp) row[i++];
        String editorId = (String) row[i++];
        String categoriesJson = (String) row[i++];
        String spatialLocations = (String) row[i++];
        String ownersJson = (String) row[i++];
        String typeInstanceId = (String) row[i++];
        String typeName = (String) row[i++];
        String equipmentTypesJson = (String) row[i++];

        // Apply spatial filter if needed
        if (inputGeometry != null && !checkSpatialIntersection(spatialLocations, inputGeometry, wktReader)) {
            return null;
        }

        // Collect keywords for filters
        if (keywords != null && !keywords.isEmpty()) {
            Arrays.stream(keywords.split(","))
                    .map(String::trim)
                    .filter(k -> !k.isEmpty())
                    .forEach(filterData.keywords::add);
        }

        // Collect facility type for filters
        if (typeInstanceId != null && typeName != null) {
            filterData.facilityTypes.add(new CategoryInfo(typeInstanceId, typeName));
        }

        // Collect equipment types for filters
        if (!isEmptyJson(equipmentTypesJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(equipmentTypesJson);
                for (JsonNode node : arrayNode) {
                    String etId = getTextOrNull(node, "instance_id");
                    String etName = getTextOrNull(node, "name");
                    if (etId != null && etName != null) {
                        filterData.equipmentTypes.add(new CategoryInfo(etId, etName));
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse equipment types: {}", e.getMessage());
            }
        }

        // Parse categories
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
                LOGGER.warn("Failed to parse categories: {}", e.getMessage());
            }
        }

        // Parse owners
        Set<String> facilityProviders = new HashSet<>();
        if (!isEmptyJson(ownersJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(ownersJson);
                for (JsonNode node : arrayNode) {
                    facilityProviders.add(getTextOrNull(node, "instance_id"));
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse owners: {}", e.getMessage());
            }
        }

        // Build available format
        List<AvailableFormat> formats = List.of(new AvailableFormat.AvailableFormatBuilder()
                .originalFormat("application/epos.geo+json")
                .format("application/epos.geo+json")
                .href(EnvironmentVariables.API_HOST + API_PATH_EXECUTE_EQUIPMENTS + "all" + API_FORMAT
                        + "application/epos.geo+json" + "&facilityid=" + instanceId)
                .label("GEOJSON")
                .type(AvailableFormatType.CONVERTED)
                .build());

        DiscoveryItemBuilder builder = new DiscoveryItemBuilder(instanceId,
                EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId,
                null)
                .uid(uid)
                .title(title)
                .description(description)
                .sha256id(DigestUtils.sha256Hex(uid))
                .availableFormats(formats)
                .facilityProvider(facilityProviders)
                .categories(categoryList.isEmpty() ? null : categoryList);

        if (user != null && parameters.containsKey("versioningStatus")) {
            builder.versioningStatus(versioningStatus)
                    .editorId(editorId);
            if (changeTimestamp != null) {
                builder.changeDate(changeTimestamp.toLocalDateTime());
            }
        }

        return builder.build();
    }

    private static boolean checkSpatialIntersection(String spatialLocationsStr, Geometry inputGeometry, WKTReader wktReader) {
        if (spatialLocationsStr == null || spatialLocationsStr.isEmpty()) {
            return true; // No spatial data, include by default
        }

        String[] locations = spatialLocationsStr.split(SPATIAL_SEPARATOR_TRIMMED);
        for (String wkt : locations) {
            if (wkt == null) continue;
            String trimmed = wkt.trim();
            if (trimmed.isEmpty()) continue;

            try {
                Geometry dsGeometry = wktReader.read(trimmed);
                if (inputGeometry.intersects(dsGeometry)) {
                    return true;
                }
            } catch (Exception e) {
                // Invalid WKT - skip
            }
        }
        return false;
    }

    private static SearchResponse buildResponse(Set<DiscoveryItem> discoveryItems, Map<String, Object> parameters,
                                                FilterData filterData) {
        Node results = new Node("results");

        if (parameters.containsKey("facets") && "true".equals(parameters.get("facets").toString())) {
            String facetsType = parameters.get("facetstype") != null ? parameters.get("facetstype").toString() : "";
            switch (facetsType) {
                case "categories":
                    var facets = FacetsGeneration.generateResponseUsingCategories(discoveryItems, Facets.Type.FACILITY)
                            .getFacets();
                    results.addChild(facets);
                    break;
                case "facilityproviders":
                    results.addChild(FacetsGeneration.generateResponseUsingDataproviders(discoveryItems).getFacets());
                    break;
                default:
                    Node child = new Node();
                    child.setDistributions(discoveryItems);
                    results.addChild(child);
                    break;
            }
        } else {
            Node child = new Node();
            child.setDistributions(discoveryItems);
            results.addChild(child);
        }

        // Build filters
        ArrayList<NodeFilters> filters = new ArrayList<>();

        // Keywords filter
        NodeFilters keywordsNodes = new NodeFilters("keywords");
        filterData.keywords.stream()
                .filter(Objects::nonNull)
                .sorted()
                .forEach(keyword -> {
                    NodeFilters node = new NodeFilters(keyword);
                    node.setId(Base64.getEncoder().encodeToString(keyword.getBytes()));
                    keywordsNodes.addChild(node);
                });
        filters.add(keywordsNodes);

        // Organizations filter (empty for now, matching JPA behavior)
        NodeFilters organisationsNodes = new NodeFilters("organisations");
        filters.add(organisationsNodes);

        // Facility types filter
        NodeFilters facilityTypesNodes = new NodeFilters(PARAMETER_FACILITY_TYPES);
        filterData.facilityTypes.forEach(ft ->
                facilityTypesNodes.addChild(new NodeFilters(ft.instanceId, ft.name)));
        filters.add(facilityTypesNodes);

        // Equipment types filter
        NodeFilters equipmentTypesNodes = new NodeFilters(PARAMETER_EQUIPMENT_TYPES);
        filterData.equipmentTypes.forEach(et ->
                equipmentTypesNodes.addChild(new NodeFilters(et.instanceId, et.name)));
        filters.add(equipmentTypesNodes);

        return new SearchResponse(results, filters);
    }

    private static List<String> getStatusList(Map<String, Object> parameters, User user) {
        if (user != null && parameters.containsKey("versioningStatus") &&
                parameters.get("versioningStatus") != null &&
                !parameters.get("versioningStatus").toString().isEmpty()) {
            return Arrays.asList(parameters.get("versioningStatus").toString().split(","));
        }
        return Collections.singletonList("PUBLISHED");
    }

    private static List<String> getListParam(Map<String, Object> params, String key) {
        if (!params.containsKey(key) || params.get(key) == null) {
            return Collections.emptyList();
        }
        String val = params.get(key).toString().trim();
        if (val.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(val.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static boolean hasSpatialParams(Map<String, Object> params) {
        return params.containsKey("epos:northernmostLatitude")
                && params.containsKey("epos:southernmostLatitude")
                && params.containsKey("epos:westernmostLongitude")
                && params.containsKey("epos:easternmostLongitude");
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

    /**
     * Builds the versioning status filter clause for SQL queries.
     * 
     * This method implements the access control logic:
     * - Admin users: can see all requested statuses (no editor_id restriction)
     * - Authenticated non-admin users: can see PUBLISHED + their own non-published content (filtered by editor_id)
     * - Unauthenticated users: can only see PUBLISHED
     * 
     * @param ctx the query context for parameter binding
     * @param user the current user (may be null for unauthenticated access)
     * @param parameters the request parameters
     * @param requestedStatuses the list of statuses requested
     * @param tableAlias the alias for the versioningstatus table (e.g., "v")
     */
    private static void buildVersioningStatusFilter(QueryContext ctx, User user, Map<String, Object> parameters,
                                                     List<String> requestedStatuses, String tableAlias) {
        boolean hasVersioningStatusParam = parameters.containsKey("versioningStatus");
        boolean includesPublished = requestedStatuses.contains("PUBLISHED");
        
        // Get non-PUBLISHED statuses
        List<String> nonPublishedStatuses = requestedStatuses.stream()
                .filter(s -> !"PUBLISHED".equals(s))
                .collect(Collectors.toList());

        if (user == null) {
            // Unauthenticated users: only PUBLISHED
            ctx.sql.append(tableAlias).append(".status = 'PUBLISHED'");
        } else if (user.getIsAdmin() && hasVersioningStatusParam) {
            // Admin users: can see all requested statuses without editor_id restriction
            if (requestedStatuses.isEmpty()) {
                ctx.sql.append(tableAlias).append(".status = 'PUBLISHED'");
            } else {
                ctx.sql.append(tableAlias).append(".status IN (");
                for (int i = 0; i < requestedStatuses.size(); i++) {
                    if (i > 0) ctx.sql.append(", ");
                    ctx.sql.append(nextParam(ctx, requestedStatuses.get(i)));
                }
                ctx.sql.append(")");
            }
        } else if (hasVersioningStatusParam && !nonPublishedStatuses.isEmpty()) {
            // Authenticated non-admin users: PUBLISHED (if requested) + their own non-published content
            ctx.sql.append("(");
            if (includesPublished) {
                ctx.sql.append(tableAlias).append(".status = 'PUBLISHED'");
                ctx.sql.append(" OR ");
            }
            ctx.sql.append("(").append(tableAlias).append(".status IN (");
            for (int i = 0; i < nonPublishedStatuses.size(); i++) {
                if (i > 0) ctx.sql.append(", ");
                ctx.sql.append(nextParam(ctx, nonPublishedStatuses.get(i)));
            }
            ctx.sql.append(") AND ").append(tableAlias).append(".editor_id = ")
                    .append(nextParam(ctx, user.getAuthIdentifier())).append(")");
            ctx.sql.append(")");
        } else {
            // Default: only PUBLISHED
            ctx.sql.append(tableAlias).append(".status = 'PUBLISHED'");
        }
    }

    /**
     * Builds versioning status filter using StringBuilder and params map (for methods not using QueryContext).
     */
    private static int buildVersioningStatusFilterSimple(StringBuilder sql, Map<Integer, Object> params, int paramIndex,
                                                          User user, Map<String, Object> parameters,
                                                          List<String> requestedStatuses, String tableAlias) {
        boolean hasVersioningStatusParam = parameters.containsKey("versioningStatus");
        boolean includesPublished = requestedStatuses.contains("PUBLISHED");
        
        // Get non-PUBLISHED statuses
        List<String> nonPublishedStatuses = requestedStatuses.stream()
                .filter(s -> !"PUBLISHED".equals(s))
                .collect(Collectors.toList());

        if (user == null) {
            // Unauthenticated users: only PUBLISHED
            sql.append(tableAlias).append(".status = 'PUBLISHED'");
        } else if (user.getIsAdmin() && hasVersioningStatusParam) {
            // Admin users: can see all requested statuses without editor_id restriction
            if (requestedStatuses.isEmpty()) {
                sql.append(tableAlias).append(".status = 'PUBLISHED'");
            } else {
                sql.append(tableAlias).append(".status IN (");
                for (int i = 0; i < requestedStatuses.size(); i++) {
                    if (i > 0) sql.append(", ");
                    sql.append("?").append(paramIndex);
                    params.put(paramIndex++, requestedStatuses.get(i));
                }
                sql.append(")");
            }
        } else if (hasVersioningStatusParam && !nonPublishedStatuses.isEmpty()) {
            // Authenticated non-admin users: PUBLISHED (if requested) + their own non-published content
            sql.append("(");
            if (includesPublished) {
                sql.append(tableAlias).append(".status = 'PUBLISHED'");
                sql.append(" OR ");
            }
            sql.append("(").append(tableAlias).append(".status IN (");
            for (int i = 0; i < nonPublishedStatuses.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("?").append(paramIndex);
                params.put(paramIndex++, nonPublishedStatuses.get(i));
            }
            sql.append(") AND ").append(tableAlias).append(".editor_id = ?").append(paramIndex);
            params.put(paramIndex++, user.getAuthIdentifier());
            sql.append(")");
            sql.append(")");
        } else {
            // Default: only PUBLISHED
            sql.append(tableAlias).append(".status = 'PUBLISHED'");
        }
        return paramIndex;
    }
}
