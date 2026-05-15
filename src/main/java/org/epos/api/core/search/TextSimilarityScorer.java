package org.epos.api.core.search;

import java.util.*;

public class TextSimilarityScorer {

    private static final PorterStemmer STEMMER = new PorterStemmer();

    private static final double FUZZY_THRESHOLD = 0.8;

    public static double calculateSimilarity(String queryTerm, String text) {
        if (queryTerm == null || text == null || text.isEmpty()) {
            return 0.0;
        }

        String lowerText = text.toLowerCase();

        if (lowerText.contains(queryTerm)) {
            return 1.0;
        }

        String stemmedQuery = STEMMER.stem(queryTerm);
        String stemmedText = STEMMER.stem(lowerText);

        if (stemmedText.contains(stemmedQuery)) {
            return 0.9;
        }

        double jaroWinkler = jaroWinklerSimilarity(queryTerm, lowerText);
        if (jaroWinkler >= FUZZY_THRESHOLD) {
            return jaroWinkler;
        }

        String[] textWords = lowerText.split("\\s+");
        double maxWordSimilarity = 0.0;
        for (String word : textWords) {
            double similarity = jaroWinklerSimilarity(queryTerm, word);
            if (similarity > maxWordSimilarity) {
                maxWordSimilarity = similarity;
            }
            if (similarity >= FUZZY_THRESHOLD) {
                return similarity;
            }
        }

        return maxWordSimilarity >= FUZZY_THRESHOLD ? maxWordSimilarity : 0.0;
    }

    public static double calculateBestTermSimilarity(List<String> queryTerms, String text) {
        if (queryTerms == null || queryTerms.isEmpty() || text == null || text.isEmpty()) {
            return 0.0;
        }

        double bestScore = 0.0;
        for (String term : queryTerms) {
            double score = calculateSimilarity(term, text);
            if (score > bestScore) {
                bestScore = score;
            }
        }

        return bestScore;
    }

    public static boolean hasAnyMatch(List<String> queryTerms, String text) {
        return calculateBestTermSimilarity(queryTerms, text) > 0.0;
    }

    public static double jaroWinklerSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }

        if (s1.equals(s2)) {
            return 1.0;
        }

        int len1 = s1.length();
        int len2 = s2.length();

        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }

        int matchDistance = Math.max(len1, len2) / 2 - 1;

        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];

        int matches = 0;
        int transpositions = 0;

        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);

            for (int j = start; j < end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) {
            return 0.0;
        }

        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) {
                continue;
            }
            while (!s2Matches[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double jaro = ((double) matches / len1
                + (double) matches / len2
                + (double) (matches - transpositions / 2.0) / matches) / 3.0;

        double prefixScale = 0.1;
        int prefixLength = 0;
        int maxPrefixLength = Math.min(4, Math.min(len1, len2));

        for (int i = 0; i < maxPrefixLength; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefixLength++;
            } else {
                break;
            }
        }

        return jaro + prefixScale * prefixLength * (1 - jaro);
    }

    public static int levenshteinDistance(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return Integer.MAX_VALUE;
        }

        int len1 = s1.length();
        int len2 = s2.length();

        if (len1 == 0) return len2;
        if (len2 == 0) return len1;

        int[][] matrix = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) {
            matrix[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            matrix[0][j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                matrix[i][j] = Math.min(
                        Math.min(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1),
                        matrix[i - 1][j - 1] + cost
                );
            }
        }

        return matrix[len1][len2];
    }

    public static double tfIdfScore(String term, String document, int docFrequency, int totalDocs) {
        if (term == null || document == null || document.isEmpty()) {
            return 0.0;
        }

        double tf = termFrequency(term, document);

        double idf = Math.log((double) totalDocs / Math.max(1, docFrequency));

        return tf * idf;
    }

    private static double termFrequency(String term, String document) {
        String lowerDoc = document.toLowerCase();
        String lowerTerm = term.toLowerCase();

        int count = 0;
        int index = 0;
        while ((index = lowerDoc.indexOf(lowerTerm, index)) != -1) {
            count++;
            index += lowerTerm.length();
        }

        String[] words = lowerDoc.split("\\s+");
        return (double) count / Math.max(1, words.length);
    }

    private static class PorterStemmer {
        public String stem(String word) {
            if (word == null || word.length() <= 2) {
                return word;
            }

            word = word.toLowerCase();

            if (word.endsWith("ies") && word.length() > 4) {
                return word.substring(0, word.length() - 3) + (word.endsWith("ies") ? "y" : "");
            }
            if (word.endsWith("sses")) {
                return word.substring(0, word.length() - 2);
            }
            if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) {
                return word.substring(0, word.length() - 1);
            }

            if (word.endsWith("ational") && word.length() > 8) {
                return word.substring(0, word.length() - 5) + "e";
            }
            if (word.endsWith("tional") && word.length() > 7) {
                return word.substring(0, word.length() - 2);
            }
            if (word.endsWith("ization") && word.length() > 8) {
                return word.substring(0, word.length() - 5) + "e";
            }
            if (word.endsWith("ation") && word.length() > 6) {
                return word.substring(0, word.length() - 3) + "e";
            }

            if (word.endsWith("ing") && word.length() > 4) {
                String stem = word.substring(0, word.length() - 3);
                if (stem.length() >= 2) {
                    return stem;
                }
            }
            if (word.endsWith("ed") && word.length() > 3) {
                String stem = word.substring(0, word.length() - 2);
                if (stem.length() >= 2) {
                    return stem;
                }
            }
            if (word.endsWith("ly") && word.length() > 3) {
                return word.substring(0, word.length() - 2);
            }
            if (word.endsWith("er") && word.length() > 3) {
                return word.substring(0, word.length() - 2);
            }
            if (word.endsWith("est") && word.length() > 4) {
                return word.substring(0, word.length() - 3);
            }

            return word;
        }
    }
}
