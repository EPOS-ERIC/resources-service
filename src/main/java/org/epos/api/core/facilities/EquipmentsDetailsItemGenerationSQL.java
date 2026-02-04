package org.epos.api.core.facilities;

import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.epos.api.core.EnvironmentVariables;
import org.epos.api.utility.Utils;
import org.epos.eposdatamodel.Equipment;
import org.epos.eposdatamodel.LinkedEntity;
import org.epos.eposdatamodel.Organization;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.epos.library.feature.Feature;
import org.epos.library.feature.FeaturesCollection;
import org.epos.library.geometries.Geometry;
import org.epos.library.geometries.Point;
import org.epos.library.geometries.PointCoordinates;
import org.epos.library.geometries.Polygon;
import org.epos.library.propertiestypes.PropertyDataKeys;
import org.epos.library.propertiestypes.PropertyMapKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL-based implementation for equipment details.
 * This replaces the JPA-based EquipmentsDetailsItemGenerationJPA for improved performance.
 */
public class EquipmentsDetailsItemGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(EquipmentsDetailsItemGenerationSQL.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SPATIAL_SEPARATOR = " #EPOS# ";

    public static Object generate(Map<String, Object> parameters) {
        final long startTime = System.nanoTime();
        LOGGER.info("Generating equipment details (SQL) for parameters: {}", parameters);

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            String facilityId = parameters.containsKey("facilityid") ?
                    parameters.get("facilityid").toString() : null;
            String equipmentId = parameters.containsKey("id") && !"all".equals(parameters.get("id")) ?
                    parameters.get("id").toString() : null;

            // Parse equipment type filter from params
            List<String> equipmentTypeFilter = parseEquipmentTypeFilter(parameters);

            // Fetch equipment data
            List<Object[]> equipmentRows = fetchEquipmentData(em, facilityId, equipmentId, equipmentTypeFilter);

            LOGGER.debug("Found {} equipment items", equipmentRows.size());

            // Check if GeoJSON format is requested
            if (parameters.containsKey("format") &&
                    "application/epos.geo+json".equals(parameters.get("format").toString())) {

                // Fetch facility data for GeoJSON
                String facilityTitle = null;
                if (facilityId != null) {
                    facilityTitle = fetchFacilityTitle(em, facilityId);
                }

                return generateAsGeoJson(equipmentRows, facilityTitle);
            }

            // Return as list of Equipment objects
            List<Equipment> equipmentList = mapToEquipmentList(equipmentRows);

            LOGGER.info("Equipment details generated (SQL) in {} ms with {} items",
                    (System.nanoTime() - startTime) / 1_000_000, equipmentList.size());

            return equipmentList;

        } catch (Exception e) {
            LOGGER.error("Failed to generate equipment details", e);
            throw new RuntimeException("Failed to generate equipment details", e);
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }

    private static List<String> parseEquipmentTypeFilter(Map<String, Object> parameters) {
        if (!parameters.containsKey("params")) {
            return Collections.emptyList();
        }

        try {
            JsonObject params = Utils.gson.fromJson(parameters.get("params").toString(), JsonObject.class);
            if (params.has("equipmenttypes")) {
                String equipmenttypes = params.get("equipmenttypes").getAsString();
                if (!equipmenttypes.isBlank()) {
                    return Arrays.asList(equipmenttypes.split(","));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Not valid json, skip filter: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    private static List<Object[]> fetchEquipmentData(EntityManager em, String facilityId, String equipmentId,
                                                     List<String> equipmentTypeFilter) {
        StringBuilder sql = new StringBuilder();

        sql.append("WITH equipment_base AS ( ");
        sql.append("  SELECT e.instance_id, e.meta_id, e.uid, e.name, e.description, e.type, e.keywords, ");
        sql.append("         e.pageurl, e.filter, e.dynamicrange, e.orientation, e.resolution, ");
        sql.append("         e.sampleperiod, e.serialnumber, e.creator, e.identifier ");
        sql.append("  FROM metadata_catalogue.equipment e ");
        sql.append("  JOIN metadata_catalogue.versioningstatus v ON e.version_id = v.version_id ");

        // Join with equipment_ispartof if filtering by facility
        if (facilityId != null) {
            sql.append("  JOIN metadata_catalogue.equipment_ispartof ei ON e.instance_id = ei.equipment_instance_id ");
        }

        sql.append("  WHERE v.status = 'PUBLISHED' ");

        if (equipmentId != null) {
            sql.append("    AND e.instance_id = '").append(equipmentId).append("' ");
        }

        if (facilityId != null) {
            sql.append("    AND ei.resource_entity = 'FACILITY' ");
            sql.append("    AND ei.entity_instance_id = '").append(facilityId).append("' ");
        }

        sql.append("), ");

        // Equipment spatial
        sql.append("equipment_spatial AS ( ");
        sql.append("  SELECT es.equipment_instance_id, STRING_AGG(s.location, '").append(SPATIAL_SEPARATOR).append("') AS locations ");
        sql.append("  FROM metadata_catalogue.equipment_spatial es ");
        sql.append("  JOIN metadata_catalogue.spatial s ON es.spatial_instance_id = s.instance_id ");
        sql.append("  WHERE es.equipment_instance_id IN (SELECT instance_id FROM equipment_base) ");
        sql.append("  GROUP BY es.equipment_instance_id ");
        sql.append("), ");

        // Equipment type name from category
        sql.append("equipment_type_info AS ( ");
        sql.append("  SELECT eb.instance_id, c.name AS type_name ");
        sql.append("  FROM equipment_base eb ");
        sql.append("  LEFT JOIN metadata_catalogue.category c ON TRIM(c.uid) = TRIM(eb.type) ");
        sql.append("), ");

        // Manufacturer (organization)
        sql.append("equipment_manufacturer AS ( ");
        sql.append("  SELECT eb.instance_id, o.uid AS manufacturer_uid ");
        sql.append("  FROM equipment_base eb ");
        sql.append("  LEFT JOIN metadata_catalogue.organization o ON eb.creator = o.instance_id ");
        sql.append("), ");

        // Equipment categories
        sql.append("equipment_categories AS ( ");
        sql.append("  SELECT ec.equipment_instance_id, ARRAY_AGG(c.name) AS category_names ");
        sql.append("  FROM metadata_catalogue.equipment_category ec ");
        sql.append("  JOIN metadata_catalogue.category c ON ec.category_instance_id = c.instance_id ");
        sql.append("  WHERE ec.equipment_instance_id IN (SELECT instance_id FROM equipment_base) ");
        sql.append("  GROUP BY ec.equipment_instance_id ");
        sql.append(") ");

        // Main SELECT
        sql.append("SELECT ");
        sql.append("  eb.instance_id, eb.meta_id, eb.uid, eb.name, eb.description, eb.type, eb.keywords, ");
        sql.append("  eb.pageurl, eb.filter, eb.dynamicrange, eb.orientation, eb.resolution, ");
        sql.append("  eb.sampleperiod, eb.serialnumber, eb.identifier, ");
        sql.append("  COALESCE(es.locations, '') AS spatial_locations, ");
        sql.append("  eti.type_name, ");
        sql.append("  em.manufacturer_uid, ");
        sql.append("  COALESCE(CAST(TO_JSON(ecat.category_names) AS text), '[]') AS category_names ");
        sql.append("FROM equipment_base eb ");
        sql.append("LEFT JOIN equipment_spatial es ON eb.instance_id = es.equipment_instance_id ");
        sql.append("LEFT JOIN equipment_type_info eti ON eb.instance_id = eti.instance_id ");
        sql.append("LEFT JOIN equipment_manufacturer em ON eb.instance_id = em.instance_id ");
        sql.append("LEFT JOIN equipment_categories ecat ON eb.instance_id = ecat.equipment_instance_id ");

        // Apply equipment type filter if specified
        if (!equipmentTypeFilter.isEmpty()) {
            sql.append("WHERE eti.type_name IN (");
            for (int i = 0; i < equipmentTypeFilter.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("'").append(equipmentTypeFilter.get(i)).append("'");
            }
            sql.append(") ");
        }

        sql.append("ORDER BY eb.instance_id ");

        Query query = em.createNativeQuery(sql.toString());

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        return results;
    }

    private static String fetchFacilityTitle(EntityManager em, String facilityId) {
        String sql = "SELECT f.title FROM metadata_catalogue.facility f WHERE f.instance_id = ?1";
        Query query = em.createNativeQuery(sql);
        query.setParameter(1, facilityId);

        @SuppressWarnings("unchecked")
        List<Object> results = query.getResultList();

        if (!results.isEmpty() && results.get(0) != null) {
            return results.get(0).toString();
        }
        return null;
    }

    private static List<Equipment> mapToEquipmentList(List<Object[]> rows) {
        List<Equipment> equipmentList = new ArrayList<>();

        for (Object[] row : rows) {
            Equipment equipment = mapRowToEquipment(row);
            equipmentList.add(equipment);
        }

        return equipmentList;
    }

    private static Equipment mapRowToEquipment(Object[] row) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String name = (String) row[i++];
        String description = (String) row[i++];
        String type = (String) row[i++];
        String keywords = (String) row[i++];
        String pageUrl = (String) row[i++];
        String filter = (String) row[i++];
        String dynamicRange = (String) row[i++];
        String orientation = (String) row[i++];
        String resolution = (String) row[i++];
        String samplePeriod = (String) row[i++];
        String serialNumber = (String) row[i++];
        String identifier = (String) row[i++];
        String spatialLocations = (String) row[i++];
        String typeName = (String) row[i++];
        String manufacturerUid = (String) row[i++];
        String categoryNamesJson = (String) row[i++];

        Equipment equipment = new Equipment();
        equipment.setInstanceId(instanceId);
        equipment.setMetaId(metaId);
        equipment.setUid(uid);
        equipment.setName(name);
        equipment.setDescription(description);
        equipment.setType(type != null ? type.trim() : null);
        equipment.setPageURL(pageUrl);
        equipment.setFilter(filter);
        equipment.setDynamicRange(dynamicRange);
        equipment.setOrientation(orientation);
        equipment.setResolution(resolution);
        equipment.setSamplePeriod(samplePeriod);
        equipment.setSerialNumber(serialNumber);

        // Parse keywords
        if (keywords != null && !keywords.isEmpty()) {
            equipment.setKeywords(Arrays.asList(keywords.split(",")));
        }

        // Note: Categories are used in GeoJSON output, but Equipment model uses LinkedEntity for category
        // The category names from SQL are used only in the GeoJSON feature generation

        // Manufacturer
        if (manufacturerUid != null) {
            LinkedEntity manufacturer = new LinkedEntity();
            manufacturer.setUid(manufacturerUid);
            equipment.setManufacturer(manufacturer);
        }

        return equipment;
    }

    private static FeaturesCollection generateAsGeoJson(List<Object[]> equipmentRows, String facilityTitle) {
        FeaturesCollection geojson = new FeaturesCollection();

        for (Object[] row : equipmentRows) {
            Feature feature = mapRowToFeature(row);
            if (feature != null) {
                geojson.addFeature(feature);
            }
        }

        return geojson;
    }

    private static Feature mapRowToFeature(Object[] row) {
        int i = 0;

        String instanceId = (String) row[i++];
        String metaId = (String) row[i++];
        String uid = (String) row[i++];
        String name = (String) row[i++];
        String description = (String) row[i++];
        String type = (String) row[i++];
        String keywords = (String) row[i++];
        String pageUrl = (String) row[i++];
        String filter = (String) row[i++];
        String dynamicRange = (String) row[i++];
        String orientation = (String) row[i++];
        String resolution = (String) row[i++];
        String samplePeriod = (String) row[i++];
        String serialNumber = (String) row[i++];
        String identifier = (String) row[i++];
        String spatialLocations = (String) row[i++];
        String typeName = (String) row[i++];
        String manufacturerUid = (String) row[i++];
        String categoryNamesJson = (String) row[i++];

        Feature feature = new Feature();

        feature.addSimpleProperty("Equipment name", name);
        feature.addSimpleProperty("Description", description);

        // Type as list
        if (typeName != null) {
            feature.addSimpleProperty("Type", Collections.singletonList(typeName));
        } else if (type != null) {
            feature.addSimpleProperty("Type", Collections.singletonList(type.trim()));
        }

        // Categories
        if (!isEmptyJson(categoryNamesJson)) {
            try {
                JsonNode arrayNode = OBJECT_MAPPER.readTree(categoryNamesJson);
                List<String> categories = new ArrayList<>();
                for (JsonNode node : arrayNode) {
                    if (!node.isNull()) {
                        categories.add(node.asText());
                    }
                }
                feature.addSimpleProperty("Category", categories);
            } catch (JsonProcessingException e) {
                feature.addSimpleProperty("Category", null);
            }
        } else {
            feature.addSimpleProperty("Category", null);
        }

        feature.addSimpleProperty("Dynamic range", dynamicRange);
        feature.addSimpleProperty("Filter", filter);
        feature.addSimpleProperty("Manufacturer", manufacturerUid);
        feature.addSimpleProperty("Orientation", orientation);
        feature.addSimpleProperty("Page url", pageUrl);
        feature.addSimpleProperty("Resolution", resolution);
        feature.addSimpleProperty("Sample period", samplePeriod);
        feature.addSimpleProperty("Serial number", serialNumber);

        // Parse spatial data
        if (spatialLocations != null && !spatialLocations.isEmpty()) {
            String[] locations = spatialLocations.split("#EPOS#");
            for (String location : locations) {
                String trimmed = location.trim();
                if (!trimmed.isEmpty()) {
                    Geometry geometry = parseGeometry(trimmed);
                    if (geometry != null) {
                        feature.setGeometry(geometry);
                        break; // Use first valid geometry
                    }
                }
            }
        }

        // Data keys and map keys for table display
        List<Object> values = Arrays.asList(
                "Equipment name", "Description", "Type", "Category",
                "Dynamic range", "Filter", "Manufacturer", "Orientation",
                "Page url", "Resolution", "Sample period", "Serial number"
        );
        PropertyDataKeys pdk = new PropertyDataKeys(values);
        PropertyMapKeys pmk = new PropertyMapKeys(values);
        feature.addPropertyFromPropertyObject(pdk);
        feature.addPropertyFromPropertyObject(pmk);

        return feature;
    }

    private static Geometry parseGeometry(String wkt) {
        try {
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
        } catch (Exception e) {
            LOGGER.warn("Failed to parse geometry: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isEmptyJson(String json) {
        return json == null || json.isEmpty() || "[]".equals(json) || "null".equals(json);
    }
}
