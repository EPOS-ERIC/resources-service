package org.epos.api;


import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.epos.api.beans.OrganizationBean;
import org.epos.eposdatamodel.DataProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import abstractapis.AbstractAPI;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import metadataapis.EntityNames;
import model.StatusType;

@CrossOrigin(origins = "*", allowedHeaders = "*")
@jakarta.annotation.Generated(value = "io.swagger.codegen.v3.generators.java.SpringCodegen", date = "2021-10-11T14:51:06.469Z[GMT]")
@RestController
public class ResourcesApiController extends ApiController implements ResourcesApi {

	private static final String A_PROBLEM_WAS_ENCOUNTERED_DECODING = "A problem was encountered decoding: ";
	private static final Logger LOGGER = LoggerFactory.getLogger(ResourcesApiController.class);

	private final ObjectMapper objectMapper;

	private final HttpServletRequest request;

	private static final Gson gson = new Gson();
	private static final int MAX_THREADS = 20; 

	@org.springframework.beans.factory.annotation.Autowired
	public ResourcesApiController(ObjectMapper objectMapper, HttpServletRequest request) {
		super(request);
		this.objectMapper = objectMapper;
		this.request = request;
	}
/*
	public ResponseEntity<List<DataProduct>> datasetUsingGet(@Parameter(in = ParameterIn.QUERY, description = "q" ,schema=@Schema()) @Valid @RequestParam(value = "q", required = false) String q,@Parameter(in = ParameterIn.QUERY, description = "startDate" ,schema=@Schema()) @Valid @RequestParam(value = "startDate", required = false) String startDate,@Parameter(in = ParameterIn.QUERY, description = "endDate" ,schema=@Schema()) @Valid @RequestParam(value = "endDate", required = false) String endDate,@Parameter(in = ParameterIn.QUERY, description = "bbox" ,schema=@Schema()) @Valid @RequestParam(value = "bbox", required = false) String bbox,@Parameter(in = ParameterIn.QUERY, description = "keywords" ,schema=@Schema()) @Valid @RequestParam(value = "keywords", required = false) String keywords,@Parameter(in = ParameterIn.QUERY, description = "organisations" ,schema=@Schema()) @Valid @RequestParam(value = "organisations", required = false) String organisations) {

		Map<String,Object> requestParameters = new HashMap<>();

		if(!StringUtils.isBlank(q)) {
			try {
				q = java.net.URLDecoder.decode(q, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "q: "+ q, e);
			}
			requestParameters.put("q", q);
		}
		if(!StringUtils.isBlank(startDate)) {
			try {
				startDate = java.net.URLDecoder.decode(startDate, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "startDate: "+ startDate, e);
			}
			try {
				requestParameters.put("schema:startDate", Utils.convertDateUsingPattern(startDate, Utils.EPOSINTERNALFORMAT, Utils.EPOSINTERNALFORMAT));
			} catch (ParseException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "startDate: "+ startDate, e);
			}
		}
		if(!StringUtils.isBlank(endDate)) {
			try {
				endDate = java.net.URLDecoder.decode(endDate, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "endDate: "+ endDate, e);
			}
			try {
				requestParameters.put("schema:endDate", Utils.convertDateUsingPattern(endDate, Utils.EPOSINTERNALFORMAT, Utils.EPOSINTERNALFORMAT));
			} catch (ParseException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "endDate: "+ endDate, e);
			}
		}
		if(!StringUtils.isBlank(bbox)){
			try {
				bbox = java.net.URLDecoder.decode(bbox, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "bbox: "+ bbox, e);
			}
			String[] bboxSplit = bbox.split(",");
			requestParameters.put("epos:northernmostLatitude",bboxSplit[0]);
			requestParameters.put("epos:easternmostLongitude",bboxSplit[1]);
			requestParameters.put("epos:southernmostLatitude",bboxSplit[2]);
			requestParameters.put("epos:westernmostLongitude",bboxSplit[3]);
		}
		if(!StringUtils.isBlank(keywords)) {
			try {
				keywords=java.net.URLDecoder.decode(keywords, StandardCharsets.UTF_8.name());
				keywords = keywords.replace(" ", "");
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "keywords: "+ keywords, e);
			}
			ArrayList<String> kws = new ArrayList<String>();
			for(String kw : keywords.split("\\,")) {
				byte[] byteArray = Base64.decodeBase64(kw.getBytes());
				kws.add(new String(byteArray));
			}
			requestParameters.put("keywords", kws.toString().replace("[", "").replace("]", ""));
		}
		if(!StringUtils.isBlank(organisations)) {
			try {
				organisations=java.net.URLDecoder.decode(organisations, StandardCharsets.UTF_8.name());
				organisations = organisations.replace(" ", "");
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "organisations: "+ organisations, e);
			}
			requestParameters.put("organisations", organisations);
		}

		// < validation >
		try {
			if(!StringUtils.isBlank(startDate)) Utils.convertDateUsingPattern(startDate, Utils.EPOSINTERNALFORMAT, Utils.EPOSINTERNALFORMAT);
			if(!StringUtils.isBlank(endDate)) Utils.convertDateUsingPattern(endDate, Utils.EPOSINTERNALFORMAT, Utils.EPOSINTERNALFORMAT);
		} catch (ParseException e1) { 
			LOGGER.error(String.format("Invalid date format specified (use the format '%s')", Utils.EPOSINTERNALFORMAT),e1);
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		}

		return standardRequest("DATASETS",requestParameters);
	}

	public ResponseEntity<List<Software>> softwareUsingGet() {
		Map<String,Object> requestParameters = new HashMap<>();

		return standardRequest("SOFTWARES", requestParameters);
	}

	public ResponseEntity<List<WebService>> webserviceUsingGet(@Parameter(in = ParameterIn.QUERY, description = "q" ,schema=@Schema()) @Valid @RequestParam(value = "q", required = false) String q,@Parameter(in = ParameterIn.QUERY, description = "startDate" ,schema=@Schema()) @Valid @RequestParam(value = "startDate", required = false) String startDate,@Parameter(in = ParameterIn.QUERY, description = "endDate" ,schema=@Schema()) @Valid @RequestParam(value = "endDate", required = false) String endDate,@Parameter(in = ParameterIn.QUERY, description = "bbox" ,schema=@Schema()) @Valid @RequestParam(value = "bbox", required = false) String bbox,@Parameter(in = ParameterIn.QUERY, description = "keywords" ,schema=@Schema()) @Valid @RequestParam(value = "keywords", required = false) String keywords,@Parameter(in = ParameterIn.QUERY, description = "organisations" ,schema=@Schema()) @Valid @RequestParam(value = "organisations", required = false) String organisations) {
		Map<String,Object> requestParameters = new HashMap<>();

		if(!StringUtils.isBlank(q)) {
			try {
				q = java.net.URLDecoder.decode(q, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "q: "+ q, e);
			}
			requestParameters.put("q", q);
		}
		if(!StringUtils.isBlank(startDate)) {
			try {
				startDate = java.net.URLDecoder.decode(startDate, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "startDate: "+ startDate, e);
			}
			try {
				requestParameters.put("schema:startDate", Utils.convertDateUsingPattern(startDate, Utils.EPOSINTERNALFORMAT, Utils.EPOSINTERNALFORMAT));
			} catch (ParseException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "startDate: "+ startDate, e);
			}
		}
		if(!StringUtils.isBlank(endDate)) {
			try {
				endDate = java.net.URLDecoder.decode(endDate, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "endDate: "+ endDate, e);
			}
			try {
				requestParameters.put("schema:endDate", Utils.convertDateUsingPattern(endDate, Utils.EPOSINTERNALFORMAT, Utils.EPOSINTERNALFORMAT));
			} catch (ParseException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "endDate: "+ endDate, e);
			}
		}
		if(!StringUtils.isBlank(bbox)){
			try {
				bbox = java.net.URLDecoder.decode(bbox, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "bbox: "+ bbox, e);
			}
			String[] bboxSplit = bbox.split(",");
			requestParameters.put("epos:northernmostLatitude",bboxSplit[0]);
			requestParameters.put("epos:easternmostLongitude",bboxSplit[1]);
			requestParameters.put("epos:southernmostLatitude",bboxSplit[2]);
			requestParameters.put("epos:westernmostLongitude",bboxSplit[3]);
		}
		if(!StringUtils.isBlank(keywords)) {
			try {
				keywords=java.net.URLDecoder.decode(keywords, StandardCharsets.UTF_8.name());
				keywords = keywords.replace(" ", "");
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "keywords: "+ keywords, e);
			}
			ArrayList<String> kws = new ArrayList<String>();
			for(String kw : keywords.split("\\,")) {
				byte[] byteArray = Base64.decodeBase64(kw.getBytes());
				kws.add(new String(byteArray));
			}
			requestParameters.put("keywords", kws.toString().replace("[", "").replace("]", ""));
		}
		if(!StringUtils.isBlank(organisations)) {
			try {
				organisations=java.net.URLDecoder.decode(organisations, StandardCharsets.UTF_8.name());
				organisations = organisations.replace(" ", "");
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "organisations: "+ organisations, e);
			}
			ArrayList<String> ors = new ArrayList<String>();
			for(String or : organisations.split("\\,")) {
				byte[] byteArray = Base64.decodeBase64(or.getBytes());
				ors.add(new String(byteArray));
			}
			requestParameters.put("organisations", ors.toString().replace("[", "").replace("]", ""));
		}

		// < validation >
		try {
			if(!StringUtils.isBlank(startDate)) Utils.convertDateUsingPattern(startDate, Utils.EPOSINTERNALFORMAT, Utils.EPOSINTERNALFORMAT);
			if(!StringUtils.isBlank(endDate)) Utils.convertDateUsingPattern(endDate, Utils.EPOSINTERNALFORMAT, Utils.EPOSINTERNALFORMAT);
		} catch (ParseException e1) { 
			LOGGER.error(String.format("Invalid date format specified (use the format '%s')", Utils.EPOSINTERNALFORMAT),e1);
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		}
		// < /validation >

		return standardRequest("WEBSERVICES", requestParameters);
	}
*/
	@Override
	public ResponseEntity<List<OrganizationBean>> organisationUsingGet(@Parameter(in = ParameterIn.QUERY, description = "the id of organisation" ,required=false,schema=@Schema()) @Valid @RequestParam(value = "id", required = false) String id,
			@Parameter(in = ParameterIn.QUERY, description = "q" ,schema=@Schema()) @Valid @RequestParam(value = "q", required = false) String q, 
			@Parameter(in = ParameterIn.QUERY, description = "country" ,schema=@Schema()) @Valid @RequestParam(value = "country", required = false) String country, 
			@Parameter(in = ParameterIn.QUERY, description = "type of organization, comma separated values from the following list {dataproviders, serviceproviders, facilitiesproviders}" ,schema=@Schema()) @Valid @RequestParam(value = "type", required = false) String type) {
		Map<String,Object> requestParams = new HashMap<String, Object>();
		if(id!=null) {
			try {
				id=java.net.URLDecoder.decode(id, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "id: "+ id, e); 
			}
			requestParams = Map.of("id", id);
		}
		if(q!=null) {
			try {
				q=java.net.URLDecoder.decode(q, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "q"+ q, e); 
			}
			requestParams = Map.of("q", q);
		}
		if(country!=null) {
			try {
				country=java.net.URLDecoder.decode(country, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "country: "+ country, e); 
			}
			requestParams = Map.of("country", country);
		}
		if(type!=null) {
			try {
				type=java.net.URLDecoder.decode(type, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				LOGGER.warn(A_PROBLEM_WAS_ENCOUNTERED_DECODING + "type: "+ type, e); 
			}
			requestParams = Map.of("type", type);
		}

		return standardRequest("ORGANISATIONS", requestParams, null);
	}
	
	@Override
	public ResponseEntity<String> exvsUsingGet() {
		List<DataProduct> list = (List<DataProduct>) AbstractAPI.retrieveAPI(EntityNames.DATAPRODUCT.name())
				.retrieveAllWithStatus(StatusType.PUBLISHED);

		// this gets the uri
		Set<String> uniqueUris = list.stream()
				.filter(dp -> dp.getVariableMeasured() != null)
				.flatMap(dp -> dp.getVariableMeasured().stream())
				.collect(Collectors.toSet());

		HttpClient client = HttpClient.newHttpClient();

		ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);

		try {
			// concurrency
			List<CompletableFuture<Map<String, String>>> futures = uniqueUris.stream()
					.map(uri -> CompletableFuture.supplyAsync(() -> {
						try {
							// attach the query
							String jsonLdUri = uri + "?_profile=nvs&_mediatype=application/ld+json";

							HttpRequest request = HttpRequest.newBuilder()
									.uri(URI.create(jsonLdUri))
									.GET()
									.build();
							// sends get request with query
							HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
							if (response.statusCode() != 200)
								return null;

							JsonObject jsonObject = gson.fromJson(response.body(), JsonObject.class);
							if (jsonObject == null || !jsonObject.has("skos:prefLabel"))
								return null;

							JsonElement prefLabelElement = jsonObject.get("skos:prefLabel");
							String name = null;

							if (prefLabelElement.isJsonObject()) {
								JsonObject prefLabelObj = prefLabelElement.getAsJsonObject();
								if (prefLabelObj.has("@value")) {
									name = prefLabelObj.get("@value").getAsString();
								}
							} else if (prefLabelElement.isJsonArray()) {
								JsonArray array = prefLabelElement.getAsJsonArray();
								for (JsonElement el : array) {
									JsonObject obj = el.getAsJsonObject();
									if (obj.has("@value")) {
										name = obj.get("@value").getAsString();
										break;
									}
								}
							}

							if (name != null) {
								Map<String, String> map = new HashMap<>();
								map.put("name", name);
								map.put("uri", uri);
								return map;
							}

						} catch (Exception e) {
							e.printStackTrace();
						}
						return null;
					}, executor))
					.collect(Collectors.toList());

			// Wait for all futures to complete and filter out null results
			List<Map<String, String>> resultList = futures.stream()
					.map(CompletableFuture::join)
					.filter(Objects::nonNull)
					.collect(Collectors.toList());

			return ResponseEntity.ok()
					.contentType(MediaType.APPLICATION_JSON)
					.body(gson.toJson(resultList));
		} finally {
			executor.shutdown(); // always shutdown the executor
		}
	}
}
