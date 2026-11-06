package com.example.codemem.domain.port.out;

/** Installs git hooks that push sync to the app on history changes. Best-effort. */
public interface GitHookInstaller {

    /** Install managed sync hooks into {@code root}'s git dir, calling {@code callbackUrl}/api/sync. */
    void install(String root, String repo, String callbackUrl);
}
