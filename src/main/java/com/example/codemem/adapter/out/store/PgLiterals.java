package com.example.codemem.adapter.out.store;

import java.util.List;

/**
 * Builds Postgres literals for values bound as {@code ::vector} / {@code ::text[]}. These
 * are string-cast params (not identifiers); element escaping here is what prevents array
 * literal breakout. Kept separate so the escaping is unit-testable in isolation.
 */
final class PgLiterals {

    private PgLiterals() {
    }

    static String vector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    static String textArray(List<String> values) {
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
