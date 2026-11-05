package com.example.codemem.adapter.out.parser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.example.codemem.config.ScopeResolver;
import com.example.codemem.domain.model.CodeEdge;
import com.example.codemem.domain.model.CodeSymbol;
import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.port.out.CodeParser;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

/**
 * Precise Java parsing via the JDK's built-in compiler Tree API — no external/native
 * dependencies. Requires a JDK at runtime (the compiler API); on a JRE it degrades to a
 * single module-level symbol. Other languages plug in via their own {@link CodeParser}.
 */
public class JavaAstParser implements CodeParser {

    private final ScopeResolver scope;

    public JavaAstParser(ScopeResolver scope) {
        this.scope = scope;
    }

    @Override
    public boolean supports(String filePath) {
        return filePath.endsWith(".java");
    }

    @Override
    public ParsedFile parse(String repo, String filePath, byte[] source) {
        String code = new String(source, StandardCharsets.UTF_8);
        List<CodeSymbol> symbols = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        List<String> acl = scope.resolve(filePath);
        String moduleId = repo + ":" + filePath + ":<module>";
        int lines = Math.max(1, (int) code.lines().count());
        symbols.add(new CodeSymbol(moduleId, repo, filePath, "java", "module", "<module>",
                baseName(filePath), 1, lines, "", Map.of(), acl));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new ParsedFile(symbols, edges);
        }
        try {
            JavaFileObject fo = new SimpleJavaFileObject(
                    URI.create("string:///" + filePath.replace('\\', '/')), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return code;
                }
            };
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) compiler.getTask(null, null, diags, List.of(), null, List.of(fo));
            Trees trees = Trees.instance(task);
            for (CompilationUnitTree cu : task.parse()) {
                for (ImportTree imp : cu.getImports()) {
                    String q = imp.getQualifiedIdentifier().toString();
                    edges.add(CodeEdge.imports(moduleId, q.substring(q.lastIndexOf('.') + 1)));
                }
                new SymbolScanner(repo, filePath, code, cu, trees.getSourcePositions(),
                        cu.getLineMap(), acl, symbols, edges).scan(cu, new Ctx("", moduleId));
            }
        }
        catch (Exception e) {  // parse/compiler error: keep the module symbol
            return new ParsedFile(symbols, edges);
        }
        return new ParsedFile(symbols, edges);
    }

    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        return p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
    }

    private record Ctx(String prefix, String enclosingId) {
    }

    private static final class SymbolScanner extends TreeScanner<Void, Ctx> {
        private final String repo;
        private final String filePath;
        private final String code;
        private final CompilationUnitTree cu;
        private final SourcePositions pos;
        private final LineMap lineMap;
        private final List<String> acl;
        private final List<CodeSymbol> symbols;
        private final List<CodeEdge> edges;

        SymbolScanner(String repo, String filePath, String code, CompilationUnitTree cu,
                      SourcePositions pos, LineMap lineMap, List<String> acl,
                      List<CodeSymbol> symbols, List<CodeEdge> edges) {
            this.repo = repo;
            this.filePath = filePath;
            this.code = code;
            this.cu = cu;
            this.pos = pos;
            this.lineMap = lineMap;
            this.acl = acl;
            this.symbols = symbols;
            this.edges = edges;
        }

        @Override
        public Void visitClass(ClassTree node, Ctx ctx) {
            String name = node.getSimpleName().toString();
            if (name.isEmpty()) {  // anonymous class: skip symbol, keep scanning
                return super.visitClass(node, ctx);
            }
            String symPath = ctx.prefix() + name;
            String id = addSymbol(node, ctx, symPath, kindOf(node));
            scan(node.getMembers(), new Ctx(symPath + ".", id));
            return null;
        }

        @Override
        public Void visitMethod(MethodTree node, Ctx ctx) {
            String symPath = ctx.prefix() + node.getName();
            String id = addSymbol(node, ctx, symPath, "method");
            scan(node.getBody(), new Ctx(ctx.prefix(), id));
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Ctx ctx) {
            String callee = calleeName(node);
            if (callee != null) {
                edges.add(CodeEdge.call(ctx.enclosingId(), callee));
            }
            return super.visitMethodInvocation(node, ctx);
        }

        private String addSymbol(Tree node, Ctx ctx, String symPath, String kind) {
            String id = repo + ":" + filePath + ":" + symPath;
            long start = pos.getStartPosition(cu, node);
            long end = pos.getEndPosition(cu, node);
            String content = "";
            int startLine = 1;
            int endLine = 1;
            if (start >= 0 && end > start && end <= code.length()) {
                content = code.substring((int) start, (int) end);
                startLine = (int) lineMap.getLineNumber(start);
                endLine = (int) lineMap.getLineNumber(end);
            }
            String signature = content.isEmpty() ? symPath : content.lines().findFirst().orElse("").trim();
            if (signature.length() > 500) {
                signature = signature.substring(0, 500);
            }
            symbols.add(new CodeSymbol(id, repo, filePath, "java", kind, symPath, signature,
                    startLine, endLine, content, Map.of(), acl));
            return id;
        }

        private static String kindOf(ClassTree node) {
            return switch (node.getKind()) {
                case INTERFACE -> "interface";
                case ENUM -> "enum";
                case RECORD -> "record";
                default -> "class";
            };
        }

        private static String calleeName(MethodInvocationTree node) {
            Tree select = node.getMethodSelect();
            if (select instanceof MemberSelectTree ms) {
                return ms.getIdentifier().toString();
            }
            return select != null ? select.toString() : null;
        }
    }
}
