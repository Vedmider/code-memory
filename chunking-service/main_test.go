package main

import (
	"encoding/json"
	"strings"
	"testing"
)

const goSample = `package pkg

import "net/http"

type Server struct{ addr string }

func New(addr string) *Server { return build(addr) }

func build(addr string) *Server { return &Server{addr: addr} }

func (s *Server) Start() error { return http.ListenAndServe(s.addr, nil) }
`

func TestChunkGoExtractsSymbolsAndEdges(t *testing.T) {
	resp := chunkGo(goSample)

	wantSyms := map[string]string{
		"Server":       "struct",
		"New":          "function",
		"build":        "function",
		"Server.Start": "method",
	}
	got := map[string]string{}
	for _, s := range resp.Symbols {
		got[s.SymbolPath] = s.Kind
		if s.StartLine < 1 || s.EndLine < s.StartLine {
			t.Errorf("bad line range for %s: %d-%d", s.SymbolPath, s.StartLine, s.EndLine)
		}
	}
	for path, kind := range wantSyms {
		if got[path] != kind {
			t.Errorf("symbol %q: want kind %q, got %q", path, kind, got[path])
		}
	}

	assertEdge(t, resp.Edges, "<module>", "imports", "http")
	assertEdge(t, resp.Edges, "New", "calls", "build")
	assertEdge(t, resp.Edges, "Server.Start", "calls", "ListenAndServe")
}

func TestChunkGoNeverEmitsModuleSymbol(t *testing.T) {
	for _, s := range chunkGo(goSample).Symbols {
		if s.SymbolPath == "<module>" {
			t.Fatal("service must not emit <module>; the consumer adds it")
		}
	}
}

func TestChunkGoMalformedSourceDoesNotPanicOrError(t *testing.T) {
	resp := chunkGo("package pkg\nfunc broken( {")
	if resp.Symbols == nil || resp.Edges == nil {
		t.Fatal("arrays must be non-nil even on parse error")
	}
}

func TestEmbeddedSpecMatchesServedContract(t *testing.T) {
	spec := string(openapiSpec)
	for _, must := range []string{"/chunk", "/health", "ChunkRequest", "ChunkResponse",
		"ChunkSymbol", "ChunkEdge", "symbolPath", "dstName"} {
		if !strings.Contains(spec, must) {
			t.Errorf("embedded openapi.yaml missing %q", must)
		}
	}
}

func TestResponseJSONFieldNamesMatchContract(t *testing.T) {
	b, _ := json.Marshal(chunkResponse{
		Symbols: []chunkSymbol{{SymbolPath: "A", Kind: "function", Signature: "sig",
			StartLine: 1, EndLine: 2, Content: "c"}},
		Edges: []chunkEdge{{Src: "A", Kind: "calls", DstName: "B"}},
	})
	j := string(b)
	for _, key := range []string{`"symbolPath"`, `"startLine"`, `"endLine"`, `"dstName"`} {
		if !strings.Contains(j, key) {
			t.Errorf("response JSON missing contract key %s in %s", key, j)
		}
	}
}

func assertEdge(t *testing.T, edges []chunkEdge, src, kind, dst string) {
	t.Helper()
	for _, e := range edges {
		if e.Src == src && e.Kind == kind && e.DstName == dst {
			return
		}
	}
	t.Errorf("missing edge {src:%q kind:%q dstName:%q}", src, kind, dst)
}
