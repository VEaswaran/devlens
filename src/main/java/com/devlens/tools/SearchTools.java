package com.devlens.tools;

import com.devlens.index.IndexManager;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.Map;

/**
 * The {@code search_code} tool: searches the cross-repo inverted indexes by
 * route path, Kafka topic, or handler/symbol name.
 */
public final class SearchTools {

    private static final String SEARCH_SCHEMA = """
            {
              "type": "object",
              "required": ["query"],
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Substring to match against HTTP route paths, Kafka topic names, or handler/class names. Case-insensitive. Examples: '/checkout', 'order.created', 'CheckoutController'."
                }
              },
              "additionalProperties": false
            }
            """;

    private final IndexManager indexManager;
    private final McpJsonMapper json;

    public SearchTools(IndexManager indexManager, McpJsonMapper json) {
        this.indexManager = indexManager;
        this.json = json;
    }

    public SyncToolSpecification searchCode() {
        Tool tool = Tool.builder("search_code", json, SEARCH_SCHEMA)
                .description("""
                        Search all indexed repos for HTTP routes, Kafka topics, or handler names \
                        matching the query string (case-insensitive substring). Use this when you \
                        need to find *where* something is defined across repos, rather than reading \
                        known fields from a specific repo. Every result carries a provenance \
                        (file + line). Results come from the inverted index, not from scanning \
                        source files at query time.""")
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    if (args == null || !args.containsKey("query")) {
                        return error("Missing required argument: query");
                    }
                    String query = String.valueOf(args.get("query")).trim();
                    if (query.isEmpty()) {
                        return error("query must not be blank");
                    }
                    if (query.length() > 200) {
                        return error("query is too long (max 200 chars)");
                    }
                    try {
                        Map<String, Object> result = indexManager.search(query);
                        return ok(result);
                    } catch (Exception e) {
                        return error("Search failed: " + e.getMessage());
                    }
                })
                .build();
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

