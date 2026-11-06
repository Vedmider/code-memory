package com.example.codemem.adapter.out.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.example.codemem.adapter.out.parser.RemoteChunkingParser.ChunkEdge;
import com.example.codemem.adapter.out.parser.RemoteChunkingParser.ChunkRequest;
import com.example.codemem.adapter.out.parser.RemoteChunkingParser.ChunkResponse;
import com.example.codemem.adapter.out.parser.RemoteChunkingParser.ChunkSymbol;

/**
 * The consumer's DTOs must match the shared OpenAPI contract the producers serve. This
 * reads the authoritative {@code chunking-service/openapi.yaml} and fails on any drift
 * (missing or extra field) between a schema and the matching record — catching contract
 * breaks at build time rather than as silent nulls at runtime.
 */
class ChunkContractTest {

    private static final Path SPEC = Path.of("chunking-service/openapi.yaml");

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemas() throws Exception {
        try (InputStream in = Files.newInputStream(SPEC)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> components = (Map<String, Object>) root.get("components");
            return (Map<String, Object>) components.get("schemas");
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> propertiesOf(String schemaName) throws Exception {
        Map<String, Object> schema = (Map<String, Object>) schemas().get(schemaName);
        assertThat(schema).as("schema %s present", schemaName).isNotNull();
        return ((Map<String, Object>) schema.get("properties")).keySet();
    }

    private static Set<String> componentsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
    }

    @Test
    void chunkRequestMatchesSchema() throws Exception {
        assertThat(componentsOf(ChunkRequest.class)).isEqualTo(propertiesOf("ChunkRequest"));
    }

    @Test
    void chunkResponseMatchesSchema() throws Exception {
        assertThat(componentsOf(ChunkResponse.class)).isEqualTo(propertiesOf("ChunkResponse"));
    }

    @Test
    void chunkSymbolMatchesSchema() throws Exception {
        assertThat(componentsOf(ChunkSymbol.class)).isEqualTo(propertiesOf("ChunkSymbol"));
    }

    @Test
    void chunkEdgeMatchesSchema() throws Exception {
        assertThat(componentsOf(ChunkEdge.class)).isEqualTo(propertiesOf("ChunkEdge"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specDeclaresTheChunkAndHealthPaths() throws Exception {
        try (InputStream in = Files.newInputStream(SPEC)) {
            Map<String, Object> root = new Yaml().load(in);
            Set<String> paths = ((Map<String, Object>) root.get("paths")).keySet();
            assertThat(paths).contains("/chunk", "/health");
        }
    }
}
