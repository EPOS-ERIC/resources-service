package org.epos.api.core.facilities;

import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.DiscoveryItem.DiscoveryItemBuilder;
import org.epos.api.beans.Facility;
import org.epos.api.beans.ServiceParameter;
import org.epos.api.beans.SpatialInformation;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.distributions.DistributionDetailsGenerationSQL;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.FacetsNodeTree;
import org.epos.eposdatamodel.Organization;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.epos.library.feature.Feature;
import org.epos.library.feature.FeaturesCollection;
import org.epos.library.geometries.Geometry;
import org.epos.library.geometries.Point;
import org.epos.library.geometries.PointCoordinates;
import org.epos.library.geometries.Polygon;
import org.epos.library.objects.LinkObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for facility details.
 * This replaces the JPA-based FacilityDetailsItemGenerationJPA for improved performance.
 */
public class FacilityDetailsItemGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(FacilityDetailsItemGenerationSQL.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/facilities/details/";
    private static final String API_PATH_EXECUTE_EQUIPMENTS = EnvironmentVariables.API_CONTEXT + "/equipments/";
    private static final String API_FORMAT = "?format=";

    private static final String SPATIAL_SEPARATOR = " #EPOS# ";

    public static Object generate(Map<String, Object> parameters) {
        final long startTime = System.nanoTime();
        LOGGER.info("Generating facility details (SQL) for parameters: {}", parameters);

        String facilityId = parameters.get("id").toString();

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            // First try to get as facility
            Object[] facilityData = fetchFacilityData(em, facilityId);

            if (facilityData == null) {
                // Maybe it's a distribution
                LOGGER.info("Given id is not of a facility, checking if it's a distribution");
                return DistributionDetailsGenerationSQL.generate(parameters, Facets.Type.FACILITY);
            }

            // Check if GeoJSON format is requested
            if (parameters.containsKey("format") &&
                    "application/epos.geo+json".equals(parameters.get("format").toString())) {
                return generateAsGeoJson(facilityData, em,
                        parameters.containsKey("equipmenttypes") ? parameters.get("equipmenttypes").toString() : null);
            }

            Facility facility = mapToFacility(facilityData, em);

            LOGGER.info("Facility details generated (SQL) in {} ms",
                    (System.nanoTime() - startTime) / 1_000_000);

            return facility;

        } catch (Exception e) {
            LOGGER.error("Failed to generate facility details", e);
            throw new RuntimeException("Failed to generate facility details", e);
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    private static Object[] fetchFacilityData(EntityManager em, String facilityId) {
        String sql = buildFacilityDetailsSQL();
        Query query = em.createNativeQuery(sql);
        query.setParameter(1, facilityId);

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        if (results.isEmpty()) {
            return null;
        }

        return results.get(0);
    }

    private static String buildFacilityDetailsSQL() {
        StringBuilder sql = new StringBuilder();

        sql.append("WITH facility_base AS ( ");
        sql.append("  SELECT f.instance_id, f.meta_id, f.uid, f.title, f.description, f.type, f.keywords, ");
        sql.append("         v.status AS versioning_status, v.editor_id ");
        sql.append("  FROM metadata_catalogue.facility f ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON f.version_id = v.version_id ");
        sql.append("  WHERE f.instance_id = ?1 ");
        sql.append("), ");

        // Spatial data
        sql.append("facility_spatial AS ( ");
        sql.append("  SELECT fs.facility_instance_id, STRING_AGG(s.location, '").append(SPATIAL_SEPARATOR).append("') AS locations ");
        sql.append("  FROM metadata_catalogue.facility_spatial fs ");
        sql.append("  JOIN metadata_catalogue.spatial s ON fs.spatial_instance_id = s.instance_id ");
        sql.append("  WHERE fs.facility_instance_id = ?1 ");
        sql.append("  GROUP BY fs.facility_instance_id ");
        sql.append("), ");

        // Categories
        sql.append("facility_categories AS ( ");
        sql.append("  SELECT fc.facility_instance_id, ");
        sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT('uid', c.uid, 'name', c.name, 'instance_id', c.instance_id)) AS categories ");
        sql.append("  FROM metadata_catalogue.facility_category fc ");
        sql.append("  JOIN metadata_catalogue.category c ON fc.category_instance_id = c.instance_id ");
        sql.append("  WHERE fc.facility_instance_id = ?1 ");
        sql.append("  GROUP BY fc.facility_instance_id ");
        sql.append("), ");

        // Page URLs
        sql.append("facility_pages AS ( ");
        sql.append("  SELECT fe.facility_instance_id, ARRAY_AGG(e.value) AS page_urls ");
        sql.append("  FROM metadata_catalogue.facility_element fe ");
        sql.append("  JOIN metadata_catalogue.element e ON fe.element_instance_id = e.instance_id ");
        sql.append("  WHERE fe.facility_instance_id = ?1 AND e.type = 'PAGEURL' ");
        sql.append("  GROUP BY fe.facility_instance_id ");
        sql.append("), ");

        // Owners (organizations)
        sql.append("facility_owners AS ( ");
        sql.append("  SELECT oo.entity_instance_id AS facility_instance_id, ");
        sql.append("         JSONB_AGG(DISTINCT JSONB_BUILD_OBJECT( ");
        sql.append("           'instance_id', o.instance_id, 'legal_name', o.legalname, ");
        sql.append("           'url', o.url, 'logo', o.logo ");
        sql.append("         )) AS owners ");
        sql.append("  FROM metadata_catalogue.organization_owns oo ");
        sql.append("  JOIN metadata_catalogue.organization o ON oo.organization_instance_id = o.instance_id ");
        sql.append("  WHERE oo.resource_entity = 'FACILITY' AND oo.entity_instance_id = ?1 ");
        sql.append("  GROUP BY oo.entity_instance_id ");
        sql.append("), ");

        // Facility type name
        sql.append("facility_type_info AS ( ");
        sql.append("  SELECT fb.instance_id, c.name AS type_name ");
        sql.append("  FROM facility_base fb ");
        sql.append("  LEFT JOIN metadata_catalogue.category c ON TRIM(c.uid) = TRIM(fb.type) ");
        sql.append("), ");

        // Equipment types
        sql.append("equipment_types AS ( ");
        sql.append("  SELECT ei.entity_instance_id AS facility_instance_id, ");
        sql.append("         JSONB_AGG(DISTINCT c.name) AS equipment_type_names ");
        sql.append("  FROM metadata_catalogue.equipment_ispartof ei ");
        sql.append("  JOIN metadata_catalogue.equipment e ON ei.equipment_instance_id = e.instance_id ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON e.version_id = v.version_id ");
        sql.append("  JOIN metadata_catalogue.category c ON TRIM(c.uid) = TRIM(e.type) ");
        sql.append("  WHERE ei.resource_entity = 'FACILITY' ");
        sql.append("    AND ei.entity_instance_id = ?1 ");
        sql.append("    AND v.status = 'PUBLISHED' ");
        sql.append("  GROUP BY ei.entity_instance_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  fb.instance_id, fb.meta_id, fb.uid, fb.title, fb.description, fb.type, fb.keywords, ");
        sql.append("  fb.versioning_status, fb.editor_id, ");
        sql.append("  COALESCE(fsp.locations, '') AS spatial_locations, ");
        sql.append("  COALESCE(CAST(fc.categories AS text), '[]') AS categories, ");
        sql.append("  COALESCE(CAST(TO_JSON(fp.page_urls) AS text), '[]') AS page_urls, ");
        sql.append("  COALESCE(CAST(fo.owners AS text), '[]') AS owners, ");
        sql.append("  fti.type_name, ");
        sql.append("  COALESCE(CAST(et.equipment_type_names AS text), '[]') AS equipment_types ");
        sql.append("FROM facility_base fb ");
        sql.append("LEFT JOIN facility_spatial fsp ON fb.instance_id = fsp.facility_instance_id ");
        sql.append("LEFT JOIN facility_categories fc ON fb.instance_id = fc.facility_instance_id ");
        sql.append("LEFT JOIN facility_pages fp ON fb.instance_id = fp.facility_instance_id ");
        sql.append("LEFT JOIN facility_owners fo ON fb.instance_id = fo.facility_instance_id ");
        sql.append("LEFT JOIN facility_type_info fti ON fb.instance_id = fti.instance_id ");
        sql.append("LEFT JOIN equipment_types et ON fb.instance_id = et.facility_instance_id ");

        return sql.toString();
    }

    private static Facility mapToFacility(Object[] row, EntityManager em) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String title = (String) row[i++];
        String description = (String) row[i++];
        String type = (String) row[i++];
        String keywords = (String) row[i++];
        String versioningStatus = (String) row[i++];
        String editorId = (String) row[i++];
        String spatialLocations = (String) row[i++];
        String categoriesJson = (String) row[i++];
        String pageUrlsJson = (String) row[i++];
        String ownersJson = (String) row[i++];
        String typeName = (String) row[i++];
        String equipmentTypesJson = (String) row[i++];

        Facility facility = new Facility();

        facility.setId(instanceId);
        facility.setUid(uid);
        facility.setTitle(title);
        facility.setDescription(description);

        if (type != null) {
            String[] typeParts = type.split("/");
            facility.setType(typeParts[typeParts.length - 1].trim());
        }

        // Override with type name from category if available
        if (typeName != null) {
            facility.setType(typeName);
        }

        facility.setHref(EnvironmentVariables.API_HOST + API_PATH_DETAILS + metaId);

        // Keywords
        if (keywords != null && !keywords.isEmpty()) {
            List<String> keywordList = Arrays.stream(keywords.split(","))
                    .map(String::trim)
                    .filter(k -> !k.isEmpty())
                    .collect(Collectors.toList());
            facility.setKeywords(keywordList);
        } else {
            facility.setKeywords(new ArrayList<>());
        }

        // Spatial
        if (spatialLocations != null && !spatialLocations.isEmpty()) {
            String[] locations = spatialLocations.split("#EPOS#");
            for (String location : locations) {
                String trimmed = location.trim();
                if (!trimmed.isEmpty()) {
                    facility.getSpatial().addPaths(
                            SpatialInformation.doSpatial(trimmed),
                            SpatialInformation.checkPoint(trimmed)
                    );
                }
            }
        }

        // Data providers (owners)
        if (!isEmptyJson(ownersJson)) {
            List<Organization> organizations = parseOrganizations(ownersJson);
            facility.setDataProvider(DataServiceProviderGeneration.getProviders(organizations));
        }

        // Page URLs
        if (!isEmptyJson(pageUrlsJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(pageUrlsJson);
                for (JsonNode node : arrayNode) {
                    if (!node.isNull()) {
                        facility.getPage().add(node.asText());
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse page URLs: {}", e.getMessage());
            }
        }

        // Equipment types as service parameter
        List<String> equipmentTypeNames = new ArrayList<>();
        if (!isEmptyJson(equipmentTypesJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(equipmentTypesJson);
                for (JsonNode node : arrayNode) {
                    if (!node.isNull()) {
                        equipmentTypeNames.add(node.asText());
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse equipment types: {}", e.getMessage());
            }
        }

        facility.setServiceParameters(new ArrayList<>());
        ServiceParameter sp = new ServiceParameter();
        sp.setName("equipmenttypes");
        sp.setLabel("Equipment types");
        sp.setEnumValue(equipmentTypeNames);
        sp.setRequired(false);
        sp.setType("string");
        sp.setMultipleValue("true");
        facility.getServiceParameters().add(sp);

        // Available formats
        facility.setAvailableFormats(List.of(new AvailableFormat.AvailableFormatBuilder()
                .originalFormat("application/epos.geo+json")
                .format("application/epos.geo+json")
                .href(EnvironmentVariables.API_HOST + API_PATH_EXECUTE_EQUIPMENTS + "all" + API_FORMAT
                        + "application/epos.geo+json" + "&facilityid=" + instanceId)
                .label("GEOJSON")
                .type(AvailableFormatType.CONVERTED)
                .build()));

        // Categories (facets)
        List<String> categoryList = new ArrayList<>();
        Set<String> facetsDataProviders = new HashSet<>();

        if (!isEmptyJson(categoriesJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(categoriesJson);
                for (JsonNode node : arrayNode) {
                    String catUid = getTextOrNull(node, "uid");
                    if (catUid != null && catUid.contains("category:")) {
                        categoryList.add(catUid);
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse categories: {}", e.getMessage());
            }
        }

        if (!isEmptyJson(ownersJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(ownersJson);
                for (JsonNode node : arrayNode) {
                    String legalName = getTextOrNull(node, "legal_name");
                    if (legalName != null) {
                        facetsDataProviders.add(legalName);
                    }
                }
            } catch (JsonProcessingException e) {
                LOGGER.warn("Failed to parse owners for facets: {}", e.getMessage());
            }
        }

        ArrayList<DiscoveryItem> discoveryList = new ArrayList<>();
        discoveryList.add(new DiscoveryItemBuilder(instanceId,
                EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId,
                null)
                .uid(uid)
                .title(title)
                .description(description)
                .availableFormats(facility.getAvailableFormats())
                .facilityProvider(facetsDataProviders)
                .categories(categoryList.isEmpty() ? null : categoryList)
                .build());

        FacetsNodeTree categories = FacetsGeneration.generateResponseUsingCategories(discoveryList, Facets.Type.FACILITY);
        categories.getNodes().forEach(node -> node.setDistributions(null));
        facility.setCategories(categories.getFacets());

        return facility;
    }

    private static FeaturesCollection generateAsGeoJson(Object[] facilityData, EntityManager em, String equipmentTypes) {
        int i = 0;

        String instanceId = (String) facilityData[i++];
        String metaId = (String) facilityData[i++];
        String uid = (String) facilityData[i++];
        String title = (String) facilityData[i++];
        String description = (String) facilityData[i++];
        String type = (String) facilityData[i++];
        i++; // keywords
        i++; // versioningStatus
        i++; // editorId
        String spatialLocations = (String) facilityData[i++];
        i++; // categoriesJson
        i++; // pageUrlsJson
        i++; // ownersJson
        String typeName = (String) facilityData[i++];

        FeaturesCollection geojson = new FeaturesCollection();
        Feature feature = new Feature();

        feature.addSimpleProperty("Name", title != null ? title : "");
        feature.addSimpleProperty("Description", description != null ? description : "");
        feature.addSimpleProperty("Type", typeName != null ? typeName : (type != null ? type.trim() : ""));

        // Parse spatial data
        if (spatialLocations != null && !spatialLocations.isEmpty()) {
            String[] locations = spatialLocations.split("#EPOS#");
            for (String location : locations) {
                String trimmed = location.trim();
                if (!trimmed.isEmpty()) {
                    Geometry geometry = parseGeometry(trimmed);
                    if (geometry != null) {
                        feature.setGeometry(geometry);
                    }
                }
            }
        }

        // Add link to equipments
        LinkObject link = new LinkObject();
        link.setAuthenticatedDownload(false);
        link.setLabel("Equipments");
        link.setType("application/epos.table.geo+json");
        link.setHref(EnvironmentVariables.API_HOST + API_PATH_EXECUTE_EQUIPMENTS + "all" + API_FORMAT
                + "application/epos.geo+json" + "&facilityid=" + metaId);

        feature.addSimpleProperty("@epos_links", List.of(link));

        geojson.addFeature(feature);

        return geojson;
    }

    private static Geometry parseGeometry(String wkt) {
        boolean isPoint = wkt.contains("POINT");
        String location = wkt.replaceAll("POLYGON", "").replaceAll("POINT", "")
                .replaceAll("\\(", "").replaceAll("\\)", "");
        String[] coordinates = location.split(",");

        if (isPoint) {
            Point point = new Point();
            for (String coo : coordinates) {
                String[] cooz = coo.trim().split("\\s+");
                if (cooz.length >= 2) {
                    point.setCoordinates(new PointCoordinates(
                            Double.parseDouble(cooz[0]),
                            Double.parseDouble(cooz[1])
                    ));
                }
            }
            return point;
        } else {
            Polygon polygon = new Polygon();
            ArrayList<PointCoordinates> points = new ArrayList<>();
            for (String coo : coordinates) {
                String[] cooz = coo.trim().split("\\s+");
                if (cooz.length >= 2) {
                    points.add(new PointCoordinates(
                            Double.parseDouble(cooz[0]),
                            Double.parseDouble(cooz[1])
                    ));
                }
            }
            if (!points.isEmpty()) {
                polygon.setStartingPoint(points.get(0));
                for (int j = 1; j < points.size(); j++) {
                    polygon.addAdditionalPoint(points.get(j));
                }
            }
            return polygon;
        }
    }

    private static List<Organization> parseOrganizations(String json) {
        List<Organization> organizations = new ArrayList<>();

        if (isEmptyJson(json)) {
            return organizations;
        }

        try {
            JsonNode arrayNode = OBJECT_MAPPER.readTree(json);
            for (JsonNode node : arrayNode) {
                Organization org = new Organization();
                org.setInstanceId(getTextOrNull(node, "instance_id"));
                String legalName = getTextOrNull(node, "legal_name");
                if (legalName != null) {
                    org.setLegalName(Collections.singletonList(legalName));
                }
                org.setURL(getTextOrNull(node, "url"));
                org.setLogo(getTextOrNull(node, "logo"));
                organizations.add(org);
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse organizations: {}", e.getMessage());
        }

        return organizations;
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
