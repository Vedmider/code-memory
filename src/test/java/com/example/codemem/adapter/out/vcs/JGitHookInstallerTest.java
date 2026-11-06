package com.example.codemem.adapter.out.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JGitHookInstallerTest {

    private final JGitHookInstaller installer = new JGitHookInstaller();

    @Test
    void installsExecutableManagedHooksCallingSync(@TempDir Path root) throws Exception {
        Git.init().setDirectory(root.toFile()).call().close();

        installer.install(root.toString(), "demo", "http://localhost:8080");

        Path postCommit = root.resolve(".git/hooks/post-commit");
        assertThat(postCommit).exists();
        assertThat(Files.isExecutable(postCommit)).isTrue();
        String body = Files.readString(postCommit);
        assertThat(body).contains("code-memory auto-sync")
                .contains("http://localhost:8080/api/sync")
                .contains("\"repo\":\"demo\"");
        for (String hook : new String[] {"post-merge", "post-checkout", "post-rewrite"}) {
            assertThat(root.resolve(".git/hooks/" + hook)).exists();
        }
    }

    @Test
    void doesNotOverwriteAUsersExistingHook(@TempDir Path root) throws Exception {
        Git.init().setDirectory(root.toFile()).call().close();
        Path postCommit = root.resolve(".git/hooks/post-commit");
        Files.createDirectories(postCommit.getParent());
        Files.writeString(postCommit, "#!/bin/sh\necho my own hook\n");

        installer.install(root.toString(), "demo", "http://localhost:8080");

        assertThat(Files.readString(postCommit)).isEqualTo("#!/bin/sh\necho my own hook\n");
    }

    @Test
    void reinstallIsIdempotentOverOwnHooks(@TempDir Path root) throws Exception {
        Git.init().setDirectory(root.toFile()).call().close();
        installer.install(root.toString(), "demo", "http://localhost:8080");
        installer.install(root.toString(), "demo", "http://localhost:9090");  // changed callback

        assertThat(Files.readString(root.resolve(".git/hooks/post-commit")))
                .contains("http://localhost:9090/api/sync")
                .doesNotContain("8080");
    }
}
