package com.example.codemem.adapter.out.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.codemem.config.ScopeResolver;
import com.example.codemem.domain.model.ParsedFile;

class RemoteChunkingParserTest {

    private static final String JSON = "{\"symbols\":[{\"symbolPath\":\"foo\",\"kind\":\"function\","
            + "\"signature\":\"def foo():\",\"startLine\":1,\"endLine\":2,\"content\":\"def foo(): ...\"}],"
            + "\"edges\":[{\"src\":\"foo\",\"kind\":\"calls\",\"dstName\":\"bar\"}]}";

    private RemoteChunkingParser parser(RestClient client) {
        return new RemoteChunkingParser(client, new LanguageDetector(), new ScopeResolver(),
                Set.of("python", "go"));
    }

    @Test
    void mapsChunkResponseToSymbolsAndEdges() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://chunk");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://chunk/chunk")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(JSON, MediaType.APPLICATION_JSON));

        ParsedFile pf = parser(client).parse("r", "pkg/mod.py", "def foo(): bar()".getBytes());

        assertThat(pf.symbols()).extracting("symbolPath").contains("<module>", "foo");
        assertThat(pf.edges()).anyMatch(e -> e.kind().equals("calls") && "bar".equals(e.dstName())
                && e.srcId().equals("r:pkg/mod.py:foo"));
        server.verify();
    }

    @Test
    void degradesToModuleSymbolWhenServiceFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://chunk");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://chunk/chunk")).andRespond(withServerError());

        ParsedFile pf = parser(client).parse("r", "pkg/mod.py", "def foo(): pass".getBytes());

        assertThat(pf.symbols()).extracting("symbolPath").containsExactly("<module>");
        assertThat(pf.edges()).isEmpty();
    }

    @Test
    void supportsOnlyItsLanguages() {
        RemoteChunkingParser p = parser(RestClient.builder().baseUrl("http://chunk").build());
        assertThat(p.supports("a.py")).isTrue();
        assertThat(p.supports("a.go")).isTrue();
        assertThat(p.supports("A.java")).isFalse();
    }
}
