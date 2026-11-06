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

    public boolean isOnRead() {
        return onRead;
    }

    public void setOnRead(boolean onRead) {
        this.onRead = onRead;
    }
}
