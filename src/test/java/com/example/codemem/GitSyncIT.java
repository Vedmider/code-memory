package com.example.codemem;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.example.codemem.domain.port.in.IndexUseCase;
import com.example.codemem.domain.port.in.RetrieveUseCase;

/**
 * Self-healing git read-gate end-to-end: after the initial index, a plain commit is picked up
 * automatically on the next retrieval (no explicit re-index), and a committed delete removes
 * the symbol — proving O(changed) sync driven purely by git, with the app sharing the repo FS.
 */
@Testcontainers
@SpringBootTest
class GitSyncIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private RetrieveUseCase retrieve;

    @TempDir
    Path repo;

    private Git git;

    private void commit(String rel, String content) throws Exception {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("c").setAuthor("t", "t@e").setCommitter("t", "t@e").setSign(false).call();
    }

    @Test
    void commitsAreSyncedOnReadWithoutAnExplicitReindex() throws Exception {
        git = Git.init().setDirectory(repo.toFile()).call();
        commit("src/Service.java", """
                package x;
                class Service {
                    String greet() { return build(); }
                    String build() { return "hi"; }
                }
                """);

        // initial explicit index establishes the cursor
        assertThat((int) index.indexPath("demo", repo.toString()).get("symbols")).isGreaterThan(0);
        assertThat(retrieve.search("build", "all", 10))
                .anyMatch(h -> h.symbol().symbolPath().endsWith("build"));

        // a new commit — NOT indexed via the API; only committed to git
        commit("src/Tax.java", """
                package x;
                class Tax {
                    int computeTax(int cents) { return cents * 2; }
                }
                """);
        assertThat(retrieve.search("computeTax", "all", 10))
                .as("read-gate auto-synced the new commit")
                .anyMatch(h -> h.symbol().symbolPath().equals("Tax.computeTax")
                        && h.symbol().language().equals("java"));

        // committed delete — symbol must disappear on the next read
        git.rm().addFilepattern("src/Tax.java").call();
        git.commit().setMessage("rm").setAuthor("t", "t@e").setCommitter("t", "t@e").setSign(false).call();
        assertThat(retrieve.search("computeTax", "all", 10))
                .noneMatch(h -> h.symbol().symbolPath().equals("Tax.computeTax"));

        git.close();
    }
}
