package com.example.codemem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.codemem")
public class CodeMemProperties {

    private int embedDim = 256;
    private int rrfK = 60;
    private String textSearchConfig = "english";
    private int topK = 8;

    public int getEmbedDim() {
        return embedDim;
    }

    public void setEmbedDim(int embedDim) {
        this.embedDim = embedDim;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public String getTextSearchConfig() {
        return textSearchConfig;
    }

    public void setTextSearchConfig(String textSearchConfig) {
        this.textSearchConfig = textSearchConfig;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }
}
