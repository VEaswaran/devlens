package com.devlens.index;

import com.devlens.DevLensConfig;
import com.devlens.store.MetadataStore;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and queries the cross-repo inverted indexes described in plan.md §5.
 *
 * <p>Three indexes live under {@code devlens-data/index/}:
 * <ul>
 *   <li>{@code symbols.json}  — class/method name → [{repo_id, file, line}]
 *   <li>{@code topics.json}   — Kafka topic → [{repo_id, role, provenance}]
 *   <li>{@code routes.json}   — HTTP path pattern → [{repo_id, method, provenance}]
 * </ul>
 *
 * <p>All reads are case-insensitive substring / prefix searches. This intentionally avoids
 * any semantic similarity search — the results are deterministic given the index state.
 */
public final class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private final DevLensConfig config;
    private final MetadataStore store;
    private final McpJsonMapper json;

    public IndexManager(DevLensConfig config, MetadataStore store, McpJsonMapper json) {
        this.config = config;
        this.store = store;
        this.json = json;
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Build
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Rebuilds all three indexes from every currently-indexed repo's metadata.json.
     * Called after a successful {@code refresh_repo_index}.
     */
    public void rebuild() throws IOException {
        List<String> repoIds = store.knownRepoIds();
        Map<String, List<Map<String, Object>>> symbols = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> topics = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> routes = new LinkedHashMap<>();

        for (String repoId : repoIds) {
            try {
                Map<String, Object> meta = store.metadata(repoId, null);
                indexApis(repoId, meta, routes);
                indexKafka(repoId, meta, topics);
                indexSymbols(repoId, meta, symbols);
            } catch (MetadataStore.UnknownRepoException e) {
                log.warn("Repo {} listed in manifest but metadata missing; skipping.", repoId);
            }
        }

        Path indexDir = config.dataDir().resolve("index");
        Files.createDirectories(indexDir);
        writeIndex(indexDir.resolve("symbols.json"), symbols);
        writeIndex(indexDir.resolve("topics.json"), topics);
        writeIndex(indexDir.resolve("routes.json"), routes);
        log.info("Index rebuilt: {} repos, {} symbols, {} topics, {} routes",
                repoIds.size(), symbols.size(), topics.size(), routes.size());
    }

    @SuppressWarnings("unchecked")
    private void indexApis(String repoId, Map<String, Object> meta,
                            Map<String, List<Map<String, Object>>> routes) {
        Object apis = meta.get("apis");
        if (!(apis instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> api)) continue;
            String path = String.valueOf(((Map<String, Object>) api).getOrDefault("path", ""));
            String method = String.valueOf(((Map<String, Object>) api).getOrDefault("method", "GET"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("repo_id", repoId);
            entry.put("method", method);
            entry.put("handler", ((Map<String, Object>) api).get("handler"));
            entry.put("provenance", ((Map<String, Object>) api).get("provenance"));
            routes.computeIfAbsent(path, k -> new ArrayList<>()).add(entry);
        }
    }

    @SuppressWarnings("unchecked")
    private void indexKafka(String repoId, Map<String, Object> meta,
                             Map<String, List<Map<String, Object>>> topics) {
        Object kafka = meta.get("kafka");
        if (!(kafka instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> k)) continue;
            String topic = String.valueOf(((Map<String, Object>) k).getOrDefault("topic", ""));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("repo_id", repoId);
            entry.put("role", ((Map<String, Object>) k).get("role"));
            entry.put("provenance", ((Map<String, Object>) k).get("provenance"));
            topics.computeIfAbsent(topic, t -> new ArrayList<>()).add(entry);
        }
    }

    @SuppressWarnings("unchecked")
    private void indexSymbols(String repoId, Map<String, Object> meta,
                               Map<String, List<Map<String, Object>>> symbols) {
        // Index handler names from APIs as symbols
        Object apis = meta.get("apis");
        if (apis instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> api)) continue;
                Object handler = ((Map<String, Object>) api).get("handler");
                if (handler instanceof String h && !h.isBlank()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("repo_id", repoId);
                    entry.put("kind", "handler");
                    entry.put("provenance", ((Map<String, Object>) api).get("provenance"));
                    symbols.computeIfAbsent(h, k -> new ArrayList<>()).add(entry);
                }
            }
        }
    }

    private void writeIndex(Path file, Map<String, List<Map<String, Object>>> index) throws IOException {
        Files.writeString(file, json.writeValueAsString(index));
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Query
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Searches all three indexes for entries matching {@code query} (case-insensitive substring).
     * Returns a combined result grouped by type.
     */
    public Map<String, Object> search(String query) throws IOException {
        String lower = query.toLowerCase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);

        List<Map<String, Object>> routeMatches = searchIndex("routes.json", lower);
        List<Map<String, Object>> topicMatches = searchIndex("topics.json", lower);
        List<Map<String, Object>> symbolMatches = searchIndex("symbols.json", lower);

        if (!routeMatches.isEmpty()) result.put("routes", routeMatches);
        if (!topicMatches.isEmpty()) result.put("topics", topicMatches);
        if (!symbolMatches.isEmpty()) result.put("symbols", symbolMatches);

        int total = routeMatches.size() + topicMatches.size() + symbolMatches.size();
        result.put("total_matches", total);
        if (total == 0) {
            result.put("note", "No matches found. The index may not include all repos — call refresh_repo_index to update.");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> searchIndex(String indexFile, String lowerQuery) throws IOException {
        Path file = config.dataDir().resolve("index").resolve(indexFile);
        if (!Files.isRegularFile(file)) return List.of();

        String content = Files.readString(file);
        Map<String, List<Map<String, Object>>> index =
                json.readValue(content, new TypeRef<Map<String, List<Map<String, Object>>>>() {});

        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : index.entrySet()) {
            if (entry.getKey().toLowerCase().contains(lowerQuery)) {
                for (Map<String, Object> occurrence : entry.getValue()) {
                    Map<String, Object> match = new LinkedHashMap<>();
                    match.put("key", entry.getKey());
                    match.putAll(occurrence);
                    matches.add(match);
                }
            }
        }
        return matches;
    }
}

