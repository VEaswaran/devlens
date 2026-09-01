package com.devlens.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor();

    @ParameterizedTest
    @ValueSource(strings = {
            "password: mysecret123",
            "token: abc123def456ghi789jklmno",
            "AKIAIOSFODNN7EXAMPLE",
            "secret: mySecretValue"
    })
    void redactsCommonSecretPatterns(String input) {
        String result = redactor.redact(input);
        assertTrue(result.contains(SecretRedactor.REDACTED),
                "Expected redaction in: " + input + " → " + result);
    }

    @Test
    void doesNotRedactNonSecretStrings() {
        String input = "spring.datasource.url=jdbc:postgresql://localhost/mydb";
        String result = redactor.redact(input);
        // URL itself should not be redacted (no secret pattern)
        assertFalse(result.contains(SecretRedactor.REDACTED),
                "Non-secret string was incorrectly redacted: " + result);
    }

    @Test
    void redactMapRecursivelyProcessesNestedMaps() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("password", "password: topsecret");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("datasource", inner);
        outer.put("name", "my-service");

        Map<String, Object> result = redactor.redactMap(outer);

        assertEquals("my-service", result.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> datasource = (Map<String, Object>) result.get("datasource");
        assertTrue(datasource.get("password").toString().contains(SecretRedactor.REDACTED));
    }

    @Test
    void redactionCountIncrements() {
        redactor.redact("password: secret1");
        redactor.redact("password: secret2");
        assertTrue(redactor.redactionCount() >= 2,
                "Expected at least 2 redactions, got: " + redactor.redactionCount());
    }

    @Test
    void nullAndBlankInputsAreReturnedAsIs() {
        assertNull(redactor.redact(null));
        assertEquals("", redactor.redact(""));
        assertEquals("   ", redactor.redact("   "));
    }
}

