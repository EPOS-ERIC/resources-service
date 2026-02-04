package org.epos.api.beans.software;

import org.epos.api.beans.Distribution;
import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.distributions.DistributionDetailsGenerationJPA;
import org.epos.api.core.distributions.DistributionDetailsGenerationSQL;

public class DistributionDetails extends SoftwareDetailsResponse {
	private final Distribution object;

	public DistributionDetails(org.epos.eposdatamodel.Distribution distribution) {
		super("distribution");
		java.util.Map<String, Object> params = java.util.Map.of("id", distribution.getInstanceId());
		if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
			this.object = DistributionDetailsGenerationSQL.generate(params);
		} else {
			this.object = DistributionDetailsGenerationJPA.generate(params);
		}
	}

	public Distribution getObject() {
		return object;
	}
}
