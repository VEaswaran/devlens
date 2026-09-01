package com.devlens.tools;

import com.devlens.DevLensConfig;
import com.devlens.extract.ExtractionEngine;
import com.devlens.index.IndexManager;
import com.devlens.store.MetadataStore;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code refresh_repo_index} tool: runs the extraction engine on a local repo
 * path and writes the results to the metadata store + rebuilds the inverted indexes.
 */
public final class IndexTools {

    private static final Logger log = LoggerFactory.getLogger(IndexTools.class);

    private static final Pattern SAFE_REPO_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private static final String REFRESH_SCHEMA = """
            {
              "type": "object",
              "required": ["repo_id", "repo_path"],
              "properties": {
                "repo_id": {
                  "type": "string",
                  "description": "Stable identifier for the repo, used as the key in all queries. Must match [A-Za-z0-9][A-Za-z0-9._-]{0,127}."
                },
                "repo_path": {
                  "type": "string",
                  "description": "Absolute or relative path to the local repo working tree to index."
                },
                "repo_url": {
                  "type": "string",
                  "description": "Optional. Canonical VCS URL stored for provenance only; no network access is performed."
                }
              },
              "additionalProperties": false
            }
            """;

    private final DevLensConfig config;
    private final IndexManager indexManager;
    private final McpJsonMapper json;

    public IndexTools(DevLensConfig config, MetadataStore store,
                      IndexManager indexManager, McpJsonMapper json) {
        this.config = config;
        this.indexManager = indexManager;
        this.json = json;
    }

    public SyncToolSpecification refreshRepoIndex() {
        Tool tool = Tool.builder("refresh_repo_index", json, REFRESH_SCHEMA)
                .description("""
                        Extract metadata from a local repo working tree and write it to the \
                        DevLens data store. Also rebuilds the cross-repo inverted indexes used \
                        by search_code. Call this after cloning or pulling a repo. Extraction \
                        is synchronous and CPU-bound; it typically takes a few seconds on a \
                        medium-sized repo. No LLM calls are made.""")
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null) return error("No arguments provided");

                    String repoId = stringArg(args, "repo_id");
                    String repoPathStr = stringArg(args, "repo_path");
                    String repoUrl = stringArg(args, "repo_url");

                    if (repoId.isEmpty()) return error("repo_id is required");
                    if (!SAFE_REPO_ID.matcher(repoId).matches()) {
                        return error("repo_id contains invalid characters: " + repoId);
                    }
                    if (repoPathStr.isEmpty()) return error("repo_path is required");

                    Path repoPath = Path.of(repoPathStr).toAbsolutePath().normalize();
                    if (!Files.isDirectory(repoPath)) {
                        return error("repo_path does not exist or is not a directory: " + repoPath);
                    }

                    try {
                        Instant start = Instant.now();
                        ExtractionEngine engine = new ExtractionEngine();
                        Map<String, Object> metadata = engine.extract(repoId, repoPath,
                                repoUrl.isEmpty() ? null : repoUrl);

                        // Write metadata.json
                        Path repoDataDir = config.reposDir().resolve(repoId);
                        Files.createDirectories(repoDataDir);
                        Path metadataFile = repoDataDir.resolve("metadata.json");
                        Files.writeString(metadataFile, json.writeValueAsString(metadata));

                        // Update manifest
                        updateManifest(repoId, metadata);

                        // Rebuild cross-repo indexes
                        indexManager.rebuild();

                        long elapsedMs = Instant.now().toEpochMilli() - start.toEpochMilli();
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "ok");
                        result.put("repo_id", repoId);
                        result.put("indexed_commit", metadata.get("indexed_commit"));
                        result.put("branch", metadata.get("branch"));
                        result.put("elapsed_ms", elapsedMs);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> report = (Map<String, Object>) metadata.get("extraction_report");
                        result.put("extraction_report", report);
                        log.info("refresh_repo_index complete repo={} elapsed={}ms", repoId, elapsedMs);
                        return ok(result);
                    } catch (Exception e) {
                        log.error("refresh_repo_index failed for repo={}", repoId, e);
                        return error("Extraction failed: " + e.getMessage());
                    }
                })
                .build();
    }

    @SuppressWarnings("unchecked")
    private void updateManifest(String repoId, Map<String, Object> metadata) throws IOException {
        Path manifestFile = config.manifestFile();
        Map<String, Object> manifest;
        if (Files.isRegularFile(manifestFile)) {
            manifest = json.readValue(Files.readString(manifestFile),
                    new io.modelcontextprotocol.json.TypeRef<Map<String, Object>>() {});
        } else {
            manifest = new LinkedHashMap<>();
            manifest.put("schema_version", 1);
        }

        List<Map<String, Object>> repos;
        Object existing = manifest.get("repos");
        if (existing instanceof List<?> l) {
            repos = new ArrayList<>();
            for (Object e : l) {
                if (e instanceof Map<?, ?> m) {
                    Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) m);
                    if (!repoId.equals(entry.get("repo_id"))) repos.add(entry);
                }
            }
        } else {
            repos = new ArrayList<>();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) metadata.get("extraction_report");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("repo_id", repoId);
        entry.put("repo_type", ((Map<String, Object>) metadata.get("repo_type")).get("value"));
        entry.put("branch", metadata.get("branch"));
        entry.put("indexed_commit", metadata.get("indexed_commit"));
        entry.put("indexed_at", metadata.get("generated_at"));
        if (report != null) {
            entry.put("files_scanned", report.get("files_scanned"));
            entry.put("files_parsed", report.get("files_parsed"));
            entry.put("parse_failure_count",
                    report.get("parse_failures") instanceof List<?> pf ? pf.size() : 0);
        }
        repos.add(entry);
        manifest.put("repos", repos);
        manifest.put("generated_at", Instant.now().toString());
        Files.writeString(manifestFile, json.writeValueAsString(manifest));
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private CallToolResult ok(Object payload) {
        try {
            return CallToolResult.builder()
                    .addTextContent(json.writeValueAsString(payload))
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return error("Serialisation failed: " + e.getMessage());
        }
    }

    private static CallToolResult error(String msg) {
        return CallToolResult.builder().addTextContent(msg).isError(true).build();
    }
}

