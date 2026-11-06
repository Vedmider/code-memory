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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.example.codemem.domain.model.RetrievedSymbol;
import com.example.codemem.domain.port.in.IndexUseCase;
import com.example.codemem.domain.port.in.RetrieveUseCase;

/**
 * End-to-end multi-language indexing: the app runs in {@code multi} mode and parses a real
 * Go file by calling the chunking-service, whose image is built from source here. Proves
 * the chunk contract, auto-detect routing, and that Go symbols/edges land in pgvector and
 * are retrievable and graph-linked — the same guarantees the Java path has.
 */
@Testcontainers
@SpringBootTest
class GoChunkingIT {

    private static final String REGISTRY = System.getProperty("chunking.it.registry",
            "armdocker.rnd.ericsson.se/dockerhub-ericsson-remote/library");

    private static final ImageFromDockerfile CHUNK_IMAGE = new ImageFromDockerfile()
            .withFileFromPath(".", Path.of("chunking-service"))
            .withBuildArg("BUILD_IMAGE", REGISTRY + "/golang:1.23-alpine")
            .withBuildArg("RUNTIME_IMAGE", REGISTRY + "/alpine:3.20");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static GenericContainer<?> chunking = new GenericContainer<>(CHUNK_IMAGE)
            .withExposedPorts(8090)
            .waitingFor(Wait.forHttp("/health").forPort(8090).forStatusCode(200));

    @DynamicPropertySource
    static void parsingProperties(DynamicPropertyRegistry registry) {
        registry.add("app.codemem.parsing.mode", () -> "multi");
        registry.add("app.codemem.parsing.chunking.base-url",
                () -> "http://" + chunking.getHost() + ":" + chunking.getMappedPort(8090));
    }

    @Autowired
    private IndexUseCase index;

    @Autowired
    private RetrieveUseCase retrieve;

    @TempDir
    Path tmp;

    @Test
    void indexesRealGoFileThroughChunkingServiceAndLinksTheCallGraph() throws IOException {
        Files.writeString(tmp.resolve("server.go"), """
                package pkg

                import "net/http"

                type Server struct{ addr string }

                func New(addr string) *Server { return build(addr) }

                func build(addr string) *Server { return &Server{addr: addr} }

                func (s *Server) Start() error { return http.ListenAndServe(s.addr, nil) }
                """);

        Map<String, Object> res = index.indexPath("go-demo", tmp.toString());
        assertThat((int) res.get("files")).isEqualTo(1);
        assertThat((int) res.get("symbols")).isGreaterThan(1);  // module + Go symbols

        List<RetrievedSymbol> startHits = retrieve.search(null, "Server Start", "all", 10);
        assertThat(startHits).anyMatch(h -> h.symbol().symbolPath().equals("Server.Start")
                && h.symbol().language().equals("go"));

        // dependency graph works for Go too: New() calls build()
        List<RetrievedSymbol> buildHits = retrieve.search(null, "build", "all", 10);
        String buildId = buildHits.stream()
                .filter(h -> h.symbol().symbolPath().equals("build")).findFirst().orElseThrow()
                .symbol().id();
        assertThat(retrieve.context(null, "build", "all", 10).callers().getOrDefault(buildId, List.of()))
                .anyMatch(caller -> caller.endsWith("New"));
    }
}
