package org.epos.api.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

import com.google.gson.JsonElement;
import org.apache.commons.lang3.StringUtils;
import org.epos.api.utility.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;

public class ZabbixExecutor {

    private static ZabbixExecutor executor;
    private JsonObject hostResults;
    private static final Logger LOGGER = LoggerFactory.getLogger(ZabbixExecutor.class);

    // API Token loaded from environment variable
    private final String apiToken;

    public static ZabbixExecutor getInstance() {
        if(executor == null) {
            executor = new ZabbixExecutor();
        }
        return executor;
    }

    private ZabbixExecutor() {
        // Validate URL
        if (EnvironmentVariables.MONITORING_URL == null || EnvironmentVariables.MONITORING_URL.isEmpty()) {
            LOGGER.error("ZABBIX_URL not configured!");
            LOGGER.error("Set the environment variable: export ZABBIX_URL=\"http://your-server/zabbix/api_jsonrpc.php\"");
            throw new IllegalStateException("Zabbix URL not configured. Set ZABBIX_URL");
        }

        // Validate API Token
        this.apiToken = EnvironmentVariables.MONITORING_API_TOKEN;
        if (apiToken == null || apiToken.isEmpty()) {
            LOGGER.error("ZABBIX_API_TOKEN not configured!");
            LOGGER.error("Set the environment variable: export ZABBIX_API_TOKEN=\"your-api-token\"");
            throw new IllegalStateException("Zabbix API Token not configured. Set ZABBIX_API_TOKEN");
        }

        LOGGER.info("ZabbixExecutor initialized with API Token for Zabbix 7.4");
        LOGGER.info("Zabbix URL: {}", EnvironmentVariables.MONITORING_URL);
    }

    /**
     * Returns the configured API Token.
     * Login/logout is no longer required.
     */
    public String getApiToken() {
        return apiToken;
    }

    /**
     * Base method to perform HTTP requests to Zabbix 7.4.
     * Uses the Authorization: Bearer header for authentication.
     */
    private String sendRequest(String requestBodyJson) throws IOException, InterruptedException {
        return sendRequest(requestBodyJson, true);
    }

    /**
     * Base method to perform HTTP requests to Zabbix 7.4.
     * @param requestBodyJson The request body in JSON format
     * @param useAuth If true, includes the Authorization Bearer header
     */
    private String sendRequest(String requestBodyJson, boolean useAuth) throws IOException, InterruptedException {
        var values = Utils.gson.fromJson(requestBodyJson, HashMap.class);
        var objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(values);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(EnvironmentVariables.MONITORING_URL))
                .header("Content-Type", "application/json-rpc")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        // Add Authorization header only if requested
        if (useAuth) {
            requestBuilder.header("Authorization", "Bearer " + apiToken);
        }

        HttpRequest request = requestBuilder.build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        LOGGER.debug("Zabbix response: {}", response.body());
        return response.body();
    }

    /**
     * Retrieves the list of hosts from Zabbix.
     */
    public String retrieveHosts() throws IOException, InterruptedException {
        String retrieveHosts = "{\n"
                + "    \"jsonrpc\": \"2.0\",\n"
                + "    \"method\": \"host.get\",\n"
                + "    \"params\": {\n"
                + "        \"output\": [\n"
                + "            \"hostid\",\n"
                + "            \"host\"\n"
                + "        ],\n"
                + "        \"selectInterfaces\": [\n"
                + "            \"interfaceid\",\n"
                + "            \"ip\"\n"
                + "        ]\n"
                + "    },\n"
                + "    \"id\": 2\n"
                + "}";

        return sendRequest(retrieveHosts);
    }

    /**
     * Retrieves all items from Zabbix.
     * To filter by specific host, use getItemsForHost(hostid).
     */
    public String getItems() throws IOException, InterruptedException {
        String retrieveItems = "{\n"
                + "    \"jsonrpc\": \"2.0\",\n"
                + "    \"method\": \"item.get\",\n"
                + "    \"params\": {\n"
                + "        \"output\": [\n"
                + "            \"itemid\",\n"
                + "            \"name\",\n"
                + "            \"key_\",\n"
                + "            \"lastclock\",\n"
                + "            \"lastvalue\",\n"
                + "            \"status\"\n"
                + "        ],\n"
                + "        \"webitems\": true,\n"
                + "        \"selectHosts\": [\n"
                + "            \"hostid\",\n"
                + "            \"host\"\n"
                + "        ]\n"
                + "    },\n"
                + "    \"id\": 2\n"
                + "}";

        return sendRequest(retrieveItems);
    }

    /**
     * Retrieves items for a specific host.
     */
    public String getItemsForHost(String hostid) throws IOException, InterruptedException {
        String retrieveItems = "{\n"
                + "    \"jsonrpc\": \"2.0\",\n"
                + "    \"method\": \"item.get\",\n"
                + "    \"params\": {\n"
                + "        \"output\": [\n"
                + "            \"itemid\",\n"
                + "            \"name\",\n"
                + "            \"key_\",\n"
                + "            \"lastclock\",\n"
                + "            \"lastvalue\",\n"
                + "            \"status\"\n"
                + "        ],\n"
                + "        \"hostids\": [\"" + hostid + "\"],\n"
                + "        \"selectHosts\": [\n"
                + "            \"hostid\",\n"
                + "            \"host\"\n"
                + "        ],\n"
                + "        \"sortfield\": \"name\"\n"
                + "    },\n"
                + "    \"id\": 2\n"
                + "}";

        return sendRequest(retrieveItems);
    }

    /**
     * Retrieves web.test.error items from Zabbix.
     */
    public String getWebTestErrorItems() throws IOException, InterruptedException {
        String retrieveItems = "{\n"
                + "    \"jsonrpc\": \"2.0\",\n"
                + "    \"method\": \"item.get\",\n"
                + "    \"params\": {\n"
                + "        \"output\": [\n"
                + "            \"itemid\",\n"
                + "            \"name\",\n"
                + "            \"key_\",\n"
                + "            \"lastclock\",\n"
                + "            \"lastvalue\"\n"
                + "        ],\n"
                + "        \"webitems\": true,\n"
                + "        \"selectHosts\": [\n"
                + "            \"hostid\",\n"
                + "            \"host\"\n"
                + "        ],\n"
                + "        \"search\": {\n"
                + "            \"key_\": \"web.test.error\"\n"
                + "        },\n"
                + "        \"searchByAny\": true\n"
                + "    },\n"
                + "    \"id\": 2\n"
                + "}";

        return sendRequest(retrieveItems);
    }

    /**
     * Retrieves all problems from Zabbix.
     * To filter by specific host, use getProblemsForHost(hostid).
     */
    public String getProblems() throws IOException, InterruptedException {
        String retrieveItems = "{\n"
                + "    \"jsonrpc\": \"2.0\",\n"
                + "    \"method\": \"problem.get\",\n"
                + "    \"params\": {\n"
                + "        \"output\": [\n"
                + "            \"eventid\",\n"
                + "            \"objectid\",\n"
                + "            \"name\",\n"
                + "            \"severity\",\n"
                + "            \"clock\"\n"
                + "        ],\n"
                + "        \"recent\": false,\n"
                + "        \"sortfield\": \"eventid\",\n"
                + "        \"sortorder\": \"DESC\"\n"
                + "    },\n"
                + "    \"id\": 3\n"
                + "}";

        return sendRequest(retrieveItems);
    }

    /**
     * Retrieves problems for a specific host.
     */
    public String getProblemsForHost(String hostid) throws IOException, InterruptedException {
        String retrieveItems = "{\n"
                + "    \"jsonrpc\": \"2.0\",\n"
                + "    \"method\": \"problem.get\",\n"
                + "    \"params\": {\n"
                + "        \"output\": [\n"
                + "            \"eventid\",\n"
                + "            \"objectid\",\n"
                + "            \"name\",\n"
                + "            \"severity\",\n"
                + "            \"clock\"\n"
                + "        ],\n"
                + "        \"hostids\": [\"" + hostid + "\"],\n"
                + "        \"recent\": false,\n"
                + "        \"sortfield\": \"clock\",\n"
                + "        \"sortorder\": \"DESC\"\n"
                + "    },\n"
                + "    \"id\": 3\n"
                + "}";

        return sendRequest(retrieveItems);
    }

    /**
     * Retrieves HTTP tests for a specific host.
     * @param hostid The ID of the host to retrieve HTTP tests for
     */
    public String getHttpItems(String hostid) throws IOException, InterruptedException {
        if (hostid == null || hostid.isEmpty()) {
            throw new IllegalArgumentException("hostid cannot be null or empty");
        }

        String retrieveItems = "{\n"
                + "    \"jsonrpc\": \"2.0\",\n"
                + "    \"method\": \"httptest.get\",\n"
                + "    \"params\": {\n"
                + "        \"output\": [\n"
                + "            \"httptestid\",\n"
                + "            \"name\",\n"
                + "            \"status\"\n"
                + "        ],\n"
                + "        \"hostids\": [\"" + hostid + "\"],\n"
                + "        \"selectHosts\": [\n"
                + "            \"hostid\",\n"
                + "            \"host\"\n"
                + "        ],\n"
                + "        \"selectSteps\": \"extend\"\n"
                + "    },\n"
                + "    \"id\": 1\n"
                + "}";

        return sendRequest(retrieveItems);
    }

    /**
     * Retrieves HTTP tests (alias for getGraphs).
     */
    public String getHttpTest() throws IOException, InterruptedException {
        return getGraphs();
    }

    /**
     * Retrieves all HTTP tests from Zabbix.
     * To filter by specific host, use getHttpItems(hostid).
     */
    public String getGraphs() throws IOException, InterruptedException {
        String httpTest = "{\n"
                + "    \"jsonrpc\": \"2.0\",\n"
                + "    \"method\": \"httptest.get\",\n"
                + "    \"params\": {\n"
                + "        \"output\": [\n"
                + "            \"httptestid\",\n"
                + "            \"name\",\n"
                + "            \"status\"\n"
                + "        ],\n"
                + "        \"selectHosts\": [\n"
                + "            \"hostid\",\n"
                + "            \"host\"\n"
                + "        ],\n"
                + "        \"selectSteps\": \"extend\"\n"
                + "    },\n"
                + "    \"id\": 4\n"
                + "}";

        return getResultAsString(httpTest);
    }

    /**
     * Performs a request and returns the "result" field as a string.
     */
    private String getResultAsString(String requestJson) throws IOException, InterruptedException {
        return getResultAsString(requestJson, true);
    }

    /**
     * Performs a request and returns the "result" field as a string.
     * @param requestJson The request JSON
     * @param useAuth If true, includes the Authorization Bearer header
     */
    private String getResultAsString(String requestJson, boolean useAuth) throws IOException, InterruptedException {
        var values = Utils.gson.fromJson(requestJson, HashMap.class);
        var objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(values);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(EnvironmentVariables.MONITORING_URL))
                .header("Content-Type", "application/json-rpc")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        // Add Authorization header only if requested
        if (useAuth) {
            requestBuilder.header("Authorization", "Bearer " + apiToken);
        }

        HttpRequest request = requestBuilder.build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonObject jsonResponse = Utils.gson.fromJson(response.body(), JsonObject.class);

        // Handle any errors from the API
        if (jsonResponse.has("error")) {
            JsonObject error = jsonResponse.getAsJsonObject("error");
            String errorMessage = error.get("message").getAsString();
            String errorData = error.has("data") ? error.get("data").getAsString() : "";
            LOGGER.error("Zabbix API Error: {} - {}", errorMessage, errorData);
            throw new IOException("Zabbix API Error: " + errorMessage + " - " + errorData);
        }

        return jsonResponse.get("result").getAsString();
    }

    // Getter and Setter for hostResults
    public JsonObject getHostResults() {
        return hostResults;
    }

    public void setHostResults(JsonObject hostResults) {
        this.hostResults = hostResults;
    }

    /**
     * Retrieves status from a SHA ID.
     */
    public Integer getStatusInfoFromSha(String idSha) {
        if(hostResults == null) return 0;
        if(!hostResults.has(idSha)) return 0;
        if(!hostResults.get(idSha).getAsJsonObject().has("status")) return 0;

        return hostResults.get(idSha).getAsJsonObject().get("status").getAsInt();
    }

    /**
     * Retrieves the status timestamp from a SHA ID.
     */
    public String getStatusTimestampInfoFromSha(String idSha) {
        if(hostResults == null) return null;
        if(!hostResults.has(idSha)) return null;
        if(!hostResults.get(idSha).getAsJsonObject().has("timestamp")) return null;

        return hostResults.get(idSha).getAsJsonObject().get("timestamp").getAsString();
    }

    /**
     * Returns the monitoring URL for a given SHA ID.
     * TODO: Make the base URL configurable
     */
    public String getStatusURLFromSha(String idSha) {
        return "https://epos-services.vm.fedcloud.eu/monitoring/?sha=" + idSha;
    }

    /**
     * Verifies the connection with Zabbix by checking the API version.
     * Note: apiinfo.version does NOT require authentication in Zabbix 7.4.
     */
    public String getApiVersion() throws IOException, InterruptedException {
        String versionRequest = "{\n"
                + "    \"jsonrpc\": \"2.0\",\n"
                + "    \"method\": \"apiinfo.version\",\n"
                + "    \"params\": {},\n"
                + "    \"id\": 1\n"
                + "}";

        // apiinfo.version must be called WITHOUT the Authorization header
        String response = sendRequest(versionRequest, false);
        JsonObject jsonResponse = Utils.gson.fromJson(response, JsonObject.class);

        if (jsonResponse.has("result")) {
            return jsonResponse.get("result").getAsString();
        }

        if (jsonResponse.has("error")) {
            JsonObject error = jsonResponse.getAsJsonObject("error");
            String errorMessage = error.get("message").getAsString();
            LOGGER.error("Error retrieving API version: {}", errorMessage);
        }

        return null;
    }
}
