package com.example.codemem.adapter.out.store;

import java.util.List;

/**
 * Plain PageRank over a symbol graph (indices), with dangling-node redistribution, normalised
 * so the top symbol = 1.0. Pure and unit-testable; the store supplies ids→indices and edges.
 */
final class PageRank {

    private PageRank() {
    }

    /** @param edges directed {src,dst} index pairs. @return rank per index, max-normalised to 1. */
    static double[] compute(int n, List<int[]> edges, double damping, int iterations) {
        double[] uniform = new double[n];
        java.util.Arrays.fill(uniform, n == 0 ? 0.0 : 1.0 / n);
        return compute(n, edges, damping, iterations, uniform);
    }

    /**
     * Personalized PageRank: the random-walk teleports to {@code personalization} (a seed
     * distribution) instead of uniformly, biasing centrality toward the query's working set.
     */
    static double[] compute(int n, List<int[]> edges, double damping, int iterations,
                            double[] personalization) {
        double[] rank = new double[n];
        if (n == 0) {
            return rank;
        }
        double[] p = normalized(personalization, n);
        System.arraycopy(p, 0, rank, 0, n);
        int[] outDegree = new int[n];
        for (int[] e : edges) {
            outDegree[e[0]]++;
        }
        for (int it = 0; it < iterations; it++) {
            double[] next = new double[n];
            double dangling = 0.0;
            for (int i = 0; i < n; i++) {
                if (outDegree[i] == 0) {
                    dangling += rank[i];
                }
            }
            for (int i = 0; i < n; i++) {
                next[i] = (1.0 - damping) * p[i] + damping * dangling * p[i];
            }
            for (int[] e : edges) {
                next[e[1]] += damping * rank[e[0]] / outDegree[e[0]];
            }
            rank = next;
        }
        double max = 0.0;
        for (double r : rank) {
            max = Math.max(max, r);
        }
        if (max > 0) {
            for (int i = 0; i < n; i++) {
                rank[i] /= max;
            }
        }
        return rank;
    }

    private static double[] normalized(double[] v, int n) {
        double sum = 0.0;
        for (double x : v) {
            sum += x;
        }
        double[] out = new double[n];
        if (sum <= 0) {
            java.util.Arrays.fill(out, 1.0 / n);
        }
        else {
            for (int i = 0; i < n; i++) {
                out[i] = v[i] / sum;
            }
        }
        return out;
    }
}
