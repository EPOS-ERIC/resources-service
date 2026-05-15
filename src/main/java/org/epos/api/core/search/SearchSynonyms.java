package org.epos.api.core.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SearchSynonyms {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchSynonyms.class);

    private static final String SYNONYMS_CONFIG_PATH = "/search-synonyms.json";

    private static final Map<String, List<String>> SYNONYM_MAP = new ConcurrentHashMap<>();

    private static volatile boolean initialized = false;

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        loadSynonymsFromJson();

        initialized = true;
        LOGGER.info("SearchSynonyms initialized with {} synonym entries", SYNONYM_MAP.size());
    }

    public static List<String> getSynonyms(String word) {
        if (!initialized) {
            initialize();
        }

        if (word == null || word.isEmpty()) {
            return Collections.emptyList();
        }

        String lowerWord = word.toLowerCase();

        List<String> synonyms = SYNONYM_MAP.get(lowerWord);
        return synonyms != null ? Collections.unmodifiableList(synonyms) : Collections.emptyList();
    }

    public static boolean hasSynonyms(String word) {
        return !getSynonyms(word).isEmpty();
    }

    public static List<String> expandTerms(List<String> terms) {
        if (!initialized) {
            initialize();
        }

        Set<String> expanded = new LinkedHashSet<>(terms);
        for (String term : terms) {
            expanded.addAll(getSynonyms(term));
        }
        return new ArrayList<>(expanded);
    }

    private static void loadSynonymsFromJson() {
        try (InputStream inputStream = SearchSynonyms.class.getResourceAsStream(SYNONYMS_CONFIG_PATH)) {
            if (inputStream == null) {
                LOGGER.warn("Synonym config file not found at {}, using empty synonym map", SYNONYMS_CONFIG_PATH);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(inputStream);

            if (!rootNode.has("synonyms")) {
                LOGGER.warn("No 'synonyms' key found in config file");
                return;
            }

            JsonNode synonymsNode = rootNode.get("synonyms");

            if (synonymsNode.isArray()) {
                for (JsonNode groupNode : synonymsNode) {
                    if (groupNode.isArray()) {
                        List<String> group = new ArrayList<>();
                        for (JsonNode termNode : groupNode) {
                            group.add(termNode.asText().toLowerCase());
                        }

                        for (String term : group) {
                            List<String> others = new ArrayList<>();
                            for (String other : group) {
                                if (!other.equals(term)) {
                                    others.add(other);
                                }
                            }
                            SYNONYM_MAP.put(term, others);
                        }
                    }
                }
            } else if (synonymsNode.isObject()) {
                Iterator<String> fieldNames = synonymsNode.fieldNames();
                while (fieldNames.hasNext()) {
                    String key = fieldNames.next();
                    JsonNode valueNode = synonymsNode.get(key);

                    List<String> synonyms = new ArrayList<>();
                    if (valueNode.isArray()) {
                        for (JsonNode synNode : valueNode) {
                            synonyms.add(synNode.asText().toLowerCase());
                        }
                    } else if (valueNode.isTextual()) {
                        String[] parts = valueNode.asText().split(",");
                        for (String part : parts) {
                            synonyms.add(part.trim().toLowerCase());
                        }
                    }

                    SYNONYM_MAP.put(key.toLowerCase(), synonyms);
                }
            }

        } catch (Exception e) {
            LOGGER.error("Failed to load synonyms from JSON config", e);
        }
    }
}
