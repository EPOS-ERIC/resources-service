package org.epos.api.core;

import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.AvailableFormatConverted;
import org.epos.api.beans.Plugin;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.routines.DatabaseConnections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared utility class for building available formats.
 * This consolidates the format construction logic used by:
 * - AvailableFormatsGenerationSQL
 * - DistributionSearchGenerationSQL
 * - FacilitySearchGenerationSQL
 */
public class AvailableFormatsBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AvailableFormatsBuilder.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // API path constants
    private static final String API_PATH_EXECUTE = EnvironmentVariables.API_CONTEXT + "/execute/";
    private static final String API_PATH_EXECUTE_OGC = EnvironmentVariables.API_CONTEXT + "/ogcexecute/";
    private static final String API_FORMAT = "?format=";
    private static final String API_INPUT_FORMAT = "inputFormat=";
    private static final String API_PLUGIN_ID = "pluginId=";

    // Compiled pattern for GeoJSON detection
    private static final Pattern GEOJSON_PATTERN = Pattern.compile(".*geo(?:json|\\+json|-json).*", Pattern.CASE_INSENSITIVE);

    /**
     * Input data class for building available formats.
     * This allows the caller to provide pre-fetched data without requiring additional SQL queries.
     */
    public static class FormatInput {
        private final String instanceId;
        private String[] downloadUrls;
        private String originalFormat;
        private String[] operationReturns;
        private String operationTemplate;
        private String availableFormatsJson;  // JSON array of encoding format mappings
        private String serviceValues;         // Service type indicators (e.g., "WMS,WFS")
        private boolean hasAccessService;

        public FormatInput(String instanceId) {
            this.instanceId = instanceId;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public FormatInput downloadUrls(String[] downloadUrls) {
            this.downloadUrls = downloadUrls;
            return this;
        }

        public FormatInput originalFormat(String originalFormat) {
            this.originalFormat = originalFormat;
            return this;
        }

        public FormatInput operationReturns(String[] operationReturns) {
            this.operationReturns = operationReturns;
            return this;
        }

        public FormatInput operationTemplate(String operationTemplate) {
            this.operationTemplate = operationTemplate;
            return this;
        }

        public FormatInput availableFormatsJson(String availableFormatsJson) {
            this.availableFormatsJson = availableFormatsJson;
            return this;
        }

        public FormatInput serviceValues(String serviceValues) {
            this.serviceValues = serviceValues;
            return this;
        }

        public FormatInput hasAccessService(boolean hasAccessService) {
            this.hasAccessService = hasAccessService;
            return this;
        }

        public String[] getDownloadUrls() {
            return downloadUrls;
        }

        public String getOriginalFormat() {
            return originalFormat;
        }

        public String[] getOperationReturns() {
            return operationReturns;
        }

        public String getOperationTemplate() {
            return operationTemplate;
        }

        public String getAvailableFormatsJson() {
            return availableFormatsJson;
        }

        public String getServiceValues() {
            return serviceValues;
        }

        public boolean hasAccessService() {
            return hasAccessService;
        }
    }

    /**
     * Build available formats from pre-fetched data.
     * This is the main entry point for Search classes that already have the data from a batch query.
     * 
     * Logic aligned with JPA AvailableFormatsGeneration:
     * 1. DOWNLOADABLE FILE: If has download URLs, no access service, and has format -> return download format
     * 2. PLUGINS: Process plugins FIRST (regardless of template existence)
     * 3. MAPPINGS: Process encoding formats ONLY if template exists
     * 4. RETURNS: Fallback to operation returns if no formats found (regardless of template)
     *
     * @param input The format input data
     * @return List of available formats
     */
    public static List<AvailableFormat> build(FormatInput input) {
        List<AvailableFormat> formats = new ArrayList<>(8);

        // DOWNLOADABLE FILE case - no access service and has download URLs
        // Matches JPA: distribution.getDownloadURL() != null && distribution.getAccessService() == null
        if (input.downloadUrls != null && input.downloadUrls.length > 0 
                && !input.hasAccessService && input.originalFormat != null) {
            String format = extractFormatFromUri(input.originalFormat);
            formats.add(createFormat(format, format, String.join(",", input.downloadUrls),
                    format.toUpperCase(), AvailableFormatType.ORIGINAL));
            return formats;
        }

        // Process plugins FIRST, regardless of template existence
        // Matches JPA: plugins are processed before mapping check (lines 143-148 of AvailableFormatsGeneration)
        addPluginFormats(input.instanceId, formats);

        // Add encoding-based formats from operation mappings ONLY if template exists
        // Matches JPA: operation.getMapping() != null && ... && operation.getTemplate() != null (line 151)
        if (input.operationTemplate != null && !input.operationTemplate.isEmpty()) {
            if (!isEmptyJson(input.availableFormatsJson)) {
                addEncodingFormats(input.instanceId, input.availableFormatsJson, 
                        input.operationTemplate, input.serviceValues, formats);
            }
        }

        // Fallback to operation returns if no formats found yet (regardless of template)
        // Matches JPA: operation.getReturns() != null && formats.isEmpty() (line 167)
        if (formats.isEmpty() && input.operationReturns != null) {
            for (String ret : input.operationReturns) {
                if (ret != null && !ret.isEmpty()) {
                    addReturnFormat(input.instanceId, ret, formats);
                }
            }
        }

        return formats;
    }

    /**
     * Build available formats using mappings JSON (for Search classes that fetch mappings as a JSON aggregate).
     * This variant handles the JSON format used by DistributionSearchGenerationSQL.
     *
     * @param instanceId The distribution instance ID
     * @param downloadUrls Download URLs (may be null)
     * @param originalFormat Original format string (may be null)
     * @param operationReturns Array of operation return types (may be null)
     * @param availableFormatsJson JSON array of encoding format data
     * @param serviceValues Service type indicators
     * @return List of available formats
     */
    public static List<AvailableFormat> buildFromSearchData(
            String instanceId, String[] downloadUrls, String originalFormat,
            String[] operationReturns, String availableFormatsJson, String serviceValues) {

        List<AvailableFormat> formats = new ArrayList<>(8);

        // Add download URL format if available
        if (downloadUrls != null && downloadUrls.length > 0 && originalFormat != null) {
            String format = extractFormatFromUri(originalFormat);
            formats.add(createFormat(format, format, String.join(",", downloadUrls),
                    format.toUpperCase(), AvailableFormatType.ORIGINAL));
        }

        // Add plugin-based converted formats
        addPluginFormats(instanceId, formats);

        // Add encoding-based formats from operation mappings
        addEncodingFormatsFromSearchJson(instanceId, availableFormatsJson, serviceValues, formats);

        // Fallback to operation returns if no encoding formats found
        if (formats.isEmpty() && operationReturns != null) {
            for (String ret : operationReturns) {
                if (ret != null) {
                    addReturnFormat(instanceId, ret, formats);
                }
            }
        }

        return formats;
    }

    /**
     * Adds converted formats from registered plugins.
     */
    public static void addPluginFormats(String instanceId, List<AvailableFormat> formats) {
        try {
            Map<String, List<Plugin.Relations>> plugins = DatabaseConnections.getInstance().getPlugins();
            if (plugins == null) {
                LOGGER.debug("Plugins map is null for instance {}", instanceId);
                return;
            }
            
            List<Plugin.Relations> relations = plugins.get(instanceId);
            if (relations == null || relations.isEmpty()) {
                LOGGER.debug("No plugin relations found for instance {}", instanceId);
                return;
            }

            LOGGER.debug("Found {} plugin relations for instance {}", relations.size(), instanceId);
            
            for (Plugin.Relations relation : relations) {
                String outputFormat = relation.getOutputFormat();
                String inputFormat = relation.getInputFormat();
                String pluginId = relation.getPluginId();

                if (outputFormat == null || inputFormat == null || pluginId == null) {
                    LOGGER.debug("Skipping plugin relation with null values: output={}, input={}, pluginId={}", 
                            outputFormat, inputFormat, pluginId);
                    continue;
                }

                String label;
                // Match exact formats only - aligned with JPA AvailableFormatsGeneration behavior
                if (outputFormat.equals("application/epos.geo+json")
                        || outputFormat.equals("application/epos.table.geo+json")
                        || outputFormat.equals("application/epos.map.geo+json")) {
                    label = "GEOJSON";
                } else if (outputFormat.equals("application/epos.graph.covjson")
                        || outputFormat.equals("application/epos.covjson")) {
                    label = "COVJSON";
                } else {
                    LOGGER.debug("Skipping plugin with unsupported output format: {}", outputFormat);
                    continue;
                }

                LOGGER.debug("Adding converted format: pluginId={}, inputFormat={}, outputFormat={}, label={}", 
                        pluginId, inputFormat, outputFormat, label);
                formats.add(createConvertedFormat(inputFormat, pluginId, inputFormat, outputFormat,
                        buildHrefConverted(instanceId, outputFormat, inputFormat, pluginId), label));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to process plugins for instance {}: {}", instanceId, e.getMessage(), e);
        }
    }

    /**
     * Add encoding formats from mappings data (used by AvailableFormatsGenerationSQL).
     * The mappingsJson format is: [{"variable": "...", "property": "...", "defaultvalue": "...", "paramvalues": [...]}]
     */
    public static void addEncodingFormats(String instanceId, String mappingsJson, 
                                          String operationTemplate, String serviceValues,
                                          List<AvailableFormat> formats) {
        if (isEmptyJson(mappingsJson)) {
            return;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(mappingsJson);

            // First pass: collect all mappings for service type detection
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

            String templateLower = operationTemplate != null ? operationTemplate.toLowerCase() : "";

            // Second pass: process encodingFormat mappings
            for (MappingInfo map : allMappings) {
                if (map.property == null || !map.property.contains("encodingFormat")) {
                    continue;
                }

                for (String pv : map.paramValues) {
                    processEncodingFormatValue(instanceId, pv, templateLower, allMappings, map, serviceValues, formats);
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse mappings JSON: {}", e.getMessage());
        }
    }

    /**
     * Add encoding formats from the search JSON format.
     * The JSON format is: [{"format": "...", "template": "...", "variable": "...", "default_value": "..."}]
     */
    private static void addEncodingFormatsFromSearchJson(String instanceId, String availableFormatsJson,
                                                          String serviceValues, List<AvailableFormat> formats) {
        if (isEmptyJson(availableFormatsJson)) {
            return;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(availableFormatsJson);
            for (JsonNode formatNode : arrayNode) {
                String paramValue = getTextOrNull(formatNode, "format");
                if (paramValue == null) {
                    continue;
                }

                String template = getTextOrNull(formatNode, "template");
                String variable = getTextOrNull(formatNode, "variable");
                String defaultValue = getTextOrNull(formatNode, "default_value");

                String templateLower = template != null ? template.toLowerCase() : "";
                String variableLower = variable != null ? variable.toLowerCase() : "";
                String defaultValueLower = defaultValue != null ? defaultValue.toLowerCase() : "";

                // Detect OGC service types
                boolean isWMS = detectServiceType(templateLower, variableLower, paramValue, defaultValueLower, serviceValues, "wms");
                boolean isWMTS = detectServiceType(templateLower, variableLower, paramValue, defaultValueLower, serviceValues, "wmts");
                boolean isWFS = detectServiceType(templateLower, variableLower, paramValue, defaultValueLower, serviceValues, "wfs");

                if (paramValue.startsWith("image/")) {
                    if (isWMS) {
                        formats.add(createOgcFormat(instanceId, paramValue, "application/vnd.ogc.wms_xml", "WMS"));
                    } else if (isWMTS) {
                        formats.add(createOgcFormat(instanceId, paramValue, "application/vnd.ogc.wmts_xml", "WMTS"));
                    }
                } else if ("json".equals(paramValue) && isWFS) {
                    formats.add(createGeoJsonFormat(instanceId, paramValue, "json"));
                } else if (paramValue.contains("geo%2Bjson") || GEOJSON_PATTERN.matcher(paramValue).matches()) {
                    formats.add(createGeoJsonFormat(instanceId, paramValue, paramValue));
                } else {
                    formats.add(createFormat(paramValue, paramValue, buildHref(instanceId, paramValue),
                            paramValue.toUpperCase(), AvailableFormatType.ORIGINAL));
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse encoding formats: {}", e.getMessage());
        }
    }

    /**
     * Process a single encoding format value and add appropriate formats.
     */
    private static void processEncodingFormatValue(String instanceId, String pv, String templateLower,
                                                    List<MappingInfo> allMappings, MappingInfo currentMap,
                                                    String serviceValues, List<AvailableFormat> formats) {
        // OGC Format Check - Image formats
        if (pv.startsWith("image/")) {
            if (templateLower.contains("service=wms") || containsServiceInMappings(allMappings, "WMS", currentMap)) {
                formats.add(createOgcFormat(instanceId, pv, "application/vnd.ogc.wms_xml", "WMS"));
            } else if (templateLower.contains("service=wmts") || containsServiceInMappings(allMappings, "WMTS", currentMap)) {
                formats.add(createOgcFormat(instanceId, pv, "application/vnd.ogc.wmts_xml", "WMTS"));
            }
        }
        // WFS with JSON
        else if (pv.equals("json") && (templateLower.contains("service=wfs") 
                || containsServiceInMappings(allMappings, "WFS", currentMap)
                || (serviceValues != null && serviceValues.contains("WFS")))) {
            formats.add(createGeoJsonFormat(instanceId, pv, "json"));
        }
        // GeoJSON variants
        else if (pv.contains("geo%2Bjson") || GEOJSON_PATTERN.matcher(pv).matches()) {
            formats.add(createGeoJsonFormat(instanceId, pv, pv));
        }
        // Other formats
        else {
            formats.add(createFormat(pv, pv, buildHref(instanceId, pv),
                    pv.toUpperCase(), AvailableFormatType.ORIGINAL));
        }
    }

    /**
     * Adds a format entry based on operation return type.
     */
    public static void addReturnFormat(String instanceId, String returnType, List<AvailableFormat> formats) {
        if (returnType.contains("geojson") || returnType.contains("geo+json")) {
            formats.add(createFormat(returnType, "application/epos.geo+json",
                    buildHref(instanceId, returnType), "GEOJSON", AvailableFormatType.ORIGINAL));
        } else {
            formats.add(createFormat(returnType, returnType, buildHref(instanceId, returnType),
                    returnType.toUpperCase(), AvailableFormatType.ORIGINAL));
        }
    }

    /**
     * Parse operation returns from JSON array string.
     */
    public static String[] parseOperationReturns(String operationReturnsJson) {
        if (isEmptyJson(operationReturnsJson)) {
            return null;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(operationReturnsJson);
            if (!arrayNode.isArray() || arrayNode.isEmpty()) {
                return null;
            }

            List<String> returns = new ArrayList<>();
            for (JsonNode node : arrayNode) {
                String value = node.asText(null);
                if (value != null && !value.isEmpty()) {
                    returns.add(value);
                }
            }
            return returns.isEmpty() ? null : returns.toArray(new String[0]);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse operation returns JSON: {}", e.getMessage());
            return null;
        }
    }

    // ===== Helper methods =====

    private static class MappingInfo {
        String variable;
        String property;
        String defaultValue;
        List<String> paramValues;
    }

    private static boolean containsServiceInMappings(List<MappingInfo> mappings, String service, MappingInfo currentMap) {
        // Added Objects::nonNull filter to match JPA AvailableFormatsGeneration behavior
        return mappings.stream()
                .filter(Objects::nonNull)
                .anyMatch(e -> e.variable != null
                        && e.variable.equalsIgnoreCase("service")
                        && ((currentMap.paramValues != null && currentMap.paramValues.contains(service))
                        || (e.defaultValue != null && e.defaultValue.toLowerCase().contains(service.toLowerCase()))));
    }

    private static boolean detectServiceType(String templateLower, String variableLower,
                                              String paramValue, String defaultValueLower, 
                                              String serviceValues, String serviceType) {
        String servicePattern = "service=" + serviceType;
        String serviceUpper = serviceType.toUpperCase();

        return templateLower.contains(servicePattern)
                || ("service".equals(variableLower) && (paramValue.contains(serviceUpper) || defaultValueLower.contains(serviceType)))
                || (serviceValues != null && serviceValues.contains(serviceUpper));
    }

    private static String extractFormatFromUri(String format) {
        if (format == null) return "";
        int lastSlash = format.lastIndexOf('/');
        return lastSlash >= 0 ? format.substring(lastSlash + 1) : format;
    }

    public static AvailableFormat createFormat(String originalFormat, String format, String href,
                                                String label, AvailableFormatType type) {
        return new AvailableFormat.AvailableFormatBuilder()
                .originalFormat(originalFormat)
                .format(format)
                .href(href)
                .label(label)
                .type(type)
                .build();
    }

    public static AvailableFormat createConvertedFormat(String inputFormat, String pluginId,
                                                         String originalFormat, String format, 
                                                         String href, String label) {
        return new AvailableFormatConverted.AvailableFormatConvertedBuilder()
                .inputFormat(inputFormat)
                .pluginId(pluginId)
                .originalFormat(originalFormat)
                .format(format)
                .href(href)
                .label(label)
                .type(AvailableFormatType.CONVERTED)
                .build();
    }

    public static AvailableFormat createOgcFormat(String instanceId, String original, String format, String label) {
        return createFormat(original, format, buildHrefOgc(instanceId), label, AvailableFormatType.ORIGINAL);
    }

    public static AvailableFormat createGeoJsonFormat(String instanceId, String original, String formatParam) {
        return createFormat(original, "application/epos.geo+json", buildHref(instanceId, formatParam),
                "GEOJSON (" + original + ")", AvailableFormatType.ORIGINAL);
    }

    public static String buildHref(String instanceId, String format) {
        return EnvironmentVariables.API_HOST + API_PATH_EXECUTE + instanceId + API_FORMAT + format;
    }

    public static String buildHrefConverted(String instanceId, String outputFormat, String inputFormat, String pluginId) {
        return buildHref(instanceId, outputFormat) + "&" + API_INPUT_FORMAT + inputFormat + "&" + API_PLUGIN_ID + pluginId;
    }

    public static String buildHrefOgc(String instanceId) {
        return EnvironmentVariables.API_HOST + API_PATH_EXECUTE_OGC + instanceId;
    }

    public static boolean isEmptyJson(String json) {
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
