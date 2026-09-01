package com.devlens.extract.extractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts Kafka topic facts from YAML config and Java annotations. */
public final class KafkaExtractor {

    private static final Pattern TOPICS_YAML = Pattern.compile(
            "^\\s*topics?:\\s*[\"']?([^#\"'\\n]+)[\"']?\\s*$");
    private static final Pattern GROUP_YAML = Pattern.compile(
            "^\\s*group-?id:\\s*[\"']?([^#\"'\\n]+)[\"']?\\s*$");
    private static final Pattern LISTENER_TOPICS = Pattern.compile(
            "@KafkaListener\\s*\\([^)]*topics\\s*=\\s*\\{?[\"']([^\"']+)[\"']");
    private static final Pattern LISTENER_GROUP = Pattern.compile(
            "@KafkaListener\\s*\\([^)]*groupId\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern TEMPLATE_SEND = Pattern.compile(
            "\\.send\\(\\s*[\"']([^\"']+)[\"']");

    public List<Map<String, Object>> extract(Path repoRoot) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        try (var stream = Files.walk(repoRoot)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                try {
                    if ((name.startsWith("application") || name.startsWith("bootstrap"))
                            && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"))) {
                        parseYaml(p, repoRoot, results);
                    } else if (name.endsWith(".java")) {
                        parseJava(p, repoRoot, results);
                    }
                } catch (IOException ignored) {}
            });
        }
        return results;
    }

    private void parseYaml(Path file, Path root, List<Map<String, Object>> out) throws IOException {
        List<String> lines = Files.readAllLines(file);
        boolean inProducer = false;
        String groupId = null;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("producer:")) { inProducer = true; }
            else if (t.startsWith("consumer:")) { inProducer = false; }
            Matcher gm = GROUP_YAML.matcher(lines.get(i));
            if (gm.matches()) groupId = gm.group(1).trim();
            Matcher tm = TOPICS_YAML.matcher(lines.get(i));
            if (tm.matches()) {
                for (String topic : tm.group(1).split(",")) {
                    topic = topic.trim().replaceAll("[\"']", "");
                    if (!topic.isEmpty() && !topic.startsWith("$")) {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("topic", topic); e.put("role", inProducer ? "producer" : "consumer");
                        if (groupId != null && !inProducer) e.put("group_id", groupId);
                        e.put("provenance", prov(file, root, i + 1));
                        out.add(e);
                    }
                }
            }
        }
    }

    private void parseJava(Path file, Path root, List<Map<String, Object>> out) throws IOException {
        List<String> lines = Files.readAllLines(file);
        String groupId = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher gm = LISTENER_GROUP.matcher(line);
            if (gm.find()) groupId = gm.group(1);
            Matcher lm = LISTENER_TOPICS.matcher(line);
            if (lm.find()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("topic", lm.group(1)); e.put("role", "consumer");
                if (groupId != null) e.put("group_id", groupId);
                e.put("provenance", prov(file, root, i + 1));
                out.add(e); groupId = null;
            }
            Matcher sm = TEMPLATE_SEND.matcher(line);
            if (sm.find()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("topic", sm.group(1)); e.put("role", "producer");
                e.put("provenance", prov(file, root, i + 1));
                out.add(e);
            }
        }
    }

    private static Map<String, Object> prov(Path file, Path root, int line) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("file", root.relativize(file).toString().replace('\\', '/'));
        p.put("line", line);
        return p;
    }
}

