package org.epos.api.core.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SearchQueryProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchQueryProcessor.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\s,;]+");
    private static final Pattern NON_ALPHA_PATTERN = Pattern.compile("[^a-z0-9\\s'-]");
    private static final Pattern QUOTED_PHRASE_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern PROPER_NOUN_PATTERN = Pattern.compile("\\b[A-Z]{2,}\\b");

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
            "give", "tell", "think", "say", "much", "many", "really", "must",
            "looking", "regarding", "regards", "needs", "please",
            "help", "search", "showing", "shows", "something",
            "anything", "everything", "nothing", "someone", "everyone"
    );

    public static List<String> processQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<QueryTerm> terms = analyzeQuery(query);
        return terms.stream()
                .map(QueryTerm::getTerm)
                .collect(Collectors.toList());
    }

    public static List<QueryTerm> analyzeQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<QueryTerm> terms = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        List<String> phrases = extractQuotedPhrases(query);
        for (String phrase : phrases) {
            String normalized = phrase.toLowerCase().trim();
            if (!normalized.isEmpty() && !seen.contains(normalized)) {
                terms.add(new QueryTerm(normalized, QueryTerm.Type.PHRASE, 3.0));
                seen.add(normalized);
            }
        }

        String queryWithoutQuotes = QUOTED_PHRASE_PATTERN.matcher(query).replaceAll(" ");
        List<String> tokens = tokenize(queryWithoutQuotes);

        Set<String> properNouns = detectProperNouns(query);

        for (String token : tokens) {
            String lower = token.toLowerCase();
            if (seen.contains(lower)) {
                continue;
            }

            if (properNouns.contains(token)) {
                terms.add(new QueryTerm(lower, QueryTerm.Type.PROPER_NOUN, 2.5));
                seen.add(lower);
            } else {
                terms.add(new QueryTerm(lower, QueryTerm.Type.COMMON_TERM, 1.0));
                seen.add(lower);
            }
        }

        LOGGER.debug("Query analysis: '{}' -> {}", query, terms);
        return terms;
    }

    public static List<String> processQueryWithSynonyms(String query, SearchSynonyms synonyms) {
        List<QueryTerm> analyzedTerms = analyzeQuery(query);
        if (analyzedTerms.isEmpty()) {
            return Collections.emptyList();
        }

        return expandWithSynonyms(analyzedTerms, synonyms);
    }

    public static List<String> getSearchTermsForSQL(String query) {
        return processQuery(query);
    }

    public static List<String> getSearchTermsForSQLWithSynonyms(String query, SearchSynonyms synonyms) {
        List<QueryTerm> analyzedTerms = analyzeQuery(query);
        if (analyzedTerms.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> expanded = new LinkedHashSet<>();

        for (QueryTerm qTerm : analyzedTerms) {
            String term = qTerm.getTerm();
            expanded.add(term);

            if (qTerm.isPhrase()) {
                continue;
            }

            List<String> syns = synonyms.getSynonyms(term);
            if (syns.size() <= 3) {
                for (String syn : syns) {
                    if (!syn.contains(" ")) {
                        expanded.add(syn);
                    }
                }
            }
        }

        return new ArrayList<>(expanded);
    }

    public static List<QueryTerm> getAnalyzedTermsWithSynonyms(String query, SearchSynonyms synonyms) {
        List<QueryTerm> analyzedTerms = analyzeQuery(query);
        if (analyzedTerms.isEmpty()) {
            return Collections.emptyList();
        }

        List<QueryTerm> expanded = new ArrayList<>(analyzedTerms);
        Set<String> seen = analyzedTerms.stream()
                .map(t -> t.getTerm())
                .collect(Collectors.toSet());

        for (QueryTerm qTerm : analyzedTerms) {
            if (qTerm.isPhrase()) {
                continue;
            }

            List<String> syns = synonyms.getSynonyms(qTerm.getTerm());
            for (String syn : syns) {
                if (!seen.contains(syn)) {
                    double synWeight = qTerm.getWeight() * 0.7;
                    QueryTerm.Type synType = syn.contains(" ") ? QueryTerm.Type.PHRASE : qTerm.getType();
                    expanded.add(new QueryTerm(syn, synType, synWeight));
                    seen.add(syn);
                }
            }
        }

        return expanded;
    }

    private static List<String> expandWithSynonyms(List<QueryTerm> terms, SearchSynonyms synonyms) {
        Set<String> expanded = new LinkedHashSet<>();

        for (QueryTerm qTerm : terms) {
            expanded.add(qTerm.getTerm());

            if (qTerm.isPhrase()) {
                continue;
            }

            expanded.addAll(synonyms.getSynonyms(qTerm.getTerm()));
        }

        return new ArrayList<>(expanded);
    }

    private static List<String> extractQuotedPhrases(String query) {
        List<String> phrases = new ArrayList<>();
        Matcher matcher = QUOTED_PHRASE_PATTERN.matcher(query);
        while (matcher.find()) {
            phrases.add(matcher.group(1));
        }
        return phrases;
    }

    private static Set<String> detectProperNouns(String query) {
        Set<String> properNouns = new HashSet<>();
        Matcher matcher = PROPER_NOUN_PATTERN.matcher(query);
        while (matcher.find()) {
            properNouns.add(matcher.group());
        }

        String[] words = query.split("\\s+");
        for (String word : words) {
            String cleaned = word.replaceAll("[^a-zA-Z]", "");
            if (cleaned.length() > 1 && Character.isUpperCase(cleaned.charAt(0))) {
                boolean hasLowercase = false;
                for (int i = 1; i < cleaned.length(); i++) {
                    if (Character.isLowerCase(cleaned.charAt(i))) {
                        hasLowercase = true;
                        break;
                    }
                }
                if (hasLowercase && !STOPWORDS.contains(cleaned.toLowerCase())) {
                    properNouns.add(cleaned);
                }
            }
        }

        return properNouns;
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
