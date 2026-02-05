package org.epos.api.core.software;

import org.epos.api.beans.software.DistributionDetails;
import org.epos.api.beans.software.SoftwareApplicationDetails;
import org.epos.api.beans.software.SoftwareDetailsResponse;
import org.epos.api.beans.software.SoftwareSourceCodeDetails;
import org.epos.api.core.EnvironmentVariables;
import org.epos.eposdatamodel.SoftwareApplication;
import org.epos.eposdatamodel.SoftwareSourceCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import abstractapis.AbstractAPI;
import metadataapis.EntityNames;

public class SoftwareDetails {
	private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareDetails.class);

	public static SoftwareDetailsResponse generate(String instanceID) {
		// Use SQL implementation if enabled
		if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
			return SoftwareDetailsSQL.generate(instanceID);
		}

		LOGGER.info("Generating details for software with instanceID: {}", instanceID);

		var distribution = (org.epos.eposdatamodel.Distribution) AbstractAPI
				.retrieveAPI(EntityNames.DISTRIBUTION.name())
				.retrieve(instanceID);
		if (distribution != null) {
			return new DistributionDetails(distribution);
		}

		SoftwareSourceCode softwareSourceCode = (SoftwareSourceCode) AbstractAPI
				.retrieveAPI(EntityNames.SOFTWARESOURCECODE.name()).retrieve(instanceID);
		if (softwareSourceCode != null) {
			return new SoftwareSourceCodeDetails(softwareSourceCode);
		}

		SoftwareApplication softwareApplication = (SoftwareApplication) AbstractAPI
				.retrieveAPI(EntityNames.SOFTWAREAPPLICATION.name()).retrieve(instanceID);
		if (softwareApplication != null) {
			return new SoftwareApplicationDetails(softwareApplication);
		}

		return null;
	}
}
