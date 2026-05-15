package org.epos.api.core.search;

import org.epos.api.beans.DiscoveryItem;
import org.epos.api.facets.FacetsNodeTree;
import org.epos.api.facets.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class HierarchicalSearchReranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(HierarchicalSearchReranker.class);

    public static FacetsNodeTree rerankHierarchicalResponse(FacetsNodeTree facets, String originalQuery) {
        if (facets == null || originalQuery == null || originalQuery.trim().isEmpty()) {
            return facets;
        }

        SearchSynonyms synonyms = new SearchSynonyms();
        List<QueryTerm> queryTerms = SearchQueryProcessor.getAnalyzedTermsWithSynonyms(originalQuery, synonyms);
        if (queryTerms.isEmpty()) {
            return facets;
        }

        rerankNode(facets.getFacets(), queryTerms);

        return facets;
    }

    public static Node rerankNodeTree(Node rootNode, String originalQuery) {
        if (rootNode == null || originalQuery == null || originalQuery.trim().isEmpty()) {
            return rootNode;
        }

        SearchSynonyms synonyms = new SearchSynonyms();
        List<QueryTerm> queryTerms = SearchQueryProcessor.getAnalyzedTermsWithSynonyms(originalQuery, synonyms);
        if (queryTerms.isEmpty()) {
            return rootNode;
        }

        rerankNode(rootNode, queryTerms);

        return rootNode;
    }

    private static void rerankNode(Node node, List<QueryTerm> queryTerms) {
        if (node == null) {
            return;
        }

        if (node.getDistributions() != null && !node.getDistributions().isEmpty()) {
            List<DiscoveryItem> reranked = SearchReranker.rerank(
                    new ArrayList<>(node.getDistributions()), queryTerms);
            node.setDistributions(reranked);
        }

        if (node.getChildren() != null) {
            for (Node child : node.getChildren()) {
                rerankNode(child, queryTerms);
            }
        }
    }

    public static List<DiscoveryItem> collectAllDistributions(Node rootNode) {
        List<DiscoveryItem> all = new ArrayList<>();
        collectDistributions(rootNode, all);
        return all;
    }

    private static void collectDistributions(Node node, List<DiscoveryItem> collector) {
        if (node == null) {
            return;
        }

        if (node.getDistributions() != null) {
            collector.addAll(node.getDistributions());
        }

        if (node.getChildren() != null) {
            for (Node child : node.getChildren()) {
                collectDistributions(child, collector);
            }
        }
    }

    public static Map<String, Object> generateSearchMetrics(List<DiscoveryItem> items, List<QueryTerm> queryTerms) {
        if (items == null || items.isEmpty() || queryTerms == null || queryTerms.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalResults", items.size());
        metrics.put("queryTerms", queryTerms.size());
        metrics.put("termTypes", queryTerms.stream()
                .collect(Collectors.groupingBy(QueryTerm::getType, Collectors.counting())));

        int matchedItems = 0;
        for (DiscoveryItem item : items) {
            String combined = combineFields(item).toLowerCase();
            boolean hasMatch = queryTerms.stream()
                    .anyMatch(qt -> combined.contains(qt.getTerm().toLowerCase()));
            if (hasMatch) {
                matchedItems++;
            }
        }

        metrics.put("matchedItems", matchedItems);
        metrics.put("matchRatio", (double) matchedItems / items.size());

        return metrics;
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
}
