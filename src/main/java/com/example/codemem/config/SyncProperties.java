package com.example.codemem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Self-healing sync. When {@code onRead} is true, {@code /api/search} and {@code /api/context}
 * run a cheap git gate over tracked repos first (local-oriented: the app shares the repo's
 * filesystem). Set false for an explicit-only workflow driven by {@code /api/sync} + hooks.
 */
@ConfigurationProperties(prefix = "app.codemem.sync")
public class SyncProperties {

    private boolean onRead = true;
    private boolean installHooks = true;
    private String callbackUrl = "http://localhost:8080";

    public boolean isOnRead() {
        return onRead;
    }

    public void setOnRead(boolean onRead) {
        this.onRead = onRead;
    }

    public boolean isInstallHooks() {
        return installHooks;
    }

    public void setInstallHooks(boolean installHooks) {
        this.installHooks = installHooks;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }
}
