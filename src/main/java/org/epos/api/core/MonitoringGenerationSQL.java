package org.epos.api.core;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.epos.api.beans.MonitoringBean;
import org.epos.api.utility.Utils;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for monitoring data generation.
 * This replaces the JPA-based MonitoringGeneration for improved performance.
 * 
 * Performance improvements:
 * - Single SQL query fetches all needed data using CTEs
 * - No N+1 query problem (original made individual queries per distribution)
 * - All joins done in database instead of Java loops
 */
public class MonitoringGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitoringGenerationSQL.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static List<MonitoringBean> generate() {
        final long startTime = System.nanoTime();
        LOGGER.info("Generating monitoring data (SQL)");

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            List<Object[]> results = fetchMonitoringData(em);

            List<MonitoringBean> monitoringList = new ArrayList<>();

            for (Object[] row : results) {
                MonitoringBean mb = mapRowToMonitoringBean(row);
                if (mb != null && mb.getOriginalURL() != null) {
                    monitoringList.add(mb);
                }
            }

            LOGGER.info("Monitoring data generated (SQL) in {} ms with {} items",
                    (System.nanoTime() - startTime) / 1_000_000, monitoringList.size());

            return monitoringList;

        } catch (Exception e) {
            LOGGER.error("Failed to generate monitoring data", e);
            throw new RuntimeException("Failed to generate monitoring data", e);
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> fetchMonitoringData(EntityManager em) {
        StringBuilder sql = new StringBuilder(8192);

        // CTE 1: Published distributions with basic info
        sql.append("WITH published_distributions AS ( ");
        sql.append("  SELECT d.instance_id, d.meta_id, d.uid ");
        sql.append("  FROM metadata_catalogue.distribution d ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON d.version_id = v.version_id ");
        sql.append("  WHERE v.status = 'PUBLISHED' ");
        sql.append("), ");

        // CTE 2: Distribution titles
        sql.append("dist_titles AS ( ");
        sql.append("  SELECT dt.distribution_instance_id, ");
        sql.append("         (ARRAY_AGG(dt.title ORDER BY dt.lang))[1] AS title ");
        sql.append("  FROM metadata_catalogue.distribution_title dt ");
        sql.append("  WHERE dt.distribution_instance_id IN (SELECT instance_id FROM published_distributions) ");
        sql.append("  GROUP BY dt.distribution_instance_id ");
        sql.append("), ");

        // CTE 3: Operation info (template URL and mappings)
        // Use DISTINCT ON to get only one operation per distribution (avoiding duplicates)
        sql.append("operation_info AS ( ");
        sql.append("  SELECT DISTINCT ON (od.distribution_instance_id) ");
        sql.append("         od.distribution_instance_id, ");
        sql.append("         o.instance_id AS operation_id, ");
        sql.append("         o.template ");
        sql.append("  FROM metadata_catalogue.operation_distribution od ");
        sql.append("  JOIN metadata_catalogue.operation o ON od.operation_instance_id = o.instance_id ");
        sql.append("  WHERE od.distribution_instance_id IN (SELECT instance_id FROM published_distributions) ");
        sql.append("  ORDER BY od.distribution_instance_id, o.instance_id ");
        sql.append("), ");

        // CTE 4: Operation mappings (parameters with default values)
        sql.append("operation_mappings AS ( ");
        sql.append("  SELECT oi.distribution_instance_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT( ");
        sql.append("           'variable', m.variable, ");
        sql.append("           'defaultvalue', m.defaultvalue, ");
        sql.append("           'property', m.property, ");
        sql.append("           'valuepattern', m.valuepattern ");
        sql.append("         )) AS mappings ");
        sql.append("  FROM operation_info oi ");
        sql.append("  JOIN metadata_catalogue.operation_mapping om ON oi.operation_id = om.operation_instance_id ");
        sql.append("  JOIN metadata_catalogue.mapping m ON om.mapping_instance_id = m.instance_id ");
        sql.append("  WHERE m.defaultvalue IS NOT NULL ");
        sql.append("  GROUP BY oi.distribution_instance_id ");
        sql.append("), ");

        // CTE 5: DataProduct info with DDSS identifier
        // Use DISTINCT ON to get only one DDSS-ID per distribution (avoiding duplicates)
        // Only include distributions that have a valid DDSS-ID (matching JPA behavior with continue)
        sql.append("dataproduct_info AS ( ");
        sql.append("  SELECT DISTINCT ON (ddp.distribution_instance_id) ");
        sql.append("         ddp.distribution_instance_id, ");
        sql.append("         i.value AS ddss_id ");
        sql.append("  FROM metadata_catalogue.distribution_dataproduct ddp ");
        sql.append("  JOIN metadata_catalogue.dataproduct dp ON ddp.dataproduct_instance_id = dp.instance_id ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON dp.version_id = v.version_id ");
        sql.append("  JOIN metadata_catalogue.dataproduct_identifier dpi ON dp.instance_id = dpi.dataproduct_instance_id ");
        sql.append("  JOIN metadata_catalogue.identifier i ON dpi.identifier_instance_id = i.instance_id AND i.type = 'DDSS-ID' ");
        sql.append("  WHERE ddp.distribution_instance_id IN (SELECT instance_id FROM published_distributions) ");
        sql.append("    AND v.status = 'PUBLISHED' ");
        sql.append("    AND i.value IS NOT NULL ");
        sql.append("  ORDER BY ddp.distribution_instance_id, i.value ");
        sql.append("), ");

        // CTE 6: WebService contacts
        // Note: webservice_distribution links distributions to webservices (as accessService)
        // Emails are stored in the element table via contactpoint_element join
        sql.append("webservice_contacts AS ( ");
        sql.append("  SELECT wd.distribution_instance_id, ");
        sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ");
        sql.append("           'uid', cp.uid, ");
        sql.append("           'role', cp.role, ");
        sql.append("           'emails', ( ");
        sql.append("             SELECT JSONB_AGG(e.value) ");
        sql.append("             FROM metadata_catalogue.contactpoint_element cpe ");
        sql.append("             JOIN metadata_catalogue.element e ON cpe.element_instance_id = e.instance_id ");
        sql.append("             WHERE cpe.contactpoint_instance_id = cp.instance_id ");
        sql.append("               AND e.type = 'EMAIL' ");
        sql.append("           ) ");
        sql.append("         )) AS contacts ");
        sql.append("  FROM metadata_catalogue.webservice_distribution wd ");
        sql.append("  JOIN metadata_catalogue.webservice ws ON wd.webservice_instance_id = ws.instance_id ");
        sql.append("  JOIN metadata_catalogue.webservice_contactpoint wcp ON ws.instance_id = wcp.webservice_instance_id ");
        sql.append("  JOIN metadata_catalogue.contactpoint cp ON wcp.contactpoint_instance_id = cp.instance_id ");
        sql.append("  WHERE wd.distribution_instance_id IN (SELECT instance_id FROM published_distributions) ");
        sql.append("  GROUP BY wd.distribution_instance_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  pd.instance_id, pd.meta_id, pd.uid, ");
        sql.append("  dt.title, ");
        sql.append("  oi.template, ");
        sql.append("  COALESCE(CAST(om.mappings AS text), '[]') AS mappings, ");
        sql.append("  dpi.ddss_id, ");
        sql.append("  COALESCE(CAST(wc.contacts AS text), '[]') AS contacts ");
        sql.append("FROM published_distributions pd ");
        sql.append("LEFT JOIN dist_titles dt ON pd.instance_id = dt.distribution_instance_id ");
        sql.append("LEFT JOIN operation_info oi ON pd.instance_id = oi.distribution_instance_id ");
        sql.append("LEFT JOIN operation_mappings om ON pd.instance_id = om.distribution_instance_id ");
        sql.append("JOIN dataproduct_info dpi ON pd.instance_id = dpi.distribution_instance_id ");
        sql.append("LEFT JOIN webservice_contacts wc ON pd.instance_id = wc.distribution_instance_id ");
        sql.append("WHERE oi.template IS NOT NULL ");

        Query query = em.createNativeQuery(sql.toString());
        return query.getResultList();
    }

    private static MonitoringBean mapRowToMonitoringBean(Object[] row) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String title = (String) row[i++];
        String template = (String) row[i++];
        String mappingsJson = (String) row[i++];
        String ddssId = (String) row[i++];
        String contactsJson = (String) row[i++];

        MonitoringBean mb = new MonitoringBean();

        // Basic info
        mb.setIdentifier(metaId);
        mb.setId(metaId);
        mb.setUid(uid);
        mb.setName(title);

        // Build URL from template and default parameters
        String compiledUrl = buildUrlFromTemplate(template, mappingsJson);
        if (compiledUrl != null) {
            try {
                compiledUrl = URLGeneration.ogcWFSChecker(compiledUrl);
            } catch (Exception e) {
                LOGGER.debug("WFS Checker issue for {}: {}", instanceId, e.getMessage());
            }
            mb.setOriginalURL(compiledUrl);
        }

        // TCS Group based on DDSS ID
        mb.setTCSGroup(determineTcsGroup(ddssId));

        // Contacts
        parseContacts(mb, contactsJson);

        // Validation rules (default to none if not specified)
        if (mb.getValidationRules() == null || mb.getValidationRules().isEmpty()) {
            mb.createValidationRule("none", null, null);
        }

        return mb;
    }

    private static String buildUrlFromTemplate(String template, String mappingsJson) {
        if (template == null || template.isEmpty()) {
            return null;
        }

        HashMap<String, Object> parametersMap = new HashMap<>();

        if (!isEmptyJson(mappingsJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(mappingsJson);
                for (JsonNode node : arrayNode) {
                    String variable = getTextOrNull(node, "variable");
                    String defaultValue = getTextOrNull(node, "defaultvalue");
                    String property = getTextOrNull(node, "property");
                    String valuePattern = getTextOrNull(node, "valuepattern");

                    if (variable != null && !variable.isEmpty() && defaultValue != null && !defaultValue.isEmpty() ) {
                        if (property != null && valuePattern != null &&
                                (property.equals("schema:startDate") || property.equals("schema:endDate"))) {
                            parametersMap.put(variable, Utils.convertDateUsingPattern(defaultValue, null, valuePattern));
                        } else {
                            parametersMap.put(variable, URLGeneration.encodeValue(defaultValue));
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse mappings JSON: {}", e.getMessage());
            }
        }

        return URLGeneration.generateURLFromTemplateAndMap(template, parametersMap);
    }

    private static String determineTcsGroup(String ddssId) {
        if (ddssId == null) {
            return "Undefined";
        }

        String ddssLower = ddssId.toLowerCase();

        if (ddssLower.contains("wp08")) return "Seismology";
        if (ddssLower.contains("wp09")) return "Near Fault Observations";
        if (ddssLower.contains("wp10")) return "Geodesy";
        if (ddssLower.contains("wp11")) return "Volcano Observations";
        if (ddssLower.contains("wp12")) return "Satellite Observations";
        if (ddssLower.contains("wp13")) return "Geoelectromagnetism";
        if (ddssLower.contains("wp14")) return "Anthropogenic Hazard Observations";
        if (ddssLower.contains("wp15")) return "Geology";
        if (ddssLower.contains("wp16")) return "Multi-Scale Laboratory";
        if (ddssLower.contains("wp18")) return "Tsunami";

        return "Undefined";
    }

    private static void parseContacts(MonitoringBean mb, String contactsJson) {
        if (isEmptyJson(contactsJson)) {
            return;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(contactsJson);
            for (JsonNode node : arrayNode) {
                String uid = getTextOrNull(node, "uid");
                String role = getTextOrNull(node, "role");

                List<String> emails = new ArrayList<>();
                JsonNode emailsNode = node.get("emails");
                if (emailsNode != null && emailsNode.isArray()) {
                    for (JsonNode emailNode : emailsNode) {
                        if (!emailNode.isNull()) {
                            emails.add(emailNode.asText());
                        }
                    }
                }

                if (uid != null && !emails.isEmpty()) {
                    try {
                        mb.createContacts(uid, role, emails);
                    } catch (Exception e) {
                        LOGGER.debug("Error creating contact: {}", e.getMessage());
                    }
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse contacts JSON: {}", e.getMessage());
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
