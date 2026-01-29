package org.epos.api.core.software;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.epos.api.beans.AvailableFormat;
import org.epos.api.beans.DiscoveryItem;
import org.epos.api.beans.DiscoveryItem.DiscoveryItemBuilder;
import org.epos.api.beans.NodeFilters;
import org.epos.api.beans.SearchResponse;
import org.epos.api.core.AvailableFormatsGeneration;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.enums.AvailableFormatType;
import org.epos.api.facets.Facets;
import org.epos.api.facets.FacetsGeneration;
import org.epos.api.facets.Node;
import org.epos.eposdatamodel.Category;
import org.epos.eposdatamodel.DataProduct;
import org.epos.eposdatamodel.Distribution;
import org.epos.eposdatamodel.LinkedEntity;
import org.epos.eposdatamodel.SoftwareApplication;
import org.epos.eposdatamodel.SoftwareSourceCode;
import org.epos.eposdatamodel.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;
import model.StatusType;

public class SoftwareSearch {
	private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareSearch.class);
	private static final String API_PATH_DETAILS = EnvironmentVariables.API_CONTEXT + "/software/details/";
	private static final Pattern FORMAT_PATTERN = Pattern.compile("\\.([a-zA-Z0-9]+)(?:/|$|\\?)");

	public static SearchResponse generate(String query, User user, String versioningStatus) {
		LOGGER.info("Generating discovery items with query {}, status {}, user {}", query, versioningStatus,
				user != null ? user.getAuthIdentifier() : "public");
		long startTime = System.currentTimeMillis();

		DataCollector dataCollector = new DataCollector(user, versioningStatus);
		Set<DiscoveryItem> discoveryItems = new HashSet<>();
		Set<String> keywords = new HashSet<>();

		processDataProducts(query, dataCollector, discoveryItems, user, versioningStatus);

		processSoftwareSourceCodes(query, dataCollector.softwareSourceCodes, discoveryItems, keywords, user, versioningStatus);

		processSoftwareApplications(query, dataCollector.softwareApplications, discoveryItems, keywords, user, versioningStatus);

		SearchResponse response = buildSearchResponse(discoveryItems, keywords);

		long duration = System.currentTimeMillis() - startTime;
		LOGGER.info("Result done in ms: " + duration);
		return response;
	}

	private static void processDataProducts(
			String query,
			DataCollector dataCollector,
			Set<DiscoveryItem> discoveryItems,
			User user,
			String versioningStatus) {
		for (DataProduct dataProduct : dataCollector.dataProducts) {
			if (dataProduct == null
					|| dataProduct.getTitle().isEmpty()
					|| dataProduct.getDescription().isEmpty()
					|| dataProduct.getCategory() == null)
				continue;

			if (!hasValidCategory(dataProduct) || !matchesQuery(
					query,
					dataProduct.getTitle().getFirst(),
					dataProduct.getDescription().getFirst())) {
				continue;
			}

			for (var linkedEntity : dataProduct.getCategory()) {
				Optional<Category> category = findSoftwareCategory(
						linkedEntity.getInstanceId(),
						dataCollector.categories);
				if (category.isEmpty()) {
					continue;
				}

				addDistributionsToDiscovery(dataProduct, category.get(), discoveryItems, user, versioningStatus);
			}
		}
	}

	private static void processSoftwareSourceCodes(
			String query,
			List<SoftwareSourceCode> softwareSourceCodes,
			Set<DiscoveryItem> discoveryItems,
			Set<String> keywords,
			User user,
			String versioningStatus) {
		for (SoftwareSourceCode software : softwareSourceCodes) {
			if (!matchesQuery(query, software.getName(), software.getDescription())) {
				continue;
			}

			if (software.getCategory() == null) {
				LOGGER.warn("software source code {} doesn't have a category set", software.getUid());
				continue;
			}
			List<String> categoryList = extractCategoryUids(software.getCategory());
			List<AvailableFormat> formats = createFormatsForSourceCode(software);

			DiscoveryItem discoveryItem = createSoftwareDiscoveryItem(
					software.getInstanceId(),
					software.getUid(),
					software.getName(),
					software.getDescription(),
					formats,
					categoryList,
					user,
					versioningStatus,
					software.getStatus().name(),
					software.getEditorId());

			addKeywordsFromSoftware(software.getKeywords(), keywords);
			discoveryItems.add(discoveryItem);
		}
	}

	private static void processSoftwareApplications(
			String query,
			List<SoftwareApplication> softwareApplications,
			Set<DiscoveryItem> discoveryItems,
			Set<String> keywords,
			User user,
			String versioningStatus) {
		for (SoftwareApplication software : softwareApplications) {
			if (!matchesQuery(query, software.getName(), software.getDescription())) {
				continue;
			}
			if (software.getCategory() == null) {
				LOGGER.warn("software application {} doesn't have a category set", software.getUid());
				continue;
			}
			List<String> categoryList = extractCategoryUids(software.getCategory());
			List<AvailableFormat> formats = createFormatsForApplication(software);

			DiscoveryItem discoveryItem = createSoftwareDiscoveryItem(
					software.getInstanceId(),
					software.getUid(),
					software.getName(),
					software.getDescription(),
					formats,
					categoryList,
					user,
					versioningStatus,
					software.getStatus().name(),
					software.getEditorId());

			addKeywordsFromSoftware(software.getKeywords(), keywords);
			discoveryItems.add(discoveryItem);
		}
	}

	private static boolean hasValidCategory(DataProduct dataProduct) {
		return dataProduct.getCategory() != null && !dataProduct.getCategory().isEmpty();
	}

	private static Optional<Category> findSoftwareCategory(String instanceId, List<Category> categories) {
		return categories.stream()
				.filter(category -> category.getInstanceId().equals(instanceId))
				.filter(category -> Facets.getCategoryType(category).equals(Facets.Type.SOFTWARE))
				.findFirst();
	}

	private static void addDistributionsToDiscovery(DataProduct dataProduct, Category category,
			Set<DiscoveryItem> discoveryItems, User user, String versioningStatus) {
		for (var distributionEntity : dataProduct.getDistribution()) {
			Distribution distribution = (Distribution) AbstractAPI.retrieveAPI(EntityNames.DISTRIBUTION.name())
					.retrieve(distributionEntity.getInstanceId());

			if (Objects.isNull(distribution)) {
				continue;
			}

			DiscoveryItemBuilder builder = new DiscoveryItemBuilder(
					distribution.getInstanceId(),
					EnvironmentVariables.API_HOST + API_PATH_DETAILS + distribution.getInstanceId(),
					null)
					.uid(distribution.getUid())
					.metaId(distribution.getMetaId())
					.title(distribution.getTitle() != null ? String.join(";", distribution.getTitle()) : null)
					.description(distribution.getDescription() != null
							? String.join(";", distribution.getDescription())
							: null)
					.availableFormats(AvailableFormatsGeneration.generate(distribution))
					.categories(Arrays.asList(category.getUid()));

			if (user != null && versioningStatus != null) {
				builder.versioningStatus(distribution.getStatus().name())
						.editorId(distribution.getEditorId());
			}

			discoveryItems.add(builder.build());
		}
	}

	private static List<String> extractCategoryUids(List<LinkedEntity> categoryEntities) {
		return categoryEntities.stream()
				.map(linkedEntity -> (Category) LinkedEntityAPI.retrieveFromLinkedEntity(linkedEntity))
				.map(Category::getUid)
				.filter(uid -> uid.contains("category:"))
				.collect(Collectors.toList());
	}

	private static List<AvailableFormat> createFormatsForSourceCode(SoftwareSourceCode software) {
		if (software.getDownloadURL() != null) {
			return createFormatsFromUrl(software.getDownloadURL());
		} else if (software.getCodeRepository() != null) {
			return createFormatsFromUrl(software.getCodeRepository());
		}
		return null;
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

	private static String extractFormatFromUrl(String url) {
		Matcher matcher = FORMAT_PATTERN.matcher(url);
		String format = null;
		// get the last match
		while (matcher.find()) {
			format = matcher.group(1);
		}
		return format;
	}

	private static DiscoveryItem createSoftwareDiscoveryItem(
			String instanceId,
			String uid,
			String name,
			String description,
			List<AvailableFormat> formats,
			List<String> categoryList,
			User user,
			String versioningStatus,
			String status,
			String editorId) {
		DiscoveryItemBuilder builder = new DiscoveryItemBuilder(instanceId,
				EnvironmentVariables.API_HOST + API_PATH_DETAILS + instanceId, null)
				.uid(uid)
				.title(name)
				.description(description)
				.sha256id(DigestUtils.sha256Hex(uid))
				.availableFormats(formats)
				.categories(categoryList.isEmpty() ? null : categoryList);

		if (user != null && versioningStatus != null) {
			builder.versioningStatus(status)
					.editorId(editorId);
		}

		return builder.build();
	}

	private static void addKeywordsFromSoftware(String keywordsString, Set<String> keywords) {
		keywords.addAll(Arrays.stream(
				Optional.ofNullable(keywordsString)
						.orElse("")
						.split(",\t"))
				.map(String::toLowerCase)
				.map(String::trim)
				.collect(Collectors.toList()));
	}

	private static SearchResponse buildSearchResponse(Set<DiscoveryItem> discoveryItems, Set<String> keywords) {
		Node results = new Node("results");
		var facets = FacetsGeneration.generateResponseUsingCategories(discoveryItems, Facets.Type.SOFTWARE).getFacets();
		results.addChild(facets);

		List<String> keywordsCollection = keywords.stream()
				.filter(Objects::nonNull)
				.filter(s -> !s.isEmpty())
				.sorted()
				.collect(Collectors.toList());

		NodeFilters keywordsNodes = new NodeFilters("keywords");
		keywordsCollection.forEach(keyword -> {
			NodeFilters node = new NodeFilters(keyword);
			node.setId(Base64.getEncoder().encodeToString(keyword.getBytes()));
			keywordsNodes.addChild(node);
		});

		ArrayList<NodeFilters> filters = new ArrayList<>();
		filters.add(keywordsNodes);

		return new SearchResponse(results, filters);
	}

	private static boolean matchesQuery(String query, String title, String description) {
		if (query == null || query.isEmpty() || title == null || title.isEmpty() || description == null
				|| description.isEmpty())
			return true;
		String lowerQuery = query.toLowerCase();
		return title.toLowerCase().contains(lowerQuery) || description.toLowerCase().contains(lowerQuery);
	}

	private static class DataCollector {
		final List<DataProduct> dataProducts;
		final List<Category> categories;
		final List<SoftwareApplication> softwareApplications;
		final List<SoftwareSourceCode> softwareSourceCodes;

		@SuppressWarnings("unchecked")
		DataCollector(User user, String versioningStatus) {
			List<String> statuses = new ArrayList<>();
			if (user != null && versioningStatus != null && !versioningStatus.isEmpty()) {
				statuses.addAll(Arrays.asList(versioningStatus.split(",")));
			} else {
				statuses.add(StatusType.PUBLISHED.name());
			}

			this.dataProducts = ((List<DataProduct>) AbstractAPI.retrieveAPI(EntityNames.DATAPRODUCT.name())
					.retrieveAll()).stream()
					.filter(d -> statuses.contains(d.getStatus().name()))
					.filter(d -> {
						if (user != null && !user.getIsAdmin() && versioningStatus != null) {
							return StatusType.PUBLISHED.equals(d.getStatus())
									|| user.getAuthIdentifier().equals(d.getEditorId());
						}
						return true;
					})
					.collect(Collectors.toList());

			this.categories = (List<Category>) AbstractAPI.retrieveAPI(EntityNames.CATEGORY.name())
					.retrieveAll();

			this.softwareApplications = ((List<SoftwareApplication>) AbstractAPI
					.retrieveAPI(EntityNames.SOFTWAREAPPLICATION.name()).retrieveAll()).stream()
					.filter(s -> statuses.contains(s.getStatus().name()))
					.filter(s -> {
						if (user != null && !user.getIsAdmin() && versioningStatus != null) {
							return StatusType.PUBLISHED.name().equals(s.getStatus().name())
									|| user.getAuthIdentifier().equals(s.getEditorId());
						}
						return true;
					})
					.collect(Collectors.toList());

			this.softwareSourceCodes = ((List<SoftwareSourceCode>) AbstractAPI
					.retrieveAPI(EntityNames.SOFTWARESOURCECODE.name()).retrieveAll()).stream()
					.filter(s -> statuses.contains(s.getStatus().name()))
					.filter(s -> {
						if (user != null && !user.getIsAdmin() && versioningStatus != null) {
							return StatusType.PUBLISHED.name().equals(s.getStatus().name())
									|| user.getAuthIdentifier().equals(s.getEditorId());
						}
						return true;
					})
					.collect(Collectors.toList());
		}
	}
}
