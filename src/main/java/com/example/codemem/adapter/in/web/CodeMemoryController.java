package com.example.codemem.adapter.in.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.codemem.config.CodeMemProperties;
import com.example.codemem.domain.model.AgentContext;
import com.example.codemem.domain.model.RetrievedSymbol;
import com.example.codemem.domain.port.in.IndexUseCase;
import com.example.codemem.domain.port.in.RetrieveUseCase;
import com.example.codemem.domain.port.in.SyncUseCase;

/** HTTP adapter: index code, incrementally re-index, and retrieve scoped task context. */
@RestController
@RequestMapping("/api")
public class CodeMemoryController {

    private final IndexUseCase index;
    private final RetrieveUseCase retrieve;
    private final SyncUseCase sync;
    private final CodeMemProperties props;

    public CodeMemoryController(IndexUseCase index, RetrieveUseCase retrieve, SyncUseCase sync,
                                CodeMemProperties props) {
        this.index = index;
        this.retrieve = retrieve;
        this.sync = sync;
        this.props = props;
    }

    @PostMapping("/index")
    public Map<String, Object> index(@Valid @RequestBody IndexRequest req) {
        return index.indexPath(req.repo(), req.root(), Boolean.TRUE.equals(req.force()));
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(@Valid @RequestBody SyncRequest req) {
        return sync.sync(req.repo(), req.root(), req.paths());
    }

    @PostMapping("/index/file")
    public Map<String, Object> indexFile(@Valid @RequestBody FileRequest req) {
        return Map.of("symbols", index.indexFile(req.repo(), req.root(), req.path()));
    }

    @DeleteMapping("/index/file")
    public Map<String, Object> removeFile(@Valid @RequestBody FileRequest req) {
        return Map.of("deleted", index.removeFile(req.repo(), req.root(), req.path()));
    }

    @PostMapping("/search")
    public Map<String, Object> search(@Valid @RequestBody SearchRequest req) {
        List<RetrievedSymbol> results = retrieve.search(req.repo(), req.query(), req.agentOrAll(), req.topKOr(props.getTopK()));
        return Map.of("results", results.stream().map(CodeMemoryController::toDto).toList());
    }

    @PostMapping("/context")
    public Map<String, Object> context(@Valid @RequestBody ContextRequest req) {
        AgentContext ctx = retrieve.context(req.repo(), req.task(), req.agentOrAll(), req.topKOr(props.getTopK()));
        return Map.of(
                "results", ctx.results().stream().map(CodeMemoryController::toDto).toList(),
                "callers", ctx.callers(),
                "tests", ctx.tests());
    }

    private static Map<String, Object> toDto(RetrievedSymbol r) {
        var s = r.symbol();
        return Map.of("id", s.id(), "file", s.filePath(), "symbol", s.symbolPath(),
                "kind", s.kind(), "signature", s.signature(),
                "lines", List.of(s.startLine(), s.endLine()),
                "score", Math.round(r.score() * 1e6) / 1e6);
    }

    public record IndexRequest(@NotBlank String repo, @NotBlank String root, Boolean force) {
    }

    public record SyncRequest(@NotBlank String repo, @NotBlank String root, List<String> paths) {
    }

    public record FileRequest(@NotBlank String repo, @NotBlank String root, @NotBlank String path) {
    }

    public record SearchRequest(@NotBlank String query, String repo, String agent, Integer topK) {
        String agentOrAll() {
            return agent == null || agent.isBlank() ? "all" : agent;
        }

        int topKOr(int fallback) {
            return topK == null || topK <= 0 ? fallback : topK;
        }
    }

    public record ContextRequest(@NotBlank String task, String repo, String agent, Integer topK) {
        String agentOrAll() {
            return agent == null || agent.isBlank() ? "all" : agent;
        }

        int topKOr(int fallback) {
            return topK == null || topK <= 0 ? fallback : topK;
        }
    }
}
