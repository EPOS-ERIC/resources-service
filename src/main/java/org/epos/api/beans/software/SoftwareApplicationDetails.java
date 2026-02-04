package org.epos.api.beans.software;

import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.software.SoftwareApplicationGenerationJPA;
import org.epos.api.core.software.SoftwareApplicationGenerationSQL;
import org.epos.eposdatamodel.SoftwareApplication;

public class SoftwareApplicationDetails extends SoftwareDetailsResponse {
	private final SoftwareApplicationResponse object;

	public SoftwareApplicationDetails(SoftwareApplication softwareApplication) {
		super("software_application");
		if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
			this.object = SoftwareApplicationGenerationSQL.generateById(softwareApplication.getInstanceId());
		} else {
			this.object = SoftwareApplicationGenerationJPA.generate(softwareApplication);
		}
	}

	public SoftwareApplicationResponse getObject() {
		return object;
	}
}
