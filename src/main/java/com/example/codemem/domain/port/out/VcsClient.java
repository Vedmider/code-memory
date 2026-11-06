package com.example.codemem.domain.port.out;

import java.util.List;
import java.util.Optional;

import com.example.codemem.domain.model.FileChange;

/**
 * Read-only view of a local git working tree, used by the self-healing read-gate. No binary
 * dependency — implemented in-process. A non-git path reports {@link #isRepo} false so the
 * gate becomes a no-op.
 */
public interface VcsClient {

    boolean isRepo(String root);

    /** Current committed HEAD sha, if {@code root} is a git repo. */
    Optional<String> head(String root);

    /**
     * File changes between {@code fromSha} (exclusive) and HEAD, unioned with the uncommitted
     * working-tree changes. A null/unknown {@code fromSha} diffs against the empty tree (every
     * tracked file as UPSERT) so a rewritten history self-heals by full re-index.
     */
    List<FileChange> changesSince(String root, String fromSha);
}
