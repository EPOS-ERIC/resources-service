package org.epos.api;

/*******************************************************************************
 * Copyright 2021 EPOS ERIC
 * 
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

import java.util.Map;

import org.epos.api.core.EnvironmentVariables;
import org.epos.api.core.MonitoringGeneration;
import org.epos.api.core.distributions.DistributionDetailsExtendedGenerationJPA;
import org.epos.api.core.distributions.DistributionDetailsExtendedGenerationSQL;
import org.epos.api.core.distributions.DistributionDetailsGenerationJPA;
import org.epos.api.core.distributions.DistributionDetailsGenerationSQL;
import org.epos.api.core.distributions.DistributionSearchGenerationJPA;
import org.epos.api.core.distributions.DistributionSearchGenerationSQL;
import org.epos.api.core.facilities.EquipmentsDetailsItemGenerationJPA;
import org.epos.api.core.facilities.EquipmentsDetailsItemGenerationSQL;
import org.epos.api.core.facilities.FacilityDetailsItemGenerationJPA;
import org.epos.api.core.facilities.FacilityDetailsItemGenerationSQL;
import org.epos.api.core.facilities.FacilitySearchGenerationJPA;
import org.epos.api.core.facilities.FacilitySearchGenerationSQL;
import org.epos.api.core.organizations.OrganisationsGeneration;
import org.epos.api.core.organizations.OrganisationsGenerationSQL;
import org.epos.api.facets.Facets;
import org.epos.api.utility.Utils;
import org.epos.eposdatamodel.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;

import jakarta.servlet.http.HttpServletRequest;

abstract class ApiController<T> {

	private static final Logger LOGGER = LoggerFactory.getLogger(ApiController.class);
	protected final HttpServletRequest request;

	protected ApiController(HttpServletRequest request) {
		this.request = request;
	}

	@SuppressWarnings("unchecked")
	protected ResponseEntity<T> standardRequest(String service, Map<String, Object> requestParams, User user) {
		
		String response = null;
		
		switch(service) {
		case "SEARCH":
			if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
				response = Utils.gson.toJson(DistributionSearchGenerationSQL.generate(requestParams, user));
			} else {
				response = Utils.gson.toJson(DistributionSearchGenerationJPA.generate(requestParams, user));
			}
			break;
		case "DETAILS":
			if(Boolean.valueOf(requestParams.get("extended").toString())) {
				if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
					response = Utils.gson.toJson(DistributionDetailsExtendedGenerationSQL.generate(requestParams, user));
				} else {
					response = Utils.gson.toJson(DistributionDetailsExtendedGenerationJPA.generate(requestParams));
				}
			} else {
				if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
					response = Utils.gson.toJson(DistributionDetailsGenerationSQL.generate(requestParams, Facets.Type.DATA, user));
				} else {
					response = Utils.gson.toJson(DistributionDetailsGenerationJPA.generate(requestParams));
				}
			}
			break;
		case "FACILITYSEARCH":
			if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
				response = Utils.gson.toJson(FacilitySearchGenerationSQL.generate(requestParams, user));
			} else {
				response = Utils.gson.toJson(FacilitySearchGenerationJPA.generate(requestParams, user));
			}
			break;
		case "FACILITYDETAILS":
			if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
				response = Utils.gson.toJson(FacilityDetailsItemGenerationSQL.generate(requestParams, user));
			} else {
				response = Utils.gson.toJson(FacilityDetailsItemGenerationJPA.generate(requestParams));
			}
			break;
		case "EQUIPMENTDETAILS":
			if (EnvironmentVariables.USE_SQL_IMPLEMENTATION) {
				response = Utils.gson.toJson(EquipmentsDetailsItemGenerationSQL.generate(requestParams));
			} else {
				response = Utils.gson.toJson(EquipmentsDetailsItemGenerationJPA.generate(requestParams));
			}
			break;
		case "MONITORING":
			response = Utils.gson.toJson(MonitoringGeneration.generate());
			break;
		case "ORGANISATIONS":
			response = Utils.gson.toJson(OrganisationsGenerationSQL.generate(requestParams));
			break;
		default:
			break;
		}
		
		if(response!=null) {
			LOGGER.debug("Payload text received => "+response);

			if(Utils.gson.fromJson(response, JsonElement.class).isJsonObject()) {
				if(!Utils.gson.fromJson(response, JsonElement.class).getAsJsonObject().entrySet().isEmpty()) {
					LOGGER.debug("Payload Array received => "+response);;
					return hasContent(response)? (ResponseEntity<T>) ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response) : ResponseEntity.status(HttpStatus.NO_CONTENT).build();
				}
			}
			else if(Utils.gson.fromJson(response, JsonElement.class).isJsonArray()) {
				if(Utils.gson.fromJson(response, JsonElement.class).getAsJsonArray().size()>0) {
					LOGGER.debug("Payload Array received => "+response);
					return hasContent(response)? (ResponseEntity<T>) ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response) : ResponseEntity.status(HttpStatus.NO_CONTENT).build();
				}
			}
			else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
			}	
		}
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
	}


	private static boolean hasContent(String payload) throws JsonSyntaxException {
		LOGGER.debug("Has Content Payload => "+payload);
		JsonElement jsonElement = Utils.gson.fromJson(payload, JsonElement.class);
		LOGGER.debug("Has Content Json Element => "+jsonElement.toString());
		return ((jsonElement.isJsonArray() && jsonElement.getAsJsonArray().size()<1) 
				|| (jsonElement.isJsonObject() && jsonElement.getAsJsonObject().entrySet().isEmpty()))? 
						false : true;
	}
}
