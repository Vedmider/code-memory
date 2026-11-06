// Chunking service: parses non-Java source into the shared "chunk contract" consumed by
// code-memory's RemoteChunkingParser. Go is parsed precisely with the standard library
// (go/parser + go/ast) — the language's own parser, no third-party grammars. Other
// languages can be added as more cases without touching the Java app.
package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"log"
	"net/http"
	"os"
	"strings"
)

//go:embed openapi.yaml
var openapiSpec []byte

type chunkRequest struct {
	Repo     string `json:"repo"`
	Path     string `json:"path"`
	Language string `json:"language"`
	Content  string `json:"content"`
}

type chunkSymbol struct {
	SymbolPath string `json:"symbolPath"`
	Kind       string `json:"kind"`
	Signature  string `json:"signature"`
	StartLine  int    `json:"startLine"`
	EndLine    int    `json:"endLine"`
	Content    string `json:"content"`
}

type chunkEdge struct {
	Src     string `json:"src"`
	Kind    string `json:"kind"`
	DstName string `json:"dstName"`
}

type chunkResponse struct {
	Symbols []chunkSymbol `json:"symbols"`
	Edges   []chunkEdge   `json:"edges"`
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8090"
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
	})
	mux.HandleFunc("/openapi.yaml", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/yaml")
		w.WriteHeader(http.StatusOK)
		w.Write(openapiSpec)
	})
	mux.HandleFunc("/chunk", handleChunk)
	log.Printf("chunking-service listening on :%s", port)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatal(err)
	}
}

func handleChunk(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var req chunkRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
		return
	}
	resp := chunkResponse{Symbols: []chunkSymbol{}, Edges: []chunkEdge{}}
	if req.Language == "go" {
		resp = chunkGo(req.Content)
	}
	writeJSON(w, http.StatusOK, resp)
}

// chunkGo extracts top-level functions, methods and types as symbols, plus calls/imports
// edges. On a parse error it returns whatever was recovered; the caller still adds a
// module symbol, so indexing never fails.
func chunkGo(src string) chunkResponse {
	resp := chunkResponse{Symbols: []chunkSymbol{}, Edges: []chunkEdge{}}
	fset := token.NewFileSet()
	file, _ := parser.ParseFile(fset, "src.go", src, parser.SkipObjectResolution)
	if file == nil {
		return resp
	}

	for _, imp := range file.Imports {
		resp.Edges = append(resp.Edges, chunkEdge{
			Src: "<module>", Kind: "imports", DstName: importName(imp),
		})
	}

	for _, decl := range file.Decls {
		switch d := decl.(type) {
		case *ast.FuncDecl:
			symPath := funcSymbolPath(d)
			kind := "function"
			if d.Recv != nil {
				kind = "method"
			}
			resp.Symbols = append(resp.Symbols, symbolOf(src, fset, symPath, kind, d.Pos(), d.End()))
			for _, callee := range calls(d) {
				resp.Edges = append(resp.Edges, chunkEdge{Src: symPath, Kind: "calls", DstName: callee})
			}
		case *ast.GenDecl:
			if d.Tok == token.TYPE {
				for _, spec := range d.Specs {
					ts, ok := spec.(*ast.TypeSpec)
					if !ok {
						continue
					}
					resp.Symbols = append(resp.Symbols,
						symbolOf(src, fset, ts.Name.Name, typeKind(ts), ts.Pos(), ts.End()))
				}
			}
		}
	}
	return resp
}

func symbolOf(src string, fset *token.FileSet, symPath, kind string, pos, end token.Pos) chunkSymbol {
	start := fset.Position(pos)
	finish := fset.Position(end)
	content := ""
	if start.Offset >= 0 && finish.Offset <= len(src) && finish.Offset > start.Offset {
		content = src[start.Offset:finish.Offset]
	}
	signature := symPath
	if content != "" {
		signature = strings.TrimSpace(strings.SplitN(content, "\n", 2)[0])
	}
	if len(signature) > 500 {
		signature = signature[:500]
	}
	return chunkSymbol{
		SymbolPath: symPath, Kind: kind, Signature: signature,
		StartLine: start.Line, EndLine: finish.Line, Content: content,
	}
}

func funcSymbolPath(d *ast.FuncDecl) string {
	if d.Recv == nil || len(d.Recv.List) == 0 {
		return d.Name.Name
	}
	return receiverType(d.Recv.List[0].Type) + "." + d.Name.Name
}

func receiverType(expr ast.Expr) string {
	switch t := expr.(type) {
	case *ast.StarExpr:
		return receiverType(t.X)
	case *ast.Ident:
		return t.Name
	case *ast.IndexExpr: // generic receiver: T[P]
		return receiverType(t.X)
	default:
		return "recv"
	}
}

func typeKind(ts *ast.TypeSpec) string {
	switch ts.Type.(type) {
	case *ast.StructType:
		return "struct"
	case *ast.InterfaceType:
		return "interface"
	default:
		return "type"
	}
}

func calls(d *ast.FuncDecl) []string {
	var out []string
	if d.Body == nil {
		return out
	}
	ast.Inspect(d.Body, func(n ast.Node) bool {
		call, ok := n.(*ast.CallExpr)
		if !ok {
			return true
		}
		switch fn := call.Fun.(type) {
		case *ast.Ident:
			out = append(out, fn.Name)
		case *ast.SelectorExpr:
			out = append(out, fn.Sel.Name)
		}
		return true
	})
	return out
}

func importName(imp *ast.ImportSpec) string {
	if imp.Name != nil && imp.Name.Name != "" && imp.Name.Name != "_" {
		return imp.Name.Name
	}
	path := strings.Trim(imp.Path.Value, "\"")
	if i := strings.LastIndex(path, "/"); i >= 0 {
		return path[i+1:]
	}
	return path
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		log.Printf("encode error: %v", err)
		fmt.Fprint(w, `{"error":"encode"}`)
	}
}
