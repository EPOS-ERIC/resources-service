package org.epos.api.core;

import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.AvailableFormatConverted;
import org.epos.api.beans.Plugin;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.routines.DatabaseConnections;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for generating available formats.
 * This replaces the JPA-based AvailableFormatsGeneration for improved performance
 * when used within SQL-based distribution generation.
 */
public class AvailableFormatsGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(AvailableFormatsGenerationSQL.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String API_PATH_EXECUTE = EnvironmentVariables.API_CONTEXT + "/execute/";
    private static final String API_PATH_EXECUTE_OGC = EnvironmentVariables.API_CONTEXT + "/ogcexecute/";
    private static final String API_FORMAT = "?format=";
    private static final String API_INPUT_FORMAT = "inputFormat=";
    private static final String API_PLUGIN_ID = "pluginId=";

    /**
     * Generate available formats for a distribution using SQL queries.
     *
     * @param distributionInstanceId The distribution instance ID
     * @return List of available formats
     */
    public static List<AvailableFormat> generate(String distributionInstanceId) {
        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();
            return generateWithEntityManager(em, distributionInstanceId);
        } catch (Exception e) {
            LOGGER.error("Failed to generate available formats for distribution: {}", distributionInstanceId, e);
            return new ArrayList<>();
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    /**
     * Generate available formats using an existing EntityManager (for use within transactions).
     */
    public static List<AvailableFormat> generateWithEntityManager(EntityManager em, String distributionInstanceId) {
        List<AvailableFormat> formats = new ArrayList<>();

        // Fetch distribution data
        DistributionData distData = fetchDistributionData(em, distributionInstanceId);
        if (distData == null) {
            return formats;
        }

        // DOWNLOADABLE FILE case
        if (distData.downloadUrls != null && !distData.downloadUrls.isEmpty()
                && !distData.hasAccessService && distData.format != null) {
            String[] uri = distData.format.split("/");
            String format = uri[uri.length - 1];
            formats.add(buildAvailableFormat(format, format, distData.downloadUrls,
                    format.toUpperCase(), AvailableFormatType.ORIGINAL));
            return formats;
        }

        // If no operation, return empty formats
        if (distData.operationTemplate == null) {
            return formats;
        }

        // Process plugins if available
        if (DatabaseConnections.getInstance().getPlugins().containsKey(distributionInstanceId)) {
            for (Plugin.Relations relation : DatabaseConnections.getInstance().getPlugins()
                    .get(distributionInstanceId)) {
                processPluginRelation(relation, distributionInstanceId, formats);
            }
        }

        // Process mappings
        boolean isOgcFormat = false;
        if (distData.mappingsJson != null && !distData.mappingsJson.isEmpty() && !"[]".equals(distData.mappingsJson)) {
            isOgcFormat = processMappings(distData.mappingsJson, distData.operationTemplate, distributionInstanceId, formats);
        }

        // Process returns if no formats found yet
        if (distData.operationReturns != null && formats.isEmpty()) {
            processReturns(distData.operationReturns, distributionInstanceId, formats);
        }

        return formats;
    }

    /**
     * Data class to hold distribution information for format generation.
     */
    private static class DistributionData {
        String downloadUrls;
        boolean hasAccessService;
        String format;
        String operationTemplate;
        String operationReturns;
        String mappingsJson;
    }

    /**
     * Fetch the distribution data needed for format generation.
     */
    private static DistributionData fetchDistributionData(EntityManager em, String distributionInstanceId) {
        StringBuilder sql = new StringBuilder(2048);

        sql.append("WITH dist_base AS ( ");
        sql.append("  SELECT d.instance_id, d.format ");
        sql.append("  FROM metadata_catalogue.distribution d ");
        sql.append("  WHERE d.instance_id = ?1 ");
        sql.append("), ");

        // Download URLs
        sql.append("dist_download AS ( ");
        sql.append("  SELECT de.distribution_instance_id, STRING_AGG(e.value, ',') AS download_urls ");
        sql.append("  FROM metadata_catalogue.distribution_element de ");
        sql.append("  JOIN metadata_catalogue.element e ON de.element_instance_id = e.instance_id ");
        sql.append("  WHERE de.distribution_instance_id = ?1 AND e.type = 'DOWNLOADURL' ");
        sql.append("  GROUP BY de.distribution_instance_id ");
        sql.append("), ");

        // Check if has access service (webservice)
        sql.append("has_access_service AS ( ");
        sql.append("  SELECT wd.distribution_instance_id, TRUE AS has_ws ");
        sql.append("  FROM metadata_catalogue.webservice_distribution wd ");
        sql.append("  WHERE wd.distribution_instance_id = ?1 ");
        sql.append("  LIMIT 1 ");
        sql.append("), ");

        // Operation info
        sql.append("operation_info AS ( ");
        sql.append("  SELECT DISTINCT ON (od.distribution_instance_id) ");
        sql.append("         od.distribution_instance_id, ");
        sql.append("         op.instance_id AS operation_id, ");
        sql.append("         op.template, ");
        sql.append("         op.returns ");
        sql.append("  FROM metadata_catalogue.operation_distribution od ");
        sql.append("  JOIN metadata_catalogue.operation op ON od.operation_instance_id = op.instance_id ");
        sql.append("  WHERE od.distribution_instance_id = ?1 ");
        sql.append("  ORDER BY od.distribution_instance_id, op.instance_id ");
        sql.append("), ");

        // Operation mappings with encodingFormat property
        sql.append("op_mappings AS ( ");
        sql.append("  SELECT oi.distribution_instance_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT( ");
        sql.append("           'variable', m.variable, ");
        sql.append("           'property', m.property, ");
        sql.append("           'defaultvalue', m.defaultvalue, ");
        sql.append("           'paramvalues', (SELECT ARRAY_AGG(e.value) FROM metadata_catalogue.mapping_element me ");
        sql.append("                           JOIN metadata_catalogue.element e ON me.element_instance_id = e.instance_id ");
        sql.append("                           WHERE me.mapping_instance_id = m.instance_id AND e.type = 'PARAMVALUE') ");
        sql.append("         )) AS mappings ");
        sql.append("  FROM operation_info oi ");
        sql.append("  JOIN metadata_catalogue.operation_mapping om ON oi.operation_id = om.operation_instance_id ");
        sql.append("  JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ");
        sql.append("  GROUP BY oi.distribution_instance_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  db.format, ");
        sql.append("  COALESCE(dd.download_urls, '') AS download_urls, ");
        sql.append("  COALESCE(has.has_ws, FALSE) AS has_access_service, ");
        sql.append("  oi.template, ");
        sql.append("  oi.returns, ");
        sql.append("  COALESCE(CAST(opm.mappings AS text), '[]') AS mappings ");
        sql.append("FROM dist_base db ");
        sql.append("LEFT JOIN dist_download dd ON db.instance_id = dd.distribution_instance_id ");
        sql.append("LEFT JOIN has_access_service has ON db.instance_id = has.distribution_instance_id ");
        sql.append("LEFT JOIN operation_info oi ON db.instance_id = oi.distribution_instance_id ");
        sql.append("LEFT JOIN op_mappings opm ON db.instance_id = opm.distribution_instance_id ");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter(1, distributionInstanceId);

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        if (results.isEmpty()) {
            return null;
        }

        Object[] row = results.get(0);
        int i = 0;

        DistributionData data = new DistributionData();
        data.format = (String) row[i++];
        data.downloadUrls = (String) row[i++];
        data.hasAccessService = (Boolean) row[i++];
        data.operationTemplate = (String) row[i++];
        data.operationReturns = (String) row[i++];
        data.mappingsJson = (String) row[i++];

        return data;
    }

    /**
     * Process plugin relations and add converted formats.
     */
    private static void processPluginRelation(Plugin.Relations relation, String distributionInstanceId,
                                              List<AvailableFormat> formats) {
        String outputFormat = relation.getOutputFormat();

        if (outputFormat.equals("application/epos.geo+json")
                || outputFormat.equals("application/epos.table.geo+json")
                || outputFormat.equals("application/epos.map.geo+json")) {
            formats.add(buildAvailableFormatConverted(
                    relation.getInputFormat(),
                    relation.getPluginId(),
                    relation.getInputFormat(),
                    outputFormat,
                    buildHrefConverted(distributionInstanceId, outputFormat, relation.getInputFormat(), relation.getPluginId()),
                    "GEOJSON",
                    AvailableFormatType.CONVERTED));
        } else if (outputFormat.equals("application/epos.graph.covjson")
                || outputFormat.equals("application/epos.covjson")) {
            formats.add(buildAvailableFormatConverted(
                    relation.getInputFormat(),
                    relation.getPluginId(),
                    relation.getInputFormat(),
                    outputFormat,
                    buildHrefConverted(distributionInstanceId, outputFormat, relation.getInputFormat(), relation.getPluginId()),
                    "COVJSON",
                    AvailableFormatType.CONVERTED));
        }
    }

    /**
     * Process mappings to determine available formats.
     */
    private static boolean processMappings(String mappingsJson, String operationTemplate,
                                           String distributionInstanceId, List<AvailableFormat> formats) {
        boolean isOgcFormat = false;

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(mappingsJson);

            // First pass: collect all mappings to check for service type
            List<MappingInfo> allMappings = new ArrayList<>();
            for (JsonNode node : arrayNode) {
                MappingInfo info = new MappingInfo();
                info.variable = getTextOrNull(node, "variable");
                info.property = getTextOrNull(node, "property");
                info.defaultValue = getTextOrNull(node, "defaultvalue");
                info.paramValues = new ArrayList<>();

                if (node.has("paramvalues") && !node.get("paramvalues").isNull()) {
                    for (JsonNode pvNode : node.get("paramvalues")) {
                        if (!pvNode.isNull()) {
                            info.paramValues.add(pvNode.asText());
                        }
                    }
                }
                allMappings.add(info);
            }

            // Second pass: process encodingFormat mappings
            for (MappingInfo map : allMappings) {
                if (map.property == null || !map.property.contains("encodingFormat")) {
                    continue;
                }

                for (String pv : map.paramValues) {
                    // OGC Format Check - Image formats
                    if (pv.startsWith("image/")) {
                        if (operationTemplate.toLowerCase().contains("service=wms")
                                || containsServiceInMappings(allMappings, "WMS", map)) {
                            formats.add(buildAvailableFormat(
                                    pv,
                                    "application/vnd.ogc.wms_xml",
                                    buildHrefOgc(distributionInstanceId),
                                    "WMS",
                                    AvailableFormatType.ORIGINAL));
                            isOgcFormat = true;
                        } else if (operationTemplate.toLowerCase().contains("service=wmts")
                                || containsServiceInMappings(allMappings, "WMTS", map)) {
                            formats.add(buildAvailableFormat(
                                    pv,
                                    "application/vnd.ogc.wmts_xml",
                                    buildHrefOgc(distributionInstanceId),
                                    "WMTS",
                                    AvailableFormatType.ORIGINAL));
                            isOgcFormat = true;
                        }
                    }
                    // WFS with JSON
                    else if (pv.equals("json") && operationTemplate != null
                            && (operationTemplate.toLowerCase().contains("service=wfs")
                            || containsServiceInMappings(allMappings, "WFS", map))) {
                        formats.add(buildAvailableFormat(
                                pv,
                                "application/epos.geo+json",
                                buildHref(distributionInstanceId, "json"),
                                "GEOJSON (" + pv + ")",
                                AvailableFormatType.ORIGINAL));
                    }
                    // GeoJSON variants
                    else if (pv.contains("geo%2Bjson") || pv.toLowerCase().matches(".*geo(?:json|\\+json|-json).*")) {
                        formats.add(buildAvailableFormat(
                                pv,
                                "application/epos.geo+json",
                                buildHref(distributionInstanceId, pv),
                                "GEOJSON (" + pv + ")",
                                AvailableFormatType.ORIGINAL));
                    }
                    // Other formats
                    else {
                        formats.add(buildAvailableFormat(
                                pv,
                                pv,
                                buildHref(distributionInstanceId, pv),
                                pv.toUpperCase(),
                                AvailableFormatType.ORIGINAL));
                    }
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse mappings JSON: {}", e.getMessage());
        }

        return isOgcFormat;
    }

    /**
     * Helper class for mapping information.
     */
    private static class MappingInfo {
        String variable;
        String property;
        String defaultValue;
        List<String> paramValues;
    }

    /**
     * Check if a service exists in the mappings.
     */
    private static boolean containsServiceInMappings(List<MappingInfo> mappings, String service, MappingInfo currentMap) {
        return mappings.stream()
                .anyMatch(e -> e.variable != null
                        && e.variable.equalsIgnoreCase("service")
                        && ((currentMap.paramValues != null && currentMap.paramValues.contains(service))
                        || (e.defaultValue != null && e.defaultValue.toLowerCase().contains(service.toLowerCase()))));
    }

    /**
     * Process operation returns to add formats.
     */
    private static void processReturns(String operationReturns, String distributionInstanceId, List<AvailableFormat> formats) {
        if (operationReturns == null || operationReturns.isEmpty()) {
            return;
        }

        // operationReturns is stored as an array in postgres, parse it
        String[] returns = operationReturns.replace("{", "").replace("}", "").split(",");
        for (String ret : returns) {
            String returnFormat = ret.trim();
            if (returnFormat.isEmpty()) {
                continue;
            }

            if (returnFormat.contains("geojson") || returnFormat.contains("geo+json")) {
                formats.add(buildAvailableFormat(
                        returnFormat,
                        "application/epos.geo+json",
                        buildHref(distributionInstanceId, returnFormat),
                        "GEOJSON",
                        AvailableFormatType.ORIGINAL));
            } else {
                formats.add(buildAvailableFormat(
                        returnFormat,
                        returnFormat,
                        buildHref(distributionInstanceId, returnFormat),
                        returnFormat.toUpperCase(),
                        AvailableFormatType.ORIGINAL));
            }
        }
    }

    /**
     * Helper method to create AvailableFormat objects.
     */
    private static AvailableFormat buildAvailableFormat(String originalFormat, String format, String href,
                                                        String label, AvailableFormatType type) {
        return new AvailableFormat.AvailableFormatBuilder()
                .originalFormat(originalFormat)
                .format(format)
                .href(href)
                .label(label)
                .type(type)
                .build();
    }

    /**
     * Helper method to create AvailableFormatConverted objects.
     */
    private static AvailableFormat buildAvailableFormatConverted(String inputFormat, String pluginId,
                                                                 String originalFormat, String format, String href,
                                                                 String label, AvailableFormatType type) {
        return new AvailableFormatConverted.AvailableFormatConvertedBuilder()
                .inputFormat(inputFormat)
                .pluginId(pluginId)
                .originalFormat(originalFormat)
                .format(format)
                .href(href)
                .label(label)
                .type(type)
                .build();
    }

    /**
     * Build href for regular execution.
     */
    private static String buildHref(String distributionInstanceId, String format) {
        return EnvironmentVariables.API_HOST + API_PATH_EXECUTE + distributionInstanceId + API_FORMAT + format;
    }

    /**
     * Build href for converted format execution.
     */
    private static String buildHrefConverted(String distributionInstanceId, String outputFormat,
                                             String inputFormat, String pluginId) {
        return buildHref(distributionInstanceId, outputFormat) + "&" + API_INPUT_FORMAT + inputFormat + "&" + API_PLUGIN_ID + pluginId;
    }

    /**
     * Build href for OGC execution.
     */
    private static String buildHrefOgc(String distributionInstanceId) {
        return EnvironmentVariables.API_HOST + API_PATH_EXECUTE_OGC + distributionInstanceId;
    }

    private static String getTextOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText(null);
    }
}
