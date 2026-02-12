package org.epos.api.core.distributions;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import model.StatusType;
import org.apache.commons.codec.digest.DigestUtils;
import org.epos.api.facets.Facets;
import org.epos.eposdatamodel.User;
import org.epos.api.beans.*;
import org.epos.api.beans.DiscoveryItem.DiscoveryItemBuilder;
import org.epos.api.core.AvailableFormatsBuilder;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.DataServiceProviderGenerationSQL;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.ZabbixExecutor;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.Node;
import org.epos.eposdatamodel.Organization;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.epos.api.utility.BBoxToPolygon;
import org.epos.api.routines.DatabaseConnections;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DistributionSearchGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributionSearchGenerationSQL.class);

    // Thread-safe singleton ObjectMapper - reuse is critical for performance
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Pre-computed API path constants to avoid repeated string concatenation
    private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/resources/details/";

    private static final String PARAMETER__SCIENCE_DOMAIN = "sciencedomains";
    private static final String PARAMETER__SERVICE_TYPE = "servicetypes";

    // Spatial location separator - trimmed version cached for split operations
    private static final String SPATIAL_SEPARATOR = " #EPOS# ";
    private static final String SPATIAL_SEPARATOR_TRIMMED = "#EPOS#";

    // Compiled patterns for hot-path regex operations
    private static final Pattern WHITESPACE_SPLIT_PATTERN = Pattern.compile("[\\s,;]+");
    private static final Pattern KEYWORD_SPLIT_PATTERN = Pattern.compile("[,\t]+");

    // Initial capacity estimates for collections (reduces resizing)
    private static final int EXPECTED_RESULT_COUNT = 256;
    private static final int SQL_BUILDER_INITIAL_CAPACITY = 8192;

    /**
     * Encapsulates the dynamic SQL query context including the statement builder
     * and positional parameter bindings.
     */
    private static final class QueryContext {
        final StringBuilder sql;
        final Map<Integer, Object> params;
        int paramIndex;

        QueryContext() {
            this.sql = new StringBuilder(SQL_BUILDER_INITIAL_CAPACITY);
            this.params = new HashMap<>(32);
            this.paramIndex = 1;
        }
    }

    /**
     * Aggregates filter metadata collected during result processing for faceted search.
     * Uses concurrent sets to support potential parallel processing.
     */
    private static final class FilterData {
        final Set<String> keywords = ConcurrentHashMap.newKeySet();
        final Set<OrganizationInfo> organizations = ConcurrentHashMap.newKeySet();
        final Set<CategoryInfo> scienceDomains = ConcurrentHashMap.newKeySet();
        final Set<CategoryInfo> serviceTypes = ConcurrentHashMap.newKeySet();
    }

    /**
     * Immutable organization metadata record for filter aggregation.
     */
    private static final class OrganizationInfo {
        final String instanceId;
        final String legalName;
        final String acronym;
        final String url;
        final String logo;

        OrganizationInfo(String instanceId, String legalName, String acronym, String url, String logo) {
            this.instanceId = instanceId;
            this.legalName = legalName;
            this.acronym = acronym;
            this.url = url;
            this.logo = logo;
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

    /**
     * Immutable category metadata record for filter aggregation.
     */
    private static final class CategoryInfo {
        final String instanceId;
        final String uid;
        final String name;

        CategoryInfo(String instanceId, String uid, String name) {
            this.instanceId = instanceId;
            this.uid = uid;
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

    /**
     * Executes the distribution search with the provided parameters and returns a complete
     * response including results and aggregated filter facets.
     *
     * @param parameters the search parameters including filters, pagination, and facet options
     * @param user       the authenticated user context, or null for anonymous access
     * @return the search response containing discovery items and filter metadata
     * @throws RuntimeException if the search operation fails
     */
    public static SearchResponse generate(Map<String, Object> parameters, User user) {
        final long startTime = System.nanoTime();
        LOGGER.info("Initiating distribution search with parameters: {}", parameters);

        try {
            FilterData filterData = new FilterData();
            List<DiscoveryItem> discoveryItems = executeWithEntityManager(parameters, user, filterData);

            LOGGER.debug("Query execution completed in {} ms, retrieved {} results",
                    (System.nanoTime() - startTime) / 1_000_000, discoveryItems.size());

            SearchResponse response = buildResponse(discoveryItems, parameters, filterData);

            LOGGER.info("Search completed in {} ms with {} items",
                    (System.nanoTime() - startTime) / 1_000_000, discoveryItems.size());

            return response;

        } catch (Exception e) {
            LOGGER.error("Distribution search failed", e);
            throw new RuntimeException("Search operation failed", e);
        }
    }

    /**
     * Executes the SQL query within a managed EntityManager context and maps results
     * to DiscoveryItem objects.
     */
    private static List<DiscoveryItem> executeWithEntityManager(
            Map<String, Object> parameters, User user, FilterData filterData) {

        EntityManager em = null;
        List<DiscoveryItem> results = new ArrayList<>(EXPECTED_RESULT_COUNT);

        try {
            em = EntityManagerService.getInstance().createEntityManager();

            QueryContext ctx = buildDynamicSQL(parameters, user);
            Query query = em.createNativeQuery(ctx.sql.toString());

            // Bind parameters using positional indices
            for (Map.Entry<Integer, Object> entry : ctx.params.entrySet()) {
                query.setParameter(entry.getKey(), entry.getValue());
            }

            final long queryStart = System.nanoTime();
            @SuppressWarnings("unchecked")
            List<Object[]> resultList = query.getResultList();
            LOGGER.debug("Native SQL executed in {} ms, fetched {} rows",
                    (System.nanoTime() - queryStart) / 1_000_000, resultList.size());

            // Prepare spatial filtering context if bounding box parameters are provided
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

            // Map each result row, applying spatial filter if configured
            final long mappingStart = System.nanoTime();
            for (Object[] row : resultList) {
                DiscoveryItem item = mapRowToDiscoveryItem(row, parameters, user, filterData, inputGeometry, wktReader);
                if (item != null) {
                    results.add(item);
                }
            }
            LOGGER.debug("Result mapping completed in {} ms", (System.nanoTime() - mappingStart) / 1_000_000);

        } finally {
            if (em != null) {
                em.close();
            }
        }

        return results;
    }

    /**
     * Checks whether spatial bounding box parameters are fully specified.
     */
    private static boolean hasSpatialParams(Map<String, Object> params) {
        return params.containsKey("epos:northernmostLatitude")
                && params.containsKey("epos:southernmostLatitude")
                && params.containsKey("epos:westernmostLongitude")
                && params.containsKey("epos:easternmostLongitude");
    }

    /**
     * Adds a parameter to the query context and returns its positional placeholder.
     */
    private static String nextParam(QueryContext ctx, Object value) {
        int idx = ctx.paramIndex++;
        ctx.params.put(idx, value);
        return "?" + idx;
    }

    /**
     * Builds a SQL IN clause with positional parameters for a list of values.
     */
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

    /**
     * Sanitizes keyword list by removing commas and empty entries.
     */
    private static List<String> cleanKeywords(List<String> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> cleaned = new ArrayList<>(input.size());
        for (String k : input) {
            if (k != null) {
                String trimmed = k.replace(",", "").trim();
                if (!trimmed.isEmpty()) {
                    cleaned.add(trimmed);
                }
            }
        }
        return cleaned;
    }

    /**
     * Parses a date parameter string to SQL Timestamp.
     * Handles ISO 8601 format with optional 'Z' suffix.
     */
    private static Timestamp parseDateParam(Object dateParam) {
        if (dateParam == null) {
            return null;
        }
        try {
            String dateStr = dateParam.toString().replace("Z", "");
            return Timestamp.valueOf(LocalDateTime.parse(dateStr));
        } catch (Exception e) {
            LOGGER.warn("Failed to parse date parameter '{}': {}", dateParam, e.getMessage());
            return null;
        }
    }

    /**
     * Constructs the complete dynamic SQL query with all CTEs and filters.
     *
     * <p>The query uses Common Table Expressions (CTEs) for modularity:</p>
     * <ol>
     *   <li>{@code published_distributions} - Base set of distributions matching status criteria</li>
     *   <li>{@code dataproduct_info} - DataProduct metadata with temporal/keyword filters</li>
     *   <li>{@code webservice_info} - WebService metadata with service type filters</li>
     *   <li>{@code filtered_ids} - UNION of valid distribution IDs from both paths</li>
     *   <li>Aggregation CTEs - Titles, descriptions, keywords, spatial, providers, etc.</li>
     * </ol>
     *
     * <p><strong>BUG FIX:</strong> The webservice_info CTE no longer excludes results when
     * temporal filters are active. Previously, {@code AND 1=0} was appended when startDate
     * or endDate were specified, which incorrectly prevented service provider retrieval.</p>
     */
    private static QueryContext buildDynamicSQL(Map<String, Object> parameters, User user) {
        QueryContext ctx = new QueryContext();

        // Extract and validate filter parameters
        List<String> tempStatuses = getStatusList(parameters, user);
        List<String> statuses = getStatusList(parameters, user);
        if(statuses.contains("PUBLISHED")) statuses.remove("PUBLISHED");
        String publishedOrNot = tempStatuses.contains("PUBLISHED") ? "PUBLISHED" : "";
        List<String> organizations = getListParam(parameters, "organisations");
        List<String> keywords = cleanKeywords(getListParam(parameters, "keywords"));
        List<String> scienceDomains = getListParam(parameters, PARAMETER__SCIENCE_DOMAIN);
        List<String> serviceTypes = getListParam(parameters, PARAMETER__SERVICE_TYPE);
        String freeTextQuery = (String) parameters.get("q");

        Timestamp startDate = parseDateParam(parameters.get("schema:startDate"));
        Timestamp endDate = parseDateParam(parameters.get("schema:endDate"));

        ctx.sql.append("WITH ");

        // CTE 1: Published Distributions - base set filtered by versioning status
        ctx.sql.append("published_distributions AS ( ")
                .append("SELECT d.instance_id, d.meta_id, d.uid, d.format, d.version_id, ")
                .append("v.status AS versioning_status, v.change_timestamp, v.editor_id ")
                .append("FROM metadata_catalogue.distribution d ")
                .append("JOIN metadata_catalogue.versioningstatus v ON d.version_id = v.version_id ")
                .append("WHERE v.status IN ('"+publishedOrNot+"')");

        // Non-admin users can only see their own drafts or submitted
        if (user != null && !user.getIsAdmin() && parameters.containsKey("versioningStatus")) {
            ctx.sql.append(" OR (v.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                if (i > 0) ctx.sql.append(", ");
                ctx.sql.append("'").append(statuses.get(i)).append("'");
            }
            ctx.sql.append(")  AND v.editor_id = ")
                    .append(nextParam(ctx, user.getAuthIdentifier())).append(")");
        }
        ctx.sql.append("), ");

        // CTE 2: DataProduct Info - with temporal, keyword, and category filters
        ctx.sql.append("dataproduct_info AS ( ")
                .append("SELECT ddp.distribution_instance_id, dp.instance_id AS dataproduct_id, dp.keywords ")
                .append("FROM metadata_catalogue.distribution_dataproduct ddp ")
                .append("JOIN metadata_catalogue.dataproduct dp ON ddp.dataproduct_instance_id = dp.instance_id ")
                .append("JOIN metadata_catalogue.versioningstatus v ON dp.version_id = v.version_id ");

        // Conditional JOINs based on active filters
        if (startDate != null || endDate != null) {
            ctx.sql.append("JOIN metadata_catalogue.dataproduct_temporal dpt ON dp.instance_id = dpt.dataproduct_instance_id ")
                    .append("JOIN metadata_catalogue.temporal t ON dpt.temporal_instance_id = t.instance_id ");
        }
        if (!scienceDomains.isEmpty()) {
            ctx.sql.append("JOIN metadata_catalogue.dataproduct_category dpc ON dp.instance_id = dpc.dataproduct_instance_id ")
                    .append("JOIN metadata_catalogue.category c_sd ON dpc.category_instance_id = c_sd.instance_id ");
        }
        if (!organizations.isEmpty()) {
            ctx.sql.append("LEFT JOIN metadata_catalogue.dataproduct_publisher dpp ON dp.instance_id = dpp.dataproduct_instance_id ")
                    .append("LEFT JOIN metadata_catalogue.organization o_pub ON dpp.organization_instance_id = o_pub.instance_id ");
        }

        ctx.sql.append("WHERE ddp.distribution_instance_id IN (SELECT instance_id FROM published_distributions) ")
                .append("WHERE v.status IN ('"+publishedOrNot+"')");

        // Temporal range filter (inclusive boundaries with NULL handling)
        if (startDate != null) {
            ctx.sql.append(" AND (t.endDate IS NULL OR t.endDate >= CAST(")
                    .append(nextParam(ctx, startDate)).append(" AS timestamp)) ");
        }
        if (endDate != null) {
            ctx.sql.append(" AND (t.startDate IS NULL OR t.startDate <= CAST(")
                    .append(nextParam(ctx, endDate)).append(" AS timestamp)) ");
        }

        // Science domain filter
        if (!scienceDomains.isEmpty()) {
            String sdParams = nextListParam(ctx, scienceDomains);
            ctx.sql.append(" AND (c_sd.uid IN ").append(sdParams)
                    .append(" OR c_sd.instance_id IN ").append(sdParams).append(") ");
        }

        // Explicit keyword filter (distinct from free-text 'q' parameter)
        if (!keywords.isEmpty()) {
            ctx.sql.append(" AND ( ");
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) ctx.sql.append(" OR ");
                ctx.sql.append("dp.keywords ILIKE ").append(nextParam(ctx, "%" + keywords.get(i) + "%"));
            }
            ctx.sql.append(" ) ");
        }
        ctx.sql.append("), ");

        // CTE 3: WebService Info - provider and service type data
        //
        // BUG FIX: Removed the erroneous "AND 1=0" clause that was previously appended
        // when temporal filters were active. This clause incorrectly excluded ALL webservice
        // data, preventing service_providers_agg from returning any results.
        //
        // The correct behavior is to include webservice data unconditionally here, as the
        // filtering is properly handled by the filtered_ids CTE which UNIONs valid
        // distribution IDs from both the dataproduct and webservice paths.
        ctx.sql.append("webservice_info AS ( ")
                .append("SELECT wd.distribution_instance_id, ws.instance_id AS webservice_id, ws.provider AS provider_id ")
                .append("FROM metadata_catalogue.webservice_distribution wd ")
                .append("JOIN metadata_catalogue.webservice ws ON wd.webservice_instance_id = ws.instance_id ");

        if (!serviceTypes.isEmpty()) {
            ctx.sql.append("JOIN metadata_catalogue.webservice_category wsc ON ws.instance_id = wsc.webservice_instance_id ")
                    .append("JOIN metadata_catalogue.category c_st ON wsc.category_instance_id = c_st.instance_id ");
        }
        if (!organizations.isEmpty()) {
            ctx.sql.append("LEFT JOIN metadata_catalogue.organization o_prov ON ws.provider = o_prov.instance_id ");
        }

        ctx.sql.append("WHERE wd.distribution_instance_id IN (SELECT instance_id FROM published_distributions) ");

        if (!serviceTypes.isEmpty()) {
            String stParams = nextListParam(ctx, serviceTypes);
            ctx.sql.append(" AND (c_st.uid IN ").append(stParams)
                    .append(" OR c_st.instance_id IN ").append(stParams).append(") ");
        }
        // NOTE: The "AND 1=0" clause has been intentionally removed to fix service provider retrieval
        ctx.sql.append("), ");

        // CTE 4: Operation Services - aggregates service type values (WMS, WFS, etc.)
        ctx.sql.append("operation_services AS ( ")
                .append("SELECT od.distribution_instance_id, STRING_AGG(DISTINCT UPPER(COALESCE(e.value, m.defaultvalue)), ',') AS service_values ")
                .append("FROM metadata_catalogue.operation_distribution od ")
                .append("JOIN metadata_catalogue.operation_mapping om ON od.operation_instance_id = om.operation_instance_id ")
                .append("JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ")
                .append("LEFT JOIN metadata_catalogue.mapping_element me ON m.instance_id = me.mapping_instance_id ")
                .append("LEFT JOIN metadata_catalogue.element e ON me.element_instance_id = e.instance_id AND e.type = 'PARAMVALUE' ")
                .append("WHERE m.variable ILIKE 'service' ")
                .append("GROUP BY od.distribution_instance_id ")
                .append("), ");

        // CTE 5: Filtered IDs - final set of distribution IDs matching all criteria
        boolean hasStrictDpFilters = startDate != null || endDate != null || !keywords.isEmpty() || !scienceDomains.isEmpty();
        boolean hasWsFilter = !serviceTypes.isEmpty();
        boolean hasOrgFilter = !organizations.isEmpty();

        ctx.sql.append("filtered_ids AS ( ");

        if (!hasStrictDpFilters && !hasWsFilter && !hasOrgFilter) {
            // No filters - return all published distributions
            ctx.sql.append("SELECT instance_id FROM published_distributions ");
        } else {
            // Build UNION of DataProduct-filtered and WebService-filtered distributions
            ctx.sql.append("SELECT distribution_instance_id AS instance_id FROM dataproduct_info ");

            String orgParams = "";
            if (hasOrgFilter) {
                orgParams = nextListParam(ctx, organizations);
                ctx.sql.append("JOIN metadata_catalogue.dataproduct_publisher dpp2 ON dataproduct_info.dataproduct_id = dpp2.dataproduct_instance_id ")
                        .append("JOIN metadata_catalogue.organization o2 ON dpp2.organization_instance_id = o2.instance_id ")
                        .append("WHERE (o2.instance_id IN ").append(orgParams)
                        .append(" OR o2.legalname IN ").append(orgParams).append(") ");
            }

            // BUG FIX: Always include webservice path in UNION when not using strict DataProduct filters
            // This ensures service providers are retrieved for all valid distributions
            if (!hasStrictDpFilters || hasWsFilter) {
                ctx.sql.append(" UNION ");
                ctx.sql.append("SELECT distribution_instance_id AS instance_id FROM webservice_info ");
                if (hasOrgFilter) {
                    ctx.sql.append("JOIN metadata_catalogue.organization o3 ON webservice_info.provider_id = o3.instance_id ")
                            .append("WHERE (o3.instance_id IN ").append(orgParams)
                            .append(" OR o3.legalname IN ").append(orgParams).append(") ");
                }
            }
        }
        ctx.sql.append("), ");

        // Aggregation CTEs for efficient single-pass data retrieval
        buildAggregationCTEs(ctx);

        // Main SELECT with all JOINs
        buildMainSelect(ctx, freeTextQuery);

        return ctx;
    }

    /**
     * Builds the aggregation CTEs for titles, descriptions, keywords, spatial data,
     * providers, categories, and format information.
     */
    private static void buildAggregationCTEs(QueryContext ctx) {
        // Distribution Titles
        ctx.sql.append("dist_titles AS ( ")
                .append("SELECT dt.distribution_instance_id, STRING_AGG(dt.title, ';' ORDER BY dt.lang) AS title ")
                .append("FROM metadata_catalogue.distribution_title dt ")
                .append("WHERE dt.distribution_instance_id IN (SELECT instance_id FROM filtered_ids) ")
                .append("GROUP BY dt.distribution_instance_id ), ");

        // Distribution Descriptions
        ctx.sql.append("dist_descriptions AS ( ")
                .append("SELECT dd.distribution_instance_id, STRING_AGG(dd.description, ';' ORDER BY dd.lang) AS description ")
                .append("FROM metadata_catalogue.distribution_description dd ")
                .append("WHERE dd.distribution_instance_id IN (SELECT instance_id FROM filtered_ids) ")
                .append("GROUP BY dd.distribution_instance_id ), ");

        // Keywords (normalized and deduplicated)
        ctx.sql.append("dist_keywords AS ( ")
                .append("SELECT di.distribution_instance_id, ARRAY_AGG(DISTINCT LOWER(TRIM(kw))) FILTER (WHERE TRIM(kw) != '') AS keywords ")
                .append("FROM dataproduct_info di, LATERAL UNNEST(regexp_split_to_array(di.keywords, '[,\\t]+')) AS kw ")
                .append("WHERE di.keywords IS NOT NULL AND di.keywords != '' ")
                .append("GROUP BY di.distribution_instance_id ), ");

        // Download URLs
        ctx.sql.append("dist_download_urls AS ( ")
                .append("SELECT de.distribution_instance_id, ARRAY_AGG(e.value) AS download_urls ")
                .append("FROM metadata_catalogue.distribution_element de ")
                .append("JOIN metadata_catalogue.element e ON de.element_instance_id = e.instance_id ")
                .append("WHERE de.distribution_instance_id IN (SELECT instance_id FROM filtered_ids) AND e.type = 'DOWNLOADURL' ")
                .append("GROUP BY de.distribution_instance_id ), ");

        // DataProduct Spatial
        ctx.sql.append("dataproduct_spatial_agg AS ( ")
                .append("SELECT di.distribution_instance_id, STRING_AGG(s.location, '").append(SPATIAL_SEPARATOR).append("') AS locations ")
                .append("FROM dataproduct_info di ")
                .append("JOIN metadata_catalogue.dataproduct_spatial dps ON di.dataproduct_id = dps.dataproduct_instance_id ")
                .append("JOIN metadata_catalogue.spatial s ON dps.spatial_instance_id = s.instance_id ")
                .append("GROUP BY di.distribution_instance_id ), ");

        // WebService Spatial
        ctx.sql.append("webservice_spatial_agg AS ( ")
                .append("SELECT wi.distribution_instance_id, STRING_AGG(s.location, '").append(SPATIAL_SEPARATOR).append("') AS locations ")
                .append("FROM webservice_info wi ")
                .append("JOIN metadata_catalogue.webservice_spatial wss ON wi.webservice_id = wss.webservice_instance_id ")
                .append("JOIN metadata_catalogue.spatial s ON wss.spatial_instance_id = s.instance_id ")
                .append("GROUP BY wi.distribution_instance_id ), ");

        // Data Providers (from DataProduct publishers)
        ctx.sql.append("dataproduct_publishers_agg AS ( ")
                .append("SELECT di.distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ")
                .append("'instance_id', o.instance_id, 'legal_name', o.legalname, 'acronym', o.acronym, 'url', o.url, 'logo', o.logo ")
                .append(")) AS data_providers ")
                .append("FROM dataproduct_info di ")
                .append("JOIN metadata_catalogue.dataproduct_publisher dpp ON di.dataproduct_id = dpp.dataproduct_instance_id ")
                .append("JOIN metadata_catalogue.organization o ON dpp.organization_instance_id = o.instance_id ")
                .append("GROUP BY di.distribution_instance_id ), ");

        // Service Providers (from WebService providers)
        // BUG FIX: This CTE now correctly returns data when temporal filters are active,
        // since webservice_info is no longer artificially emptied.
        ctx.sql.append("service_providers_agg AS ( ")
                .append("SELECT wi.distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ")
                .append("'instance_id', o.instance_id, 'legal_name', o.legalname, 'acronym', o.acronym, 'url', o.url, 'logo', o.logo ")
                .append(")) AS service_providers ")
                .append("FROM webservice_info wi ")
                .append("JOIN metadata_catalogue.organization o ON wi.provider_id = o.instance_id ")
                .append("GROUP BY wi.distribution_instance_id ), ");

        // DataProduct Categories (Science Domains)
        ctx.sql.append("dataproduct_categories_agg AS ( ")
                .append("SELECT di.distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('uid', c.uid, 'name', c.name, 'instance_id', c.instance_id)) AS categories ")
                .append("FROM dataproduct_info di ")
                .append("JOIN metadata_catalogue.dataproduct_category dpc ON di.dataproduct_id = dpc.dataproduct_instance_id ")
                .append("JOIN metadata_catalogue.category c ON dpc.category_instance_id = c.instance_id ")
                .append("GROUP BY di.distribution_instance_id ), ");

        // Service Categories (Service Types)
        ctx.sql.append("service_categories_agg AS ( ")
                .append("SELECT wi.distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('uid', c.uid, 'name', c.name, 'instance_id', c.instance_id)) AS service_types ")
                .append("FROM webservice_info wi ")
                .append("JOIN metadata_catalogue.webservice_category wc ON wi.webservice_id = wc.webservice_instance_id ")
                .append("JOIN metadata_catalogue.category c ON wc.category_instance_id = c.instance_id ")
                .append("GROUP BY wi.distribution_instance_id ), ");

        // Operation Info
        ctx.sql.append("operation_info AS ( ")
                .append("SELECT od.distribution_instance_id, op.instance_id AS operation_id, op.template, op.method ")
                .append("FROM metadata_catalogue.operation_distribution od ")
                .append("JOIN metadata_catalogue.operation op ON od.operation_instance_id = op.instance_id ")
                .append("WHERE od.distribution_instance_id IN (SELECT instance_id FROM filtered_ids) ), ");

        // Operation Returns
        ctx.sql.append("operation_returns AS ( ")
                .append("SELECT oi.distribution_instance_id, ARRAY_AGG(DISTINCT e.value) AS returns ")
                .append("FROM operation_info oi ")
                .append("JOIN metadata_catalogue.operation_element oe ON oi.operation_id = oe.operation_instance_id ")
                .append("JOIN metadata_catalogue.element e ON oe.element_instance_id = e.instance_id ")
                .append("WHERE e.type = 'RETURNS' GROUP BY oi.distribution_instance_id ), ");

        // Encoding Formats
        ctx.sql.append("encoding_formats AS ( ")
                .append("SELECT oi.distribution_instance_id, oi.template, m.variable, m.defaultvalue, ")
                .append("ARRAY_AGG(DISTINCT e.value) FILTER (WHERE e.value IS NOT NULL) AS param_values ")
                .append("FROM operation_info oi ")
                .append("JOIN metadata_catalogue.operation_mapping om ON oi.operation_id = om.operation_instance_id ")
                .append("JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ")
                .append("LEFT JOIN metadata_catalogue.mapping_element me ON m.instance_id = me.mapping_instance_id ")
                .append("LEFT JOIN metadata_catalogue.element e ON me.element_instance_id = e.instance_id AND e.type = 'PARAMVALUE' ")
                .append("WHERE m.property LIKE '%encodingFormat%' ")
                .append("GROUP BY oi.distribution_instance_id, oi.template, m.variable, m.defaultvalue ), ");

        // Available Formats Aggregation
        ctx.sql.append("available_formats_agg AS ( ")
                .append("SELECT distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ")
                .append("'format', pv.format_value, 'template', template, 'variable', variable, 'default_value', defaultvalue ")
                .append(")) AS available_formats_data ")
                .append("FROM encoding_formats ef, LATERAL UNNEST(ef.param_values) AS pv(format_value) ")
                .append("WHERE ef.param_values IS NOT NULL GROUP BY distribution_instance_id ) ");
    }

    /**
     * Builds the main SELECT statement with all JOINs and optional free-text filtering.
     */
    private static void buildMainSelect(QueryContext ctx, String freeTextQuery) {
        ctx.sql.append("SELECT ")
                .append("pd.instance_id AS id, pd.uid, pd.meta_id, ")
                .append("COALESCE(dt.title, '') AS title, ")
                .append("COALESCE(dd.description, '') AS description, ")
                .append("pd.versioning_status, pd.change_timestamp, pd.editor_id, ")
                .append("COALESCE(CAST(dpu.data_providers AS text), '[]') AS data_providers, ")
                .append("COALESCE(CAST(sp.service_providers AS text), '[]') AS service_providers, ")
                .append("COALESCE(CAST(dc.categories AS text), '[]') AS categories, ")
                .append("COALESCE(CAST(sc.service_types AS text), '[]') AS service_types, ")
                .append("COALESCE(CAST(afa.available_formats_data AS text), '[]') AS available_formats_raw, ")
                .append("COALESCE(CAST(TO_JSON(ddu.download_urls) AS text), '[]') AS download_urls, ")
                .append("pd.format AS original_format, ")
                .append("COALESCE(CAST(TO_JSON(oret.returns) AS text), '[]') AS operation_returns, ")
                .append("COALESCE(CAST(TO_JSON(dk.keywords) AS text), '[]') AS keywords, ")
                .append("COALESCE(dsa.locations, '') || '").append(SPATIAL_SEPARATOR).append("' || COALESCE(wsa.locations, '') AS spatial_locations, ")
                .append("COALESCE(os.service_values, '') AS service_values ")
                .append("FROM published_distributions pd ")
                .append("JOIN filtered_ids fi ON pd.instance_id = fi.instance_id ")
                .append("LEFT JOIN dist_titles dt ON pd.instance_id = dt.distribution_instance_id ")
                .append("LEFT JOIN dist_descriptions dd ON pd.instance_id = dd.distribution_instance_id ")
                .append("LEFT JOIN dist_download_urls ddu ON pd.instance_id = ddu.distribution_instance_id ")
                .append("LEFT JOIN dataproduct_categories_agg dc ON pd.instance_id = dc.distribution_instance_id ")
                .append("LEFT JOIN dataproduct_publishers_agg dpu ON pd.instance_id = dpu.distribution_instance_id ")
                .append("LEFT JOIN service_providers_agg sp ON pd.instance_id = sp.distribution_instance_id ")
                .append("LEFT JOIN service_categories_agg sc ON pd.instance_id = sc.distribution_instance_id ")
                .append("LEFT JOIN available_formats_agg afa ON pd.instance_id = afa.distribution_instance_id ")
                .append("LEFT JOIN operation_returns oret ON pd.instance_id = oret.distribution_instance_id ")
                .append("LEFT JOIN dist_keywords dk ON pd.instance_id = dk.distribution_instance_id ")
                .append("LEFT JOIN dataproduct_spatial_agg dsa ON pd.instance_id = dsa.distribution_instance_id ")
                .append("LEFT JOIN webservice_spatial_agg wsa ON pd.instance_id = wsa.distribution_instance_id ")
                .append("LEFT JOIN operation_services os ON pd.instance_id = os.distribution_instance_id ");

        // Free-text search across title, description, and keywords
        if (freeTextQuery != null && !freeTextQuery.trim().isEmpty()) {
            String[] tokens = WHITESPACE_SPLIT_PATTERN.split(freeTextQuery);
            List<String> validTokens = new ArrayList<>(tokens.length);
            for (String token : tokens) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    validTokens.add(trimmed);
                }
            }

            if (!validTokens.isEmpty()) {
                ctx.sql.append(" WHERE ");
                for (int k = 0; k < validTokens.size(); k++) {
                    if (k > 0) ctx.sql.append(" AND ");
                    String tokenParam = nextParam(ctx, "%" + validTokens.get(k) + "%");
                    ctx.sql.append(" (dt.title ILIKE ").append(tokenParam)
                            .append(" OR dd.description ILIKE ").append(tokenParam)
                            .append(" OR ARRAY_TO_STRING(dk.keywords, ' ') ILIKE ").append(tokenParam)
                            .append(") ");
                }
            }
        }

        ctx.sql.append(" ORDER BY pd.instance_id ");
    }

    /**
     * Extracts a comma-separated list parameter from the request map.
     */
    private static List<String> getListParam(Map<String, Object> params, String key) {
        if (!params.containsKey(key) || params.get(key) == null) {
            return Collections.emptyList();
        }
        String val = params.get(key).toString().trim();
        if (val.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = val.split(",");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Determines the versioning status list based on user permissions and request parameters.
     */
    private static List<String> getStatusList(Map<String, Object> parameters, User user) {
        if (user != null && parameters.containsKey("versioningStatus")) {
            String statusParam = parameters.get("versioningStatus").toString();
            String[] parts = statusParam.split(",");
            List<String> statuses = new ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    statuses.add(trimmed);
                }
            }
            return statuses.isEmpty() ? Collections.singletonList("PUBLISHED") : statuses;
        }
        return Collections.singletonList("PUBLISHED");
    }

    /**
     * Maps a single database result row to a DiscoveryItem, applying spatial filtering
     * and collecting filter metadata.
     */
    private static DiscoveryItem mapRowToDiscoveryItem(
            Object[] row, Map<String, Object> parameters, User user, FilterData filterData,
            Geometry inputGeometry, WKTReader wktReader) {
        try {
            int i = 0;
            String instanceId = (String) row[i++];
            String uid = (String) row[i++];
            String metaId = (String) row[i++];
            String title = (String) row[i++];
            String description = (String) row[i++];
            String versioningStatus = (String) row[i++];
            Timestamp changeTimestamp = (Timestamp) row[i++];
            String editorId = (String) row[i++];

            String dataProvidersJson = (String) row[i++];
            String serviceProvidersJson = (String) row[i++];
            String categoriesJson = (String) row[i++];
            String serviceTypesJson = (String) row[i++];
            String availableFormatsJson = (String) row[i++];

            String downloadUrlsJson = (String) row[i++];
            String originalFormat = (String) row[i++];
            String operationReturnsJson = (String) row[i++];
            String keywordsJson = (String) row[i++];
            String spatialLocationsStr = (String) row[i++];
            String serviceValues = (String) row[i++];

            // Apply spatial intersection filter if bounding box was specified
            if (inputGeometry != null && !checkSpatialIntersection(spatialLocationsStr, inputGeometry, wktReader)) {
                return null;
            }

            // Parse JSON arrays
            String[] downloadUrls = parseJsonStringArray(downloadUrlsJson);
            String[] operationReturns = parseJsonStringArray(operationReturnsJson);
            String[] keywordsArray = parseJsonStringArray(keywordsJson);

            // Collect keywords for filter facets
            if (keywordsArray != null) {
                for (String kw : keywordsArray) {
                    if (kw != null && !kw.isEmpty()) {
                        filterData.keywords.add(kw.replace(",", "").trim().toLowerCase());
                    }
                }
            }

            // Parse and collect organization data
            Set<String> facetsDataProviders = parseOrganizationNamesAndCollect(dataProvidersJson, filterData.organizations);
            Set<String> facetsServiceProviders = parseOrganizationNamesAndCollect(serviceProvidersJson, filterData.organizations);
            List<String> categoryList = parseCategoryUidsAndCollect(categoriesJson, filterData.scienceDomains);
            collectServiceTypes(serviceTypesJson, filterData.serviceTypes);

            // Build available formats list using shared builder
            List<AvailableFormat> availableFormats = AvailableFormatsBuilder.buildFromSearchData(
                    instanceId, downloadUrls, originalFormat, operationReturns, availableFormatsJson, serviceValues);

            DataServiceProvider dataServiceProvider = parseFirstServiceProvider(serviceProvidersJson);

            // Construct the discovery item
            DiscoveryItemBuilder builder = new DiscoveryItemBuilder(
                    instanceId,
                    EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId,
                    EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId + "?extended=true")
                    .uid(uid)
                    .metaId(metaId)
                    .title(title)
                    .description(description)
                    .dataServiceProvider(dataServiceProvider)
                    .versioningStatus(user != null && parameters.containsKey("versioningStatus") ? versioningStatus : null)
                    .editorId(user != null && parameters.containsKey("versioningStatus") ? editorId : null)
                    .availableFormats(availableFormats)
                    .sha256id(uid != null ? DigestUtils.sha256Hex(uid) : "")
                    .dataProvider(facetsDataProviders)
                    .serviceProvider(facetsServiceProviders)
                    .categories(categoryList.isEmpty() ? null : categoryList);

            // Add versioning metadata for backoffice users
            if (user != null && parameters.containsKey("versioningStatus")) {
                builder.editorId(editorId).versioningStatus(versioningStatus);
                if (changeTimestamp != null) {
                    builder.changeDate(changeTimestamp.toLocalDateTime());
                    if ("ingestor".equals(editorId)) {
                        builder.editorFullName("Ingestor");
                    } else {
                        User editor = DatabaseConnections.retrieveUserMap().get(editorId);
                        if (editor != null) {
                            builder.editorFullName(editor.getFirstName() + " " + editor.getLastName());
                        }
                    }
                }
            }

            DiscoveryItem item = builder.build();

            // Attach monitoring status if enabled
            if ("true".equals(EnvironmentVariables.MONITORING)) {
                ZabbixExecutor zabbix = ZabbixExecutor.getInstance();
                item.setStatus(zabbix.getStatusInfoFromSha(item.getSha256id()));
                item.setStatusTimestamp(zabbix.getStatusTimestampInfoFromSha(item.getSha256id()));
                item.setStatusURL(zabbix.getStatusURLFromSha(item.getSha256id()));
            }

            return item;

        } catch (Exception e) {
            LOGGER.warn("Failed to map result row: {}", e.getMessage());
            return null;
        }
    }

    private static List<StatusType> getVersions(Map<String, Object> parameters, boolean isBackofficeUser) {
        List<StatusType> versions = new ArrayList<>();
        if (isBackofficeUser && parameters.containsKey("versioningStatus")) {
            Arrays.stream(parameters.get("versioningStatus").toString().split(","))
                    .forEach(version -> versions.add(StatusType.valueOf(version)));
        } else {
            versions.add(StatusType.PUBLISHED);
        }
        return versions;
    }

    /**
     * Checks if any spatial location in the concatenated WKT string intersects with the input geometry.
     * Uses early-exit optimization for performance.
     */
    private static boolean checkSpatialIntersection(String spatialLocationsStr, Geometry inputGeometry, WKTReader wktReader) {
        if (spatialLocationsStr == null || spatialLocationsStr.isEmpty()) {
            return false;
        }

        String[] locations = spatialLocationsStr.split(SPATIAL_SEPARATOR_TRIMMED);
        for (String wkt : locations) {
            if (wkt == null) continue;
            String trimmed = wkt.trim();
            if (trimmed.isEmpty()) continue;

            try {
                Geometry dsGeometry = wktReader.read(trimmed);
                if (inputGeometry.intersects(dsGeometry)) {
                    return true; // Early exit on first intersection
                }
            } catch (Exception e) {
                // Invalid WKT - skip this location
            }
        }
        return false;
    }

    /**
     * Parses organization JSON array and collects organization info for filter facets.
     * Returns the set of legal names for the discovery item.
     */
    private static Set<String> parseOrganizationNamesAndCollect(String json, Set<OrganizationInfo> organizations) {
        if (isEmptyJson(json)) {
            return Collections.emptySet();
        }

        Set<String> names = new HashSet<>(4);
        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(json);
            for (JsonNode node : arrayNode) {
                String instanceId = getTextOrNull(node, "instance_id");
                String legalName = getTextOrNull(node, "legal_name");
                String acronym = getTextOrNull(node, "acronym");
                String url = getTextOrNull(node, "url");
                String logo = getTextOrNull(node, "logo");

                if (legalName != null && !legalName.isEmpty()) {
                    names.add(legalName);
                    if (instanceId != null) {
                        organizations.add(new OrganizationInfo(instanceId, legalName, acronym, url, logo));
                    }
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse organization JSON: {}", e.getMessage());
        }
        return names;
    }

    /**
     * Parses category JSON array, collects science domains for filters, and returns UIDs.
     */
    private static List<String> parseCategoryUidsAndCollect(String json, Set<CategoryInfo> scienceDomains) {
        if (isEmptyJson(json)) {
            return Collections.emptyList();
        }

        List<String> uids = new ArrayList<>(4);
        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(json);
            for (JsonNode node : arrayNode) {
                String instanceId = getTextOrNull(node, "instance_id");
                String uid = getTextOrNull(node, "uid");
                String name = getTextOrNull(node, "name");

                if (uid != null && uid.contains("category:")) {
                    uids.add(uid);
                } else if (instanceId != null) {
                    scienceDomains.add(new CategoryInfo(instanceId, uid, name));
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse category JSON: {}", e.getMessage());
        }
        return uids;
    }

    /**
     * Collects service type information from JSON for filter facets.
     */
    private static void collectServiceTypes(String json, Set<CategoryInfo> serviceTypes) {
        if (isEmptyJson(json)) {
            return;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(json);
            for (JsonNode node : arrayNode) {
                String instanceId = getTextOrNull(node, "instance_id");
                String uid = getTextOrNull(node, "uid");
                String name = getTextOrNull(node, "name");
                if (instanceId != null) {
                    serviceTypes.add(new CategoryInfo(instanceId, uid, name));
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse service types JSON: {}", e.getMessage());
        }
    }

    /**
     * Parses the first service provider from JSON for the discovery item's primary provider.
     */
    private static DataServiceProvider parseFirstServiceProvider(String json) {
        if (isEmptyJson(json)) {
            return null;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(json);
            if (arrayNode.size() > 0) {
                JsonNode node = arrayNode.get(0);
                DataServiceProvider provider = new DataServiceProvider();
                provider.setInstanceid(getTextOrNull(node, "instance_id"));
                provider.setDataProviderLegalName(getTextOrNull(node, "legal_name"));
                provider.setDataProviderUrl(getTextOrNull(node, "url"));
                return provider;
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse service provider JSON: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Checks if a JSON string is empty or represents an empty/null value.
     */
    private static boolean isEmptyJson(String json) {
        return json == null || json.isEmpty() || "[]".equals(json) || "null".equals(json);
    }

    /**
     * Safely extracts a text value from a JSON node, returning null if missing or null.
     */
    private static String getTextOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText(null);
    }

    /**
     * Parses a JSON string array, returning null for empty or invalid input.
     */
    private static String[] parseJsonStringArray(String json) {
        if (isEmptyJson(json)) {
            return null;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(json);
            if (!arrayNode.isArray() || arrayNode.isEmpty()) {
                return null;
            }

            String[] result = new String[arrayNode.size()];
            for (int i = 0; i < arrayNode.size(); i++) {
                result[i] = arrayNode.get(i).asText(null);
            }
            return result;
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse JSON array: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds the search response with results and filter facets.
     */
    private static SearchResponse buildResponse(List<DiscoveryItem> items, Map<String, Object> parameters, FilterData filterData) {
        Set<DiscoveryItem> discoverySet = new HashSet<>(items);
        Node results = new Node("results");

        if ("true".equals(String.valueOf(parameters.get("facets")))) {
            String facetsType = (String) parameters.getOrDefault("facetstype", "default");
            switch (facetsType) {
                case "categories":
                    results.addChild(FacetsGeneration.generateResponseUsingCategories(discoverySet, Facets.Type.DATA).getFacets());
                    break;
                case "dataproviders":
                    results.addChild(FacetsGeneration.generateResponseUsingDataproviders(discoverySet).getFacets());
                    break;
                case "serviceproviders":
                    results.addChild(FacetsGeneration.generateResponseUsingServiceproviders(discoverySet).getFacets());
                    break;
                default:
                    Node child = new Node();
                    child.setDistributions(discoverySet);
                    results.addChild(child);
                    break;
            }
        } else {
            Node child = new Node();
            child.setDistributions(discoverySet);
            results.addChild(child);
        }

        return new SearchResponse(results, buildFilters(filterData));
    }

    /**
     * Builds the filter facets from collected metadata.
     */
    private static ArrayList<NodeFilters> buildFilters(FilterData filterData) {
        ArrayList<NodeFilters> filters = new ArrayList<>(4);

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

        // Organizations filter
        List<Organization> orgEntities = convertToOrganizationEntities(filterData.organizations);
        NodeFilters organisationsNodes = new NodeFilters("organisations");
        DataServiceProviderGenerationSQL.getProviders(orgEntities).forEach(resource -> {
            NodeFilters node = new NodeFilters(resource.getDataProviderLegalName());
            node.setId(resource.getInstanceid());
            organisationsNodes.addChild(node);

            if (resource.getRelatedDataProvider() != null) {
                resource.getRelatedDataProvider().forEach(r -> {
                    NodeFilters n = new NodeFilters(r.getDataProviderLegalName());
                    n.setId(r.getInstanceid());
                    organisationsNodes.addChild(n);
                });
            }
            if (resource.getRelatedDataServiceProvider() != null) {
                resource.getRelatedDataServiceProvider().forEach(r -> {
                    NodeFilters n = new NodeFilters(r.getDataProviderLegalName());
                    n.setId(r.getInstanceid());
                    organisationsNodes.addChild(n);
                });
            }
        });
        filters.add(organisationsNodes);

        // Science Domains filter
        NodeFilters scienceDomainsNodes = new NodeFilters(PARAMETER__SCIENCE_DOMAIN);
        filterData.scienceDomains.forEach(r ->
                scienceDomainsNodes.addChild(new NodeFilters(r.instanceId, r.name)));
        filters.add(scienceDomainsNodes);

        // Service Types filter
        NodeFilters serviceTypesNodes = new NodeFilters(PARAMETER__SERVICE_TYPE);
        filterData.serviceTypes.forEach(r ->
                serviceTypesNodes.addChild(new NodeFilters(r.instanceId, r.name)));
        filters.add(serviceTypesNodes);

        return filters;
    }

    /**
     * Converts OrganizationInfo records to Organization entities for provider generation.
     */
    private static List<Organization> convertToOrganizationEntities(Set<OrganizationInfo> orgInfos) {
        List<Organization> organizations = new ArrayList<>(orgInfos.size());
        for (OrganizationInfo info : orgInfos) {
            Organization org = new Organization();
            org.setInstanceId(info.instanceId);
            org.setLegalName(Collections.singletonList(info.legalName));
            if (info.url != null) {
                org.setURL(info.url);
            }
            if (info.logo != null) {
                org.setLogo(info.logo);
            }
            organizations.add(org);
        }
        return organizations;
    }
}