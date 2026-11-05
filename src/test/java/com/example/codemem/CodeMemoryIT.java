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
        List<RetrievedSymbol> buildHits = retrieve.search("build", "all", 10);
        assertThat(buildHits).anyMatch(h -> h.symbol().symbolPath().endsWith("build"));

        // RBAC scope: test-only symbols are hidden from a generic caller, visible to 'qa'
        assertThat(retrieve.search("ServiceTest checksGreeting", "all", 10))
                .noneMatch(h -> h.symbol().symbolPath().contains("ServiceTest"));
        assertThat(retrieve.search("ServiceTest checksGreeting", "qa", 10))
                .anyMatch(h -> h.symbol().symbolPath().contains("ServiceTest"));

        // dependency graph: build() is called by greet()
        String buildId = buildHits.stream()
                .filter(h -> h.symbol().symbolPath().endsWith("build")).findFirst().orElseThrow()
                .symbol().id();
        assertThat(retrieve.context("build", "all", 10).callers().getOrDefault(buildId, List.of()))
                .anyMatch(caller -> caller.endsWith("greet"));
    }
}
