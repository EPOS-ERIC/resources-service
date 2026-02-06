package org.epos.api.core.software;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.epos.api.beans.AvailableContactPoints;
import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.software.SoftwareApplicationResponse;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.enums.ProviderType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.eposdatamodel.SoftwareApplication;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for software application details.
 * This replaces the JPA-based SoftwareApplicationGenerationJPA for improved performance.
 */
public class SoftwareApplicationGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareApplicationGenerationSQL.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String EMAIL_SENDER = EnvironmentVariables.API_CONTEXT + "/sender/send-email?id=";

    private static final Pattern FORMAT_PATTERN = Pattern.compile("\\.([a-zA-Z0-9]+)(?:/|$|\\?)");

    /**
     * Generate SoftwareApplicationResponse from a SoftwareApplication entity.
     * This method accepts the entity directly to maintain compatibility with existing code.
     */
    public static SoftwareApplicationResponse generate(SoftwareApplication softwareApplication) {
        return generateFromEntity(softwareApplication);
    }

    /**
     * Generate SoftwareApplicationResponse using SQL query by instance ID.
     */
    public static SoftwareApplicationResponse generateById(String instanceId) {
        final long startTime = System.nanoTime();
        LOGGER.info("Generating software application details (SQL) for id: {}", instanceId);

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            Object[] row = fetchSoftwareApplicationData(em, instanceId);

            if (row == null) {
                LOGGER.warn("SoftwareApplication not found for id: {}", instanceId);
                return null;
            }

            SoftwareApplicationResponse response = mapRowToResponse(row);

            LOGGER.info("Software application details generated (SQL) in {} ms",
                    (System.nanoTime() - startTime) / 1_000_000);

            return response;

        } catch (Exception e) {
            LOGGER.error("Failed to generate software application details", e);
            throw new RuntimeException("Failed to generate software application details", e);
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    /**
     * Generate from entity directly (maintains backward compatibility)
     */
    private static SoftwareApplicationResponse generateFromEntity(SoftwareApplication softwareApplication) {
        SoftwareApplicationResponse response = new SoftwareApplicationResponse();

        response.setName(softwareApplication.getName());
        response.setDescription(softwareApplication.getDescription());
        response.setDownloadURL(softwareApplication.getDownloadURL());
        response.setInstallURL(softwareApplication.getInstallURL());
        response.setLicenseURL(softwareApplication.getLicenseURL());
        response.setMainEntityOfPage(softwareApplication.getMainEntityOfPage());
        response.setSoftwareRequirements(softwareApplication.getRequirements());
        response.setSoftwareVersion(softwareApplication.getSoftwareVersion());
        response.setSoftwareStatus(softwareApplication.getSoftwareStatus());
        response.setSpatial(softwareApplication.getSpatial());
        response.setTemporal(softwareApplication.getTemporal());
        response.setFileSize(softwareApplication.getFileSize());
        response.setTimeRequired(softwareApplication.getTimeRequired());
        response.setProcessorRequirements(softwareApplication.getProcessorRequirements());
        response.setMemoryRequirements(softwareApplication.getMemoryrequirements());
        response.setStorageRequirements(softwareApplication.getStorageRequirements());
        response.setCitation(softwareApplication.getCitation());
        response.setOperatingSystem(softwareApplication.getOperatingSystem());
        response.setCreator(createCreatorUids(softwareApplication.getCreator()));
        response.setAvailableFormats(createFormatsForApplication(
                softwareApplication.getDownloadURL(),
                softwareApplication.getMainEntityOfPage()
        ));
        response.setId(softwareApplication.getInstanceId());

        // Keywords
        String keywordsStr = softwareApplication.getKeywords();
        if (keywordsStr != null && !keywordsStr.trim().isEmpty()) {
            List<String> kw = Arrays.stream(keywordsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            response.setKeywords(kw.isEmpty() ? null : kw);
        }

        // Identifiers - this requires fetching from database
        // For backward compatibility, we fetch identifiers via SQL
        fetchAndSetIdentifiers(response, softwareApplication.getInstanceId());

        // Contact points
        if (softwareApplication.getContactPoint() != null && !softwareApplication.getContactPoint().isEmpty()) {
            response.getAvailableContactPoints()
                    .add(new AvailableContactPoints.AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + softwareApplication.getInstanceId()
                                    + "&contactType=" + ProviderType.DATAPROVIDERS)
                            .type(ProviderType.DATAPROVIDERS).build());
        }
        if (response.getAvailableContactPoints().isEmpty()) {
            response.setAvailableContactPoints(null);
        }

        // Categories - fetch via SQL
        fetchAndSetCategories(response, softwareApplication.getInstanceId());

        return response;
    }

    private static Object[] fetchSoftwareApplicationData(EntityManager em, String instanceId) {
        StringBuilder sql = new StringBuilder();

        sql.append("WITH software_base AS ( ");
        sql.append("  SELECT sa.instance_id, sa.meta_id, sa.uid, sa.name, sa.description, ");
        sql.append("         sa.downloadurl, sa.installurl, sa.licenseurl, sa.mainentityofpage, ");
        sql.append("         sa.requirements, sa.softwareversion, sa.softwarestatus, ");
        sql.append("         sa.spatial, sa.temporal, sa.filesize, sa.timerequired, ");
        sql.append("         sa.processorrequirements, sa.memoryrequirements, sa.storagerequirements, ");
        sql.append("         sa.keywords ");
        sql.append("  FROM metadata_catalogue.softwareapplication sa ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON sa.version_id = v.version_id ");
        sql.append("  WHERE sa.instance_id = ?1 ");
        sql.append("), ");

        // Identifiers
        sql.append("software_identifiers AS ( ");
        sql.append("  SELECT sb.instance_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT('type', i.type, 'value', i.value)) AS identifiers ");
        sql.append("  FROM software_base sb ");
        sql.append("  JOIN metadata_catalogue.softwareapplication_identifier sai ON sb.instance_id = sai.softwareapplication_instance_id ");
        sql.append("  JOIN metadata_catalogue.identifier i ON sai.identifier_instance_id = i.instance_id ");
        sql.append("  GROUP BY sb.instance_id ");
        sql.append("), ");

        // Contact points count
        sql.append("software_contacts AS ( ");
        sql.append("  SELECT sb.instance_id, COUNT(*) AS contact_count ");
        sql.append("  FROM software_base sb ");
        sql.append("  JOIN metadata_catalogue.softwareapplication_contactpoint sac ON sb.instance_id = sac.softwareapplication_instance_id ");
        sql.append("  GROUP BY sb.instance_id ");
        sql.append("), ");

        // Categories
        sql.append("software_categories AS ( ");
        sql.append("  SELECT sb.instance_id, ");
        sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('uid', c.uid, 'name', c.name, 'instance_id', c.instance_id)) AS categories ");
        sql.append("  FROM software_base sb ");
        sql.append("  JOIN metadata_catalogue.softwareapplication_category sac ON sb.instance_id = sac.softwareapplication_instance_id ");
        sql.append("  JOIN metadata_catalogue.category c ON sac.category_instance_id = c.instance_id ");
        sql.append("  GROUP BY sb.instance_id ");
        sql.append("), ");

        // Elements (citation, operating system)
        sql.append("software_elements AS ( ");
        sql.append("  SELECT sb.instance_id, ");
        sql.append("         MAX(CASE WHEN e.type = 'CITATION' THEN e.value END) AS citation, ");
        sql.append("         ARRAY_AGG(CASE WHEN e.type = 'OPERATINGSYSTEM' THEN e.value END) FILTER (WHERE e.type = 'OPERATINGSYSTEM') AS operating_systems ");
        sql.append("  FROM software_base sb ");
        sql.append("  JOIN metadata_catalogue.softwareapplication_element sae ON sb.instance_id = sae.softwareapplication_instance_id ");
        sql.append("  JOIN metadata_catalogue.element e ON sae.element_instance_id = e.instance_id ");
        sql.append("  GROUP BY sb.instance_id ");
        sql.append("), ");

        // Creators
        sql.append("software_creators AS ( ");
        sql.append("  SELECT sb.instance_id, ");
        sql.append("         JSONB_AGG(le.uid) AS creator_uids ");
        sql.append("  FROM software_base sb ");
        sql.append("  JOIN metadata_catalogue.softwareapplication_creator sac ON sb.instance_id = sac.softwareapplication_instance_id ");
        sql.append("  JOIN metadata_catalogue.linkedentity le ON sac.entity_instance_id = le.instance_id ");
        sql.append("  GROUP BY sb.instance_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  sb.instance_id, sb.meta_id, sb.uid, sb.name, sb.description, ");
        sql.append("  sb.downloadurl, sb.installurl, sb.licenseurl, sb.mainentityofpage, ");
        sql.append("  sb.requirements, sb.softwareversion, sb.softwarestatus, ");
        sql.append("  sb.spatial, sb.temporal, sb.filesize, sb.timerequired, ");
        sql.append("  sb.processorrequirements, sb.memoryrequirements, sb.storagerequirements, ");
        sql.append("  sb.keywords, ");
        sql.append("  COALESCE(CAST(si.identifiers AS text), '[]') AS identifiers, ");
        sql.append("  COALESCE(sc.contact_count, 0) AS contact_count, ");
        sql.append("  COALESCE(CAST(scat.categories AS text), '[]') AS categories, ");
        sql.append("  se.citation, ");
        sql.append("  COALESCE(CAST(TO_JSON(se.operating_systems) AS text), '[]') AS operating_systems, ");
        sql.append("  COALESCE(CAST(scr.creator_uids AS text), '[]') AS creator_uids ");
        sql.append("FROM software_base sb ");
        sql.append("LEFT JOIN software_identifiers si ON sb.instance_id = si.instance_id ");
        sql.append("LEFT JOIN software_contacts sc ON sb.instance_id = sc.instance_id ");
        sql.append("LEFT JOIN software_categories scat ON sb.instance_id = scat.instance_id ");
        sql.append("LEFT JOIN software_elements se ON sb.instance_id = se.instance_id ");
        sql.append("LEFT JOIN software_creators scr ON sb.instance_id = scr.instance_id ");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter(1, instanceId);

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        if (results.isEmpty()) {
            return null;
        }

        return results.get(0);
    }

    private static SoftwareApplicationResponse mapRowToResponse(Object[] row) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String name = (String) row[i++];
        String description = (String) row[i++];
        String downloadUrl = (String) row[i++];
        String installUrl = (String) row[i++];
        String licenseUrl = (String) row[i++];
        String mainEntityOfPage = (String) row[i++];
        String requirements = (String) row[i++];
        String softwareVersion = (String) row[i++];
        String softwareStatus = (String) row[i++];
        String spatial = (String) row[i++];
        String temporal = (String) row[i++];
        String fileSize = (String) row[i++];
        String timeRequired = (String) row[i++];
        String processorRequirements = (String) row[i++];
        String memoryRequirements = (String) row[i++];
        String storageRequirements = (String) row[i++];
        String keywords = (String) row[i++];
        String identifiersJson = (String) row[i++];
        Long contactCount = ((Number) row[i++]).longValue();
        String categoriesJson = (String) row[i++];
        String citation = (String) row[i++];
        String operatingSystemsJson = (String) row[i++];
        String creatorUidsJson = (String) row[i++];

        SoftwareApplicationResponse response = new SoftwareApplicationResponse();

        response.setId(instanceId);
        response.setName(name);
        response.setDescription(description);
        response.setDownloadURL(downloadUrl);
        response.setInstallURL(installUrl);
        response.setLicenseURL(licenseUrl);
        response.setMainEntityOfPage(mainEntityOfPage);
        response.setSoftwareRequirements(requirements);
        response.setSoftwareVersion(softwareVersion);
        response.setSoftwareStatus(softwareStatus);
        response.setSpatial(spatial);
        response.setTemporal(temporal);
        response.setFileSize(fileSize);
        response.setTimeRequired(timeRequired);
        response.setProcessorRequirements(processorRequirements);
        response.setMemoryRequirements(memoryRequirements);
        response.setStorageRequirements(storageRequirements);
        if (citation != null && !citation.isEmpty()) {
            response.setCitation(Collections.singletonList(citation));
        }

        // Operating systems
        if (!isEmptyJson(operatingSystemsJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(operatingSystemsJson);
                List<String> osList = new ArrayList<>();
                for (JsonNode node : arrayNode) {
                    if (!node.isNull()) {
                        osList.add(node.asText());
                    }
                }
                response.setOperatingSystem(osList);
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse operating systems: {}", e.getMessage());
            }
        }

        // Creator UIDs
        if (!isEmptyJson(creatorUidsJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(creatorUidsJson);
                List<String> creators = new ArrayList<>();
                for (JsonNode node : arrayNode) {
                    if (!node.isNull()) {
                        creators.add(node.asText());
                    }
                }
                response.setCreator(creators.isEmpty() ? null : creators);
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse creator UIDs: {}", e.getMessage());
            }
        }

        // Available formats
        response.setAvailableFormats(createFormatsForApplication(downloadUrl, mainEntityOfPage));

        // Keywords
        if (keywords != null && !keywords.trim().isEmpty()) {
            List<String> kw = Arrays.stream(keywords.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            response.setKeywords(kw.isEmpty() ? null : kw);
        }

        // Identifiers
        parseIdentifiers(response, identifiersJson);

        // Contact points
        if (contactCount > 0) {
            response.getAvailableContactPoints()
                    .add(new AvailableContactPoints.AvailableContactPointsBuilder()
                            .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + instanceId
                                    + "&contactType=" + ProviderType.DATAPROVIDERS)
                            .type(ProviderType.DATAPROVIDERS).build());
        }
        if (response.getAvailableContactPoints().isEmpty()) {
            response.setAvailableContactPoints(null);
        }

        // Categories
        parseAndSetCategories(response, instanceId, categoriesJson);

        return response;
    }

    private static void parseIdentifiers(SoftwareApplicationResponse response, String identifiersJson) {
        if (isEmptyJson(identifiersJson)) {
            return;
        }

        List<String> doi = new ArrayList<>();
        List<String> identifiers = new ArrayList<>();

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(identifiersJson);
            for (JsonNode node : arrayNode) {
                String type = getTextOrNull(node, "type");
                String value = getTextOrNull(node, "value");
                if ("DOI".equals(type) && value != null) {
                    doi.add(value);
                } else if ("DDSS-ID".equals(type) && value != null) {
                    identifiers.add(value);
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse identifiers: {}", e.getMessage());
        }

        response.setDoi(doi.isEmpty() ? null : doi);
        response.setIdentifiers(identifiers.isEmpty() ? null : identifiers);
    }

    private static void parseAndSetCategories(SoftwareApplicationResponse response, String instanceId, String categoriesJson) {
        if (isEmptyJson(categoriesJson)) {
            return;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(categoriesJson);
            List<String> categoryList = new ArrayList<>();

            for (JsonNode node : arrayNode) {
                String uid = getTextOrNull(node, "uid");
                if (uid != null && uid.contains("category:")) {
                    categoryList.add(uid);
                }
            }

            if (!categoryList.isEmpty()) {
                List<DiscoveryItem> discoveryList = new ArrayList<>();
                discoveryList.add(new DiscoveryItem.DiscoveryItemBuilder(instanceId, null, null)
                        .categories(categoryList)
                        .build());

                org.epos.api.facets.FacetsNodeTree categoriesTree = FacetsGeneration
                        .generateResponseUsingCategories(discoveryList, Facets.Type.SOFTWARE);
                categoriesTree.getNodes().forEach(node -> node.setDistributions(null));
                response.setCategories(categoriesTree.getFacets());
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse categories: {}", e.getMessage());
        }
    }

    /**
     * Fetch and set identifiers for backward compatibility with entity-based generation
     */
    private static void fetchAndSetIdentifiers(SoftwareApplicationResponse response, String instanceId) {
        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            String sql = "SELECT i.type, i.value FROM metadata_catalogue.softwareapplication_identifier sai " +
                    "JOIN metadata_catalogue.identifier i ON sai.identifier_instance_id = i.instance_id " +
                    "WHERE sai.softwareapplication_instance_id = ?1";

            Query query = em.createNativeQuery(sql);
            query.setParameter(1, instanceId);

            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();

            List<String> doi = new ArrayList<>();
            List<String> identifiers = new ArrayList<>();

            for (Object[] row : results) {
                String type = (String) row[0];
                String value = (String) row[1];
                if ("DOI".equals(type) && value != null) {
                    doi.add(value);
                } else if ("DDSS-ID".equals(type) && value != null) {
                    identifiers.add(value);
                }
            }

            response.setDoi(doi.isEmpty() ? null : doi);
            response.setIdentifiers(identifiers.isEmpty() ? null : identifiers);

        } catch (Exception e) {
            LOGGER.warn("Failed to fetch identifiers: {}", e.getMessage());
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    /**
     * Fetch and set categories for backward compatibility with entity-based generation
     */
    private static void fetchAndSetCategories(SoftwareApplicationResponse response, String instanceId) {
        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            String sql = "SELECT c.uid, c.name, c.instance_id FROM metadata_catalogue.softwareapplication_category sac " +
                    "JOIN metadata_catalogue.category c ON sac.category_instance_id = c.instance_id " +
                    "WHERE sac.softwareapplication_instance_id = ?1";

            Query query = em.createNativeQuery(sql);
            query.setParameter(1, instanceId);

            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();

            List<String> categoryList = new ArrayList<>();
            for (Object[] row : results) {
                String uid = (String) row[0];
                if (uid != null && uid.contains("category:")) {
                    categoryList.add(uid);
                }
            }

            if (!categoryList.isEmpty()) {
                List<DiscoveryItem> discoveryList = new ArrayList<>();
                discoveryList.add(new DiscoveryItem.DiscoveryItemBuilder(instanceId, null, null)
                        .categories(categoryList)
                        .build());

                org.epos.api.facets.FacetsNodeTree categoriesTree = FacetsGeneration
                        .generateResponseUsingCategories(discoveryList, Facets.Type.SOFTWARE);
                categoriesTree.getNodes().forEach(node -> node.setDistributions(null));
                response.setCategories(categoriesTree.getFacets());
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to fetch categories: {}", e.getMessage());
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    private static List<AvailableFormat> createFormatsForApplication(String downloadUrl, String mainEntityOfPage) {
        if (downloadUrl != null) {
            return createFormatsFromUrl(downloadUrl);
        } else if (mainEntityOfPage != null) {
            return createFormatsFromUrl(mainEntityOfPage);
        }
        return null;
    }

    private static List<AvailableFormat> createFormatsFromUrl(String url) {
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
        while (matcher.find()) {
            format = matcher.group(1);
        }
        return format;
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

    private static List<String> createCreatorUids(List<org.epos.eposdatamodel.LinkedEntity> creators) {
        if (creators == null) {
            return null;
        }
        List<String> creatorUids = creators.stream()
                .map(org.epos.eposdatamodel.LinkedEntity::getUid)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        return creatorUids.isEmpty() ? null : creatorUids;
    }
}
