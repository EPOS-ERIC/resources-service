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
					String compiledUrl = null;
					compiledUrl = URLGeneration.generateURLFromTemplateAndMap(distribution.getEndpoint(),
							parametersMap);

					System.out.println(compiledUrl);
					try {
						compiledUrl = URLGeneration.ogcWFSChecker(compiledUrl);
					} catch (Exception e) {
						LOGGER.error("Found the following issue whilst executing the WFS Checker, issue raised "
								+ e.getMessage() + " - Continuing execution");
					}

					// compiledUrl = java.net.URLDecoder.decode(compiledUrl,
					// StandardCharsets.UTF_8);
					mb.setOriginalURL(compiledUrl);
				}

				// DDSS
				for (DataProduct d : datasetList) {
					ArrayList<String> distrs = new ArrayList<String>();
					d.getDistribution().forEach(dist -> {
						distrs.add(dist.getInstanceId());
					});
					if (distrs.contains(dx.getInstanceId())) {
						String ddss = null;
						if (Objects.nonNull(d.getIdentifier()))
							for (LinkedEntity item : d.getIdentifier()) {
								Identifier i = (Identifier) LinkedEntityAPI.retrieveFromLinkedEntity(item);
								if (i.getType().equals("DDSS-ID")) {
									ddss = i.getIdentifier();
								}
							}
						if (ddss == null)
							continue;

						if (ddss.toLowerCase().contains("wp08"))
							mb.setTCSGroup("Seismology");
						else if (ddss.toLowerCase().contains("wp09"))
							mb.setTCSGroup("Near Fault Observations");
						else if (ddss.toLowerCase().contains("wp10"))
							mb.setTCSGroup("Geodesy");
						else if (ddss.toLowerCase().contains("wp11"))
							mb.setTCSGroup("Volcano Observations");
						else if (ddss.toLowerCase().contains("wp12"))
							mb.setTCSGroup("Satellite Observations");
						else if (ddss.toLowerCase().contains("wp13"))
							mb.setTCSGroup("Geoelectromagnetism");
						else if (ddss.toLowerCase().contains("wp14"))
							mb.setTCSGroup("Anthropogenic Hazard Observations");
						else if (ddss.toLowerCase().contains("wp15"))
							mb.setTCSGroup("Geology");
						else if (ddss.toLowerCase().contains("wp16"))
							mb.setTCSGroup("Multi-Scale Laboratory");
						else if (ddss.toLowerCase().contains("wp18"))
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
						}
					}
				}
				// //VALIDATION RULES
				// for(SoftwareApplication sw : softwareApplicationList) {
				// for(LinkedEntity item : sw.getIdentifier()) {
				// Identifier identifier = (Identifier)
				// LinkedEntityAPI.retrieveFromLinkedEntity(item);
				// if(identifier.getIdentifier().toLowerCase().contains("monitoring/zabbix")) {
				//
				// if(dx.getAccessURLByInstanceId()!=null &&
				// sw.getRelation().stream().map(LinkedEntity::getUid).collect(Collectors.toList()).containsAll(dx.getAccessURLByInstanceId().stream()
				// .map(EDMDistributionAccessURL::getOperationByInstanceOperationId).map(EDMOperation::getUid).collect(Collectors.toList()))){
				// String validationtype = sw.getRequirements().replace("validation-type=", "");
				// if(validationtype.equals("")) validationtype="none";
				// String encodingFormatObject = null;
				// String schemaversionObject = null;
				// for (Parameter parameter : sw.getParameter()) {
				// if (parameter.getAction().equals(ActionEnum.OBJECT)){
				// encodingFormatObject = parameter.getEncodingFormat();
				// schemaversionObject = parameter.getConformsTo();
				// }
				// }
				// mb.createValidationRule(validationtype, encodingFormatObject,
				// schemaversionObject);
				// }
				// }
				// }
				// }
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
