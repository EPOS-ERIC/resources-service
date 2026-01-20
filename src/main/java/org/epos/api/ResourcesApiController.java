package org.epos.api;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import org.epos.api.beans.OrganizationBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*", allowedHeaders = "*")
@jakarta.annotation.Generated(value = "io.swagger.codegen.v3.generators.java.SpringCodegen", date = "2021-10-11T14:51:06.469Z[GMT]")
@RestController
public class ResourcesApiController extends ApiController implements ResourcesApi {

	private static final String A_PROBLEM_WAS_ENCOUNTERED_DECODING = "A problem was encountered decoding: ";
	private static final Logger LOGGER = LoggerFactory.getLogger(ResourcesApiController.class);

	private final ObjectMapper objectMapper;

	private final HttpServletRequest request;


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
			@Parameter(in = ParameterIn.QUERY, description = "type of organization, comma separated values from the following list {dataproviders, serviceproviders}" ,schema=@Schema()) @Valid @RequestParam(value = "type", required = false) String type) {
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

}