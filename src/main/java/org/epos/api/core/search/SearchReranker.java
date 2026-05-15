package org.epos.api.core.search;

import org.epos.api.beans.DiscoveryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class SearchReranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchReranker.class);

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private static final double TITLE_WEIGHT = 3.0;
    private static final double KEYWORD_WEIGHT = 2.5;
    private static final double DESCRIPTION_WEIGHT = 1.0;
    private static final double UID_WEIGHT = 1.5;

    private static final double PROPER_NOUN_BOOST = 1.8;
    private static final double PHRASE_MATCH_BOOST = 2.0;
    private static final double EXACT_MATCH_BOOST = 1.5;

    public static List<DiscoveryItem> rerank(List<DiscoveryItem> items, String originalQuery) {
        if (items == null || items.isEmpty() || originalQuery == null || originalQuery.trim().isEmpty()) {
            return items;
        }

        SearchSynonyms synonyms = new SearchSynonyms();
        List<QueryTerm> queryTerms = SearchQueryProcessor.getAnalyzedTermsWithSynonyms(originalQuery, synonyms);
        if (queryTerms.isEmpty()) {
            return items;
        }

        return rerank(items, queryTerms);
    }

    public static List<DiscoveryItem> rerank(List<DiscoveryItem> items, List<QueryTerm> queryTerms) {
        if (items == null || items.isEmpty() || queryTerms == null || queryTerms.isEmpty()) {
            return items;
        }

        int totalDocs = items.size();

        Map<String, Integer> termDocFrequency = calculateDocFrequencies(items, queryTerms);

        List<ScoredItem> scoredItems = new ArrayList<>();

        for (DiscoveryItem item : items) {
            double score = calculateBM25Score(item, queryTerms, termDocFrequency, totalDocs);
            scoredItems.add(new ScoredItem(item, score));
        }

        scoredItems.sort((a, b) -> Double.compare(b.score, a.score));

        LOGGER.debug("Reranked {} items with {} query terms", items.size(), queryTerms.size());

        return scoredItems.stream()
                .map(si -> si.item)
                .collect(Collectors.toList());
    }

    private static double calculateBM25Score(DiscoveryItem item, List<QueryTerm> queryTerms,
                                              Map<String, Integer> termDocFrequency, int totalDocs) {
        double totalScore = 0.0;

        String title = item.getTitle();
        String description = item.getDescription();
        String uid = item.getUid();

        double titleLength = title != null ? title.split("\\s+").length : 0;
        double descriptionLength = description != null ? description.split("\\s+").length : 0;

        double avgDocLength = (titleLength + descriptionLength) / 2.0;

        for (QueryTerm qTerm : queryTerms) {
            String term = qTerm.getTerm();

            double idf = calculateIDF(termDocFrequency.getOrDefault(term, 0), totalDocs);

            double titleScore = scoreFieldBM25(title, term, qTerm, titleLength, avgDocLength, idf);
            double descScore = scoreFieldBM25(description, term, qTerm, descriptionLength, avgDocLength, idf);
            double uidScore = scoreFieldExact(uid, term, qTerm, idf);

            double termScore = (titleScore * TITLE_WEIGHT) +
                             (descScore * DESCRIPTION_WEIGHT) +
                             (uidScore * UID_WEIGHT);

            double typeBoost = getTypeBoost(qTerm);
            totalScore += termScore * typeBoost;
        }

        double matchCoverage = calculateMatchCoverage(item, queryTerms);
        totalScore *= (0.6 + 0.4 * matchCoverage);

        return totalScore;
    }

    private static double scoreFieldBM25(String fieldText, String term, QueryTerm qTerm,
                                          double fieldLength, double avgDocLength, double idf) {
        if (fieldText == null || fieldText.isEmpty()) {
            return 0.0;
        }

        String lowerField = fieldText.toLowerCase();
        String lowerTerm = term.toLowerCase();

        double termFrequency = 0.0;

        if (qTerm.isPhrase()) {
            if (lowerField.contains(lowerTerm)) {
                termFrequency = 1.0;
                termFrequency *= PHRASE_MATCH_BOOST;
            }
        } else {
            long count = countOccurrences(lowerField, lowerTerm);
            if (count > 0) {
                termFrequency = count;
                termFrequency *= EXACT_MATCH_BOOST;
            } else {
                double similarity = TextSimilarityScorer.calculateSimilarity(lowerTerm, lowerField);
                if (similarity > 0.7) {
                    termFrequency = similarity;
                }
            }
        }

        if (termFrequency == 0.0) {
            return 0.0;
        }

        double numerator = termFrequency * (K1 + 1.0);
        double denominator = termFrequency + K1 * (1.0 - B + B * (fieldLength / Math.max(1.0, avgDocLength)));

        return idf * (numerator / denominator);
    }

    private static double scoreFieldExact(String fieldText, String term, QueryTerm qTerm, double idf) {
        if (fieldText == null || fieldText.isEmpty()) {
            return 0.0;
        }

        String lowerField = fieldText.toLowerCase();
        String lowerTerm = term.toLowerCase();

        if (lowerField.contains(lowerTerm)) {
            return idf * EXACT_MATCH_BOOST;
        }

        return 0.0;
    }

    private static double calculateIDF(int docFrequency, int totalDocs) {
        if (docFrequency == 0) {
            return Math.log(2);
        }

        return Math.log((totalDocs - docFrequency + 0.5) / (docFrequency + 0.5) + 1.0);
    }

    private static double getTypeBoost(QueryTerm qTerm) {
        switch (qTerm.getType()) {
            case PROPER_NOUN:
                return PROPER_NOUN_BOOST;
            case PHRASE:
                return PHRASE_MATCH_BOOST;
            default:
                return 1.0;
        }
    }

    private static double calculateMatchCoverage(DiscoveryItem item, List<QueryTerm> queryTerms) {
        String combinedText = combineFields(item).toLowerCase();
        if (combinedText.isEmpty()) {
            return 0.0;
        }

        int matchedTerms = 0;
        for (QueryTerm qTerm : queryTerms) {
            if (combinedText.contains(qTerm.getTerm().toLowerCase())) {
                matchedTerms++;
            }
        }

        return (double) matchedTerms / queryTerms.size();
    }

    private static String combineFields(DiscoveryItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.getTitle() != null) {
            sb.append(item.getTitle()).append(" ");
        }
        if (item.getDescription() != null) {
            sb.append(item.getDescription()).append(" ");
        }
        if (item.getUid() != null) {
            sb.append(item.getUid());
        }
        return sb.toString();
    }

    private static Map<String, Integer> calculateDocFrequencies(List<DiscoveryItem> items, List<QueryTerm> queryTerms) {
        Map<String, Integer> docFreq = new HashMap<>();

        for (QueryTerm qTerm : queryTerms) {
            String term = qTerm.getTerm().toLowerCase();
            int count = 0;

            for (DiscoveryItem item : items) {
                String combined = combineFields(item).toLowerCase();
                if (combined.contains(term)) {
                    count++;
                }
            }

            docFreq.put(term, count);
        }

        return docFreq;
    }

    private static long countOccurrences(String text, String term) {
        long count = 0;
        int index = 0;
        while ((index = text.indexOf(term, index)) != -1) {
            count++;
            index += term.length();
        }
        return count;
    }

    private static class ScoredItem {
        final DiscoveryItem item;
        final double score;

        ScoredItem(DiscoveryItem item, double score) {
            this.item = item;
            this.score = score;
        }
    }
}
