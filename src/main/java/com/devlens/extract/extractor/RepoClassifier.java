package com.devlens.extract.extractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies a repo as backend / MFE / BFF / unknown.
 * Deterministic and evidence-based — never a confidence float.
 */
public final class RepoClassifier {

    public Map<String, Object> classify(Path repoRoot) throws IOException {
        boolean hasPom = Files.isRegularFile(repoRoot.resolve("pom.xml"));
        boolean hasGradle = Files.isRegularFile(repoRoot.resolve("build.gradle"))
                || Files.isRegularFile(repoRoot.resolve("build.gradle.kts"));
        boolean hasPackageJson = Files.isRegularFile(repoRoot.resolve("package.json"));

        List<String> evidence = new ArrayList<>();
        boolean isJvm = detectJvm(repoRoot, hasPom, hasGradle, evidence);
        boolean isFrontend = hasPackageJson && detectFrontend(repoRoot, evidence);

        String type;
        if (isJvm && isFrontend) {
            type = "bff";
            evidence.add("mixed JVM + JS/TS frontend → BFF");
        } else if (isFrontend) {
            type = "mfe";
        } else if (isJvm) {
            type = "backend";
        } else if (hasPackageJson) {
            // JS/TS but no recognised UI framework
            type = "backend";
            evidence.add("package.json present (no UI framework detected)");
        } else {
            type = "unknown";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", type);
        result.put("evidence", evidence);
        return result;
    }

    private boolean detectJvm(Path root, boolean hasPom, boolean hasGradle,
                               List<String> evidence) throws IOException {
        boolean found = false;
        if (hasPom) {
            String pom = Files.readString(root.resolve("pom.xml"));
            if (pom.contains("spring-boot-starter-web") || pom.contains("spring-webmvc")) {
                evidence.add("pom.xml: spring-boot-starter-web");
                found = true;
            }
            if (pom.contains("spring-boot-starter-webflux")) {
                evidence.add("pom.xml: spring-boot-starter-webflux");
                found = true;
            }
            if (!found) {
                evidence.add("pom.xml present");
                found = true;
            }
        }
        if (hasGradle) {
            String gradleFile = Files.isRegularFile(root.resolve("build.gradle"))
                    ? "build.gradle" : "build.gradle.kts";
            String gradle = Files.readString(root.resolve(gradleFile));
            if (gradle.contains("spring-boot-starter-web")) {
                evidence.add(gradleFile + ": spring-boot-starter-web");
            } else {
                evidence.add(gradleFile + " present");
            }
            found = true;
        }
        return found;
    }

    /** Returns true if the package.json contains a recognised UI framework. */
    private boolean detectFrontend(Path root, List<String> evidence) throws IOException {
        String pkg = Files.readString(root.resolve("package.json"));
        if (pkg.contains("\"react\"") || pkg.contains("\"next\"")) {
            evidence.add("package.json: react");
            return true;
        }
        if (pkg.contains("\"@angular/core\"")) {
            evidence.add("package.json: angular");
            return true;
        }
        if (pkg.contains("\"vue\"")) {
            evidence.add("package.json: vue");
            return true;
        }
        return false;
    }
}
