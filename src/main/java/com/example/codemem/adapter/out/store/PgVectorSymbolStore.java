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
    private final double rankWeight;
    private final String ts;

    public PgVectorSymbolStore(JdbcTemplate jdbc, EmbeddingProvider embeddings, int rrfK,
                               double rankWeight, String textSearchConfig) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.rrfK = rrfK;
        this.rankWeight = rankWeight;
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
                    PgLiterals.textArray(s.allowedAgents()),
                    PgLiterals.vector(embeddings.embed(s.symbolPath() + "\n" + s.content())));
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
    public void clearRepo(String repo) {
        jdbc.update("DELETE FROM code_edges WHERE src_id IN "
                + "(SELECT id FROM code_symbols WHERE repo=?)", repo);
        jdbc.update("DELETE FROM code_symbols WHERE repo=?", repo);
    }

    @Override
    public List<RetrievedSymbol> search(String repo, String query, String agent, int topK) {
        String roles = PgLiterals.textArray(List.of(agent, CodeSymbol.ALL));
        String qvec = PgLiterals.vector(embeddings.embed(query));
        int pool = Math.max(topK * 5, 50);
        boolean scoped = repo != null && !repo.isBlank();
        String repoPred = scoped ? " AND repo=?" : "";
        String sql = """
                WITH vec AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rnk
                    FROM code_symbols
                    WHERE embedding IS NOT NULL AND allowed_agents && ?::text[]%s
                    ORDER BY embedding <=> ?::vector LIMIT ?
                ),
                txt AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank(content_tsv, q.query) DESC) AS rnk
                    FROM code_symbols, websearch_to_tsquery('%s', ?) AS q(query)
                    WHERE allowed_agents && ?::text[] AND content_tsv @@ q.query%s
                    ORDER BY ts_rank(content_tsv, q.query) DESC LIMIT ?
                )
                SELECT s.*,
                       COALESCE(1.0/(?+vec.rnk),0) + COALESCE(1.0/(?+txt.rnk),0) + ?*s.rank AS score
                FROM code_symbols s
                LEFT JOIN vec ON vec.id = s.id
                LEFT JOIN txt ON txt.id = s.id
                WHERE vec.id IS NOT NULL OR txt.id IS NOT NULL
                ORDER BY score DESC LIMIT ?
                """.formatted(repoPred, ts, repoPred);
        List<Object> p = new java.util.ArrayList<>();
        p.add(qvec);
        p.add(roles);
        if (scoped) {
            p.add(repo);
        }
        p.add(qvec);
        p.add(pool);
        p.add(query);
        p.add(roles);
        if (scoped) {
            p.add(repo);
        }
        p.add(pool);
        p.add(rrfK);
        p.add(rrfK);
        p.add(rankWeight);
        p.add(pool);
        return jdbc.query(sql, (rs, i) -> new RetrievedSymbol(mapSymbol(rs), rs.getDouble("score")),
                p.toArray())
                .stream().collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(), hits -> personalizedRerank(hits, topK)));
    }

    /** Personalized-PageRank rerank: bias ranking toward symbols graph-connected to the top hits. */
    private List<RetrievedSymbol> personalizedRerank(List<RetrievedSymbol> hits, int topK) {
        int n = hits.size();
        if (n <= 1) {
            return hits;
        }
        java.util.Map<String, Integer> idx = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            idx.put(hits.get(i).symbol().id(), i);
        }
        double[] seed = new double[n];
        for (int i = 0; i < Math.min(5, n); i++) {   // hits are score-desc; top ones seed the walk
            seed[i] = 1.0;
        }
        double[] ppr = PageRank.compute(n, edgesAmong(idx), 0.85, 20, seed);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(
                hits.get(b).score() + rankWeight * ppr[b], hits.get(a).score() + rankWeight * ppr[a]));
        List<RetrievedSymbol> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(topK, n); i++) {
            out.add(hits.get(order[i]));
        }
        return out;
    }

    private List<int[]> edgesAmong(java.util.Map<String, Integer> idx) {
        List<int[]> edges = new java.util.ArrayList<>();
        List<String> ids = new java.util.ArrayList<>(idx.keySet());
        String ph = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() * 2];
        for (int i = 0; i < ids.size(); i++) {
            args[i] = ids.get(i);
            args[ids.size() + i] = ids.get(i);
        }
        jdbc.query("SELECT src_id, dst_id FROM code_edges WHERE dst_id IS NOT NULL AND src_id IN ("
                + ph + ") AND dst_id IN (" + ph + ")", rs -> {
                    Integer a = idx.get(rs.getString(1));
                    Integer b = idx.get(rs.getString(2));
                    if (a != null && b != null) {
                        edges.add(new int[] {a, b});
                    }
                }, args);
        return edges;
    }

    @Override
    public List<String> callersOf(String symbolId) {
        String sql = """
                SELECT DISTINCT s.symbol_path FROM code_edges e
                JOIN code_symbols s ON s.id = e.src_id
                WHERE e.kind='calls' AND e.dst_id=?""";
        return jdbc.queryForList(sql, String.class, symbolId);
    }

    @Override
    public List<String> testsFor(String symbolId) {
        String sql = """
                SELECT DISTINCT s.symbol_path FROM code_edges e
                JOIN code_symbols s ON s.id = e.src_id
                WHERE e.kind='calls' AND s.file_path ILIKE '%test%' AND e.dst_id=?""";
        return jdbc.queryForList(sql, String.class, symbolId);
    }

    @Override
    public int resolvePendingEdges(String repo) {
        // Pass 1: resolve within the caller's own file (handles intra-class calls precisely and
        // avoids linking a bare method name to a same-named method in an unrelated file).
        String sameFile = """
                UPDATE code_edges e SET dst_id = s.id
                FROM code_symbols src, code_symbols s
                WHERE e.dst_id IS NULL AND e.kind IN ('calls','imports')
                  AND src.id = e.src_id
                  AND s.repo = ? AND s.file_path = src.file_path
                  AND regexp_replace(s.symbol_path, '^.*\\.', '') = e.dst_name""";
        int resolved = jdbc.update(sameFile, repo);

        // Pass 2: only edges with no same-file target fall back to a unique repo-wide match.
        // Ambiguous names (>1 candidate across files) are left unresolved rather than over-linked.
        String uniqueRepoWide = """
                UPDATE code_edges e SET dst_id = s.id
                FROM code_symbols s
                WHERE e.dst_id IS NULL AND e.kind IN ('calls','imports')
                  AND s.repo = ? AND regexp_replace(s.symbol_path, '^.*\\.', '') = e.dst_name
                  AND (SELECT count(*) FROM code_symbols c
                       WHERE c.repo = ? AND regexp_replace(c.symbol_path, '^.*\\.', '') = e.dst_name) = 1""";
        resolved += jdbc.update(uniqueRepoWide, repo, repo);
        return resolved;
    }

    @Override
    public void computeGraphRank(String repo) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM code_symbols WHERE repo=? ORDER BY id", String.class, repo);
        if (ids.isEmpty()) {
            return;
        }
        java.util.Map<String, Integer> idx = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            idx.put(ids.get(i), i);
        }
        List<int[]> edges = new java.util.ArrayList<>();
        jdbc.query("""
                SELECT e.src_id, e.dst_id FROM code_edges e
                JOIN code_symbols s ON s.id = e.src_id
                WHERE s.repo=? AND e.dst_id IS NOT NULL""",
                rs -> {
                    Integer a = idx.get(rs.getString("src_id"));
                    Integer b = idx.get(rs.getString("dst_id"));
                    if (a != null && b != null) {
                        edges.add(new int[] {a, b});
                    }
                }, repo);
        double[] rank = PageRank.compute(ids.size(), edges, 0.85, 30);
        jdbc.batchUpdate("UPDATE code_symbols SET rank=? WHERE id=?",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        ps.setDouble(1, rank[i]);
                        ps.setString(2, ids.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return ids.size();
                    }
                });
    }

    private CodeSymbol mapSymbol(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Array arr = rs.getArray("allowed_agents");
        List<String> agents = arr != null ? List.of((String[]) arr.getArray()) : List.of(CodeSymbol.ALL);
        return new CodeSymbol(rs.getString("id"), rs.getString("repo"), rs.getString("file_path"),
                rs.getString("language"), rs.getString("kind"), rs.getString("symbol_path"),
                rs.getString("signature"), rs.getInt("start_line"), rs.getInt("end_line"),
                rs.getString("content"), java.util.Map.of(), agents);
    }
}
