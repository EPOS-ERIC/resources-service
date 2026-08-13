package org.epos.api.core;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.*;
import java.util.stream.Collectors;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;
import model.StatusType;
import org.epos.api.beans.Distribution;
import org.epos.api.beans.MonitoringBean;
import org.epos.api.core.distributions.DistributionDetailsGenerationJPA;
import org.epos.api.core.distributions.DistributionDetailsGenerationSQL;
import org.epos.api.routines.DatabaseConnections;
import org.epos.api.utility.Utils;
import org.epos.eposdatamodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MonitoringGeneration {

	private static final Logger LOGGER = LoggerFactory.getLogger(MonitoringGeneration.class);

	public static List<MonitoringBean> generate() {
		// Use SQL implementation if enabled
		if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
			return MonitoringGenerationSQL.generate();
		}

		List<MonitoringBean> monitoringList = new ArrayList<>();
		List<DataProduct> datasetList = ((List<DataProduct>) AbstractAPI.retrieveAPI(EntityNames.DATAPRODUCT.name())
				.retrieveAllWithStatus(StatusType.PUBLISHED));
		List<org.epos.eposdatamodel.Distribution> distributionList = ((List<org.epos.eposdatamodel.Distribution>) AbstractAPI
				.retrieveAPI(EntityNames.DISTRIBUTION.name()).retrieveAllWithStatus(StatusType.PUBLISHED));

		for (org.epos.eposdatamodel.Distribution dx : distributionList) {

			MonitoringBean mb = new MonitoringBean();
			// IDENTIFIER
			mb.setIdentifier(dx.getMetaId());

			String title = null;
			if (dx.getTitle() != null && !dx.getTitle().isEmpty()) {
				title = new ArrayList<>(dx.getTitle()).get(0);
			}

			mb.setName(title);

			Map<String, Object> params = new HashMap<String, Object>();
			params.put("id", dx.getInstanceId());
			params.put("useDefaults", "true");

			Distribution distribution = EnvironmentVariables.USE_SQL_IMPLEMENTATION 
					? DistributionDetailsGenerationSQL.generate(params)
					: DistributionDetailsGenerationJPA.generate(params);

			HashMap<String, Object> parametersMap = new HashMap<>();

			if (distribution != null) {

				if (distribution.getParameters() != null) {
					distribution.getParameters().forEach(p -> {
						if (p.getDefaultValue() != null) {
							if (p.getProperty() != null && p.getValuePattern() != null) {
								if (p.getProperty().equals("schema:startDate")
										|| p.getProperty().equals("schema:endDate")) {
									parametersMap.put(p.getName(), Utils.convertDateUsingPattern(p.getDefaultValue(),
											null, p.getValuePattern()));
								}
							} else {
								parametersMap.put(p.getName(), URLGeneration.encodeValue(p.getDefaultValue()));
							}
						}
					});
				}


				if (distribution.getEndpoint() != null) {
					String compiledUrl = URLGeneration.generateURLFromTemplateAndMap(distribution.getEndpoint(),
							parametersMap);
					try {
						compiledUrl = URLGeneration.ogcWFSChecker(compiledUrl);
					} catch (Exception e) {
						LOGGER.error("Found the following issue whilst executing the WFS Checker, issue raised "
								+ e.getMessage() + " - Continuing execution");
					}

					mb.setOriginalURL(compiledUrl);
				} else {
					// In that case it's a download url
					mb.setOriginalURL(distribution.getDownloadURL());
				}

				// DDSS
				for (DataProduct d : datasetList) {
					ArrayList<String> distrs = new ArrayList<String>();
					d.getDistribution().forEach(dist -> {
						distrs.add(dist.getInstanceId());
					});
					if (distrs.contains(dx.getInstanceId())) {
						Category cat = (Category) LinkedEntityAPI.retrieveFromLinkedEntity(d.getCategory().getFirst());
						CategoryScheme catschem = (CategoryScheme) LinkedEntityAPI.retrieveFromLinkedEntity(cat.getInScheme());

						String catName = catschem.getTitle();

						if (catName.toLowerCase().contains("seismology"))
							mb.setTCSGroup("Seismology");
						else if (catName.toLowerCase().contains("near fault observatories"))
							mb.setTCSGroup("Near Fault Observations");
						else if (catName.toLowerCase().contains("gnss data and products"))
							mb.setTCSGroup("Geodesy");
						else if (catName.toLowerCase().contains("volcano observations"))
							mb.setTCSGroup("Volcano Observations");
						else if (catName.toLowerCase().contains("satellite data"))
							mb.setTCSGroup("Satellite Observations");
						else if (catName.toLowerCase().contains("geomagnetic observations"))
							mb.setTCSGroup("Geoelectromagnetism");
						else if (catName.toLowerCase().contains("anthropogenic hazards"))
							mb.setTCSGroup("Anthropogenic Hazard Observations");
						else if (catName.toLowerCase().contains("geological information and modeling"))
							mb.setTCSGroup("Geology");
						else if (catName.toLowerCase().contains("multi-scale laboratories"))
							mb.setTCSGroup("Multi-Scale Laboratory");
						else if (catName.toLowerCase().contains("tsunami"))
							mb.setTCSGroup("Tsunami");
						else
							mb.setTCSGroup("Undefined");

						if (dx.getAccessService() != null) {
							for (LinkedEntity item : dx.getAccessService()) {
								WebService ws = (WebService) LinkedEntityAPI.retrieveFromLinkedEntity(item);
								if (Objects.nonNull(ws.getContactPoint())) {
									for (LinkedEntity le : ws.getContactPoint()) {
										ContactPoint contact = (ContactPoint) LinkedEntityAPI
												.retrieveFromLinkedEntity(le);
										try {
											mb.createContacts(contact.getUid(), contact.getRole(),
													new HashSet<>(contact.getEmail()).stream().toList());
										} catch (Exception e) {
											LOGGER.error(
													"Found the following issue whilst creating contacts, issue raised "
															+ e.getMessage() + " - Continuing execution");
										}
									}
								}
							}
						} else {
							for (LinkedEntity item : d.getContactPoint()) {
								ContactPoint contact = (ContactPoint) LinkedEntityAPI.retrieveFromLinkedEntity(item);
								try {
									mb.createContacts(contact.getUid(), contact.getRole(),
											new HashSet<>(contact.getEmail()).stream().toList());
								} catch (Exception e) {
									LOGGER.error(
											"Found the following issue whilst creating contacts, issue raised "
													+ e.getMessage() + " - Continuing execution");
								}
							}

						}
					}
				}
				if (mb.getValidationRules() == null || mb.getValidationRules().isEmpty()) {
					mb.createValidationRule("none", null, null);
				}

				mb.setId(dx.getMetaId());
				mb.setUid(dx.getUid());
				if (mb.getOriginalURL() != null)
					monitoringList.add(mb);
			}
		}
		return monitoringList;
	}

}
