package com.cnh.goodfish.question;

import java.util.HashMap;
import java.util.Map;

/**
 * Similarity helpers for ELSER sparse vectors (token -> weight maps).
 *
 * <p>Raw ELSER relevance scores are dominated by words every question on a board shares, such as the
 * talk's topic ("eBPF"), so two different questions about the same topic look like duplicates. We
 * therefore compare questions with a cosine similarity in which the board topic's tokens are
 * down-weighted.
 */
final class SparseVectors {

    private SparseVectors() {
    }

    /**
     * Builds per-token weights that shrink the influence of the board topic's own tokens.
     *
     * @param topic      ELSER expansion of the board topic
     * @param minWeight  only topic tokens with at least this ELSER weight are down-weighted
     * @param topicFloor weight applied to those tokens (1.0 = no change)
     */
    static Map<String, Double> topicWeights(Map<String, Float> topic, double minWeight, double topicFloor) {
        Map<String, Double> weights = new HashMap<>();
        topic.forEach((token, weight) -> {
            if (weight >= minWeight) {
                weights.put(token, topicFloor);
            }
        });
        return weights;
    }

    /** Weighted cosine similarity in [0, 1]; tokens missing from {@code weights} count fully. */
    static double cosine(Map<String, Float> a, Map<String, Float> b, Map<String, Double> weights) {
        double dot = 0;
        for (Map.Entry<String, Float> entry : a.entrySet()) {
            Float other = b.get(entry.getKey());
            if (other != null) {
                double w = weights.getOrDefault(entry.getKey(), 1.0);
                dot += entry.getValue() * other * w * w;
            }
        }
        double norm = norm(a, weights) * norm(b, weights);
        return norm == 0 ? 0 : dot / norm;
    }

    private static double norm(Map<String, Float> vector, Map<String, Double> weights) {
        double sum = 0;
        for (Map.Entry<String, Float> entry : vector.entrySet()) {
            double v = entry.getValue() * weights.getOrDefault(entry.getKey(), 1.0);
            sum += v * v;
        }
        return Math.sqrt(sum);
    }
}
