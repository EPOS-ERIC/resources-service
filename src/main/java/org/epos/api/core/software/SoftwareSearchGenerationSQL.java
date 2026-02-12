package org.epos.api.core.software;

import java.sql.Timestamp;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.apache.commons.codec.digest.DigestUtils;
import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.DiscoveryItem.DiscoveryItemBuilder;
import org.epos.api.beans.NodeFilters;
import org.epos.api.beans.SearchResponse;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.Node;
import org.epos.api.routines.DatabaseConnections;
import org.epos.eposdatamodel.User;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for software search.
 * This replaces the JPA-based SoftwareSearch for improved performance.
 */
public class SoftwareSearchGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareSearchGenerationSQL.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/software/details/";

    private static final Pattern FORMAT_PATTERN = Pattern.compile("\\.([a-zA-Z0-9]+)(?:/|$|\\?)");

    public static SearchResponse generate(String query, User user, String versioningStatus) {
        final long startTime = System.nanoTime();
        LOGGER.info("Generating software search (SQL) with query {}, status {}, user {}",
                query, versioningStatus, user != null ? user.getAuthIdentifier() : "public");

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            Set<DiscoveryItem> discoveryItems = new HashSet<>();
            Set<String> keywords = new HashSet<>();

            // Determine status filter
            List<String> statuses = new ArrayList<>();
            if (user != null && versioningStatus != null && !versioningStatus.isEmpty()) {
                statuses.addAll(Arrays.asList(versioningStatus.split(",")));
            } else {
                statuses.add("PUBLISHED");
            }

            // Fetch software source codes
            List<Object[]> sourceCodeResults = fetchSoftwareSourceCodes(em, query, statuses, user, versioningStatus);
            for (Object[] row : sourceCodeResults) {
                DiscoveryItem item = mapSourceCodeToDiscoveryItem(row, user, versioningStatus);
                if (item != null) {
                    discoveryItems.add(item);
                    addKeywords(row, keywords);
                }
            }

            // Fetch software applications
            List<Object[]> applicationResults = fetchSoftwareApplications(em, query, statuses, user, versioningStatus);
            for (Object[] row : applicationResults) {
                DiscoveryItem item = mapApplicationToDiscoveryItem(row, user, versioningStatus);
                if (item != null) {
                    discoveryItems.add(item);
                    addKeywords(row, keywords);
                }
            }

            // Fetch distributions with software categories
            List<Object[]> distributionResults = fetchSoftwareDistributions(em, query, statuses, user, versioningStatus);
            for (Object[] row : distributionResults) {
                DiscoveryItem item = mapDistributionToDiscoveryItem(row, user, versioningStatus);
                if (item != null) {
                    discoveryItems.add(item);
                }
            }

            SearchResponse response = buildSearchResponse(discoveryItems, keywords);

            LOGGER.info("Software search completed (SQL) in {} ms with {} items",
                    (System.nanoTime() - startTime) / 1_000_000, discoveryItems.size());

            return response;

        } catch (Exception e) {
            LOGGER.error("Software search failed", e);
            throw new RuntimeException("Software search failed", e);
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    private static List<Object[]> fetchSoftwareSourceCodes(EntityManager em, String query, List<String> statuses,
                                                            User user, String versioningStatus) {

        String publishedOrNot = statuses.contains("PUBLISHED") ? "PUBLISHED" : "";
        if(statuses.contains("PUBLISHED")) statuses.remove("PUBLISHED");

        StringBuilder sql = new StringBuilder();

        sql.append("WITH source_code_base AS ( ");
        sql.append("  SELECT ss.instance_id, ss.meta_id, ss.uid, ss.name, ss.description, ");
        sql.append("         ss.downloadurl, ss.coderepository, ss.keywords, ");
        sql.append("         v.status AS versioning_status, v.change_timestamp, v.editor_id ");
        sql.append("  FROM metadata_catalogue.softwaresourcecode ss ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON ss.version_id = v.version_id ");
        sql.append("  WHERE v.status IN ('"+publishedOrNot+"')");

        if (user != null && !user.getIsAdmin()  && versioningStatus != null) {
            sql.append(" OR (v.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("'").append(statuses.get(i)).append("'");
            }
            sql.append(")  AND v.editor_id = ").append(user.getAuthIdentifier()).append(")");
        }
        sql.append("), ");

        // Categories
        sql.append("source_code_categories AS ( ");
        sql.append("  SELECT scc.softwaresourcecode_instance_id AS instance_id, ");
        sql.append("         JSONB_AGG(DISTINCT c.uid) AS categories ");
        sql.append("  FROM metadata_catalogue.softwaresourcecode_category scc ");
        sql.append("  JOIN metadata_catalogue.category c ON scc.category_instance_id = c.instance_id ");
        sql.append("  WHERE scc.softwaresourcecode_instance_id IN (SELECT instance_id FROM source_code_base) ");
        sql.append("  GROUP BY scc.softwaresourcecode_instance_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  scb.instance_id, scb.meta_id, scb.uid, scb.name, scb.description, ");
        sql.append("  scb.downloadurl, scb.coderepository, scb.keywords, ");
        sql.append("  scb.versioning_status, scb.change_timestamp, scb.editor_id, ");
        sql.append("  COALESCE(CAST(scc.categories AS text), '[]') AS categories ");
        sql.append("FROM source_code_base scb ");
        sql.append("LEFT JOIN source_code_categories scc ON scb.instance_id = scc.instance_id ");

        // Apply free text query filter
        if (query != null && !query.trim().isEmpty()) {
            sql.append("WHERE (scb.name ILIKE '%").append(query.trim()).append("%' ");
            sql.append("    OR scb.description ILIKE '%").append(query.trim()).append("%') ");
        }

        sql.append("ORDER BY scb.instance_id ");

        Query nativeQuery = em.createNativeQuery(sql.toString());

        @SuppressWarnings("unchecked")
        List<Object[]> results = nativeQuery.getResultList();

        return results;
    }

    private static List<Object[]> fetchSoftwareApplications(EntityManager em, String query, List<String> statuses,
                                                             User user, String versioningStatus) {

        String publishedOrNot = statuses.contains("PUBLISHED") ? "PUBLISHED" : "";
        if(statuses.contains("PUBLISHED")) statuses.remove("PUBLISHED");

        StringBuilder sql = new StringBuilder();

        sql.append("WITH application_base AS ( ");
        sql.append("  SELECT sa.instance_id, sa.meta_id, sa.uid, sa.name, sa.description, ");
        sql.append("         sa.downloadurl, sa.mainentityofpage, sa.keywords, ");
        sql.append("         v.status AS versioning_status, v.change_timestamp, v.editor_id ");
        sql.append("  FROM metadata_catalogue.softwareapplication sa ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON sa.version_id = v.version_id ");
        sql.append("  WHERE v.status IN ('"+publishedOrNot+"')");

        if (user != null && !user.getIsAdmin()  && versioningStatus != null) {
            sql.append(" OR (v.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("'").append(statuses.get(i)).append("'");
            }
            sql.append(")  AND v.editor_id = ").append(user.getAuthIdentifier()).append(")");
        }
        sql.append("), ");

        // Categories
        sql.append("application_categories AS ( ");
        sql.append("  SELECT sac.softwareapplication_instance_id AS instance_id, ");
        sql.append("         JSONB_AGG(DISTINCT c.uid) AS categories ");
        sql.append("  FROM metadata_catalogue.softwareapplication_category sac ");
        sql.append("  JOIN metadata_catalogue.category c ON sac.category_instance_id = c.instance_id ");
        sql.append("  WHERE sac.softwareapplication_instance_id IN (SELECT instance_id FROM application_base) ");
        sql.append("  GROUP BY sac.softwareapplication_instance_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  ab.instance_id, ab.meta_id, ab.uid, ab.name, ab.description, ");
        sql.append("  ab.downloadurl, ab.mainentityofpage, ab.keywords, ");
        sql.append("  ab.versioning_status, ab.change_timestamp, ab.editor_id, ");
        sql.append("  COALESCE(CAST(ac.categories AS text), '[]') AS categories ");
        sql.append("FROM application_base ab ");
        sql.append("LEFT JOIN application_categories ac ON ab.instance_id = ac.instance_id ");

        // Apply free text query filter
        if (query != null && !query.trim().isEmpty()) {
            sql.append("WHERE (ab.name ILIKE '%").append(query.trim()).append("%' ");
            sql.append("    OR ab.description ILIKE '%").append(query.trim()).append("%') ");
        }

        sql.append("ORDER BY ab.instance_id ");

        Query nativeQuery = em.createNativeQuery(sql.toString());

        @SuppressWarnings("unchecked")
        List<Object[]> results = nativeQuery.getResultList();

        return results;
    }

    private static List<Object[]> fetchSoftwareDistributions(EntityManager em, String query, List<String> statuses,
                                                              User user, String versioningStatus) {

        String publishedOrNot = statuses.contains("PUBLISHED") ? "PUBLISHED" : "";
        if(statuses.contains("PUBLISHED")) statuses.remove("PUBLISHED");


        StringBuilder sql = new StringBuilder();

        // Build status list for SQL
        StringBuilder statusList = new StringBuilder();
        for (int i = 0; i < statuses.size(); i++) {
            if (i > 0) statusList.append(", ");
            statusList.append("'").append(statuses.get(i)).append("'");
        }
        String statusSql = statusList.toString();

        sql.append("WITH software_distributions AS ( ");
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
        sql.append("  WHERE v.status IN ('"+publishedOrNot+"') ");
        sql.append("    AND tc.uid = 'category:facets/software-theme' ");
        sql.append("    AND vdp.status IN ('"+publishedOrNot+"') ");

        if (user != null && !user.getIsAdmin()  && versioningStatus != null) {
            sql.append(" OR (v.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("'").append(statuses.get(i)).append("'");
            }
            sql.append(")  AND v.editor_id = ").append(user.getAuthIdentifier()).append(")");

            sql.append(" OR (vdp.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("'").append(statuses.get(i)).append("'");
            }
            sql.append(")  AND vdp.editor_id = ").append(user.getAuthIdentifier()).append(")");
        }

        sql.append("), ");

        // Titles
        sql.append("dist_titles AS ( ");
        sql.append("  SELECT dt.distribution_instance_id, STRING_AGG(dt.title, ';' ORDER BY dt.lang) AS title ");
        sql.append("  FROM metadata_catalogue.distribution_title dt ");
        sql.append("  WHERE dt.distribution_instance_id IN (SELECT instance_id FROM software_distributions) ");
        sql.append("  GROUP BY dt.distribution_instance_id ");
        sql.append("), ");

        // Descriptions
        sql.append("dist_descriptions AS ( ");
        sql.append("  SELECT dd.distribution_instance_id, STRING_AGG(dd.description, ';' ORDER BY dd.lang) AS description ");
        sql.append("  FROM metadata_catalogue.distribution_description dd ");
        sql.append("  WHERE dd.distribution_instance_id IN (SELECT instance_id FROM software_distributions) ");
        sql.append("  GROUP BY dd.distribution_instance_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  sd.instance_id, sd.meta_id, sd.uid, sd.original_format, ");
        sql.append("  sd.versioning_status, sd.change_timestamp, sd.editor_id, sd.category_uid, ");
        sql.append("  COALESCE(dt.title, '') AS title, ");
        sql.append("  COALESCE(dd.description, '') AS description ");
        sql.append("FROM software_distributions sd ");
        sql.append("LEFT JOIN dist_titles dt ON sd.instance_id = dt.distribution_instance_id ");
        sql.append("LEFT JOIN dist_descriptions dd ON sd.instance_id = dd.distribution_instance_id ");

        // Apply free text query filter
        if (query != null && !query.trim().isEmpty()) {
            sql.append("WHERE (COALESCE(dt.title, '') ILIKE '%").append(query.trim()).append("%' ");
            sql.append("    OR COALESCE(dd.description, '') ILIKE '%").append(query.trim()).append("%') ");
        }

        Query nativeQuery = em.createNativeQuery(sql.toString());

        @SuppressWarnings("unchecked")
        List<Object[]> results = nativeQuery.getResultList();

        return results;
    }

    private static DiscoveryItem mapSourceCodeToDiscoveryItem(Object[] row, User user, String versioningStatus) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String name = (String) row[i++];
        String description = (String) row[i++];
        String downloadUrl = (String) row[i++];
        String codeRepository = (String) row[i++];
        String keywords = (String) row[i++];
        String versioningStatusValue = (String) row[i++];
        Timestamp changeTimestamp = (Timestamp) row[i++];
        String editorId = (String) row[i++];
        String categoriesJson = (String) row[i++];

        // Skip if no category
        List<String> categoryList = parseCategoryList(categoriesJson);
        if (categoryList.isEmpty()) {
            LOGGER.debug("Software source code {} doesn't have a category set", uid);
            return null;
        }

        // Build available formats
        List<AvailableFormat> formats = createFormatsFromUrl(
                downloadUrl != null ? downloadUrl : codeRepository);

        DiscoveryItemBuilder builder = new DiscoveryItemBuilder(
                instanceId,
                EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId,
                null)
                .uid(uid)
                .title(name)
                .description(description)
                .sha256id(DigestUtils.sha256Hex(uid))
                .availableFormats(formats)
                .categories(categoryList);

        if (user != null && versioningStatus != null) {
            builder.versioningStatus(versioningStatusValue)
                    .editorId(editorId);
            User editor = DatabaseConnections.retrieveUserMap().get(editorId);
            if (editor != null) {
                builder.editorFullName(editor.getFirstName() + " " + editor.getLastName());
            }
            if (changeTimestamp != null) {
                builder.changeDate(changeTimestamp.toLocalDateTime());
            }
        }

        return builder.build();
    }

    private static DiscoveryItem mapApplicationToDiscoveryItem(Object[] row, User user, String versioningStatus) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String name = (String) row[i++];
        String description = (String) row[i++];
        String downloadUrl = (String) row[i++];
        String mainEntityOfPage = (String) row[i++];
        String keywords = (String) row[i++];
        String versioningStatusValue = (String) row[i++];
        Timestamp changeTimestamp = (Timestamp) row[i++];
        String editorId = (String) row[i++];
        String categoriesJson = (String) row[i++];

        // Skip if no category
        List<String> categoryList = parseCategoryList(categoriesJson);
        if (categoryList.isEmpty()) {
            LOGGER.debug("Software application {} doesn't have a category set", uid);
            return null;
        }

        // Build available formats
        List<AvailableFormat> formats = createFormatsFromUrl(
                downloadUrl != null ? downloadUrl : mainEntityOfPage);

        DiscoveryItemBuilder builder = new DiscoveryItemBuilder(
                instanceId,
                EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId,
                null)
                .uid(uid)
                .title(name)
                .description(description)
                .sha256id(DigestUtils.sha256Hex(uid))
                .availableFormats(formats)
                .categories(categoryList);

        if (user != null && versioningStatus != null) {
            builder.versioningStatus(versioningStatusValue)
                    .editorId(editorId);
            if (changeTimestamp != null) {
                builder.changeDate(changeTimestamp.toLocalDateTime());
            }
        }

        return builder.build();
    }

    private static DiscoveryItem mapDistributionToDiscoveryItem(Object[] row, User user, String versioningStatus) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String originalFormat = (String) row[i++];
        String versioningStatusValue = (String) row[i++];
        Timestamp changeTimestamp = (Timestamp) row[i++];
        String editorId = (String) row[i++];
        String categoryUid = (String) row[i++];
        String title = (String) row[i++];
        String description = (String) row[i++];

        // Build available formats (simplified for distributions)
        List<AvailableFormat> formats = null;
        if (originalFormat != null) {
            int lastSlash = originalFormat.lastIndexOf('/');
            String format = lastSlash >= 0 ? originalFormat.substring(lastSlash + 1) : originalFormat;
            formats = List.of(new AvailableFormat.AvailableFormatBuilder()
                    .originalFormat(format)
                    .format(format)
                    .href(EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId)
                    .label(format.toUpperCase())
                    .type(AvailableFormatType.ORIGINAL)
                    .build());
        }

        DiscoveryItemBuilder builder = new DiscoveryItemBuilder(
                instanceId,
                EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId,
                null)
                .uid(uid)
                .metaId(metaId)
                .title(title)
                .description(description)
                .availableFormats(formats)
                .categories(Arrays.asList(categoryUid));

        if (user != null && versioningStatus != null) {
            builder.versioningStatus(versioningStatusValue)
                    .editorId(editorId);
            if (changeTimestamp != null) {
                builder.changeDate(changeTimestamp.toLocalDateTime());
            }
        }

        return builder.build();
    }

    private static List<String> parseCategoryList(String categoriesJson) {
        List<String> categoryList = new ArrayList<>();

        if (isEmptyJson(categoriesJson)) {
            return categoryList;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(categoriesJson);
            for (JsonNode node : arrayNode) {
                if (!node.isNull()) {
                    String uid = node.asText();
                    if (uid != null && uid.contains("category:")) {
                        categoryList.add(uid);
                    }
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse categories: {}", e.getMessage());
        }

        return categoryList;
    }

    private static void addKeywords(Object[] row, Set<String> keywords) {
        // Keywords are at index 7 for both source codes and applications
        if (row.length > 7 && row[7] != null) {
            String keywordsStr = (String) row[7];
            if (keywordsStr != null && !keywordsStr.isEmpty()) {
                Arrays.stream(keywordsStr.split(",\t"))
                        .map(String::toLowerCase)
                        .map(String::trim)
                        .filter(k -> !k.isEmpty())
                        .forEach(keywords::add);
            }
        }
    }

    private static List<AvailableFormat> createFormatsFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        String format = extractFormatFromUrl(url);
        if (format != null) {
            format = format.toUpperCase();
            return List.of(new AvailableFormat.AvailableFormatBuilder()
                    .originalFormat(format)
                    .format(format)
                    .href(url)
                    .label(format)
                    .type(AvailableFormatType.ORIGINAL)
                    .build());
        }
        return null;
    }

    private static String extractFormatFromUrl(String url) {
        Matcher matcher = FORMAT_PATTERN.matcher(url);
        String format = null;
        // Get the last match
        while (matcher.find()) {
            format = matcher.group(1);
        }
        return format;
    }

    private static SearchResponse buildSearchResponse(Set<DiscoveryItem> discoveryItems, Set<String> keywords) {
        Node results = new Node("results");
        var facets = FacetsGeneration.generateResponseUsingCategories(discoveryItems, Facets.Type.SOFTWARE).getFacets();
        results.addChild(facets);

        List<String> keywordsCollection = keywords.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isEmpty())
                .sorted()
                .collect(Collectors.toList());

        NodeFilters keywordsNodes = new NodeFilters("keywords");
        keywordsCollection.forEach(keyword -> {
            NodeFilters node = new NodeFilters(keyword);
            node.setId(Base64.getEncoder().encodeToString(keyword.getBytes()));
            keywordsNodes.addChild(node);
        });

        ArrayList<NodeFilters> filters = new ArrayList<>();
        filters.add(keywordsNodes);

        return new SearchResponse(results, filters);
    }

    private static boolean isEmptyJson(String json) {
        return json == null || json.isEmpty() || "[]".equals(json) || "null".equals(json);
    }
}
