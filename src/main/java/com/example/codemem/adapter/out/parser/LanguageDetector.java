package com.example.codemem.adapter.out.parser;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Maps a file to a language. Extension-first (reliable for code); extend with content
 *  sniffing if ambiguous extensions are ever added. */
@Component
public class LanguageDetector {

    public static final String JAVA = "java";
    public static final String PYTHON = "python";
    public static final String GO = "go";

    private static final Map<String, String> BY_EXTENSION = Map.of(
            ".java", JAVA,
            ".py", PYTHON,
            ".go", GO);

    public Optional<String> detect(String filePath) {
        String p = filePath.toLowerCase();
        int dot = p.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_EXTENSION.get(p.substring(dot)));
    }
}
