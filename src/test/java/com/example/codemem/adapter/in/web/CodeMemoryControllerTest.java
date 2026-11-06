package com.example.codemem.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.codemem.config.CodeMemProperties;
import com.example.codemem.domain.port.in.IndexUseCase;
import com.example.codemem.domain.port.in.RetrieveUseCase;
import com.example.codemem.domain.port.in.SyncUseCase;

class CodeMemoryControllerTest {

    private final IndexUseCase index = Mockito.mock(IndexUseCase.class);
    private final RetrieveUseCase retrieve = Mockito.mock(RetrieveUseCase.class);
    private final SyncUseCase sync = Mockito.mock(SyncUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CodeMemProperties props = new CodeMemProperties();  // top-k default = 8
        mvc = MockMvcBuilders.standaloneSetup(new CodeMemoryController(index, retrieve, sync, props))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void searchWithValidQueryReturns200() throws Exception {
        when(retrieve.search(any(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"retry logic\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray());
    }

    @Test
    void searchWithBlankQueryReturns400() throws Exception {
        mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchWithMissingQueryReturns400() throws Exception {
        mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchDefaultsAgentToAllAndTopKToConfiguredValue() throws Exception {
        when(retrieve.search(any(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"x\"}"))
                .andExpect(status().isOk());
        verify(retrieve).search(isNull(), eq("x"), eq("all"), eq(8));
    }

    @Test
    void indexWithBlankRepoReturns400() throws Exception {
        mvc.perform(post("/api/index").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\":\"\",\"root\":\"/tmp/x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void indexWithMissingRootReturns400() throws Exception {
        mvc.perform(post("/api/index").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repo\":\"demo\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingInternals() throws Exception {
        when(retrieve.search(any(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("relation code_symbols does not exist at /opt/app/Store.java:42"));
        mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"x\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal error"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }
}
