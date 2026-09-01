package com.devlens.index;

import com.devlens.DevLensConfig;
import com.devlens.store.MetadataStore;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexManagerTest {

    @TempDir
    Path dataDir;

    private IndexManager indexManager;

    @BeforeEach
    void setUp() throws Exception {
        McpJsonMapper json = McpJsonDefaults.getMapper();
        DevLensConfig config = new DevLensConfig(dataDir);
        MetadataStore store = new MetadataStore(config, json);

        // Write a manifest and metadata fixture
        Files.writeString(dataDir.resolve("manifest.json"), """
                {"schema_version":1,"repos":[{"repo_id":"svc-a"}]}
                """);
        Path repoDir = dataDir.resolve("repos/svc-a");
        Files.createDirectories(repoDir);
        Files.writeString(repoDir.resolve("metadata.json"), """
                {
                  "schema_version": 1, "repo_id": "svc-a",
                  "apis": [{"method":"POST","path":"/orders","handler":"OrderController.create",
                             "provenance":{"file":"OrderController.java","line":10}}],
                  "kafka": [{"topic":"order.placed","role":"producer",
                             "provenance":{"file":"OrderPublisher.java","line":5}}],
                  "extraction_report": {"files_scanned":10,"files_parsed":10,"parse_failures":[],"extractors_run":[],"redactions":0}
                }
                """);

        indexManager = new IndexManager(config, store, json);
    }

    @Test
    void rebuildCreatesIndexFiles() throws Exception {
        indexManager.rebuild();
        assertTrue(Files.isRegularFile(dataDir.resolve("index/routes.json")));
        assertTrue(Files.isRegularFile(dataDir.resolve("index/topics.json")));
        assertTrue(Files.isRegularFile(dataDir.resolve("index/symbols.json")));
    }

    @Test
    void searchFindsRouteByPath() throws Exception {
        indexManager.rebuild();
        Map<String, Object> result = indexManager.search("orders");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) result.get("routes");
        assertNotNull(routes, "expected routes in result");
        assertEquals(1, routes.size());
        assertEquals("/orders", routes.get(0).get("key"));
        assertEquals("svc-a", routes.get(0).get("repo_id"));
    }

    @Test
    void searchFindsKafkaTopicByName() throws Exception {
        indexManager.rebuild();
        Map<String, Object> result = indexManager.search("order.placed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topics = (List<Map<String, Object>>) result.get("topics");
        assertNotNull(topics, "expected topics in result");
        assertEquals("svc-a", topics.get(0).get("repo_id"));
        assertEquals("producer", topics.get(0).get("role"));
    }

    @Test
    void searchIsCaseInsensitive() throws Exception {
        indexManager.rebuild();
        Map<String, Object> result = indexManager.search("ORDERS");
        assertTrue((Integer) result.get("total_matches") > 0,
                "case-insensitive search should match");
    }

    @Test
    void searchReturnsEmptyWhenNoIndexExists() throws Exception {
        // Don't call rebuild — no index files exist
        Map<String, Object> result = indexManager.search("anything");
        assertEquals(0, result.get("total_matches"));
        assertNotNull(result.get("note"), "should include a note explaining no index");
    }
}

