package com.example.codemem.domain.port.in;

import java.util.List;
import java.util.Map;

/** Inbound port: explicitly sync a repo (hook/agent path), optionally for specific paths. */
public interface SyncUseCase {

    /**
     * @param paths agent-supplied change set (re-index existing, drop missing); when null/empty
     *              the delta is computed from git (cursor..HEAD ∪ working tree).
     */
    Map<String, Object> sync(String repo, String root, List<String> paths);
}
