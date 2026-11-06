package com.example.codemem.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import com.example.codemem.adapter.out.embed.HashEmbeddingProvider;
import com.example.codemem.adapter.out.parser.JavaAstParser;
import com.example.codemem.adapter.out.parser.LanguageDetector;
import com.example.codemem.adapter.out.parser.ParserRouter;
import com.example.codemem.adapter.out.parser.RemoteChunkingParser;
import com.example.codemem.adapter.out.store.PgVectorSymbolStore;
import com.example.codemem.domain.port.out.CodeParser;
import com.example.codemem.domain.port.out.EmbeddingProvider;
import com.example.codemem.domain.port.out.SymbolStore;

/**
 * Wires the outbound adapters. The parser is a {@link ParserRouter} assembled from the
 * configured mode: Java is always parsed in-process; Python/Go (when enabled) are parsed
 * by the external chunking service. Default mode is pure Java.
 */
@Configuration
public class AdapterConfig {

    private static final Set<String> REMOTE_LANGUAGES = Set.of(LanguageDetector.PYTHON, LanguageDetector.GO);

    @Bean
    CodeParser codeParser(ParsingProperties parsing, LanguageDetector detector, ScopeResolver scope) {
        JavaAstParser java = new JavaAstParser(scope);
        RemoteChunkingParser remote =
                new RemoteChunkingParser(chunkingClient(parsing), detector, scope, REMOTE_LANGUAGES);

        Map<String, CodeParser> byLanguage = new HashMap<>();
        switch (parsing.getMode()) {
            case java -> byLanguage.put(LanguageDetector.JAVA, java);
            case single -> {
                String lang = parsing.getLanguage();
                if (LanguageDetector.JAVA.equals(lang)) {
                    byLanguage.put(LanguageDetector.JAVA, java);
                }
                else if (REMOTE_LANGUAGES.contains(lang)) {
                    byLanguage.put(lang, remote);
                }
                else {
                    throw new IllegalStateException("Unsupported single language: '" + lang
                            + "'. Use one of: java, python, go.");
                }
            }
            case multi -> {
                byLanguage.put(LanguageDetector.JAVA, java);
                REMOTE_LANGUAGES.forEach(l -> byLanguage.put(l, remote));
            }
        }
        return new ParserRouter(detector, byLanguage);
    }

    private static RestClient chunkingClient(ParsingProperties parsing) {
        var c = parsing.getChunking();
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(c.getConnectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(c.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(c.getBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Bean
    EmbeddingProvider embeddingProvider(CodeMemProperties props) {
        return new HashEmbeddingProvider(props.getEmbedDim());
    }

    @Bean
    SymbolStore symbolStore(JdbcTemplate jdbc, EmbeddingProvider embeddings, CodeMemProperties props) {
        return new PgVectorSymbolStore(jdbc, embeddings, props.getRrfK(), props.getTextSearchConfig());
    }
}
