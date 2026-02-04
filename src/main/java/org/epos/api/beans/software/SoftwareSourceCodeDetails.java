package org.epos.api.beans.software;

import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.software.SoftwareSourceCodeGenerationJPA;
import org.epos.api.core.software.SoftwareSourceCodeGenerationSQL;
import org.epos.eposdatamodel.SoftwareSourceCode;

public class SoftwareSourceCodeDetails extends SoftwareDetailsResponse {
	private final SoftwareSourceCodeResponse object;

	public SoftwareSourceCodeDetails(SoftwareSourceCode softwareSourceCode) {
		super("software_source_code");
		if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
			this.object = SoftwareSourceCodeGenerationSQL.generate(softwareSourceCode.getInstanceId());
		} else {
			this.object = SoftwareSourceCodeGenerationJPA.generate(softwareSourceCode);
		}
	}

	public SoftwareSourceCodeResponse getObject() {
		return object;
	}
}
