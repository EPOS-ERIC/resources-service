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
import org.epos.api.beans.software.SoftwareSourceCodeResponse;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.enums.ProviderType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for software source code details.
 * This replaces the JPA-based SoftwareSourceCodeGenerationJPA for improved performance.
 */
public class SoftwareSourceCodeGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareSourceCodeGenerationSQL.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String EMAIL_SENDER = EnvironmentVariables.API_CONTEXT + "/sender/send-email?id=";

    private static final Pattern FORMAT_PATTERN = Pattern.compile("\\.([a-zA-Z0-9]+)(?:/|$|\\?)");

    /**
     * Generate a SoftwareSourceCodeResponse for a given instance ID using native SQL queries.
     *
     * @param instanceId The instance ID of the software source code
     * @return SoftwareSourceCodeResponse or null if not found
     */
    public static SoftwareSourceCodeResponse generate(String instanceId) {
        EntityManager em = EntityManagerService.getInstance().createEntityManager();

        try {
            Object[] row = fetchSoftwareSourceCode(em, instanceId);

            if (row == null) {
                LOGGER.warn("No software source code found for instanceId: {}", instanceId);
                return null;
            }

            return mapRowToResponse(row);

        } catch (Exception e) {
            LOGGER.error("Error generating software source code response for instanceId: {}", instanceId, e);
            return null;
        } finally {
            if (em != null && em.isOpen()) {
                em.close();
            }
        }
    }

    private static Object[] fetchSoftwareSourceCode(EntityManager em, String instanceId) {
        StringBuilder sql = new StringBuilder();

        // CTE for software base data
        sql.append("WITH software_base AS ( ");
        sql.append("  SELECT ss.instance_id, ss.meta_id, ss.uid, ss.name, ss.description, ");
        sql.append("         ss.licenseurl, ss.downloadurl, ss.coderepository, ss.mainentityofpage, ");
        sql.append("         ss.runtimeplatform, ss.softwareversion, ss.softwarestatus, ");
        sql.append("         ss.spatial, ss.temporal, ss.filesize, ss.timerequired, ");
        sql.append("         ss.keywords, ss.softwarerequirements ");
        sql.append("  FROM metadata_catalogue.softwaresourcecode ss ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON ss.version_id = v.version_id ");
        sql.append("  WHERE ss.instance_id = ?1 ");
        sql.append("    AND (v.status = 'PUBLISHED' OR v.status = 'ARCHIVED') ");
        sql.append("), ");

        // CTE for identifiers (DOI and DDSS-ID)
        sql.append("software_identifiers AS ( ");
        sql.append("  SELECT si.softwaresourcecode_instance_id AS instance_id, ");
        sql.append("         JSONB_AGG(JSONB_BUILD_OBJECT( ");
        sql.append("           'type', i.type, ");
        sql.append("           'identifier', i.value ");
        sql.append("         )) AS identifiers ");
        sql.append("  FROM metadata_catalogue.softwaresourcecode_identifier si ");
        sql.append("  JOIN metadata_catalogue.identifier i ON si.identifier_instance_id = i.instance_id ");
        sql.append("  WHERE si.softwaresourcecode_instance_id = ?1 ");
        sql.append("  GROUP BY si.softwaresourcecode_instance_id ");
        sql.append("), ");

        // CTE for contact points count
        sql.append("software_contacts AS ( ");
        sql.append("  SELECT sc.softwaresourcecode_instance_id AS instance_id, ");
        sql.append("         COUNT(*) AS contact_count ");
        sql.append("  FROM metadata_catalogue.softwaresourcecode_contactpoint sc ");
        sql.append("  WHERE sc.softwaresourcecode_instance_id = ?1 ");
        sql.append("  GROUP BY sc.softwaresourcecode_instance_id ");
        sql.append("), ");

        // CTE for categories
        sql.append("software_categories AS ( ");
        sql.append("  SELECT scat.softwaresourcecode_instance_id AS instance_id, ");
        sql.append("         JSONB_AGG(c.uid) AS categories ");
        sql.append("  FROM metadata_catalogue.softwaresourcecode_category scat ");
        sql.append("  JOIN metadata_catalogue.category c ON scat.category_instance_id = c.instance_id ");
        sql.append("  WHERE scat.softwaresourcecode_instance_id = ?1 ");
        sql.append("  GROUP BY scat.softwaresourcecode_instance_id ");
        sql.append("), ");

        // CTE for elements (programming languages and citation from element table)
        sql.append("software_elements AS ( ");
        sql.append("  SELECT se.softwaresourcecode_instance_id AS instance_id, ");
        sql.append("         JSONB_AGG(CASE WHEN e.type = 'PROGRAMMINGLANGUAGE' OR e.type IS NULL THEN e.value END) ");
        sql.append("           FILTER (WHERE e.type = 'PROGRAMMINGLANGUAGE' OR e.type IS NULL) AS programming_languages, ");
        sql.append("         JSONB_AGG(CASE WHEN e.type = 'CITATION' THEN e.value END) ");
        sql.append("           FILTER (WHERE e.type = 'CITATION') AS citations ");
        sql.append("  FROM metadata_catalogue.softwaresourcecode_element se ");
        sql.append("  JOIN metadata_catalogue.element e ON se.element_instance_id = e.instance_id ");
        sql.append("  WHERE se.softwaresourcecode_instance_id = ?1 ");
        sql.append("  GROUP BY se.softwaresourcecode_instance_id ");
        sql.append("), ");

        // CTE for creator UIDs (creators can be persons or organizations)
        sql.append("software_creators AS ( ");
        sql.append("  SELECT sc.softwaresourcecode_instance_id AS instance_id, ");
        sql.append("         JSONB_AGG(COALESCE(p.uid, o.uid)) FILTER (WHERE COALESCE(p.uid, o.uid) IS NOT NULL) AS creator_uids ");
        sql.append("  FROM metadata_catalogue.softwaresourcecode_creator sc ");
        sql.append("  LEFT JOIN metadata_catalogue.person p ON sc.entity_instance_id = p.instance_id AND sc.resource_entity = 'PERSON' ");
        sql.append("  LEFT JOIN metadata_catalogue.organization o ON sc.entity_instance_id = o.instance_id AND sc.resource_entity = 'ORGANIZATION' ");
        sql.append("  WHERE sc.softwaresourcecode_instance_id = ?1 ");
        sql.append("  GROUP BY sc.softwaresourcecode_instance_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  sb.instance_id, sb.meta_id, sb.uid, sb.name, sb.description, ");
        sql.append("  sb.licenseurl, sb.downloadurl, sb.coderepository, sb.mainentityofpage, ");
        sql.append("  sb.runtimeplatform, sb.softwareversion, sb.softwarestatus, ");
        sql.append("  sb.spatial, sb.temporal, sb.filesize, sb.timerequired, ");
        sql.append("  sb.keywords, sb.softwarerequirements, ");
        sql.append("  COALESCE(CAST(si.identifiers AS text), '[]') AS identifiers, ");
        sql.append("  COALESCE(sc.contact_count, 0) AS contact_count, ");
        sql.append("  COALESCE(CAST(scat.categories AS text), '[]') AS categories, ");
        sql.append("  COALESCE(CAST(se.programming_languages AS text), '[]') AS programming_languages, ");
        sql.append("  COALESCE(CAST(se.citations AS text), '[]') AS citations, ");
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

    private static SoftwareSourceCodeResponse mapRowToResponse(Object[] row) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String name = (String) row[i++];
        String description = (String) row[i++];
        String licenseUrl = (String) row[i++];
        String downloadUrl = (String) row[i++];
        String codeRepository = (String) row[i++];
        String mainEntityOfPage = (String) row[i++];
        String runtimePlatform = (String) row[i++];
        String softwareVersion = (String) row[i++];
        String softwareStatus = (String) row[i++];
        String spatial = (String) row[i++];
        String temporal = (String) row[i++];
        String fileSize = (String) row[i++];
        String timeRequired = (String) row[i++];
        String keywords = (String) row[i++];
        String softwareRequirements = (String) row[i++];
        String identifiersJson = (String) row[i++];
        Long contactCount = ((Number) row[i++]).longValue();
        String categoriesJson = (String) row[i++];
        String programmingLanguagesJson = (String) row[i++];
        String citationsJson = (String) row[i++];
        String creatorUidsJson = (String) row[i++];

        SoftwareSourceCodeResponse response = new SoftwareSourceCodeResponse();

        response.setId(instanceId);
        response.setName(name);
        response.setDescription(description);
        response.setLicenseURL(nullIfEmpty(licenseUrl));
        // downloadURL is not part of the EPOS-DCAT-AP model for SoftwareSourceCode
        // response.setDownloadURL(downloadUrl);
        response.setCodeRepository(nullIfEmpty(codeRepository));
        response.setMainEntityOfPage(nullIfEmpty(mainEntityOfPage));
        response.setRuntimePlatform(nullIfEmpty(runtimePlatform));
        response.setSoftwareVersion(nullIfEmpty(softwareVersion));
        response.setSoftwareStatus(nullIfEmpty(softwareStatus));
        response.setSpatial(nullIfEmpty(spatial));
        response.setTemporal(nullIfEmpty(temporal));
        response.setSize(nullIfEmpty(fileSize));
        response.setTimeRequired(nullIfEmpty(timeRequired));
        response.setSoftwareRequirements(nullIfEmpty(softwareRequirements));

        // Keywords
        if (keywords != null && !keywords.trim().isEmpty()) {
            List<String> kw = Arrays.stream(keywords.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            response.setKeywords(kw.isEmpty() ? null : kw);
        }

        // Identifiers (DOI and DDSS-ID)
        if (!isEmptyJson(identifiersJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(identifiersJson);
                List<String> doiList = new ArrayList<>();
                List<String> ddssIdList = new ArrayList<>();

                for (JsonNode node : arrayNode) {
                    String type = node.has("type") ? node.get("type").asText() : null;
                    String identifier = node.has("identifier") ? node.get("identifier").asText() : null;

                    if (type != null && identifier != null) {
                        if ("DOI".equals(type)) {
                            doiList.add(identifier);
                        } else if ("DDSS-ID".equals(type)) {
                            ddssIdList.add(identifier);
                        }
                    }
                }

                response.setDOI(doiList.isEmpty() ? null : doiList);
                response.setIdentifiers(ddssIdList.isEmpty() ? null : ddssIdList);
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse identifiers: {}", e.getMessage());
            }
        }

        // Contact points
        if (contactCount > 0) {
            List<AvailableContactPoints> contactPoints = new ArrayList<>();
            contactPoints.add(new AvailableContactPoints.AvailableContactPointsBuilder()
                    .href(EnvironmentVariables.API_HOST + EMAIL_SENDER + instanceId
                            + "&contactType=" + ProviderType.DATAPROVIDERS)
                    .type(ProviderType.DATAPROVIDERS)
                    .build());
            response.setAvailableContactPoints(contactPoints);
        } else {
            response.setAvailableContactPoints(null);
        }

        // Programming languages
        if (!isEmptyJson(programmingLanguagesJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(programmingLanguagesJson);
                List<String> languages = new ArrayList<>();
                for (JsonNode node : arrayNode) {
                    if (!node.isNull()) {
                        languages.add(node.asText());
                    }
                }
                response.setProgrammingLanguage(languages.isEmpty() ? null : languages);
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse programming languages: {}", e.getMessage());
            }
        }

        // Citations
        if (!isEmptyJson(citationsJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(citationsJson);
                List<String> citations = new ArrayList<>();
                for (JsonNode node : arrayNode) {
                    if (!node.isNull()) {
                        citations.add(node.asText());
                    }
                }
                response.setCitation(citations.isEmpty() ? null : citations);
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse citations: {}", e.getMessage());
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

        // Categories
        if (!isEmptyJson(categoriesJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(categoriesJson);
                List<String> categoryList = new ArrayList<>();

                for (JsonNode node : arrayNode) {
                    if (!node.isNull()) {
                        String categoryUid = node.asText();
                        if (categoryUid.contains("category:")) {
                            categoryList.add(categoryUid);
                        }
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

        // Available formats
        response.setAvailableFormats(createFormatsForSourceCode(downloadUrl, codeRepository));

        return response;
    }

    private static boolean isEmptyJson(String json) {
        return json == null || json.isEmpty() || "[]".equals(json) || "null".equals(json);
    }

    private static String nullIfEmpty(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value;
    }

    private static List<AvailableFormat> createFormatsForSourceCode(String downloadUrl, String codeRepository) {
        if (downloadUrl != null && !downloadUrl.trim().isEmpty()) {
            return createFormatsFromUrl(downloadUrl);
        } else if (codeRepository != null && !codeRepository.trim().isEmpty()) {
            return createFormatsFromUrl(codeRepository);
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
        // get the last match
        while (matcher.find()) {
            format = matcher.group(1);
        }
        return format;
    }
}
