package com.example.codemem.domain.port.out;

import com.example.codemem.domain.model.ParsedFile;

/** Turns source into AST-level symbols + dependency edges (Transformation #1). */
public interface CodeParser {

    boolean supports(String filePath);

    ParsedFile parse(String repo, String filePath, byte[] source);
}
