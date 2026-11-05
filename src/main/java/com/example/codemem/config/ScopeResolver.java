package com.example.codemem.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Agent context scoping (Transformation #4): maps a file path to the agent roles allowed
 * to retrieve its symbols. Segment-based so it is layout-robust. "all" = every agent.
 */
@Component
public class ScopeResolver {

    private static final Set<String> DEVOPS_DIRS =
            Set.of("deploy", "charts", "helm", "k8s", "kustomize", "infra");
    private static final Set<String> TEST_DIRS =
            Set.of("test", "tests", "__tests__", "spec");

    public List<String> resolve(String filePath) {
        String p = filePath.replace('\\', '/').toLowerCase();
        String name = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
        Set<String> segments = new HashSet<>(Arrays.asList(p.split("/")));

        if (p.contains("secret") || name.endsWith(".env")) {
            return List.of("devops");
        }
        if (!disjoint(segments, DEVOPS_DIRS)) {
            return List.of("devops");
        }
        if (!disjoint(segments, TEST_DIRS) || name.startsWith("test_")
                || name.contains("_test.") || name.contains(".test.")) {
            return List.of("dev", "qa");
        }
        return List.of("all");
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        for (String x : b) {
            if (a.contains(x)) {
                return false;
            }
        }
        return true;
    }
}
