package org.epos.api.core.search;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SearchQueryProcessor {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\s,;]+");
    private static final Pattern NON_ALPHA_PATTERN = Pattern.compile("[^a-z0-9\\s]");

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "shall", "can", "need", "dare",
            "ought", "used", "it", "its", "this", "that", "these", "those", "i",
            "you", "he", "she", "we", "they", "what", "which", "who", "whom",
            "whose", "where", "when", "why", "how", "all", "each", "every",
            "both", "few", "more", "most", "other", "some", "such", "no", "nor",
            "not", "only", "own", "same", "so", "than", "too", "very", "s", "t",
            "just", "don", "now", "about", "above", "below", "between", "into",
            "through", "during", "before", "after", "out", "up", "down", "off",
            "over", "under", "again", "further", "then", "once", "here", "there",
            "any", "as", "if", "because", "until", "while", "show", "me", "get",
            "make", "like", "also", "well", "back", "even", "still", "way",
            "take", "come", "go", "see", "know", "want", "look", "use", "find",
            "give", "tell", "think", "say", "much", "many", "really"
    );

    public static List<String> processQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        return tokenize(query);
    }

    public static List<String> processQueryWithSynonyms(String query, SearchSynonyms synonyms) {
        List<String> tokens = processQuery(query);
        if (tokens.isEmpty()) {
            return tokens;
        }

        Set<String> expanded = new LinkedHashSet<>(tokens);
        for (String token : tokens) {
            expanded.addAll(synonyms.getSynonyms(token));
        }

        return new ArrayList<>(expanded);
    }

    public static List<String> getSearchTermsForSQL(String query) {
        return processQuery(query);
    }

    public static List<String> getSearchTermsForSQLWithSynonyms(String query, SearchSynonyms synonyms) {
        List<String> tokens = processQuery(query);
        if (tokens.isEmpty()) {
            return tokens;
        }

        Set<String> expanded = new LinkedHashSet<>();
        for (String token : tokens) {
            expanded.add(token);
            List<String> synonyms_list = synonyms.getSynonyms(token);
            if (synonyms_list.size() <= 2) {
                expanded.addAll(synonyms_list);
            }
        }

        return new ArrayList<>(expanded);
    }

    private static List<String> tokenize(String query) {
        String lowercased = query.toLowerCase().trim();
        String cleaned = NON_ALPHA_PATTERN.matcher(lowercased).replaceAll(" ");

        return Arrays.stream(TOKEN_PATTERN.split(cleaned))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .filter(token -> token.length() > 1)
                .filter(token -> !STOPWORDS.contains(token))
                .distinct()
                .collect(Collectors.toList());
    }
}
