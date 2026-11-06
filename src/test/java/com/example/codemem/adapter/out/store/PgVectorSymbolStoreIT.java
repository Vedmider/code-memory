package com.example.codemem.adapter.out.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.example.codemem.domain.model.CodeEdge;
import com.example.codemem.domain.model.CodeSymbol;
import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.model.RetrievedSymbol;
import com.example.codemem.domain.port.out.SymbolStore;

/**
 * Component-level tests for the pgvector store against a real database: exercises the
 * hybrid/scope SQL and incremental upsert/delete that cannot be unit-tested in isolation.
 */
@Testcontainers
@SpringBootTest
class PgVectorSymbolStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private SymbolStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private static CodeSymbol symbol(String repo, String file, String path, String content,
                                     List<String> agents) {
        return new CodeSymbol(repo + ":" + file + ":" + path, repo, file, "java", "method", path,
                path, 1, 5, content, Map.of(), agents);
    }

    private int symbolCount(String repo, String file) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM code_symbols WHERE repo=? AND file_path=?", Integer.class, repo, file);
    }

    @Test
    void upsertReplacesPriorSymbolsForSameFile() {
        String repo = "r1";
        String file = "A.java";
        store.upsertFile(repo, file, new ParsedFile(List.of(
                symbol(repo, file, "A.one", "one", List.of("all")),
                symbol(repo, file, "A.two", "two", List.of("all"))), List.of()));
        assertThat(symbolCount(repo, file)).isEqualTo(2);

        store.upsertFile(repo, file, new ParsedFile(List.of(
                symbol(repo, file, "A.one", "one changed", List.of("all"))), List.of()));

        assertThat(symbolCount(repo, file)).isEqualTo(1);
    }

    @Test
    void deleteFileRemovesSymbolsAndEdges() {
        String repo = "r2";
        String file = "B.java";
        CodeSymbol caller = symbol(repo, file, "B.caller", "caller", List.of("all"));
        store.upsertFile(repo, file, new ParsedFile(List.of(caller),
                List.of(CodeEdge.call(caller.id(), "callee"))));
        assertThat(symbolCount(repo, file)).isEqualTo(1);

        int deleted = store.deleteFile(repo, file);

        assertThat(deleted).isEqualTo(1);
        assertThat(symbolCount(repo, file)).isZero();
        Integer edges = jdbc.queryForObject(
                "SELECT count(*) FROM code_edges WHERE src_id=?", Integer.class, caller.id());
        assertThat(edges).isZero();
    }

    @Test
    void exactRareTokenIsRetrievableViaFullTextWhereVectorAloneWouldMiss() {
        String repo = "r3";
        String file = "C.java";
        store.upsertFile(repo, file, new ParsedFile(List.of(
                symbol(repo, file, "C.handle", "throw new TimeoutException(\"ERR_TIMEOUT_4711\")",
                        List.of("all")),
                symbol(repo, file, "C.other", "return happy path value", List.of("all"))), List.of()));

        List<RetrievedSymbol> hits = store.search("ERR_TIMEOUT_4711", "all", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).symbol().symbolPath()).isEqualTo("C.handle");
    }

    @Test
    void scopeFilterHidesRestrictedSymbolsFromOtherRoles() {
        String repo = "r4";
        String file = "deploy/Secrets.java";
        store.upsertFile(repo, file, new ParsedFile(List.of(
                symbol(repo, file, "Secrets.apiKey", "rotate apiKey rotation deploy", List.of("devops"))),
                List.of()));

        assertThat(store.search("apiKey rotation deploy", "all", 5))
                .noneMatch(h -> h.symbol().symbolPath().equals("Secrets.apiKey"));
        assertThat(store.search("apiKey rotation deploy", "devops", 5))
                .anyMatch(h -> h.symbol().symbolPath().equals("Secrets.apiKey"));
    }

    @Test
    void searchWithSqlInjectionCharactersIsSafe() {
        String repo = "r5";
        String file = "D.java";
        store.upsertFile(repo, file, new ParsedFile(List.of(
                symbol(repo, file, "D.m", "normal content", List.of("all"))), List.of()));

        assertThatCode(() -> store.search("\"; DROP TABLE code_symbols; --", "all'; --", 5))
                .doesNotThrowAnyException();
        assertThat(symbolCount(repo, file)).isEqualTo(1);  // table intact
    }
}
