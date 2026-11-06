package com.example.codemem.adapter.out.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PageRankTest {

    @Test
    void hubNodePointedToByManyRanksHighest() {
        // 0,1,3 all call 2  -> 2 is the hub
        double[] rank = PageRank.compute(4, List.of(
                new int[] {0, 2}, new int[] {1, 2}, new int[] {3, 2}), 0.85, 30);

        assertThat(rank[2]).isEqualTo(1.0);                 // max-normalised
        assertThat(rank[2]).isGreaterThan(rank[0])
                .matches(x -> x > rank[1] && x > rank[3]);
    }

    @Test
    void emptyGraphYieldsEmptyRanks() {
        assertThat(PageRank.compute(0, List.of(), 0.85, 30)).isEmpty();
    }

    @Test
    void noEdgesGivesUniformRanks() {
        double[] rank = PageRank.compute(3, List.of(), 0.85, 30);
        assertThat(rank).containsExactly(1.0, 1.0, 1.0);    // equal -> all normalise to 1
    }

    @Test
    void personalizationBiasesTowardTheSeedAndItsNeighbors() {
        // 0 -> 1 ; node 2 isolated. Teleport all mass to node 0.
        double[] ppr = PageRank.compute(3, List.of(new int[] {0, 1}), 0.85, 30,
                new double[] {1.0, 0.0, 0.0});

        assertThat(ppr[0]).isEqualTo(1.0);              // seed is the peak
        assertThat(ppr[1]).isGreaterThan(ppr[2]);       // seed's neighbor beats the disconnected node
    }
}
