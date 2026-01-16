package org.epos.api.core.distributions;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.apache.commons.codec.digest.DigestUtils;
import org.epos.api.facets.Facets;
import org.epos.eposdatamodel.User;
import org.epos.api.beans.*;
import org.epos.api.beans.DiscoveryItem.DiscoveryItemBuilder;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.ZabbixExecutor;
import org.epos.api.enums.AvailableFormatType;
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
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/resources/details/";
    private static final String API_PATH_EXECUTE = EnvironmentVariables.API_CONTEXT + "/execute/";
    private static final String API_PATH_EXECUTE_OGC = EnvironmentVariables.API_CONTEXT + "/ogcexecute/";
    private static final String API_FORMAT = "?format=";
    private static final String API_INPUT_FORMAT = "inputFormat=";
    private static final String API_PLUGIN_ID = "pluginId=";

    private static final String PARAMETER__SCIENCE_DOMAIN = "sciencedomains";
    private static final String PARAMETER__SERVICE_TYPE = "servicetypes";
    private static final String SPATIAL_SEPARATOR = " #EPOS# ";

    private static class QueryContext {
        final StringBuilder sql = new StringBuilder();
        final Map<Integer, Object> params = new HashMap<>();
        int paramIndex = 1;
    }

    private static class FilterData {
        final Set<String> keywords = ConcurrentHashMap.newKeySet();
        final Set<OrganizationInfo> organizations = ConcurrentHashMap.newKeySet();
        final Set<CategoryInfo> scienceDomains = ConcurrentHashMap.newKeySet();
        final Set<CategoryInfo> serviceTypes = ConcurrentHashMap.newKeySet();
    }

    private static class OrganizationInfo {
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
            if (o == null || getClass() != o.getClass()) return false;
            OrganizationInfo that = (OrganizationInfo) o;
            return Objects.equals(instanceId, that.instanceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(instanceId);
        }
    }

    private static class CategoryInfo {
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
            if (o == null || getClass() != o.getClass()) return false;
            CategoryInfo that = (CategoryInfo) o;
            return Objects.equals(instanceId, that.instanceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(instanceId);
        }
    }

    public static SearchResponse generate(Map<String, Object> parameters, User user) {
        LOGGER.info("Starting optimized distribution search with params: {}", parameters);
        long startTime = System.currentTimeMillis();

        try {
            FilterData filterData = new FilterData();
            List<DiscoveryItem> discoveryItems = executeWithEntityManager(parameters, user, filterData);

            LOGGER.info("[PERF] Query execution completed: {} ms, {} results",
                    System.currentTimeMillis() - startTime, discoveryItems.size());

            SearchResponse response = buildResponse(discoveryItems, parameters, filterData);

            long duration = System.currentTimeMillis() - startTime;
            LOGGER.info("[PERF] TOTAL: {} ms", duration);

            return response;

        } catch (Exception e) {
            LOGGER.error("Error during optimized search", e);
            throw new RuntimeException("Search failed", e);
        }
    }

    private static List<DiscoveryItem> executeWithEntityManager(Map<String, Object> parameters, User user, FilterData filterData) {
        EntityManager em = null;
        List<DiscoveryItem> results = new ArrayList<>();

        try {
            em = EntityManagerService.getInstance().createEntityManager();

            QueryContext ctx = buildDynamicSQL(parameters, user);

            Query query = em.createNativeQuery(ctx.sql.toString());

            for (Map.Entry<Integer, Object> entry : ctx.params.entrySet()) {
                query.setParameter(entry.getKey(), entry.getValue());
            }

            long queryStart = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<Object[]> resultList = query.getResultList();
            LOGGER.info("[PERF] Native SQL executed: {} ms, {} rows",
                    System.currentTimeMillis() - queryStart, resultList.size());

            long mappingStart = System.currentTimeMillis();

            Geometry inputGeometry = null;
            WKTReader wktReader = null;
            if (hasSpatialParams(parameters)) {
                try {
                    GeometryFactory geometryFactory = new GeometryFactory();
                    wktReader = new WKTReader(geometryFactory);
                    inputGeometry = wktReader.read(BBoxToPolygon.transform(parameters));
                } catch (Exception e) {
                    LOGGER.error("Error parsing BBox parameters", e);
                }
            }

            for (Object[] row : resultList) {
                DiscoveryItem item = mapRowToDiscoveryItem(row, parameters, user, filterData, inputGeometry, wktReader);
                if (item != null) {
                    results.add(item);
                }
            }
            LOGGER.info("[PERF] Result mapping & filtering: {} ms", System.currentTimeMillis() - mappingStart);

        } finally {
            if (em != null) {
                em.close();
            }
        }

        return results;
    }

    private static boolean hasSpatialParams(Map<String, Object> params) {
        return params.containsKey("epos:northernmostLatitude") && params.containsKey("epos:southernmostLatitude")
                && params.containsKey("epos:westernmostLongitude") && params.containsKey("epos:easternmostLongitude");
    }

    private static String nextParam(QueryContext ctx, Object value) {
        int idx = ctx.paramIndex++;
        ctx.params.put(idx, value);
        return "?" + idx;
    }

    private static String nextListParam(QueryContext ctx, List<String> values) {
        if (values == null || values.isEmpty()) return "(NULL)";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(nextParam(ctx, values.get(i)));
        }
        sb.append(")");
        return sb.toString();
    }

    private static List<String> cleanKeywords(List<String> input) {
        if (input == null) return new ArrayList<>();
        return input.stream()
                .filter(Objects::nonNull)
                .map(k -> k.replace(",", "").trim())
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toList());
    }

    private static Timestamp parseDateParam(Object dateParam) {
        if (dateParam == null) return null;
        try {
            String dateStr = dateParam.toString().replace("Z", "");
            return Timestamp.valueOf(LocalDateTime.parse(dateStr));
        } catch (Exception e) {
            LOGGER.warn("Failed to parse date: " + dateParam, e);
            return null;
        }
    }

    // --- SQL CONSTRUCTION ---
    private static QueryContext buildDynamicSQL(Map<String, Object> parameters, User user) {
        QueryContext ctx = new QueryContext();

        // 1. Base Parameters
        List<String> statuses = getStatusList(parameters, user);
        List<String> organizations = getListParam(parameters, "organisations");
        List<String> scienceDomains = getListParam(parameters, PARAMETER__SCIENCE_DOMAIN);
        List<String> serviceTypes = getListParam(parameters, PARAMETER__SERVICE_TYPE);

        // 2. Keywords Management: Merge 'keywords' param + split 'q' param
        List<String> keywords = new ArrayList<>(cleanKeywords(getListParam(parameters, "keywords")));
        String q = (String) parameters.get("q");

        // === FIX: Split 'q' into tokens and add to keyword list ===
        if (q != null && !q.trim().isEmpty()) {
            // Split by whitespace, comma, semicolon
            String[] qTokens = q.split("[\\s,;]+");
            for (String token : qTokens) {
                if (token != null && !token.trim().isEmpty()) {
                    String cleanToken = token.trim().toLowerCase();
                    // Add only if not already present to avoid duplication in SQL params
                    if (!keywords.contains(cleanToken)) {
                        keywords.add(cleanToken);
                    }
                }
            }
        }

        Timestamp startDate = parseDateParam(parameters.get("schema:startDate"));
        Timestamp endDate = parseDateParam(parameters.get("schema:endDate"));

        ctx.sql.append("WITH ");

        String statusParams = nextListParam(ctx, statuses);

        ctx.sql.append("published_distributions AS ( ")
                .append("  SELECT d.instance_id, d.meta_id, d.uid, d.format, d.version_id, ")
                .append("         v.status AS versioning_status, v.change_timestamp, v.editor_id ")
                .append("  FROM metadata_catalogue.distribution d ")
                .append("  JOIN metadata_catalogue.versioningstatus v ON d.version_id = v.version_id ")
                .append("  WHERE v.status IN ").append(statusParams);

        if (user != null && !user.getIsAdmin() && parameters.containsKey("versioningStatus")) {
            ctx.sql.append(" AND v.editor_id = ").append(nextParam(ctx, user.getAuthIdentifier()));
        }
        ctx.sql.append("), ");

        ctx.sql.append("dataproduct_info AS ( ")
                .append("  SELECT ddp.distribution_instance_id, dp.instance_id AS dataproduct_id, dp.keywords ")
                .append("  FROM metadata_catalogue.distribution_dataproduct ddp ")
                .append("  JOIN metadata_catalogue.dataproduct dp ON ddp.dataproduct_instance_id = dp.instance_id ")
                .append("  JOIN metadata_catalogue.versioningstatus v ON dp.version_id = v.version_id ");

        if (startDate != null || endDate != null) {
            ctx.sql.append(" JOIN metadata_catalogue.dataproduct_temporal dpt ON dp.instance_id = dpt.dataproduct_instance_id ")
                    .append(" JOIN metadata_catalogue.temporal t ON dpt.temporal_instance_id = t.instance_id ");
        }

        if (!scienceDomains.isEmpty()) {
            ctx.sql.append(" JOIN metadata_catalogue.dataproduct_category dpc ON dp.instance_id = dpc.dataproduct_instance_id ")
                    .append(" JOIN metadata_catalogue.category c_sd ON dpc.category_instance_id = c_sd.instance_id ");
        }

        if (!organizations.isEmpty()) {
            ctx.sql.append(" LEFT JOIN metadata_catalogue.dataproduct_publisher dpp ON dp.instance_id = dpp.dataproduct_instance_id ")
                    .append(" LEFT JOIN metadata_catalogue.organization o_pub ON dpp.organization_instance_id = o_pub.instance_id ");
        }

        ctx.sql.append("  WHERE ddp.distribution_instance_id IN (SELECT instance_id FROM published_distributions) ")
                .append("    AND v.status = 'PUBLISHED' ");

        if (startDate != null) {
            ctx.sql.append(" AND (t.endDate IS NULL OR t.endDate >= CAST(").append(nextParam(ctx, startDate)).append(" AS timestamp)) ");
        }
        if (endDate != null) {
            ctx.sql.append(" AND (t.startDate IS NULL OR t.startDate <= CAST(").append(nextParam(ctx, endDate)).append(" AS timestamp)) ");
        }

        if (!scienceDomains.isEmpty()) {
            String sdParams = nextListParam(ctx, scienceDomains);
            ctx.sql.append(" AND (c_sd.uid IN ").append(sdParams).append(" OR c_sd.instance_id IN ").append(sdParams).append(") ");
        }

        // Apply Extended Keyword Filter (includes 'q' tokens)
        if (!keywords.isEmpty()) {
            ctx.sql.append(" AND ( ");
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) ctx.sql.append(" OR ");
                ctx.sql.append(" dp.keywords ILIKE ").append(nextParam(ctx, "%" + keywords.get(i) + "%"));
            }
            ctx.sql.append(" ) ");
        }

        ctx.sql.append("), ");

        ctx.sql.append("webservice_info AS ( ")
                .append("  SELECT wd.distribution_instance_id, ws.instance_id AS webservice_id, ws.provider AS provider_id ")
                .append("  FROM metadata_catalogue.webservice_distribution wd ")
                .append("  JOIN metadata_catalogue.webservice ws ON wd.webservice_instance_id = ws.instance_id ");

        if (!serviceTypes.isEmpty()) {
            ctx.sql.append(" JOIN metadata_catalogue.webservice_category wsc ON ws.instance_id = wsc.webservice_instance_id ")
                    .append(" JOIN metadata_catalogue.category c_st ON wsc.category_instance_id = c_st.instance_id ");
        }
        if (!organizations.isEmpty()) {
            ctx.sql.append(" LEFT JOIN metadata_catalogue.organization o_prov ON ws.provider = o_prov.instance_id ");
        }

        ctx.sql.append("  WHERE wd.distribution_instance_id IN (SELECT instance_id FROM published_distributions) ");

        if (!serviceTypes.isEmpty()) {
            String stParams = nextListParam(ctx, serviceTypes);
            ctx.sql.append(" AND (c_st.uid IN ").append(stParams).append(" OR c_st.instance_id IN ").append(stParams).append(") ");
        }

        boolean hasStrictDpFilters = startDate != null || endDate != null || !keywords.isEmpty() || !scienceDomains.isEmpty();
        if (hasStrictDpFilters) {
            ctx.sql.append(" AND 1=0 ");
        }
        ctx.sql.append("), ");

        // 4. Operation Services
        ctx.sql.append("operation_services AS ( ")
                .append("  SELECT od.distribution_instance_id, STRING_AGG(DISTINCT UPPER(COALESCE(e.value, m.defaultvalue)), ',') AS service_values ")
                .append("  FROM metadata_catalogue.operation_distribution od ")
                .append("  JOIN metadata_catalogue.operation_mapping om ON od.operation_instance_id = om.operation_instance_id ")
                .append("  JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ")
                .append("  LEFT JOIN metadata_catalogue.mapping_element me ON m.instance_id = me.mapping_instance_id ")
                .append("  LEFT JOIN metadata_catalogue.element e ON me.element_instance_id = e.instance_id AND e.type = 'PARAMVALUE' ")
                .append("  WHERE m.variable ILIKE 'service' ")
                .append("  GROUP BY od.distribution_instance_id ")
                .append("), ");

        ctx.sql.append("filtered_ids AS ( ");
        boolean hasWsFilter = !serviceTypes.isEmpty();
        boolean hasOrgFilter = !organizations.isEmpty();

        if (!hasStrictDpFilters && !hasWsFilter && !hasOrgFilter) {
            ctx.sql.append(" SELECT instance_id FROM published_distributions ");
        } else {
            ctx.sql.append(" SELECT distribution_instance_id AS instance_id FROM dataproduct_info ");
            String orgParams = "";
            if (hasOrgFilter) {
                orgParams = nextListParam(ctx, organizations);
                ctx.sql.append(" JOIN metadata_catalogue.dataproduct_publisher dpp2 ON dataproduct_info.dataproduct_id = dpp2.dataproduct_instance_id ")
                        .append(" JOIN metadata_catalogue.organization o2 ON dpp2.organization_instance_id = o2.instance_id ")
                        .append(" WHERE (o2.instance_id IN ").append(orgParams).append(" OR o2.legalname IN ").append(orgParams).append(") ");
            }
            ctx.sql.append(" UNION ");
            ctx.sql.append(" SELECT distribution_instance_id AS instance_id FROM webservice_info ");
            if (hasOrgFilter) {
                ctx.sql.append(" JOIN metadata_catalogue.organization o3 ON webservice_info.provider_id = o3.instance_id ")
                        .append(" WHERE (o3.instance_id IN ").append(orgParams).append(" OR o3.legalname IN ").append(orgParams).append(") ");
            }
        }
        ctx.sql.append("), ");

        ctx.sql.append("dist_titles AS ( ")
                .append(" SELECT dt.distribution_instance_id, STRING_AGG(dt.title, ';' ORDER BY dt.lang) AS title ")
                .append(" FROM metadata_catalogue.distribution_title dt ")
                .append(" WHERE dt.distribution_instance_id IN (SELECT instance_id FROM filtered_ids) ")
                .append(" GROUP BY dt.distribution_instance_id ), ");

        ctx.sql.append("dist_descriptions AS ( ")
                .append(" SELECT dd.distribution_instance_id, STRING_AGG(dd.description, ';' ORDER BY dd.lang) AS description ")
                .append(" FROM metadata_catalogue.distribution_description dd ")
                .append(" WHERE dd.distribution_instance_id IN (SELECT instance_id FROM filtered_ids) ")
                .append(" GROUP BY dd.distribution_instance_id ), ");

        ctx.sql.append("dist_keywords AS ( ")
                .append(" SELECT di.distribution_instance_id, ARRAY_AGG(DISTINCT LOWER(TRIM(kw))) FILTER (WHERE TRIM(kw) != '') AS keywords ")
                .append(" FROM dataproduct_info di, LATERAL UNNEST(regexp_split_to_array(di.keywords, '[,\t]+')) AS kw ")
                .append(" WHERE di.keywords IS NOT NULL AND di.keywords != '' ")
                .append(" GROUP BY di.distribution_instance_id ), ");

        ctx.sql.append("dist_download_urls AS ( ")
                .append(" SELECT de.distribution_instance_id, ARRAY_AGG(e.value) AS download_urls ")
                .append(" FROM metadata_catalogue.distribution_element de ")
                .append(" JOIN metadata_catalogue.element e ON de.element_instance_id = e.instance_id ")
                .append(" WHERE de.distribution_instance_id IN (SELECT instance_id FROM filtered_ids) AND e.type = 'DOWNLOADURL' ")
                .append(" GROUP BY de.distribution_instance_id ), ");

        ctx.sql.append("dataproduct_spatial_agg AS ( ")
                .append(" SELECT di.distribution_instance_id, STRING_AGG(s.location, '").append(SPATIAL_SEPARATOR).append("') AS locations ")
                .append(" FROM dataproduct_info di ")
                .append(" JOIN metadata_catalogue.dataproduct_spatial dps ON di.dataproduct_id = dps.dataproduct_instance_id ")
                .append(" JOIN metadata_catalogue.spatial s ON dps.spatial_instance_id = s.instance_id ")
                .append(" GROUP BY di.distribution_instance_id ), ");

        ctx.sql.append("webservice_spatial_agg AS ( ")
                .append(" SELECT wi.distribution_instance_id, STRING_AGG(s.location, '").append(SPATIAL_SEPARATOR).append("') AS locations ")
                .append(" FROM webservice_info wi ")
                .append(" JOIN metadata_catalogue.webservice_spatial wss ON wi.webservice_id = wss.webservice_instance_id ")
                .append(" JOIN metadata_catalogue.spatial s ON wss.spatial_instance_id = s.instance_id ")
                .append(" GROUP BY wi.distribution_instance_id ), ");

        ctx.sql.append("dataproduct_publishers_agg AS ( ")
                .append(" SELECT di.distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ")
                .append("   'instance_id', o.instance_id, 'legal_name', o.legalname, 'acronym', o.acronym, 'url', o.url, 'logo', o.logo ")
                .append(" )) AS data_providers ")
                .append(" FROM dataproduct_info di ")
                .append(" JOIN metadata_catalogue.dataproduct_publisher dpp ON di.dataproduct_id = dpp.dataproduct_instance_id ")
                .append(" JOIN metadata_catalogue.organization o ON dpp.organization_instance_id = o.instance_id ")
                .append(" GROUP BY di.distribution_instance_id ), ");

        ctx.sql.append("service_providers_agg AS ( ")
                .append(" SELECT wi.distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ")
                .append("   'instance_id', o.instance_id, 'legal_name', o.legalname, 'acronym', o.acronym, 'url', o.url, 'logo', o.logo ")
                .append(" )) AS service_providers ")
                .append(" FROM webservice_info wi ")
                .append(" JOIN metadata_catalogue.organization o ON wi.provider_id = o.instance_id ")
                .append(" GROUP BY wi.distribution_instance_id ), ");

        ctx.sql.append("dataproduct_categories_agg AS ( ")
                .append(" SELECT di.distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('uid', c.uid, 'name', c.name, 'instance_id', c.instance_id)) AS categories ")
                .append(" FROM dataproduct_info di ")
                .append(" JOIN metadata_catalogue.dataproduct_category dpc ON di.dataproduct_id = dpc.dataproduct_instance_id ")
                .append(" JOIN metadata_catalogue.category c ON dpc.category_instance_id = c.instance_id ")
                .append(" GROUP BY di.distribution_instance_id ), ");

        ctx.sql.append("service_categories_agg AS ( ")
                .append(" SELECT wi.distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('uid', c.uid, 'name', c.name, 'instance_id', c.instance_id)) AS service_types ")
                .append(" FROM webservice_info wi ")
                .append(" JOIN metadata_catalogue.webservice_category wc ON wi.webservice_id = wc.webservice_instance_id ")
                .append(" JOIN metadata_catalogue.category c ON wc.category_instance_id = c.instance_id ")
                .append(" GROUP BY wi.distribution_instance_id ), ");

        ctx.sql.append("operation_info AS ( ")
                .append(" SELECT od.distribution_instance_id, op.instance_id AS operation_id, op.template, op.method ")
                .append(" FROM metadata_catalogue.operation_distribution od ")
                .append(" JOIN metadata_catalogue.operation op ON od.operation_instance_id = op.instance_id ")
                .append(" WHERE od.distribution_instance_id IN (SELECT instance_id FROM filtered_ids) ), ");

        ctx.sql.append("operation_returns AS ( ")
                .append(" SELECT oi.distribution_instance_id, ARRAY_AGG(DISTINCT e.value) AS returns ")
                .append(" FROM operation_info oi ")
                .append(" JOIN metadata_catalogue.operation_element oe ON oi.operation_id = oe.operation_instance_id ")
                .append(" JOIN metadata_catalogue.element e ON oe.element_instance_id = e.instance_id ")
                .append(" WHERE e.type = 'RETURNS' GROUP BY oi.distribution_instance_id ), ");

        ctx.sql.append("encoding_formats AS ( ")
                .append(" SELECT oi.distribution_instance_id, oi.template, m.variable, m.defaultvalue, ")
                .append(" ARRAY_AGG(DISTINCT e.value) FILTER (WHERE e.value IS NOT NULL) AS param_values ")
                .append(" FROM operation_info oi ")
                .append(" JOIN metadata_catalogue.operation_mapping om ON oi.operation_id = om.operation_instance_id ")
                .append(" JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ")
                .append(" LEFT JOIN metadata_catalogue.mapping_element me ON m.instance_id = me.mapping_instance_id ")
                .append(" LEFT JOIN metadata_catalogue.element e ON me.element_instance_id = e.instance_id AND e.type = 'PARAMVALUE' ")
                .append(" WHERE m.property LIKE '%encodingFormat%' ")
                .append(" GROUP BY oi.distribution_instance_id, oi.template, m.variable, m.defaultvalue ), ");

        ctx.sql.append("available_formats_agg AS ( ")
                .append(" SELECT distribution_instance_id, JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ")
                .append("   'format', pv.format_value, 'template', template, 'variable', variable, 'default_value', defaultvalue ")
                .append(" )) AS available_formats_data ")
                .append(" FROM encoding_formats ef, LATERAL UNNEST(ef.param_values) AS pv(format_value) ")
                .append(" WHERE ef.param_values IS NOT NULL GROUP BY distribution_instance_id ) ");

        // --- MAIN SELECT ---
        ctx.sql.append("SELECT ")
                .append(" pd.instance_id AS id, pd.uid, pd.meta_id, ")
                .append(" COALESCE(dt.title, '') AS title, ")
                .append(" COALESCE(dd.description, '') AS description, ")
                .append(" pd.versioning_status, pd.change_timestamp, pd.editor_id, ")
                .append(" COALESCE(CAST(dpu.data_providers AS text), '[]') AS data_providers, ")
                .append(" COALESCE(CAST(sp.service_providers AS text), '[]') AS service_providers, ")
                .append(" COALESCE(CAST(dc.categories AS text), '[]') AS categories, ")
                .append(" COALESCE(CAST(sc.service_types AS text), '[]') AS service_types, ")
                .append(" COALESCE(CAST(afa.available_formats_data AS text), '[]') AS available_formats_raw, ")
                .append(" COALESCE(CAST(TO_JSON(ddu.download_urls) AS text), '[]') AS download_urls, ")
                .append(" pd.format AS original_format, ")
                .append(" COALESCE(CAST(TO_JSON(oret.returns) AS text), '[]') AS operation_returns, ")
                .append(" COALESCE(CAST(TO_JSON(dk.keywords) AS text), '[]') AS keywords, ")
                .append(" COALESCE(dsa.locations, '') || '").append(SPATIAL_SEPARATOR).append("' || COALESCE(wsa.locations, '') AS spatial_locations, ")
                .append(" COALESCE(os.service_values, '') AS service_values ")

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

        if (q != null && !q.trim().isEmpty()) {
            String qParam = nextParam(ctx, "%" + q + "%");
            ctx.sql.append(" WHERE (dt.title ILIKE ").append(qParam)
                    .append(" OR dd.description ILIKE ").append(qParam)
                    .append(" OR ARRAY_TO_STRING(dk.keywords, ' ') ILIKE ").append(qParam).append(") ");
        }

        ctx.sql.append(" ORDER BY pd.instance_id ");

        return ctx;
    }

    private static List<String> getListParam(Map<String, Object> params, String key) {
        List<String> list = new ArrayList<>();
        if (params.containsKey(key) && params.get(key) != null) {
            String val = params.get(key).toString();
            if (!val.trim().isEmpty()) {
                Collections.addAll(list, val.split(","));
            }
        }
        return list;
    }

    private static List<String> getStatusList(Map<String, Object> parameters, User user) {
        List<String> statuses = new ArrayList<>();
        boolean isBackofficeUser = user != null;
        if (isBackofficeUser && parameters.containsKey("versioningStatus")) {
            statuses.addAll(Arrays.asList(parameters.get("versioningStatus").toString().split(",")));
        } else {
            statuses.add("PUBLISHED");
        }
        return statuses;
    }

    private static DiscoveryItem mapRowToDiscoveryItem(Object[] row, Map<String, Object> parameters, User user, FilterData filterData,
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

            if (inputGeometry != null) {
                boolean intersects = false;
                if (spatialLocationsStr != null && !spatialLocationsStr.isEmpty()) {
                    String[] locations = spatialLocationsStr.split(SPATIAL_SEPARATOR.trim());
                    for (String wkt : locations) {
                        if (wkt == null || wkt.trim().isEmpty()) continue;
                        try {
                            Geometry dsGeometry = wktReader.read(wkt.trim());
                            if (inputGeometry.intersects(dsGeometry)) {
                                intersects = true;
                                break;
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
                if (!intersects) {
                    return null;
                }
            }

            String[] downloadUrls = parseJsonStringArray(downloadUrlsJson);
            String[] operationReturns = parseJsonStringArray(operationReturnsJson);
            String[] keywordsArray = parseJsonStringArray(keywordsJson);

            if (keywordsArray != null) {
                for (String kw : keywordsArray) {
                    if (kw != null && !kw.trim().isEmpty()) {
                        filterData.keywords.add(kw.replace(",", "").trim().toLowerCase());
                    }
                }
            }

            Set<String> facetsDataProviders = parseOrganizationNamesAndCollect(dataProvidersJson, filterData.organizations);
            Set<String> facetsServiceProviders = parseOrganizationNamesAndCollect(serviceProvidersJson, filterData.organizations);
            List<String> categoryList = parseCategoryUidsAndCollect(categoriesJson, filterData.scienceDomains);
            collectServiceTypes(serviceTypesJson, filterData.serviceTypes);

            List<AvailableFormat> availableFormats = buildAvailableFormats(
                    instanceId, downloadUrls, originalFormat,
                    operationReturns, availableFormatsJson, serviceValues);

            DataServiceProvider dataServiceProvider = parseFirstServiceProvider(serviceProvidersJson);

            DiscoveryItemBuilder builder = new DiscoveryItemBuilder(
                    instanceId,
                    EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId,
                    EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId + "?extended=true")
                    .uid(uid)
                    .metaId(metaId)
                    .title(title)
                    .description(description)
                    .dataServiceProvider(dataServiceProvider)
                    .availableFormats(availableFormats)
                    .sha256id(uid != null ? DigestUtils.sha256Hex(uid) : "")
                    .dataProvider(facetsDataProviders)
                    .serviceProvider(facetsServiceProviders)
                    .categories(categoryList.isEmpty() ? null : categoryList);

            if (user != null && parameters.containsKey("versioningStatus")) {
                builder.editorId(editorId)
                        .versioningStatus(versioningStatus);
                if (changeTimestamp != null) {
                    builder.changeDate(changeTimestamp.toLocalDateTime());
                }
            }

            DiscoveryItem item = builder.build();

            if ("true".equals(EnvironmentVariables.MONITORING)) {
                item.setStatus(ZabbixExecutor.getInstance().getStatusInfoFromSha(item.getSha256id()));
                item.setStatusTimestamp(ZabbixExecutor.getInstance().getStatusTimestampInfoFromSha(item.getSha256id()));
                item.setStatusURL(ZabbixExecutor.getInstance().getStatusURLFromSha(item.getSha256id()));
            }

            return item;

        } catch (Exception e) {
            LOGGER.warn("Error mapping result row: {}", e.getMessage());
            return null;
        }
    }

    private static Set<String> parseOrganizationNamesAndCollect(String json, Set<OrganizationInfo> organizations) {
        Set<String> names = new HashSet<>();
        try {
            if (json != null && !json.equals("[]") && !json.equals("null")) {
                JsonNode arrayNode = objectMapper.readTree(json);
                for (JsonNode node : arrayNode) {
                    String instanceId = node.path("instance_id").asText(null);
                    String legalName = node.path("legal_name").asText(null);
                    String acronym = node.path("acronym").asText(null);
                    String url = node.path("url").asText(null);
                    String logo = node.path("logo").asText(null);

                    if (legalName != null && !legalName.isEmpty()) {
                        names.add(legalName);
                        if (instanceId != null) {
                            organizations.add(new OrganizationInfo(instanceId, legalName, acronym, url, logo));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error parsing organization names: {}", e.getMessage());
        }
        return names;
    }

    private static List<String> parseCategoryUidsAndCollect(String json, Set<CategoryInfo> scienceDomains) {
        List<String> uids = new ArrayList<>();
        try {
            if (json != null && !json.equals("[]") && !json.equals("null")) {
                JsonNode arrayNode = objectMapper.readTree(json);
                for (JsonNode node : arrayNode) {
                    String instanceId = node.path("instance_id").asText(null);
                    String uid = node.path("uid").asText(null);
                    String name = node.path("name").asText(null);

                    if (uid != null && uid.contains("category:")) {
                        uids.add(uid);
                    } else if (instanceId != null) {
                        scienceDomains.add(new CategoryInfo(instanceId, uid, name));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error parsing category UIDs: {}", e.getMessage());
        }
        return uids;
    }

    private static void collectServiceTypes(String json, Set<CategoryInfo> serviceTypes) {
        try {
            if (json != null && !json.equals("[]") && !json.equals("null")) {
                JsonNode arrayNode = objectMapper.readTree(json);
                for (JsonNode node : arrayNode) {
                    String instanceId = node.path("instance_id").asText(null);
                    String uid = node.path("uid").asText(null);
                    String name = node.path("name").asText(null);
                    if (instanceId != null) {
                        serviceTypes.add(new CategoryInfo(instanceId, uid, name));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error parsing service types: {}", e.getMessage());
        }
    }

    private static DataServiceProvider parseFirstServiceProvider(String json) {
        try {
            if (json != null && !json.equals("[]") && !json.equals("null")) {
                JsonNode arrayNode = objectMapper.readTree(json);
                if (arrayNode.size() > 0) {
                    JsonNode node = arrayNode.get(0);
                    DataServiceProvider provider = new DataServiceProvider();
                    provider.setInstanceid(node.path("instance_id").asText(null));
                    provider.setDataProviderLegalName(node.path("legal_name").asText(null));
                    provider.setDataProviderUrl(node.path("url").asText(null));
                    return provider;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error parsing service provider: {}", e.getMessage());
        }
        return null;
    }

    private static List<AvailableFormat> buildAvailableFormats(String instanceId, String[] downloadUrls, String originalFormat,
                                                               String[] operationReturns, String availableFormatsJson,
                                                               String serviceValues) {
        List<AvailableFormat> formats = new ArrayList<>();

        if (downloadUrls != null && downloadUrls.length > 0 && originalFormat != null) {
            String[] uri = originalFormat.split("/");
            String format = uri[uri.length - 1];
            formats.add(new AvailableFormat.AvailableFormatBuilder()
                    .originalFormat(format).format(format).href(String.join(",", downloadUrls))
                    .label(format.toUpperCase()).type(AvailableFormatType.ORIGINAL).build());
        }

        try {
            if (DatabaseConnections.getInstance().getPlugins().containsKey(instanceId)) {
                for (Plugin.Relations relation : DatabaseConnections.getInstance().getPlugins().get(instanceId)) {
                    String outputFormat = relation.getOutputFormat();
                    String inputFormat = relation.getInputFormat();
                    String pluginId = relation.getPluginId();

                    if (outputFormat.equals("application/epos.geo+json")
                            || outputFormat.equals("application/epos.table.geo+json")
                            || outputFormat.equals("application/epos.map.geo+json")) {
                        formats.add(new AvailableFormatConverted.AvailableFormatConvertedBuilder()
                                .inputFormat(inputFormat).pluginId(pluginId).originalFormat(inputFormat).format(outputFormat)
                                .href(buildHrefConverted(instanceId, outputFormat, inputFormat, pluginId))
                                .label("GEOJSON").type(AvailableFormatType.CONVERTED).build());
                    } else if (outputFormat.equals("application/epos.graph.covjson")
                            || outputFormat.equals("application/epos.covjson")) {
                        formats.add(new AvailableFormatConverted.AvailableFormatConvertedBuilder()
                                .inputFormat(inputFormat).pluginId(pluginId).originalFormat(inputFormat).format(outputFormat)
                                .href(buildHrefConverted(instanceId, outputFormat, inputFormat, pluginId))
                                .label("COVJSON").type(AvailableFormatType.CONVERTED).build());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error processing plugins for instance {}: {}", instanceId, e.getMessage());
        }

        try {
            if (availableFormatsJson != null && !availableFormatsJson.equals("[]")) {
                JsonNode arrayNode = objectMapper.readTree(availableFormatsJson);
                for (JsonNode formatNode : arrayNode) {
                    String paramValue = formatNode.has("format") ? formatNode.get("format").asText() : null;
                    String template = formatNode.has("template") ? formatNode.get("template").asText() : "";
                    String variable = formatNode.has("variable") ? formatNode.get("variable").asText() : "";
                    String defaultValue = formatNode.has("default_value") ? formatNode.get("default_value").asText() : "";

                    if (paramValue == null || paramValue.equals("null")) continue;

                    String templateLower = template != null ? template.toLowerCase() : "";
                    String variableLower = variable != null ? variable.toLowerCase() : "";
                    String defaultValueLower = defaultValue != null ? defaultValue.toLowerCase() : "";

                    boolean isWMS = templateLower.contains("service=wms")
                            || (variableLower.equals("service") && (paramValue.contains("WMS") || defaultValueLower.contains("wms")))
                            || (serviceValues != null && serviceValues.contains("WMS"));

                    boolean isWMTS = templateLower.contains("service=wmts")
                            || (variableLower.equals("service") && (paramValue.contains("WMTS") || defaultValueLower.contains("wmts")))
                            || (serviceValues != null && serviceValues.contains("WMTS"));

                    boolean isWFS = templateLower.contains("service=wfs")
                            || (variableLower.equals("service") && (paramValue.contains("WFS") || defaultValueLower.contains("wfs")))
                            || (serviceValues != null && serviceValues.contains("WFS"));

                    if (paramValue.startsWith("image/")) {
                        if (isWMS) {
                            formats.add(new AvailableFormat.AvailableFormatBuilder()
                                    .originalFormat(paramValue).format("application/vnd.ogc.wms_xml")
                                    .href(buildHrefOgc(instanceId)).label("WMS").type(AvailableFormatType.ORIGINAL).build());
                        } else if (isWMTS) {
                            formats.add(new AvailableFormat.AvailableFormatBuilder()
                                    .originalFormat(paramValue).format("application/vnd.ogc.wmts_xml")
                                    .href(buildHrefOgc(instanceId)).label("WMTS").type(AvailableFormatType.ORIGINAL).build());
                        }
                    } else if (paramValue.equals("json") && isWFS) {
                        formats.add(new AvailableFormat.AvailableFormatBuilder()
                                .originalFormat(paramValue).format("application/epos.geo+json")
                                .href(buildHref(instanceId, "json")).label("GEOJSON (" + paramValue + ")").type(AvailableFormatType.ORIGINAL).build());
                    } else if (paramValue.contains("geo%2Bjson") || paramValue.toLowerCase().matches(".*geo(?:json|\\+json|-json).*")) {
                        formats.add(new AvailableFormat.AvailableFormatBuilder()
                                .originalFormat(paramValue).format("application/epos.geo+json")
                                .href(buildHref(instanceId, paramValue)).label("GEOJSON (" + paramValue + ")").type(AvailableFormatType.ORIGINAL).build());
                    } else {
                        formats.add(new AvailableFormat.AvailableFormatBuilder()
                                .originalFormat(paramValue).format(paramValue)
                                .href(buildHref(instanceId, paramValue)).label(paramValue.toUpperCase()).type(AvailableFormatType.ORIGINAL).build());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error parsing encoding formats: {}", e.getMessage());
        }

        if (formats.isEmpty() && operationReturns != null) {
            for (String ret : operationReturns) {
                if (ret != null) {
                    if (ret.contains("geojson") || ret.contains("geo+json")) {
                        formats.add(new AvailableFormat.AvailableFormatBuilder()
                                .originalFormat(ret).format("application/epos.geo+json")
                                .href(buildHref(instanceId, ret)).label("GEOJSON").type(AvailableFormatType.ORIGINAL).build());
                    } else {
                        formats.add(new AvailableFormat.AvailableFormatBuilder()
                                .originalFormat(ret).format(ret)
                                .href(buildHref(instanceId, ret)).label(ret.toUpperCase()).type(AvailableFormatType.ORIGINAL).build());
                    }
                }
            }
        }
        return formats;
    }

    private static String buildHref(String instanceId, String format) {
        return EnvironmentVariables.API_HOST + API_PATH_EXECUTE + instanceId + API_FORMAT + format;
    }

    private static String buildHrefConverted(String instanceId, String outputFormat, String inputFormat, String pluginId) {
        return buildHref(instanceId, outputFormat) + "&" + API_INPUT_FORMAT + inputFormat + "&" + API_PLUGIN_ID + pluginId;
    }

    private static String buildHrefOgc(String instanceId) {
        return EnvironmentVariables.API_HOST + API_PATH_EXECUTE_OGC + instanceId;
    }

    private static String[] parseJsonStringArray(String json) {
        if (json == null || json.isEmpty() || json.equals("[]") || json.equals("null")) return null;
        try {
            JsonNode arrayNode = objectMapper.readTree(json);
            if (arrayNode.isArray() && arrayNode.size() > 0) {
                String[] result = new String[arrayNode.size()];
                for (int i = 0; i < arrayNode.size(); i++) result[i] = arrayNode.get(i).asText(null);
                return result;
            }
        } catch (Exception e) {
            LOGGER.warn("Error parsing JSON array: {}", e.getMessage());
        }
        return null;
    }

    private static SearchResponse buildResponse(List<DiscoveryItem> items, Map<String, Object> parameters, FilterData filterData) {
        Set<DiscoveryItem> discoverySet = new HashSet<>(items);
        Node results = new Node("results");

        if ("true".equals(String.valueOf(parameters.get("facets")))) {
            String facetsType = (String) parameters.getOrDefault("facetstype", "default");
            switch (facetsType) {
                case "categories": results.addChild(FacetsGeneration.generateResponseUsingCategories(discoverySet, Facets.Type.DATA).getFacets()); break;
                case "dataproviders": results.addChild(FacetsGeneration.generateResponseUsingDataproviders(discoverySet).getFacets()); break;
                case "serviceproviders": results.addChild(FacetsGeneration.generateResponseUsingServiceproviders(discoverySet).getFacets()); break;
                default:
                    Node child = new Node(); child.setDistributions(discoverySet); results.addChild(child); break;
            }
        } else {
            Node child = new Node(); child.setDistributions(discoverySet); results.addChild(child);
        }

        return new SearchResponse(results, buildFilters(filterData));
    }

    private static ArrayList<NodeFilters> buildFilters(FilterData filterData) {
        ArrayList<NodeFilters> filters = new ArrayList<>();

        // Keywords
        NodeFilters keywordsNodes = new NodeFilters("keywords");
        filterData.keywords.stream().filter(Objects::nonNull).sorted().forEach(keyword -> {
            NodeFilters node = new NodeFilters(keyword);
            node.setId(Base64.getEncoder().encodeToString(keyword.getBytes()));
            keywordsNodes.addChild(node);
        });
        filters.add(keywordsNodes);

        // Organizations
        List<Organization> orgEntities = convertToOrganizationEntities(filterData.organizations);
        NodeFilters organisationsNodes = new NodeFilters("organisations");
        DataServiceProviderGeneration.getProviders(orgEntities).forEach(resource -> {
            NodeFilters node = new NodeFilters(resource.getDataProviderLegalName());
            node.setId(resource.getInstanceid());
            organisationsNodes.addChild(node);
            if(resource.getRelatedDataProvider() != null)
                resource.getRelatedDataProvider().forEach(r -> {
                    NodeFilters n = new NodeFilters(r.getDataProviderLegalName()); n.setId(r.getInstanceid()); organisationsNodes.addChild(n);
                });
            if(resource.getRelatedDataServiceProvider() != null)
                resource.getRelatedDataServiceProvider().forEach(r -> {
                    NodeFilters n = new NodeFilters(r.getDataProviderLegalName()); n.setId(r.getInstanceid()); organisationsNodes.addChild(n);
                });
        });
        filters.add(organisationsNodes);

        // Science Domains
        NodeFilters scienceDomainsNodes = new NodeFilters(PARAMETER__SCIENCE_DOMAIN);
        filterData.scienceDomains.forEach(r -> scienceDomainsNodes.addChild(new NodeFilters(r.instanceId, r.name)));
        filters.add(scienceDomainsNodes);

        // Service Types
        NodeFilters serviceTypesNodes = new NodeFilters(PARAMETER__SERVICE_TYPE);
        filterData.serviceTypes.forEach(r -> serviceTypesNodes.addChild(new NodeFilters(r.instanceId, r.name)));
        filters.add(serviceTypesNodes);

        return filters;
    }

    private static List<Organization> convertToOrganizationEntities(Set<OrganizationInfo> orgInfos) {
        List<Organization> organizations = new ArrayList<>();
        for (OrganizationInfo info : orgInfos) {
            Organization org = new Organization();
            org.setInstanceId(info.instanceId);
            org.setLegalName(Collections.singletonList(info.legalName));
            if (info.url != null) org.setURL(info.url);
            if (info.logo != null) org.setLogo(info.logo);
            organizations.add(org);
        }
        return organizations;
    }
}