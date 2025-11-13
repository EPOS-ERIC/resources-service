package org.epos.api.core.software;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.epos.api.beans.AvailableContactPoints;
import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.software.SoftwareApplicationResponse;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.enums.ProviderType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.eposdatamodel.Category;
import org.epos.eposdatamodel.Identifier;
import org.epos.eposdatamodel.SoftwareApplication;

import commonapis.LinkedEntityAPI;
import org.epos.eposdatamodel.SoftwareSourceCode;

public class SoftwareApplicationGenerationJPA {

	private static final Logger logger = LogManager.getLogger(SoftwareApplicationGenerationJPA.class);
	private static final String EMAIL_SENDER = EnvironmentVariables.API_CONTEXT + "/sender/send-email?id=";

	public static SoftwareApplicationResponse generate(SoftwareApplication softwareApplication) {
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
        response.setAvailableFormats(createFormatsForApplication(softwareApplication));
		// Split keywords by comma and return as List<String>, trimming spaces
		String keywordsStr = softwareApplication.getKeywords();
		if (keywordsStr != null && !keywordsStr.trim().isEmpty()) {
			List<String> kw = List.of(keywordsStr.split(",")).stream()
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.toList();
			response.setKeywords(kw.isEmpty() ? null : kw);
		} else {
			response.setKeywords(null);
		}
		response.setId(softwareApplication.getInstanceId());

		// Identifiers
		logger.info("SoftwareApplicationGenerationJPA.generate: softwareApplication.getIdentifier()={}",
				softwareApplication.getIdentifier());
		List<String> doi = new ArrayList<>();
		List<String> identifiers = new ArrayList<>();
		if (softwareApplication.getIdentifier() != null) {
			softwareApplication.getIdentifier().forEach(identifierLe -> {
				Identifier identifier = (Identifier) LinkedEntityAPI.retrieveFromLinkedEntity(identifierLe);
				if (identifier != null) {
					if (identifier.getType().equals("DOI")) {
						doi.add(identifier.getIdentifier());
					}
					if (identifier.getType().equals("DDSS-ID")) {
						String ddss = identifier.getIdentifier();
						if (ddss != null) {
							identifiers.add(ddss);
						}
					}
				}
			});
		}
		response.setDoi(doi.isEmpty() ? null : doi);
		response.setIdentifiers(identifiers.isEmpty() ? null : identifiers);

		// Contact Points
		logger.info("SoftwareApplicationGenerationJPA.generate: softwareApplication.getContactPoint()={}",
				softwareApplication.getContactPoint());
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

		// Categories
		logger.info("SoftwareApplicationGenerationJPA.generate: softwareApplication.getCategory()={}",
				softwareApplication.getCategory());
		if (softwareApplication.getCategory() != null) {
			List<String> categoryList = softwareApplication.getCategory().stream()
					.map(linkedEntity -> (Category) LinkedEntityAPI.retrieveFromLinkedEntity(linkedEntity))
					.filter(Objects::nonNull)
					.map(Category::getUid)
					.filter(uid -> uid.contains("category:"))
					.collect(Collectors.toList());

			if (!categoryList.isEmpty()) {
				List<DiscoveryItem> discoveryList = new ArrayList<>();
				discoveryList
						.add(new DiscoveryItem.DiscoveryItemBuilder(softwareApplication.getInstanceId(), null, null)
								.categories(categoryList)
								.build());

				org.epos.api.facets.FacetsNodeTree categoriesTree = FacetsGeneration
						.generateResponseUsingCategories(discoveryList, Facets.Type.SOFTWARE);
				categoriesTree.getNodes().forEach(node -> node.setDistributions(null));
				response.setCategories(categoriesTree.getFacets());
			}
		}

		return response;
	}

    private static List<AvailableFormat> createFormatsForApplication(SoftwareApplication software) {
        if (software.getDownloadURL() != null) {
            return createFormatsFromUrl(software.getDownloadURL());
        } else if (software.getMainEntityOfPage() != null) {
            return createFormatsFromUrl(software.getMainEntityOfPage());
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
    private static final Pattern FORMAT_PATTERN = Pattern.compile("\\.([a-zA-Z0-9]+)(?:/|$|\\?)");

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
