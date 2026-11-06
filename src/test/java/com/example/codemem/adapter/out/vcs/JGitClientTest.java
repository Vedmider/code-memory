package com.example.codemem.adapter.out.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.codemem.domain.model.FileChange;

class JGitClientTest {

    private final JGitClient git = new JGitClient();

    private static String commit(Git g, Path root, String rel, String content) throws Exception {
        Files.writeString(root.resolve(rel), content);
        g.add().addFilepattern(".").call();
        return g.commit().setMessage("c").setAuthor("t", "t@e").setCommitter("t", "t@e")
                .setSign(false).call().getName();
    }

    @Test
    void detectsRepoAndHead(@TempDir Path root) throws Exception {
        assertThat(git.isRepo(root.toString())).isFalse();
        try (Git g = Git.init().setDirectory(root.toFile()).call()) {
            commit(g, root, "A.java", "class A {}");
            assertThat(git.isRepo(root.toString())).isTrue();
            assertThat(git.head(root.toString())).isPresent();
        }
    }

    @Test
    void committedDeltaSinceCursorListsOnlyNewFiles(@TempDir Path root) throws Exception {
        try (Git g = Git.init().setDirectory(root.toFile()).call()) {
            String first = commit(g, root, "A.java", "class A {}");
            commit(g, root, "B.java", "class B {}");

            assertThat(git.changesSince(root.toString(), first))
                    .containsExactly(FileChange.upsert("B.java"));
        }
    }

    @Test
    void nullCursorTreatsAllTrackedFilesAsUpserts(@TempDir Path root) throws Exception {
        try (Git g = Git.init().setDirectory(root.toFile()).call()) {
            commit(g, root, "A.java", "class A {}");
            assertThat(git.changesSince(root.toString(), null))
                    .containsExactly(FileChange.upsert("A.java"));
        }
    }

    @Test
    void uncommittedEditsAndDeletesShowInWorkingTree(@TempDir Path root) throws Exception {
        try (Git g = Git.init().setDirectory(root.toFile()).call()) {
            String head = commit(g, root, "A.java", "class A {}");
            commit(g, root, "B.java", "class B {}");
            Files.writeString(root.resolve("A.java"), "class A { int x; }");  // modify, uncommitted
            Files.delete(root.resolve("B.java"));                             // delete, uncommitted

            assertThat(git.changesSince(root.toString(), head))
                    .contains(FileChange.upsert("A.java"), FileChange.delete("B.java"));
        }
    }
}
