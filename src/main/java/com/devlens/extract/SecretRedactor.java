package com.devlens.extract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redacts common secret patterns from string values before they are written to the metadata store.
 *
 * <p>The patterns are conservative on purpose — a false positive (redacting a non-secret) is far
 * preferable to a false negative (leaking a real credential). The count of redactions is reported
 * in the extraction_report so the operator can audit.
 */
public final class SecretRedactor {

    public static final String REDACTED = "[REDACTED]";

    /** Patterns that match common credential shapes in YAML/properties values. */
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            // AWS secret access key / generic 40-char hex token
            Pattern.compile("(?i)(password|passwd|secret|token|api[_-]?key|auth[_-]?key|access[_-]?key"
                    + "|private[_-]?key|client[_-]?secret|db[_-]?pass|jdbc[_-]?password"
                    + "|spring\\.datasource\\.password)\\s*[=:]\\s*\\S+"),
            // Bearer / Basic tokens that appear as raw values
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9+/=_-]{20,}"),
            Pattern.compile("(?i)basic\\s+[A-Za-z0-9+/=]{20,}"),
            // AWS keys
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            // Generic high-entropy strings that look like secrets (≥32 hex chars)
            Pattern.compile("[0-9a-fA-F]{32,}")
    );

    private int redactionCount = 0;

    /**
     * Redacts any secret-shaped values found in the string. Returns the sanitised string.
     */
    public String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String result = value;
        for (Pattern p : SECRET_PATTERNS) {
            result = p.matcher(result).replaceAll(m -> {
                redactionCount++;
                return REDACTED;
            });
        }
        return result;
    }

    /**
     * Recursively redacts string values inside a map (in-place mutation on a copy).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> redactMap(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, Object> e : input.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                out.put(e.getKey(), redact(s));
            } else if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), redactMap((Map<String, Object>) m));
            } else if (v instanceof List<?> list) {
                out.put(e.getKey(), redactList(list));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Object> redactList(List<?> list) {
        return list.stream().map(item -> {
            if (item instanceof String s) return (Object) redact(s);
            if (item instanceof Map<?, ?> m) return redactMap((Map<String, Object>) m);
            if (item instanceof List<?> l) return redactList(l);
            return item;
        }).toList();
    }

    /** Returns the number of redactions performed since this instance was created. */
    public int redactionCount() {
        return redactionCount;
    }
}

