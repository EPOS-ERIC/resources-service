/*******************************************************************************
 * Copyright 2021 EPOS ERIC
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.epos.api.core;

public class EnvironmentVariables {

	public static final String API_HOST = System.getenv("APIHOST");
	public static final String API_CONTEXT = System.getenv("APICONTEXT");
	public static final String MONITORING = System.getenv("MONITORING");
	public static final String MONITORING_URL = System.getenv("MONITORING_URL");
    public static final String MONITORING_API_TOKEN = System.getenv("MONITORING_PWD");
    
    /**
     * Feature flag to switch between JPA and SQL implementations.
     * Defaults to "true" (SQL implementations). Set to "false" to use JPA.
     */
    public static final boolean USE_SQL_IMPLEMENTATION = 
            !"false".equalsIgnoreCase(System.getenv("USE_SQL_IMPLEMENTATION"));

    ///api/frontend/v1
	
	private EnvironmentVariables() {}

}
