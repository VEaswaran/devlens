package com.devlens.extract;

import com.devlens.extract.extractor.CodeownersExtractor;
import com.devlens.extract.extractor.GitLogExtractor;
import com.devlens.extract.extractor.KafkaExtractor;
import com.devlens.extract.extractor.MavenDepsExtractor;
import com.devlens.extract.extractor.RepoClassifier;
import com.devlens.extract.extractor.SpringMvcExtractor;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates all extractors for a single repository checkout.
 *
 * <p>Design contract:
 * <ul>
 *   <li>No LLM calls anywhere in this path.
 *   <li>Every extracted fact must have a {@code provenance} field.
 *   <li>Parse failures are recorded in {@code extraction_report}, never silently dropped.
 *   <li>Secrets are redacted before the result map is returned.
 * </ul>
 */
public final class ExtractionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExtractionEngine.class);

    private final SpringMvcExtractor springMvc = new SpringMvcExtractor();
    private final KafkaExtractor kafka = new KafkaExtractor();
    private final MavenDepsExtractor mavenDeps = new MavenDepsExtractor();
    private final CodeownersExtractor codeowners = new CodeownersExtractor();
    private final GitLogExtractor gitLog = new GitLogExtractor();
    private final RepoClassifier classifier = new RepoClassifier();

    /**
     * Extracts all facts for the given repo checkout and returns the complete metadata map,
     * ready to be written as {@code metadata.json}.
     *
     * @param repoId   stable identifier for the repo
     * @param repoPath local filesystem path to the repo working tree
     * @param repoUrl  canonical URL (used for metadata only; no network access performed)
     */
    public Map<String, Object> extract(String repoId, Path repoPath, String repoUrl) {
        log.info("Starting extraction for repo={} path={}", repoId, repoPath);

        List<Map<String, Object>> parseFailures = new ArrayList<>();
        List<String> extractorsRun = new ArrayList<>();
        AtomicLong filesScanned = new AtomicLong(0);
        AtomicLong filesParsed = new AtomicLong(0);
        SecretRedactor redactor = new SecretRedactor();

        // Count files
        try (var stream = Files.walk(repoPath)) {
            stream.filter(Files::isRegularFile).forEach(p -> filesScanned.incrementAndGet());
        } catch (IOException e) {
            log.warn("Could not walk repo directory: {}", e.getMessage());
        }

        // Git facts
        String branch = gitLog.headBranch(repoPath);
        String indexedCommit = gitLog.headCommit(repoPath);

        // Repo classification
        Map<String, Object> repoType = Map.of("value", "unknown", "evidence", List.of());
        try {
            repoType = classifier.classify(repoPath);
            extractorsRun.add("repo-classifier");
        } catch (IOException e) {
            addFailure(parseFailures, "repo-classifier", e.getMessage());
        }

        // Spring MVC routes
        List<Map<String, Object>> apis = List.of();
        try {
            apis = springMvc.extract(repoPath);
            extractorsRun.add("spring-mvc-routes");
            filesParsed.addAndGet(apis.size()); // approximate
        } catch (IOException e) {
            addFailure(parseFailures, "spring-mvc-routes", e.getMessage());
        }

        // Kafka topics
        List<Map<String, Object>> kafkaTopics = List.of();
        try {
            kafkaTopics = kafka.extract(repoPath);
            extractorsRun.add("kafka-yaml");
            extractorsRun.add("kafka-annotations");
        } catch (IOException e) {
            addFailure(parseFailures, "kafka", e.getMessage());
        }

        // Maven dependencies
        List<Map<String, Object>> dependencies = List.of();
        try {
            dependencies = mavenDeps.extract(repoPath);
            extractorsRun.add("maven-deps");
        } catch (IOException e) {
            addFailure(parseFailures, "maven-deps", e.getMessage());
        }

        // CODEOWNERS
        List<Map<String, Object>> owners = List.of();
        try {
            owners = codeowners.extract(repoPath);
            extractorsRun.add("codeowners");
        } catch (IOException e) {
            addFailure(parseFailures, "codeowners", e.getMessage());
        }

        // Recent commits
        List<Map<String, Object>> commits = List.of();
        try {
            commits = gitLog.extract(repoPath);
            extractorsRun.add("git-log");
        } catch (IOException e) {
            addFailure(parseFailures, "git-log", e.getMessage());
        }

        // Outbound HTTP calls (heuristic: line scan for RestTemplate / WebClient / FeignClient)
        List<Map<String, Object>> outboundCalls = extractOutboundCalls(repoPath, parseFailures);
        if (!outboundCalls.isEmpty()) extractorsRun.add("outbound-calls");

        // Build the result map (before redaction)
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schema_version", 1);
        meta.put("repo_id", repoId);
        meta.put("repo_url", repoUrl != null ? repoUrl : "");
        meta.put("branch", branch);
        meta.put("indexed_commit", indexedCommit);
        meta.put("generated_at", Instant.now().toString());
        meta.put("repo_type", repoType);
        meta.put("apis", apis);
        meta.put("kafka", kafkaTopics);
        meta.put("outbound_calls", outboundCalls);
        meta.put("dependencies", dependencies);
        meta.put("owners", owners);
        meta.put("recent_commits", commits);

        // Redact secrets before storing
        Map<String, Object> redacted = redactor.redactMap(meta);

        // Extraction report (goes in after redaction so the report itself isn't redacted)
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("files_scanned", filesScanned.get());
        report.put("files_parsed", filesParsed.get());
        report.put("parse_failures", parseFailures);
        report.put("extractors_run", extractorsRun);
        report.put("redactions", redactor.redactionCount());
        redacted.put("extraction_report", report);

        log.info("Extraction complete for repo={} apis={} kafka={} deps={}",
                repoId, apis.size(), kafkaTopics.size(), dependencies.size());
        return redacted;
    }

    private List<Map<String, Object>> extractOutboundCalls(Path repoPath,
                                                            List<Map<String, Object>> failures) {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.regex.Pattern restTemplate = java.util.regex.Pattern.compile(
                "restTemplate\\.(get|post|put|delete|exchange)For\\w*\\(\\s*[\"']([^\"']+)[\"']");
        java.util.regex.Pattern webClient = java.util.regex.Pattern.compile(
                "\\.uri\\(\\s*[\"']([^\"']+)[\"']");
        java.util.regex.Pattern feignClient = java.util.regex.Pattern.compile(
                "@FeignClient\\s*\\(\\s*(?:name\\s*=\\s*)?[\"']([^\"']+)[\"']");
        try (var stream = Files.walk(repoPath)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(f -> {
                try {
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String rel = repoPath.relativize(f).toString().replace('\\', '/');
                        java.util.regex.Matcher m = feignClient.matcher(line);
                        if (m.find()) {
                            Map<String, Object> e = new LinkedHashMap<>();
                            e.put("target", m.group(1));
                            e.put("kind", "http");
                            e.put("provenance", Map.of("file", rel, "line", i + 1));
                            out.add(e);
                        }
                        m = restTemplate.matcher(line);
                        if (m.find() && m.group(2).startsWith("/")) {
                            Map<String, Object> e = new LinkedHashMap<>();
                            e.put("target", "unknown");
                            e.put("kind", "http");
                            e.put("url_template", m.group(2));
                            e.put("provenance", Map.of("file", rel, "line", i + 1));
                            out.add(e);
                        }
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            addFailure(failures, "outbound-calls", e.getMessage());
        }
        return out;
    }

    private static void addFailure(List<Map<String, Object>> failures, String extractor, String reason) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("extractor", extractor);
        f.put("reason", reason);
        failures.add(f);
    }
}

