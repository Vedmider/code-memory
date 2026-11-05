package com.example.codemem.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.codemem.adapter.out.embed.HashEmbeddingProvider;
import com.example.codemem.adapter.out.parser.CompositeCodeParser;
import com.example.codemem.adapter.out.parser.GoHeuristicParser;
import com.example.codemem.adapter.out.parser.JavaAstParser;
import com.example.codemem.adapter.out.parser.PythonHeuristicParser;
import com.example.codemem.adapter.out.store.PgVectorSymbolStore;
import com.example.codemem.domain.port.out.CodeParser;
import com.example.codemem.domain.port.out.EmbeddingProvider;
import com.example.codemem.domain.port.out.SymbolStore;

/** Wires the outbound adapters. The language switch (Java/Python/Go) is a composite parser. */
@Configuration
public class AdapterConfig {

    @Bean
    CodeParser codeParser(ScopeResolver scope) {
        return new CompositeCodeParser(List.of(
                new JavaAstParser(scope),
                new PythonHeuristicParser(scope),
                new GoHeuristicParser(scope)));
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
