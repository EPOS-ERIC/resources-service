package org.epos.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

import org.epos.api.beans.OrganizationBean;
import org.epos.api.beans.OrganizationFullBean;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

import jakarta.validation.Valid;

@jakarta.annotation.Generated(value = "io.swagger.codegen.v3.generators.java.SpringCodegen", date = "2021-10-11T14:51:06.469Z[GMT]")
@Validated
public interface ResourcesApi {

//	@Operation(summary = "dataset operation", description = "Dataset endpoint", tags={ "Resources Service" })
//	@ApiResponses(value = { 
//			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "*/*", schema = @Schema(implementation = DataProduct.class))),
//
//			@ApiResponse(responseCode = "201", description = "Created."),
//
//			@ApiResponse(responseCode = "204", description = "No content."),
//
//			@ApiResponse(responseCode = "301", description = "Moved Permanently."),
//
//			@ApiResponse(responseCode = "400", description = "Bad request."),
//
//			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),
//
//			@ApiResponse(responseCode = "403", description = "Forbidden"),
//
//			@ApiResponse(responseCode = "404", description = "Not Found") })
//	@RequestMapping(value = "/resources/datasets",
//	produces = { "*/*" }, 
//	method = RequestMethod.GET)
//	ResponseEntity<List<DataProduct>> datasetUsingGet(@Parameter(in = ParameterIn.QUERY, description = "q" ,schema=@Schema()) @Valid @RequestParam(value = "q", required = false) String q, @Parameter(in = ParameterIn.QUERY, description = "startDate" ,schema=@Schema()) @Valid @RequestParam(value = "startDate", required = false) String startDate, @Parameter(in = ParameterIn.QUERY, description = "endDate" ,schema=@Schema()) @Valid @RequestParam(value = "endDate", required = false) String endDate, @Parameter(in = ParameterIn.QUERY, description = "bbox" ,schema=@Schema()) @Valid @RequestParam(value = "bbox", required = false) String bbox, @Parameter(in = ParameterIn.QUERY, description = "keywords" ,schema=@Schema()) @Valid @RequestParam(value = "keywords", required = false) String keywords, @Parameter(in = ParameterIn.QUERY, description = "organisations" ,schema=@Schema()) @Valid @RequestParam(value = "organisations", required = false) String organisations);
//
//
//	@Operation(summary = "software operation", description = "Software endpoint", tags={ "Resources Service" })
//	@ApiResponses(value = { 
//			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "*/*", schema = @Schema(implementation = Software.class))),
//
//			@ApiResponse(responseCode = "201", description = "Created."),
//
//			@ApiResponse(responseCode = "204", description = "No content."),
//
//			@ApiResponse(responseCode = "301", description = "Moved Permanently."),
//
//			@ApiResponse(responseCode = "400", description = "Bad request."),
//
//			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),
//
//			@ApiResponse(responseCode = "403", description = "Forbidden"),
//
//			@ApiResponse(responseCode = "404", description = "Not Found") })
//	@RequestMapping(value = "/resources/softwares",
//	produces = { "*/*" }, 
//	method = RequestMethod.GET)
//	ResponseEntity<List<Software>> softwareUsingGet();
//
//
//	@Operation(summary = "webservice operation", description = "Webservice endpoint", tags={ "Resources Service" })
//	@ApiResponses(value = { 
//			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "*/*", schema = @Schema(implementation = WebService.class))),
//
//			@ApiResponse(responseCode = "201", description = "Created."),
//
//			@ApiResponse(responseCode = "204", description = "No content."),
//
//			@ApiResponse(responseCode = "301", description = "Moved Permanently."),
//
//			@ApiResponse(responseCode = "400", description = "Bad request."),
//
//			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),
//
//			@ApiResponse(responseCode = "403", description = "Forbidden"),
//
//			@ApiResponse(responseCode = "404", description = "Not Found") })
//	@RequestMapping(value = "/resources/webservices",
//	produces = { "*/*" }, 
//	method = RequestMethod.GET)
//	ResponseEntity<List<WebService>> webserviceUsingGet(@Parameter(in = ParameterIn.QUERY, description = "q" ,schema=@Schema()) @Valid @RequestParam(value = "q", required = false) String q, @Parameter(in = ParameterIn.QUERY, description = "startDate" ,schema=@Schema()) @Valid @RequestParam(value = "startDate", required = false) String startDate, @Parameter(in = ParameterIn.QUERY, description = "endDate" ,schema=@Schema()) @Valid @RequestParam(value = "endDate", required = false) String endDate, @Parameter(in = ParameterIn.QUERY, description = "bbox" ,schema=@Schema()) @Valid @RequestParam(value = "bbox", required = false) String bbox, @Parameter(in = ParameterIn.QUERY, description = "keywords" ,schema=@Schema()) @Valid @RequestParam(value = "keywords", required = false) String keywords, @Parameter(in = ParameterIn.QUERY, description = "organisations" ,schema=@Schema()) @Valid @RequestParam(value = "organisations", required = false) String organisations);

	@Operation(summary = "organisation operation", description = "organisation endpoint", tags={ "Resources Service" })
	@ApiResponses(value = { 
			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "*/*", schema = @Schema(implementation = OrganizationBean.class))),

			@ApiResponse(responseCode = "201", description = "Created."),

			@ApiResponse(responseCode = "204", description = "No content."),

			@ApiResponse(responseCode = "301", description = "Moved Permanently."),

			@ApiResponse(responseCode = "400", description = "Bad request."),

			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),

			@ApiResponse(responseCode = "403", description = "Forbidden"),

			@ApiResponse(responseCode = "404", description = "Not Found") })
	@RequestMapping(value = "/resources/organizations",
	produces = { "*/*" }, 
	method = RequestMethod.GET)
	ResponseEntity<List<OrganizationBean>> organisationUsingGet(@Parameter(in = ParameterIn.QUERY, description = "the id of organization" ,required=false,schema=@Schema()) @Valid @RequestParam(value = "id", required = false) String id,
			@Parameter(in = ParameterIn.QUERY, description = "q" ,schema=@Schema()) @Valid @RequestParam(value = "q", required = false) String q, 
			@Parameter(in = ParameterIn.QUERY, description = "country" ,schema=@Schema()) @Valid @RequestParam(value = "country", required = false) String country, 
			@Parameter(in = ParameterIn.QUERY, description = "type of organization, comma separated values from the following list {dataproviders, serviceproviders, facilitiesproviders}" ,schema=@Schema()) @Valid @RequestParam(value = "type", required = false) String type);

	@Operation(summary = "organisation full operation", description = "organisation full endpoint", tags={ "Resources Service" })
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "ok.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrganizationFullBean.class))),

			@ApiResponse(responseCode = "201", description = "Created."),

			@ApiResponse(responseCode = "204", description = "No content."),

			@ApiResponse(responseCode = "301", description = "Moved Permanently."),

			@ApiResponse(responseCode = "400", description = "Bad request."),

			@ApiResponse(responseCode = "401", description = "Access token is missing or invalid"),

			@ApiResponse(responseCode = "403", description = "Forbidden"),

			@ApiResponse(responseCode = "404", description = "Not Found") })
	@RequestMapping(value = "/resources/organizations/full",
	produces = { "application/json" },
	method = RequestMethod.GET)
	ResponseEntity<List<OrganizationFullBean>> organisationsFullUsingGet();


}
