package com.example.codemem.adapter.out.vcs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.codemem.domain.port.out.GitHookInstaller;

/**
 * Writes managed sync hooks into a repo's hooks dir (honouring {@code core.hooksPath}). Only
 * creates/updates files carrying our marker — a user's own hook is never overwritten. Needs
 * write access to the hooks dir (pure-Java-on-host or a writable mount); failures are logged,
 * not fatal, since the read-gate already guarantees freshness.
 */
@Component
public class JGitHookInstaller implements GitHookInstaller {

    private static final Logger log = LoggerFactory.getLogger(JGitHookInstaller.class);
    private static final String MARKER = "# code-memory auto-sync";
    private static final String[] HOOKS = {"post-commit", "post-merge", "post-checkout", "post-rewrite"};

    @Override
    public void install(String root, String repo, String callbackUrl) {
        try (Repository r = new FileRepositoryBuilder().findGitDir(new File(root)).build()) {
            if (r.getDirectory() == null) {
                return;
            }
            File hooksDir = resolveHooksDir(r);
            Files.createDirectories(hooksDir.toPath());
            String body = hookBody(repo, root, callbackUrl);
            for (String hook : HOOKS) {
                writeHook(new File(hooksDir, hook).toPath(), body);
            }
        }
        catch (Exception e) {
            log.warn("hook auto-install skipped for {}: {}", root, e.getMessage());
        }
    }

    private static File resolveHooksDir(Repository r) {
        String hooksPath = r.getConfig().getString(
                ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_HOOKS_PATH);
        if (hooksPath != null && !hooksPath.isBlank()) {
            File f = new File(hooksPath);
            return f.isAbsolute() ? f : new File(r.getWorkTree(), hooksPath);
        }
        return new File(r.getDirectory(), "hooks");
    }

    private static void writeHook(Path path, String body) throws IOException {
        if (Files.exists(path) && !Files.readString(path).contains(MARKER)) {
            log.warn("keeping existing non-managed hook: {}", path);
            return;
        }
        Files.writeString(path, body);
        makeExecutable(path);
    }

    private static void makeExecutable(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        catch (Exception e) {
            path.toFile().setExecutable(true, false);
        }
    }

    private static String hookBody(String repo, String root, String callbackUrl) {
        return "#!/bin/sh\n" + MARKER + "\n"
                + "curl -fsS -m 10 -X POST \"" + callbackUrl + "/api/sync\" "
                + "-H 'content-type: application/json' "
                + "-d '{\"repo\":\"" + json(repo) + "\",\"root\":\"" + json(root) + "\"}' "
                + ">/dev/null 2>&1 || true\n";
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
