package org.epos.api.core.facilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.DiscoveryItem.DiscoveryItemBuilder;
import org.epos.api.beans.Facility;
import org.epos.api.beans.ServiceParameter;
import org.epos.api.beans.SpatialInformation;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.distributions.DistributionDetailsGenerationJPA;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.FacetsNodeTree;
import org.epos.eposdatamodel.Category;
import org.epos.eposdatamodel.Equipment;
import org.epos.eposdatamodel.LinkedEntity;
import org.epos.eposdatamodel.Location;
import org.epos.eposdatamodel.Organization;
import org.epos.library.feature.Feature;
import org.epos.library.feature.FeaturesCollection;
import org.epos.library.geometries.Geometry;
import org.epos.library.geometries.Point;
import org.epos.library.geometries.PointCoordinates;
import org.epos.library.geometries.Polygon;
import org.epos.library.objects.LinkObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;

public class FacilityDetailsItemGenerationJPA {

	private static final Logger LOGGER = LoggerFactory.getLogger(FacilityDetailsItemGenerationJPA.class);

	private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/facilities/details/";
	private static final String API_PATH_EXECUTE_EQUIPMENTS  = EnvironmentVariables.API_CONTEXT+"/equipments/";
	private static final String API_FORMAT = "?format=";

	public static Object generate(Map<String,Object> parameters) {

		LOGGER.info("Parameters {}", parameters);

		org.epos.eposdatamodel.Facility facilitySelected = (org.epos.eposdatamodel.Facility) AbstractAPI.retrieveAPI(EntityNames.FACILITY.name()).retrieve(parameters.get("id").toString());
		// maybe the details is for a service from the data panel
		if (facilitySelected == null) {
			LOGGER.info("Given id is not of a facility, checking if it's a distribution");
			return DistributionDetailsGenerationJPA.generate(parameters, Facets.Type.FACILITY);
		}
		List<Organization> organizationForOwners  = (List<Organization>) AbstractAPI.retrieveAPI(EntityNames.ORGANIZATION.name()).retrieveAll();
		List<Category> categoriesFromDB = (List<Category>) AbstractAPI.retrieveAPI(EntityNames.CATEGORY.name()).retrieveAll();

		if(parameters.containsKey("format") && parameters.get("format").toString().equals("application/epos.geo+json"))
			return generateAsGeoJson(facilitySelected, parameters.containsKey("equipmenttypes")? parameters.get("equipmenttypes").toString() : null);
		else {
			Facility facility = new Facility();

			if (facilitySelected.getTitle() != null) facility.setTitle(facilitySelected.getTitle());
			if (facilitySelected.getDescription() != null) facility.setDescription(facilitySelected.getDescription());

			if (facilitySelected.getType() != null) {
				String[] type = facilitySelected.getType().split("\\/");
				facility.setType(type[type.length - 1]);
			}

			facility.setId(facilitySelected.getInstanceId());
			facility.setUid(facilitySelected.getUid());

			facility.setHref(EnvironmentVariables.API_HOST + API_PATH_DETAILS + facilitySelected.getMetaId());


            List<String> keywords =facilitySelected.getKeywords();
			
			keywords.removeAll(Collections.singleton(null));
			keywords.removeAll(Collections.singleton(""));
			facility.setKeywords(new ArrayList<>(keywords));

			// Facility Types
			List<Category> type = categoriesFromDB
					.stream()
                    .filter(cat -> cat.getUid()!=null)
					.filter(cat -> cat.getUid().equals(facilitySelected.getType())).collect(Collectors.toList());

			if(type!=null && !type.isEmpty())
				facility.setType(type.get(0).getName());


			if (facilitySelected.getSpatialExtent() != null) {
				for (LinkedEntity s : facilitySelected.getSpatialExtent()) {
					Location location = (Location) LinkedEntityAPI.retrieveFromLinkedEntity(s);
					facility.getSpatial().addPaths(SpatialInformation.doSpatial(location.getLocation()), SpatialInformation.checkPoint(location.getLocation()));
				}
			}

			Set<Organization> organizationsEntityIds = new HashSet<>();

			organizationForOwners.forEach(organization -> {
				if(organization.getOwns()!=null)
					for(LinkedEntity linkedEntity : organization.getOwns()){
						if(linkedEntity.getEntityType().equals(EntityNames.FACILITY.name()) && linkedEntity.getInstanceId().equals(facilitySelected.getInstanceId())){
							organizationsEntityIds.add(organization);
						}
					}
			});

			facility.setDataProvider(DataServiceProviderGeneration.getProviders(new ArrayList<Organization>(organizationsEntityIds)));
			if(facilitySelected.getPageURL()!=null) {
				facilitySelected.getPageURL().forEach(page -> {
					facility.getPage().add(page);
				});
			}

			Set<Category> equipmentTypes = new HashSet<>();

			//Equipment types
			((List<Equipment>) AbstractAPI.retrieveAPI(EntityNames.EQUIPMENT.name()).retrieveAll()).forEach(equipment -> {
				if(equipment.getIsPartOf()!=null)
					for(LinkedEntity linkedEntity : equipment.getIsPartOf()){
						if(linkedEntity.getEntityType().equals(EntityNames.FACILITY.name()) && linkedEntity.getInstanceId().equals(facilitySelected.getInstanceId())){
							categoriesFromDB
									.stream()
                                    .filter(cat -> cat.getUid()!=null)
									.filter(cat -> cat.getUid().equals(equipment.getType()))
									.forEach(equipmentTypes::add);
						}
					}
			});

			facility.setServiceParameters(new ArrayList<>());
			ServiceParameter sp = new ServiceParameter();
			sp.setName("equipmenttypes");
			sp.setLabel("Equipment types");
			sp.setEnumValue(
                    !equipmentTypes.isEmpty() ?
							equipmentTypes.stream().map(Category::getName).collect(Collectors.toList())
							: new ArrayList<>()
					);
			sp.setRequired(false);
			sp.setType("string");
			sp.setMultipleValue("true");
			facility.getServiceParameters().add(sp);

			facility.setAvailableFormats(List.of(new AvailableFormat.AvailableFormatBuilder()
					.originalFormat("application/epos.geo+json")
					.format("application/epos.geo+json")
					.href(EnvironmentVariables.API_HOST + API_PATH_EXECUTE_EQUIPMENTS + "all"+ API_FORMAT + "application/epos.geo+json"+"&facilityid=" + facilitySelected.getInstanceId())
					.label("GEOJSON")
					.type(AvailableFormatType.CONVERTED)
					.build()));

			// TEMP SECTION
			ArrayList<DiscoveryItem> discoveryList = new ArrayList<>();

			Set<String> facetsDataProviders = new HashSet<String>();

			organizationForOwners.forEach(organization -> {
				if(organization.getOwns()!=null)
					for(LinkedEntity linkedEntity : organization.getOwns()){
						if(linkedEntity.getEntityType().equals(EntityNames.FACILITY.name()) && linkedEntity.getInstanceId().equals(facilitySelected.getInstanceId())){
							facetsDataProviders.add(String.join(",",organization.getLegalName()));
						}
					}
			});

			List<String> categoryList = facilitySelected.getCategory().stream()
					.map(linkedEntity -> (Category) LinkedEntityAPI.retrieveFromLinkedEntity(linkedEntity))
					.map(Category::getUid)
					.filter(uid -> uid.contains("category:"))
					.collect(Collectors.toList());

			discoveryList.add(new DiscoveryItemBuilder(facilitySelected.getInstanceId(),
					EnvironmentVariables.API_HOST + API_PATH_DETAILS + facilitySelected.getInstanceId(),
					null)
					.uid(facility.getUid())
					.title(facility.getTitle())
					.description(facility.getDescription())
					.availableFormats(List.of(new AvailableFormat.AvailableFormatBuilder()
							.originalFormat("application/epos.geo+json")
							.format("application/epos.geo+json")
							.href(EnvironmentVariables.API_HOST + API_PATH_EXECUTE_EQUIPMENTS + "all"+ API_FORMAT + "application/epos.geo+json"+"&facilityid=" + facilitySelected.getInstanceId())
							.label("GEOJSON")
							.type(AvailableFormatType.CONVERTED)
							.build()))
					.facilityProvider(facetsDataProviders)
					.categories(categoryList.isEmpty() ? null : categoryList)
					.build());

			FacetsNodeTree categories = FacetsGeneration.generateResponseUsingCategories(discoveryList, Facets.Type.FACILITY);
			categories.getNodes().forEach(node -> node.setDistributions(null));
			facility.setCategories(categories.getFacets());

			return facility;
		}
	}

	public static FeaturesCollection generateAsGeoJson(org.epos.eposdatamodel.Facility facilitySelected, String equipmenttypes) {

		List<Category> categoriesFromDB = (List<Category>) AbstractAPI.retrieveAPI(EntityNames.CATEGORY.name()).retrieveAll();

		FeaturesCollection geojson = new FeaturesCollection();

		Feature feature = new Feature();

		//COMMON FOR FACILITY
		feature.addSimpleProperty("Name", Optional.ofNullable(facilitySelected.getTitle()).orElse(""));
		feature.addSimpleProperty("Description", Optional.ofNullable(facilitySelected.getDescription()).orElse(""));
		feature.addSimpleProperty("Type", Optional.of(categoriesFromDB
				.stream()
                        .filter(cat -> cat.getUid()!=null)
				.filter(cat -> cat.getUid().equals(facilitySelected.getType())).map(Category::getName).collect(Collectors.toList())).get().toString());

		for(LinkedEntity locLe : facilitySelected.getSpatialExtent()) {
			Location loc = (Location) LinkedEntityAPI.retrieveFromLinkedEntity(locLe);
			String location = loc.getLocation();
			boolean isPoint = location.contains("POINT");
			location = location.replaceAll("POLYGON", "").replaceAll("POINT", "").replaceAll("\\(", "").replaceAll("\\)", "");
			String[] coordinates = location.split("\\,");
			Geometry geometry = null;

			if(isPoint) {
				geometry = new Point();
				for(String coo : coordinates) {
					String[] cooz = coo.split(" ");
					if(cooz.length==2) {
						((Point) geometry).setCoordinates(new PointCoordinates(Double.parseDouble(cooz[0]), Double.parseDouble(cooz[1])));
					}else {
						((Point) geometry).setCoordinates(new PointCoordinates(Double.parseDouble(cooz[1]), Double.parseDouble(cooz[2])));
					}
				}
			}else {
				geometry = new Polygon();
				ArrayList<PointCoordinates> deep = new ArrayList<PointCoordinates>();
				for(String coo : coordinates) {
					String[] cooz = coo.split(" ");
					if(cooz.length==2) {
						deep.add(new PointCoordinates(Double.parseDouble(cooz[0]), Double.parseDouble(cooz[1])));
					}else {
						deep.add(new PointCoordinates(Double.parseDouble(cooz[1]), Double.parseDouble(cooz[2])));
					}
				}
				((Polygon) geometry).setStartingPoint(deep.get(0));
				for(int i = 1; i<deep.size();i++)
					((Polygon) geometry).addAdditionalPoint(deep.get(i));	
			}
			feature.setGeometry(geometry);
		}

		LinkObject link = new LinkObject();

		link.setAuthenticatedDownload(false);
		link.setLabel("Equipments");
		link.setType("application/epos.table.geo+json");
		link.setHref(EnvironmentVariables.API_HOST + API_PATH_EXECUTE_EQUIPMENTS + "all"+ API_FORMAT + "application/epos.geo+json"+"&facilityid=" + facilitySelected.getMetaId());

		feature.addSimpleProperty("@epos_links",List.of(link));



		geojson.addFeature(feature);

		return geojson;
	}


}
