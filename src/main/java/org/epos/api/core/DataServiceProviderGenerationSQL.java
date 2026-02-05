package org.epos.api.core;

import java.util.*;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.epos.api.beans.DataServiceProvider;
import org.epos.eposdatamodel.Organization;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for generating DataServiceProvider objects.
 * This replaces the JPA-based DataServiceProviderGeneration for improved performance.
 * 
 * The original implementation had performance issues because it:
 * 1. Called AbstractAPI.retrieveAll() to load ALL organizations
 * 2. Made individual JPA calls for addresses in a loop
 * 
 * This SQL implementation fetches all needed data in a single query.
 */
public class DataServiceProviderGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataServiceProviderGenerationSQL.class);

    /**
     * Convert Organization entities to DataServiceProvider objects using pure SQL.
     * This method accepts Organization objects (which may have partial data) and
     * enriches them with related organization and address data via SQL.
     *
     * @param organizations List of Organization entities with at least instanceId set
     * @return List of DataServiceProvider objects
     */
    public static List<DataServiceProvider> getProviders(List<Organization> organizations) {
        if (organizations == null || organizations.isEmpty()) {
            return new ArrayList<>();
        }

        // Extract instance IDs from the organizations
        List<String> instanceIds = organizations.stream()
                .filter(Objects::nonNull)
                .map(Organization::getInstanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (instanceIds.isEmpty()) {
            return new ArrayList<>();
        }

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();
            return getProvidersByInstanceIds(em, instanceIds);
        } catch (Exception e) {
            LOGGER.error("Failed to generate data service providers", e);
            return new ArrayList<>();
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    /**
     * Get DataServiceProvider objects for a list of organization instance IDs.
     * This fetches all necessary data in a single SQL query.
     */
    public static List<DataServiceProvider> getProvidersByInstanceIds(EntityManager em, List<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return new ArrayList<>();
        }

        // Build the SQL query to fetch organizations with their addresses and related organizations
        StringBuilder sql = new StringBuilder();

        sql.append("WITH target_orgs AS ( ");
        sql.append("  SELECT o.instance_id, o.legalname, o.url, o.logo, o.address_id ");
        sql.append("  FROM metadata_catalogue.organization o ");
        sql.append("  WHERE o.instance_id IN (");
        for (int i = 0; i < instanceIds.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?").append(i + 1);
        }
        sql.append(") ");
        sql.append("), ");

        // Get addresses for target organizations
        sql.append("org_addresses AS ( ");
        sql.append("  SELECT t.instance_id, a.country ");
        sql.append("  FROM target_orgs t ");
        sql.append("  LEFT JOIN metadata_catalogue.address a ON t.address_id = a.instance_id ");
        sql.append("), ");

        // Get related organizations (organizations that are member_of target orgs)
        sql.append("related_orgs AS ( ");
        sql.append("  SELECT om.organization_instance_id, t.instance_id AS parent_instance_id, ");
        sql.append("         o.legalname AS related_legalname, o.url AS related_url, ");
        sql.append("         o.instance_id AS related_instance_id, a.country AS related_country ");
        sql.append("  FROM target_orgs t ");
        sql.append("  JOIN metadata_catalogue.organization_memberof om ON om.organization_instance_id_memberof = t.instance_id ");
        sql.append("  JOIN metadata_catalogue.organization o ON om.organization_instance_id = o.instance_id ");
        sql.append("  LEFT JOIN metadata_catalogue.address a ON o.address_id = a.instance_id ");
        sql.append("  WHERE o.legalname IS NOT NULL ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  t.instance_id, t.legalname, t.url, t.logo, ");
        sql.append("  oa.country, ");
        sql.append("  COALESCE(( ");
        sql.append("    SELECT JSONB_AGG(JSONB_BUILD_OBJECT( ");
        sql.append("      'instance_id', r.related_instance_id, ");
        sql.append("      'legalname', r.related_legalname, ");
        sql.append("      'url', r.related_url, ");
        sql.append("      'country', r.related_country ");
        sql.append("    ) ORDER BY r.related_legalname) ");
        sql.append("    FROM related_orgs r ");
        sql.append("    WHERE r.parent_instance_id = t.instance_id ");
        sql.append("  ), '[]'::jsonb) AS related_organizations ");
        sql.append("FROM target_orgs t ");
        sql.append("LEFT JOIN org_addresses oa ON t.instance_id = oa.instance_id ");
        sql.append("ORDER BY t.legalname ");

        Query query = em.createNativeQuery(sql.toString());
        for (int i = 0; i < instanceIds.size(); i++) {
            query.setParameter(i + 1, instanceIds.get(i));
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        return mapResultsToProviders(results);
    }

    private static List<DataServiceProvider> mapResultsToProviders(List<Object[]> results) {
        List<DataServiceProvider> providers = new ArrayList<>();

        for (Object[] row : results) {
            int i = 0;
            String instanceId = (String) row[i++];
            String legalName = (String) row[i++];
            String url = (String) row[i++];
            String logo = (String) row[i++];
            String country = (String) row[i++];
            String relatedOrgsJson = row[i] != null ? row[i].toString() : "[]";

            // Skip organizations without legal name
            if (legalName == null || legalName.isEmpty()) {
                continue;
            }

            DataServiceProvider provider = new DataServiceProvider();
            provider.setDataProviderLegalName(legalName);
            provider.setDataProviderUrl(url);
            provider.setUid(instanceId);
            provider.setInstanceid(instanceId);
            provider.setMetaid(instanceId);
            if (country != null) {
                provider.setCountry(country);
            }

            // Parse related organizations
            List<DataServiceProvider> relatedProviders = parseRelatedOrganizations(relatedOrgsJson);
            provider.setRelatedDataProvider(relatedProviders);

            providers.add(provider);
        }

        // Sort by legal name
        providers.sort(Comparator.comparing(DataServiceProvider::getDataProviderLegalName));

        return providers;
    }

    private static List<DataServiceProvider> parseRelatedOrganizations(String json) {
        List<DataServiceProvider> related = new ArrayList<>();

        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return related;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arrayNode = mapper.readTree(json);

            for (com.fasterxml.jackson.databind.JsonNode node : arrayNode) {
                String instanceId = getTextOrNull(node, "instance_id");
                String legalName = getTextOrNull(node, "legalname");
                String url = getTextOrNull(node, "url");
                String country = getTextOrNull(node, "country");

                if (legalName != null && !legalName.isEmpty()) {
                    DataServiceProvider relatedProvider = new DataServiceProvider();
                    relatedProvider.setDataProviderLegalName(legalName);
                    relatedProvider.setDataProviderUrl(url);
                    relatedProvider.setUid(instanceId);
                    relatedProvider.setInstanceid(instanceId);
                    relatedProvider.setMetaid(instanceId);
                    if (country != null) {
                        relatedProvider.setCountry(country);
                    }
                    related.add(relatedProvider);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse related organizations JSON: {}", e.getMessage());
        }

        return related;
    }

    private static String getTextOrNull(com.fasterxml.jackson.databind.JsonNode node, String fieldName) {
        com.fasterxml.jackson.databind.JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText(null);
    }
}
