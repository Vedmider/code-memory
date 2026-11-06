package com.example.codemem.domain.port.in;

import java.util.Map;

/** Inbound port: index a codebase (full walk) or a single file (incremental). */
public interface IndexUseCase {

    default Map<String, Object> indexPath(String repo, String root) {
        return indexPath(repo, root, false);
    }

    /** @param force when true, forget the repo's symbols + file hashes first (full clean reindex). */
    Map<String, Object> indexPath(String repo, String root, boolean force);

    int indexFile(String repo, String root, String filePath);

    int removeFile(String repo, String root, String filePath);
}
