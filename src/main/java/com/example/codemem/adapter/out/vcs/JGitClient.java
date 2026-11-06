package com.example.codemem.adapter.out.vcs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.codemem.domain.model.FileChange;
import com.example.codemem.domain.model.FileChange.Type;
import com.example.codemem.domain.port.out.VcsClient;

/** In-process git (JGit) — no git binary required. Read-only: resolve HEAD and diff. */
@Component
public class JGitClient implements VcsClient {

    private static final Logger log = LoggerFactory.getLogger(JGitClient.class);

    @Override
    public boolean isRepo(String root) {
        return new FileRepositoryBuilder().findGitDir(new File(root)).getGitDir() != null;
    }

    private Repository open(String root) throws IOException {
        return new FileRepositoryBuilder().findGitDir(new File(root)).setMustExist(true).build();
    }

    @Override
    public Optional<String> head(String root) {
        try (Repository repo = open(root)) {
            ObjectId h = repo.resolve("HEAD");
            return h == null ? Optional.empty() : Optional.of(h.getName());
        }
        catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public List<FileChange> changesSince(String root, String fromSha) {
        Map<String, Type> acc = new LinkedHashMap<>();
        try (Repository repo = open(root); Git git = new Git(repo)) {
            ObjectId headTree = repo.resolve("HEAD^{tree}");
            if (headTree != null) {
                try (ObjectReader reader = repo.newObjectReader();
                        DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                    df.setRepository(repo);
                    df.setDetectRenames(true);
                    AbstractTreeIterator oldTree = treeFor(repo, reader, fromSha);
                    CanonicalTreeParser newTree = new CanonicalTreeParser();
                    newTree.reset(reader, headTree);
                    for (DiffEntry e : df.scan(oldTree, newTree)) {
                        switch (e.getChangeType()) {
                            case ADD, MODIFY, COPY -> acc.put(e.getNewPath(), Type.UPSERT);
                            case DELETE -> acc.put(e.getOldPath(), Type.DELETE);
                            case RENAME -> {
                                acc.put(e.getOldPath(), Type.DELETE);
                                acc.put(e.getNewPath(), Type.UPSERT);
                            }
                        }
                    }
                }
            }
            Status st = git.status().call();
            for (String p : union(st.getAdded(), st.getChanged(), st.getModified(), st.getUntracked())) {
                acc.put(p, Type.UPSERT);
            }
            for (String p : union(st.getRemoved(), st.getMissing())) {
                acc.put(p, Type.DELETE);
            }
        }
        catch (Exception e) {
            log.warn("git changes failed for {}: {}", root, e.getMessage());
        }
        return acc.entrySet().stream().map(en -> new FileChange(en.getKey(), en.getValue())).toList();
    }

    private static AbstractTreeIterator treeFor(Repository repo, ObjectReader reader, String fromSha)
            throws IOException {
        ObjectId fromTree = fromSha == null ? null : repo.resolve(fromSha + "^{tree}");
        if (fromTree == null) {
            return new EmptyTreeIterator();
        }
        CanonicalTreeParser p = new CanonicalTreeParser();
        p.reset(reader, fromTree);
        return p;
    }

    @SafeVarargs
    private static List<String> union(Collection<String>... sets) {
        List<String> out = new ArrayList<>();
        for (Collection<String> s : sets) {
            out.addAll(s);
        }
        return out;
    }
}
