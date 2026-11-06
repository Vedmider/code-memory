package com.example.codemem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Selects how code is parsed into symbols.
 * <ul>
 *   <li>{@code java} (default) — only Java, parsed in-process by the JDK compiler API.</li>
 *   <li>{@code single} — one chosen {@code language} (java in-process; python/go via the
 *       chunking service).</li>
 *   <li>{@code multi} — auto-detect by extension and dispatch (java in-process, others remote).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.codemem.parsing")
public class ParsingProperties {

    public enum Mode { java, single, multi }

    private Mode mode = Mode.java;
    private String language = "";          // required when mode=single
    private final Chunking chunking = new Chunking();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Chunking getChunking() {
        return chunking;
    }

    /** Connection settings for the external chunking service (used for non-Java languages). */
    public static class Chunking {
        private String baseUrl = "http://localhost:8090";
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 10000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
