package com.example.codemem.adapter.out.store;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;

import com.example.codemem.domain.model.CodeEdge;
import com.example.codemem.domain.model.CodeSymbol;
import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.model.RetrievedSymbol;
import com.example.codemem.domain.port.out.EmbeddingProvider;
import com.example.codemem.domain.port.out.SymbolStore;

/**
 * pgvector-backed store: AST symbols + dependency graph, hybrid (vector + full-text + RRF)
 * retrieval filtered by agent scope, and incremental upsert/delete by file. Schema is
 * managed by Flyway (V1 extension, V2 tables).
 */
public class PgVectorSymbolStore implements SymbolStore {

    private static final Pattern SAFE_IDENT = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final JdbcTemplate jdbc;
    private final EmbeddingProvider embeddings;
    private final int rrfK;
    private final String ts;

    public PgVectorSymbolStore(JdbcTemplate jdbc, EmbeddingProvider embeddings, int rrfK,
                               String textSearchConfig) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.rrfK = rrfK;
        this.ts = SAFE_IDENT.matcher(textSearchConfig).matches() ? textSearchConfig : "english";
    }

    @Override
    public int upsertFile(String repo, String filePath, ParsedFile parsed) {
        jdbc.update("DELETE FROM code_edges WHERE src_id IN "
                + "(SELECT id FROM code_symbols WHERE repo=? AND file_path=?)", repo, filePath);
        jdbc.update("DELETE FROM code_symbols WHERE repo=? AND file_path=?", repo, filePath);
        for (CodeSymbol s : parsed.symbols()) {
            jdbc.update("""
                    INSERT INTO code_symbols
                      (id,repo,file_path,language,kind,symbol_path,signature,start_line,end_line,
                       content,metadata,allowed_agents,embedding)
                    VALUES (?,?,?,?,?,?,?,?,?,?,'{}'::jsonb,?::text[],?::vector)
                    ON CONFLICT (id) DO NOTHING""",
                    s.id(), s.repo(), s.filePath(), s.language(), s.kind(), s.symbolPath(),
                    s.signature(), s.startLine(), s.endLine(), s.content(),
                    toPgArray(s.allowedAgents()), toVector(embeddings.embed(s.symbolPath() + "\n" + s.content())));
        }
        for (CodeEdge e : parsed.edges()) {
            jdbc.update("INSERT INTO code_edges (src_id,kind,dst_id,dst_name) VALUES (?,?,?,?) "
                    + "ON CONFLICT DO NOTHING", e.srcId(), e.kind(), e.dstId(), e.dstName());
        }
        return parsed.symbols().size();
    }

    @Override
    public int deleteFile(String repo, String filePath) {
        jdbc.update("DELETE FROM code_edges WHERE src_id IN "
                + "(SELECT id FROM code_symbols WHERE repo=? AND file_path=?)", repo, filePath);
        return jdbc.update("DELETE FROM code_symbols WHERE repo=? AND file_path=?", repo, filePath);
    }

    @Override
    public List<RetrievedSymbol> search(String query, String agent, int topK) {
        String roles = toPgArray(List.of(agent, CodeSymbol.ALL));
        String qvec = toVector(embeddings.embed(query));
        int pool = Math.max(topK * 5, 50);
        String sql = """
                WITH vec AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rnk
                    FROM code_symbols
                    WHERE embedding IS NOT NULL AND allowed_agents && ?::text[]
                    ORDER BY embedding <=> ?::vector LIMIT ?
                ),
                txt AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank(content_tsv, q.query) DESC) AS rnk
                    FROM code_symbols, websearch_to_tsquery('%s', ?) AS q(query)
                    WHERE allowed_agents && ?::text[] AND content_tsv @@ q.query
                    ORDER BY ts_rank(content_tsv, q.query) DESC LIMIT ?
                )
                SELECT s.*,
                       COALESCE(1.0/(?+vec.rnk),0) + COALESCE(1.0/(?+txt.rnk),0) AS score
                FROM code_symbols s
                LEFT JOIN vec ON vec.id = s.id
                LEFT JOIN txt ON txt.id = s.id
                WHERE vec.id IS NOT NULL OR txt.id IS NOT NULL
                ORDER BY score DESC LIMIT ?
                """.formatted(ts);
        return jdbc.query(sql, (rs, i) -> new RetrievedSymbol(mapSymbol(rs), rs.getDouble("score")),
                qvec, roles, qvec, pool, query, roles, pool, rrfK, rrfK, topK);
    }

    @Override
    public List<String> callersOf(String symbolId) {
        String sql = """
                SELECT DISTINCT s.symbol_path FROM code_edges e
                JOIN code_symbols s ON s.id = e.src_id
                WHERE e.kind='calls' AND (e.dst_id=? OR e.dst_name=(
                    SELECT regexp_replace(symbol_path, '^.*\\.', '') FROM code_symbols WHERE id=?))""";
        return jdbc.queryForList(sql, String.class, symbolId, symbolId);
    }

    @Override
    public List<String> testsFor(String symbolId) {
        String sql = """
                SELECT DISTINCT s.symbol_path FROM code_edges e
                JOIN code_symbols s ON s.id = e.src_id
                WHERE e.kind='calls' AND s.file_path ILIKE '%test%'
                  AND (e.dst_id=? OR e.dst_name=(
                      SELECT regexp_replace(symbol_path, '^.*\\.', '') FROM code_symbols WHERE id=?))""";
        return jdbc.queryForList(sql, String.class, symbolId, symbolId);
    }

    @Override
    public int resolvePendingEdges(String repo) {
        String sql = """
                UPDATE code_edges e SET dst_id = s.id
                FROM code_symbols s
                WHERE e.dst_id IS NULL AND e.kind IN ('calls','imports')
                  AND s.repo=? AND regexp_replace(s.symbol_path, '^.*\\.', '') = e.dst_name""";
        return jdbc.update(sql, repo);
    }

    private CodeSymbol mapSymbol(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Array arr = rs.getArray("allowed_agents");
        List<String> agents = arr != null ? List.of((String[]) arr.getArray()) : List.of(CodeSymbol.ALL);
        return new CodeSymbol(rs.getString("id"), rs.getString("repo"), rs.getString("file_path"),
                rs.getString("language"), rs.getString("kind"), rs.getString("symbol_path"),
                rs.getString("signature"), rs.getInt("start_line"), rs.getInt("end_line"),
                rs.getString("content"), java.util.Map.of(), agents);
    }

    private static String toVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static String toPgArray(List<String> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
