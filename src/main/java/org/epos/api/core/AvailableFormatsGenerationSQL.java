package org.epos.api.core;

import java.util.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.epos.api.beans.AvailableFormat;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for generating available formats.
 * This replaces the JPA-based AvailableFormatsGeneration for improved performance
 * when used within SQL-based distribution generation.
 * 
 * Uses AvailableFormatsBuilder for format construction logic shared with
 * DistributionSearchGenerationSQL and FacilitySearchGenerationSQL.
 */
public class AvailableFormatsGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(AvailableFormatsGenerationSQL.class);

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
        // Fetch distribution data
        DistributionData distData = fetchDistributionData(em, distributionInstanceId);
        if (distData == null) {
            return new ArrayList<>();
        }

        // Parse download URLs
        String[] downloadUrls = null;
        if (distData.downloadUrls != null && !distData.downloadUrls.isEmpty()) {
            downloadUrls = distData.downloadUrls.split(",");
        }

        // Parse operation returns from JSON
        String[] operationReturns = AvailableFormatsBuilder.parseOperationReturns(distData.operationReturns);

        // Build formats using the shared builder
        AvailableFormatsBuilder.FormatInput input = new AvailableFormatsBuilder.FormatInput(distributionInstanceId)
                .downloadUrls(downloadUrls)
                .originalFormat(distData.format)
                .operationReturns(operationReturns)
                .operationTemplate(distData.operationTemplate)
                .availableFormatsJson(distData.mappingsJson)
                .hasAccessService(distData.hasAccessService);

        return AvailableFormatsBuilder.build(input);
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
        sql.append("         op.template ");
        sql.append("  FROM metadata_catalogue.operation_distribution od ");
        sql.append("  JOIN metadata_catalogue.operation op ON od.operation_instance_id = op.instance_id ");
        sql.append("  WHERE od.distribution_instance_id = ?1 ");
        sql.append("  ORDER BY od.distribution_instance_id, op.instance_id ");
        sql.append("), ");

        // Operation returns (stored in operation_element table)
        sql.append("operation_returns AS ( ");
        sql.append("  SELECT oi.distribution_instance_id, ");
        sql.append("         ARRAY_AGG(DISTINCT e.value) AS returns ");
        sql.append("  FROM operation_info oi ");
        sql.append("  JOIN metadata_catalogue.operation_element oe ON oi.operation_id = oe.operation_instance_id ");
        sql.append("  JOIN metadata_catalogue.element e ON oe.element_instance_id = e.instance_id ");
        sql.append("  WHERE e.type = 'RETURNS' ");
        sql.append("  GROUP BY oi.distribution_instance_id ");
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
        sql.append("  COALESCE(CAST(TO_JSON(oret.returns) AS text), '[]') AS operation_returns, ");
        sql.append("  COALESCE(CAST(opm.mappings AS text), '[]') AS mappings ");
        sql.append("FROM dist_base db ");
        sql.append("LEFT JOIN dist_download dd ON db.instance_id = dd.distribution_instance_id ");
        sql.append("LEFT JOIN has_access_service has ON db.instance_id = has.distribution_instance_id ");
        sql.append("LEFT JOIN operation_info oi ON db.instance_id = oi.distribution_instance_id ");
        sql.append("LEFT JOIN operation_returns oret ON db.instance_id = oret.distribution_instance_id ");
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
}
