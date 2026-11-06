package com.example.codemem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.example.codemem.domain.model.RetrievedSymbol;
import com.example.codemem.domain.port.in.IndexUseCase;
import com.example.codemem.domain.port.in.RetrieveUseCase;
import com.example.codemem.domain.port.out.SymbolStore;

@Testcontainers
@SpringBootTest
class CodeMemoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private RetrieveUseCase retrieve;

    @Autowired
    private SymbolStore symbolStore;

    @TempDir
    Path tmp;

    @Test
    void indexesSearchesScopesAndLinks() throws IOException {
        Files.writeString(tmp.resolve("Service.java"), """
                package x;
                class Service {
                    String greet() { return build(); }
                    String build() { return "hi"; }
                }
                """);
        Files.createDirectories(tmp.resolve("test"));
        Files.writeString(tmp.resolve("test/ServiceTest.java"), """
                package x;
                class ServiceTest {
                    void checksGreeting() { new Service().greet(); }
                }
                """);

        Map<String, Object> res = index.indexPath("demo", tmp.toString());
        assertThat((int) res.get("files")).isEqualTo(2);
        assertThat((int) res.get("symbols")).isGreaterThan(0);

        // hybrid retrieval finds the symbol
        List<RetrievedSymbol> buildHits = retrieve.search(null, "build", "all", 10);
        assertThat(buildHits).anyMatch(h -> h.symbol().symbolPath().endsWith("build"));

        // RBAC scope: test-only symbols are hidden from a generic caller, visible to 'qa'
        assertThat(retrieve.search(null, "ServiceTest checksGreeting", "all", 10))
                .noneMatch(h -> h.symbol().symbolPath().contains("ServiceTest"));
        assertThat(retrieve.search(null, "ServiceTest checksGreeting", "qa", 10))
                .anyMatch(h -> h.symbol().symbolPath().contains("ServiceTest"));

        // dependency graph: build() is called by greet()
        String buildId = buildHits.stream()
                .filter(h -> h.symbol().symbolPath().endsWith("build")).findFirst().orElseThrow()
                .symbol().id();
        assertThat(retrieve.context(null, "build", "all", 10).callers().getOrDefault(buildId, List.of()))
                .anyMatch(caller -> caller.endsWith("greet"));
    }

    @Test
    void forceReindexRepopulatesWhenSymbolsAndHashesDiverge(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Alpha.java"), "package y; class Alpha { int one() { return 1; } }");
        index.indexPath("fx", dir.toString());
        assertThat(retrieve.search("fx", "one", "all", 10)).isNotEmpty();

        // Simulate divergence: symbols wiped, but file hashes remain (the real bug).
        symbolStore.clearRepo("fx");
        assertThat(retrieve.search("fx", "one", "all", 10)).isEmpty();

        // Plain reindex is a no-op (hash says unchanged) -> still empty.
        index.indexPath("fx", dir.toString());
        assertThat(retrieve.search("fx", "one", "all", 10)).isEmpty();

        // Force reindex clears hashes first -> repopulates.
        Map<String, Object> forced = index.indexPath("fx", dir.toString(), true);
        assertThat((int) forced.get("files")).isEqualTo(1);
        assertThat(retrieve.search("fx", "one", "all", 10)).isNotEmpty();
    }

    @Test
    void sameNamedMethodInAnotherFileIsNotCrossLinked(@TempDir Path dir) throws IOException {
        // Two classes each define save(); Alpha.run() calls save() — only Alpha.save is its caller.
        Files.writeString(dir.resolve("Alpha.java"), """
                package z;
                class Alpha {
                    void run() { save(); }
                    void save() { }
                }
                """);
        Files.writeString(dir.resolve("Beta.java"), """
                package z;
                class Beta {
                    void save() { }
                }
                """);
        index.indexPath("g5", dir.toString(), true);

        String betaSave = "g5:Beta.java:Beta.save";
        String alphaSave = "g5:Alpha.java:Alpha.save";
        // Beta.save has no in-repo caller — the same-file resolution must NOT link Alpha.run to it.
        assertThat(symbolStore.callersOf(betaSave)).noneMatch(p -> p.endsWith("run"));
        // Alpha.run correctly resolves to its own-file Alpha.save.
        assertThat(symbolStore.callersOf(alphaSave)).anyMatch(p -> p.endsWith("run"));
    }
}
