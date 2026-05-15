package org.epos.api.core.search;

import org.epos.api.beans.DiscoveryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class SearchReranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchReranker.class);

    private static final double TITLE_WEIGHT = 3.0;
    private static final double KEYWORD_WEIGHT = 2.0;
    private static final double DESCRIPTION_WEIGHT = 1.0;
    private static final double UID_WEIGHT = 1.5;

    public static List<DiscoveryItem> rerank(List<DiscoveryItem> items, String originalQuery) {
        if (items == null || items.isEmpty() || originalQuery == null || originalQuery.trim().isEmpty()) {
            return items;
        }

        List<String> queryTerms = SearchQueryProcessor.processQuery(originalQuery);
        if (queryTerms.isEmpty()) {
            return items;
        }

        return rerank(items, queryTerms);
    }

    public static List<DiscoveryItem> rerank(List<DiscoveryItem> items, List<String> queryTerms) {
        if (items == null || items.isEmpty() || queryTerms == null || queryTerms.isEmpty()) {
            return items;
        }

        List<ScoredItem> scoredItems = new ArrayList<>();

        for (DiscoveryItem item : items) {
            double score = calculateScore(item, queryTerms);
            scoredItems.add(new ScoredItem(item, score));
        }

        scoredItems.sort((a, b) -> Double.compare(b.score, a.score));

        LOGGER.debug("Reranked {} items with {} query terms", items.size(), queryTerms.size());

        return scoredItems.stream()
                .map(si -> si.item)
                .collect(Collectors.toList());
    }

    private static double calculateScore(DiscoveryItem item, List<String> queryTerms) {
        double totalScore = 0.0;

        String title = item.getTitle();
        if (title != null && !title.isEmpty()) {
            totalScore += scoreField(title, queryTerms) * TITLE_WEIGHT;
        }

        String description = item.getDescription();
        if (description != null && !description.isEmpty()) {
            totalScore += scoreField(description, queryTerms) * DESCRIPTION_WEIGHT;
        }

        String uid = item.getUid();
        if (uid != null && !uid.isEmpty()) {
            totalScore += scoreField(uid, queryTerms) * UID_WEIGHT;
        }

        totalScore += 1.0;

        return totalScore;
    }

    private static double scoreField(String fieldText, List<String> queryTerms) {
        String lowerField = fieldText.toLowerCase();
        double fieldScore = 0.0;

        int exactMatches = 0;
        int fuzzyMatches = 0;

        for (String term : queryTerms) {
            String lowerTerm = term.toLowerCase();

            if (lowerField.contains(lowerTerm)) {
                exactMatches++;
                fieldScore += 1.0;

                long count = countOccurrences(lowerField, lowerTerm);
                fieldScore += count * 0.2;
            } else {
                double similarity = TextSimilarityScorer.calculateSimilarity(lowerTerm, lowerField);
                if (similarity > 0.0) {
                    fuzzyMatches++;
                    fieldScore += similarity * 0.7;
                }
            }
        }

        if (exactMatches == queryTerms.size()) {
            fieldScore *= 1.5;
        }

        double matchRatio = (exactMatches + fuzzyMatches * 0.7) / queryTerms.size();
        fieldScore *= (0.5 + 0.5 * matchRatio);

        return fieldScore;
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
