package org.epos.api;

import org.epos.api.beans.Distribution;
import org.epos.api.beans.Facility;
import org.epos.api.beans.LinkedResponse;
import org.epos.api.beans.ParametersResponse;
import org.epos.api.beans.SearchResponse;
import org.epos.api.beans.software.SoftwareDetailsResponse;
import org.epos.eposdatamodel.Equipment;
import org.epos.library.feature.FeaturesCollection;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@jakarta.annotation.Generated(value = "io.swagger.codegen.v3.generators.java.SpringCodegen", date = "2021-10-11T14:51:06.469Z[GMT]")
@Validated
public interface ClientHelpersApi {

	@Operation(summary = "metadata resources details", description = "returns detailed information useful to contextualise the discovery phase", tags = {
			"Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Distribution.class))),

			@ApiResponse(responseCode = "201", description = "Created."),

			@ApiResponse(responseCode = "204", description = "No content."),

			@ApiResponse(responseCode = "301", description = "Moved Permanently."),

			@ApiResponse(responseCode = "400", description = "Bad request."),

			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),

			@ApiResponse(responseCode = "403", description = "Forbidden"),

			@ApiResponse(responseCode = "404", description = "Not Found") })
	@RequestMapping(value = "/resources/details/{instance_id}", produces = {
			"application/json" }, method = RequestMethod.GET)
	ResponseEntity<Distribution> resourcesDiscoveryGetUsingGET(
			@Parameter(in = ParameterIn.PATH, description = "The distribution ID", required = true, schema = @Schema()) @PathVariable("instance_id") String id,
			@Parameter(in = ParameterIn.QUERY, description = "extended payload", schema = @Schema()) @Valid @RequestParam(value = "extended", required = false) Boolean extended);

	@Operation(summary = "search operation", description = "Search endpoint", tags = { "Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SearchResponse.class))),

			@ApiResponse(responseCode = "201", description = "Created."),

			@ApiResponse(responseCode = "204", description = "No content."),

			@ApiResponse(responseCode = "301", description = "Moved Permanently."),

			@ApiResponse(responseCode = "400", description = "Bad request."),

			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),

			@ApiResponse(responseCode = "403", description = "Forbidden"),

			@ApiResponse(responseCode = "404", description = "Not Found") })
	@RequestMapping(value = "/resources/search", produces = { "application/json" }, method = RequestMethod.GET)
	ResponseEntity<SearchResponse> searchUsingGet(
			@Parameter(in = ParameterIn.QUERY, description = "q", schema = @Schema()) @Valid @RequestParam(value = "q", required = false) String q,
			@Parameter(in = ParameterIn.QUERY, description = "startDate", schema = @Schema()) @Valid @RequestParam(value = "startDate", required = false) String startDate,
			@Parameter(in = ParameterIn.QUERY, description = "endDate", schema = @Schema()) @Valid @RequestParam(value = "endDate", required = false) String endDate,
			@Parameter(in = ParameterIn.QUERY, description = "bbox", schema = @Schema()) @Valid @RequestParam(value = "bbox", required = false) String bbox,
			@Parameter(in = ParameterIn.QUERY, description = "keywords", schema = @Schema()) @Valid @RequestParam(value = "keywords", required = false) String keywords,
			@Parameter(in = ParameterIn.QUERY, description = "sciencedomains", schema = @Schema()) @Valid @RequestParam(value = "sciencedomains", required = false) String sciencedomains,
			@Parameter(in = ParameterIn.QUERY, description = "servicetypes", schema = @Schema()) @Valid @RequestParam(value = "servicetypes", required = false) String servicetypes,
			@Parameter(in = ParameterIn.QUERY, description = "organisations", schema = @Schema()) @Valid @RequestParam(value = "organisations", required = false) String organisations,
			@Parameter(in = ParameterIn.QUERY, description = "facetstype {categories, dataproviders, serviceproviders}", schema = @Schema()) @Valid @RequestParam(value = "facetstype", required = false) String facetsType,
			@Parameter(in = ParameterIn.QUERY, description = "facets", schema = @Schema()) @Valid @RequestParam(value = "facets", required = false) Boolean facets,
			@Parameter(in = ParameterIn.QUERY, description = "versioningStatus", schema = @Schema()) @Valid @RequestParam(value = "versioningStatus", required = false) String versioningStatus);

	@Operation(summary = "metadata resources details", description = "returns detailed information useful to contextualise the discovery phase", tags = {
			"Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {
					FeaturesCollection.class, Facility.class }))),

			@ApiResponse(responseCode = "201", description = "Created."),

			@ApiResponse(responseCode = "204", description = "No content."),

			@ApiResponse(responseCode = "301", description = "Moved Permanently."),

			@ApiResponse(responseCode = "400", description = "Bad request."),

			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),

			@ApiResponse(responseCode = "403", description = "Forbidden"),

			@ApiResponse(responseCode = "404", description = "Not Found") })
	@RequestMapping(value = "/facilities/details/{instance_id}", produces = {
			"application/json" }, method = RequestMethod.GET)
	ResponseEntity<Object> facilityDiscoveryGetUsingGET(
			@Parameter(in = ParameterIn.PATH, description = "The facility ID", required = true, schema = @Schema()) @PathVariable("instance_id") String id,
			@Parameter(in = ParameterIn.QUERY, description = "equipmenttypes", schema = @Schema()) @Valid @RequestParam(value = "equipmenttypes", required = false) String equipmenttypes,
			@Parameter(in = ParameterIn.QUERY, description = "format {json/plain, application/epos.geo+json}", schema = @Schema()) @Valid @RequestParam(value = "format", required = false) String format);

	@Operation(summary = "search operation", description = "Search endpoint", tags = { "Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SearchResponse.class))),

			@ApiResponse(responseCode = "201", description = "Created."),

			@ApiResponse(responseCode = "204", description = "No content."),

			@ApiResponse(responseCode = "301", description = "Moved Permanently."),

			@ApiResponse(responseCode = "400", description = "Bad request."),

			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),

			@ApiResponse(responseCode = "403", description = "Forbidden"),

			@ApiResponse(responseCode = "404", description = "Not Found") })
	@RequestMapping(value = "/facilities/search", produces = { "application/json" }, method = RequestMethod.GET)
	ResponseEntity<SearchResponse> facilitySearchUsingGet(
			@Parameter(in = ParameterIn.QUERY, description = "q", schema = @Schema()) @Valid @RequestParam(value = "q", required = false) String q,
			@Parameter(in = ParameterIn.QUERY, description = "bbox", schema = @Schema()) @Valid @RequestParam(value = "bbox", required = false) String bbox,
			@Parameter(in = ParameterIn.QUERY, description = "keywords", schema = @Schema()) @Valid @RequestParam(value = "keywords", required = false) String keywords,
			@Parameter(in = ParameterIn.QUERY, description = "facilitytypes", schema = @Schema()) @Valid @RequestParam(value = "facilitytypes", required = false) String facilitytypes,
			@Parameter(in = ParameterIn.QUERY, description = "equipmenttypes", schema = @Schema()) @Valid @RequestParam(value = "equipmenttypes", required = false) String equipmenttypes,
			@Parameter(in = ParameterIn.QUERY, description = "organisations", schema = @Schema()) @Valid @RequestParam(value = "organisations", required = false) String organisations,
			@Parameter(in = ParameterIn.QUERY, description = "facetstype {categories, facilityprovider}", schema = @Schema()) @Valid @RequestParam(value = "facetstype", required = false) String facetsType,
			@Parameter(in = ParameterIn.QUERY, description = "facets", schema = @Schema()) @Valid @RequestParam(value = "facets", required = false) Boolean facets,
			@Parameter(in = ParameterIn.QUERY, description = "versioningStatus", schema = @Schema()) @Valid @RequestParam(value = "versioningStatus", required = false) String versioningStatus);

	@Operation(summary = "metadata resources details", description = "returns detailed information useful to contextualise the discovery phase", tags = {
			"Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {
					FeaturesCollection.class, Equipment.class }))),

			@ApiResponse(responseCode = "201", description = "Created."),

			@ApiResponse(responseCode = "204", description = "No content."),

			@ApiResponse(responseCode = "301", description = "Moved Permanently."),

			@ApiResponse(responseCode = "400", description = "Bad request."),

			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),

			@ApiResponse(responseCode = "403", description = "Forbidden"),

			@ApiResponse(responseCode = "404", description = "Not Found") })
	@RequestMapping(value = "/equipments/{instance_id}", produces = { "application/json" }, method = RequestMethod.GET)
	ResponseEntity<Object> equipmentsDiscoveryGetUsingGET(
			@Parameter(in = ParameterIn.PATH, description = "The equipment ID", required = true, schema = @Schema()) @PathVariable("instance_id") String id,
			@Parameter(in = ParameterIn.QUERY, description = "The facility ID", schema = @Schema()) @Valid @RequestParam(value = "facilityid", required = false) String facilityid,
			@Parameter(in = ParameterIn.QUERY, description = "format {json/plain, application/epos.geo+json}", schema = @Schema()) @Valid @RequestParam(value = "format", required = false) String format,
			@Parameter(in = ParameterIn.QUERY, description = "params", schema = @Schema()) @Valid @RequestParam(value = "params", required = false) String params);

	@Operation(summary = "linke entities", description = "returns distributions that permit all the parameters specified", tags = {
			"Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Ok", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LinkedResponse.class))),
			@ApiResponse(responseCode = "204", description = "No content."),
			@ApiResponse(responseCode = "301", description = "Moved Permanently."),
			@ApiResponse(responseCode = "400", description = "Bad request."),
			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),
			@ApiResponse(responseCode = "403", description = "Forbidden"),
			@ApiResponse(responseCode = "404", description = "Not Found") })
	@GetMapping(value = "/resources/linkedentities/webservices", produces = { "application/json" })
	ResponseEntity<LinkedResponse> searchLinkedWebservices(
			@Parameter(in = ParameterIn.QUERY, description = "The instance ID", schema = @Schema()) @RequestParam(value = "instance_id", required = false) String id,
			@Parameter(in = ParameterIn.QUERY, description = "Parameter names", schema = @Schema()) @RequestParam(value = "params", required = true) String params);

	@Operation(summary = "linked parameters", description = "returns all the output parameters available for a distribution that are linked to something", tags = {
			"Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Ok", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ParametersResponse.class))),
			@ApiResponse(responseCode = "204", description = "No content."),
			@ApiResponse(responseCode = "301", description = "Moved Permanently."),
			@ApiResponse(responseCode = "400", description = "Bad request."),
			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),
			@ApiResponse(responseCode = "403", description = "Forbidden"),
			@ApiResponse(responseCode = "404", description = "Not Found") })
	@GetMapping(value = "/resources/linkedentities/parameters/{instance_id}", produces = { "application/json" })
	ResponseEntity<ParametersResponse> searchLinkedParameters(
			@Parameter(in = ParameterIn.PATH, description = "The instance ID", required = true, schema = @Schema()) @PathVariable("instance_id") String id);

	@Operation(summary = "software search", description = "search for softwares in the catalog", tags = {
			"Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Ok", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SearchResponse.class))),
			@ApiResponse(responseCode = "204", description = "No content."),
			@ApiResponse(responseCode = "301", description = "Moved Permanently."),
			@ApiResponse(responseCode = "400", description = "Bad request."),
			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),
			@ApiResponse(responseCode = "403", description = "Forbidden"),
			@ApiResponse(responseCode = "404", description = "Not Found") })
	@GetMapping(value = "/software/search", produces = { "application/json" })
	ResponseEntity<SearchResponse> searchSoftware(
			@Parameter(in = ParameterIn.QUERY, description = "q", schema = @Schema()) @RequestParam(value = "q", required = false) String query,
			@Parameter(in = ParameterIn.QUERY, description = "versioningStatus", schema = @Schema()) @Valid @RequestParam(value = "versioningStatus", required = false) String versioningStatus);

	@Operation(summary = "metadata software details", description = "returns detailed information useful to contextualise the software discovery phase", tags = {
			"Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SoftwareDetailsResponse.class))),
			@ApiResponse(responseCode = "201", description = "Created."),
			@ApiResponse(responseCode = "204", description = "No content."),
			@ApiResponse(responseCode = "301", description = "Moved Permanently."),
			@ApiResponse(responseCode = "400", description = "Bad request."),
			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),
			@ApiResponse(responseCode = "403", description = "Forbidden"),
			@ApiResponse(responseCode = "404", description = "Not Found") })
	@RequestMapping(value = "/software/details/{instance_id}", produces = {
			"application/json" }, method = RequestMethod.GET)
	ResponseEntity<SoftwareDetailsResponse> detailsSoftware(
			@Parameter(in = ParameterIn.PATH, description = "The software ID", required = true, schema = @Schema()) @PathVariable("instance_id") String id);
}
