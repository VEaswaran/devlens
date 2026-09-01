package com.devlens.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionEngineTest {

    @TempDir
    Path repoRoot;

    private final ExtractionEngine engine = new ExtractionEngine();

    @Test
    void extractsFromEmptyDirectory() {
        Map<String, Object> meta = engine.extract("empty-repo", repoRoot, "https://example.com/empty");
        assertEquals("empty-repo", meta.get("repo_id"));
        assertEquals(1, meta.get("schema_version"));
        assertNotNull(meta.get("extraction_report"));
        assertNotNull(meta.get("generated_at"));
    }

    @Test
    void extractsMavenDependenciesFromPomXml() throws Exception {
        Files.writeString(repoRoot.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                      <version>3.2.1</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Map<String, Object> meta = engine.extract("maven-repo", repoRoot, null);
        @SuppressWarnings("unchecked")
        var deps = (java.util.List<Map<String, Object>>) meta.get("dependencies");
        assertFalse(deps.isEmpty(), "expected at least one dependency");
        assertEquals("org.springframework.boot:spring-boot-starter-web",
                deps.get(0).get("name"));
    }

    @Test
    void classifiesBackendFromPom() throws Exception {
        Files.writeString(repoRoot.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Map<String, Object> meta = engine.extract("backend-repo", repoRoot, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> repoType = (Map<String, Object>) meta.get("repo_type");
        assertEquals("backend", repoType.get("value"));
    }

    @Test
    void classifiesMfeFromPackageJson() throws Exception {
        Files.writeString(repoRoot.resolve("package.json"), """
                { "name": "my-app", "dependencies": { "react": "^18.0.0" } }
                """);
        Map<String, Object> meta = engine.extract("mfe-repo", repoRoot, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> repoType = (Map<String, Object>) meta.get("repo_type");
        assertEquals("mfe", repoType.get("value"));
    }

    @Test
    void extractsCodeownersIfPresent() throws Exception {
        Files.writeString(repoRoot.resolve("CODEOWNERS"), """
                /src/api/ @team-api
                /src/core/ @team-core @team-lead
                """);
        Map<String, Object> meta = engine.extract("codeowners-repo", repoRoot, null);
        @SuppressWarnings("unchecked")
        var owners = (java.util.List<Map<String, Object>>) meta.get("owners");
        assertFalse(owners.isEmpty(), "expected CODEOWNERS entries");
        assertEquals("/src/api/", owners.get(0).get("pattern"));
    }

    @Test
    void extractsKafkaTopicsFromYaml() throws Exception {
        Path resources = repoRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.yml"), """
                spring:
                  kafka:
                    consumer:
                      group-id: my-service
                      topics: order.created
                    producer:
                      topics: order.processed
                """);
        Map<String, Object> meta = engine.extract("kafka-repo", repoRoot, null);
        @SuppressWarnings("unchecked")
        var kafka = (java.util.List<Map<String, Object>>) meta.get("kafka");
        assertFalse(kafka.isEmpty(), "expected kafka topics");
        assertTrue(kafka.stream().anyMatch(k -> "order.created".equals(k.get("topic"))),
                "consumer topic missing");
    }

    @Test
    void extractionReportIsAlwaysPresent() throws Exception {
        Map<String, Object> meta = engine.extract("report-repo", repoRoot, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) meta.get("extraction_report");
        assertNotNull(report);
        assertTrue(report.containsKey("files_scanned"));
        assertTrue(report.containsKey("parse_failures"));
        assertTrue(report.containsKey("extractors_run"));
        assertTrue(report.containsKey("redactions"));
    }

    @Test
    void secretsInYamlAreRedacted() throws Exception {
        Path resources = repoRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        // This value matches the password pattern and should be redacted
        Files.writeString(resources.resolve("application.yml"), """
                spring:
                  datasource:
                    password: supersecretpassword123
                    url: jdbc:postgresql://localhost/mydb
                """);
        Map<String, Object> meta = engine.extract("secret-repo", repoRoot, null);
        String metaString = meta.toString();
        assertFalse(metaString.contains("supersecretpassword123"),
                "raw password must not appear in metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) meta.get("extraction_report");
        assertTrue(((Number) report.get("redactions")).intValue() >= 0,
                "redactions must be reported");
    }
}

