package com.example.codemem.domain.model;

/** A single file-level change the sync layer must apply: re-index (UPSERT) or drop (DELETE). */
public record FileChange(String path, Type type) {

    public enum Type { UPSERT, DELETE }

    public static FileChange upsert(String path) {
        return new FileChange(path, Type.UPSERT);
    }

    public static FileChange delete(String path) {
        return new FileChange(path, Type.DELETE);
    }
}
