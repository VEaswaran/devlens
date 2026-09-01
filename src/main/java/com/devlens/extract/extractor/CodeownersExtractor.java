package com.devlens.extract.extractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses CODEOWNERS / .github/CODEOWNERS to extract ownership patterns. */
public final class CodeownersExtractor {

    public List<Map<String, Object>> extract(Path repoRoot) throws IOException {
        // CODEOWNERS can live in root, .github/, or docs/
        for (String location : List.of("CODEOWNERS", ".github/CODEOWNERS", "docs/CODEOWNERS")) {
            Path f = repoRoot.resolve(location);
            if (Files.isRegularFile(f)) return parse(f);
        }
        return List.of();
    }

    private List<Map<String, Object>> parse(Path file) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;
            String pattern = parts[0];
            List<String> owners = new ArrayList<>(Arrays.asList(parts).subList(1, parts.length));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("pattern", pattern);
            e.put("owners", owners);
            e.put("source", "CODEOWNERS");
            out.add(e);
        }
        return out;
    }
}

