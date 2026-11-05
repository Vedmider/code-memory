package com.example.codemem.domain.port.in;

import java.util.Map;

/** Inbound port: index a codebase (full walk) or a single file (incremental). */
public interface IndexUseCase {

    Map<String, Object> indexPath(String repo, String root);

    int indexFile(String repo, String root, String filePath);

    int removeFile(String repo, String root, String filePath);
}
