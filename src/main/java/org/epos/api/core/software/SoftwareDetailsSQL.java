package org.epos.api.core.software;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.epos.api.beans.software.SoftwareApplicationResponse;
import org.epos.api.beans.software.SoftwareDetailsResponse;
import org.epos.api.beans.software.SoftwareSourceCodeResponse;
import org.epos.api.core.distributions.DistributionDetailsGenerationSQL;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL-based implementation for software details.
 * This replaces the JPA-based SoftwareDetails for improved performance.
 */
public class SoftwareDetailsSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareDetailsSQL.class);

    public static SoftwareDetailsResponse generate(String instanceId) {
        final long startTime = System.nanoTime();
        LOGGER.info("Generating software details (SQL) for instanceId: {}", instanceId);

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            // Check what type of entity this is
            String entityType = determineEntityType(em, instanceId);

            if (entityType == null) {
                LOGGER.warn("No software entity found for instanceId: {}", instanceId);
                return null;
            }

            SoftwareDetailsResponse response;

            switch (entityType) {
                case "DISTRIBUTION":
                    Map<String, Object> params = new HashMap<>();
                    params.put("id", instanceId);
                    var distribution = DistributionDetailsGenerationSQL.generate(params);
                    response = new SoftwareDetailsResponse("distribution") {
                        public Object getObject() {
                            return distribution;
                        }
                    };
                    break;

                case "SOFTWARESOURCECODE":
                    SoftwareSourceCodeResponse sourceCodeResponse = SoftwareSourceCodeGenerationSQL.generate(instanceId);
                    response = new SoftwareDetailsResponse("software_source_code") {
                        public Object getObject() {
                            return sourceCodeResponse;
                        }
                    };
                    break;

                case "SOFTWAREAPPLICATION":
                    SoftwareApplicationResponse applicationResponse = SoftwareApplicationGenerationSQL.generateById(instanceId);
                    response = new SoftwareDetailsResponse("software_application") {
                        public Object getObject() {
                            return applicationResponse;
                        }
                    };
                    break;

                default:
                    LOGGER.warn("Unknown entity type: {} for instanceId: {}", entityType, instanceId);
                    return null;
            }

            LOGGER.info("Software details generated (SQL) in {} ms",
                    (System.nanoTime() - startTime) / 1_000_000);

            return response;

        } catch (Exception e) {
            LOGGER.error("Failed to generate software details", e);
            throw new RuntimeException("Failed to generate software details", e);
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    /**
     * Determines the entity type for the given instance ID by checking each table.
     */
    private static String determineEntityType(EntityManager em, String instanceId) {
        // Check distribution first
        String sql = "SELECT 'DISTRIBUTION' FROM metadata_catalogue.distribution WHERE instance_id = ?1 " +
                "UNION ALL " +
                "SELECT 'SOFTWARESOURCECODE' FROM metadata_catalogue.softwaresourcecode WHERE instance_id = ?1 " +
                "UNION ALL " +
                "SELECT 'SOFTWAREAPPLICATION' FROM metadata_catalogue.softwareapplication WHERE instance_id = ?1 " +
                "LIMIT 1";

        Query query = em.createNativeQuery(sql);
        query.setParameter(1, instanceId);

        @SuppressWarnings("unchecked")
        List<String> results = query.getResultList();

        if (results.isEmpty()) {
            return null;
        }

        return results.get(0);
    }
}
