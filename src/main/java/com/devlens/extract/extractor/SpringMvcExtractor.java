package com.devlens.extract.extractor;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts Spring MVC HTTP route definitions from Java source files using tree-sitter.
 * Handles @RequestMapping, @GetMapping, @PostMapping, @PutMapping, @DeleteMapping, @PatchMapping.
 */
public final class SpringMvcExtractor {

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping");

    private static final Map<String, String> DEFAULT_METHOD = Map.of(
            "GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH",
            "RequestMapping", "GET");

    public List<Map<String, Object>> extract(Path repoRoot) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        try (var stream = Files.walk(repoRoot)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(f -> {
                        try { parseFile(f, repoRoot, results); }
                        catch (IOException ignored) {}
                    });
        }
        return results;
    }

    private void parseFile(Path file, Path root, List<Map<String, Object>> out) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        try (TSParser parser = new TSParser()) {
            if (!parser.setLanguage(new TreeSitterJava())) return;
            TSTree tree = parser.parseString(null, source);
            TSNode rootNode = tree.getRootNode();
            if (rootNode.isNull() || rootNode.hasError()) return;

            String relativePath = root.relativize(file).toString().replace('\\', '/');
            // Track class-level @RequestMapping prefix
            String[] classPrefix = {""};
            String[] currentClass = {""};
            visitForClass(rootNode, bytes, classPrefix, currentClass, relativePath, out);
        }
    }

    private void visitForClass(TSNode node, byte[] src,
                                String[] classPrefix, String[] currentClass,
                                String file, List<Map<String, Object>> out) {
        String type = node.getType();

        if ("class_declaration".equals(type) || "interface_declaration".equals(type)) {
            String savedPrefix = classPrefix[0];
            String savedClass = currentClass[0];
            // find class name
            for (int i = 0; i < node.getChildCount(); i++) {
                TSNode child = node.getChild(i);
                if ("identifier".equals(child.getType())) {
                    currentClass[0] = text(src, child);
                    break;
                }
            }
            // find class-level @RequestMapping
            String prefix = "";
            for (int i = 0; i < node.getChildCount(); i++) {
                TSNode child = node.getChild(i);
                if ("modifiers".equals(child.getType())) {
                    prefix = extractMappingPath(child, src);
                    break;
                }
            }
            classPrefix[0] = prefix;
            // recurse into body
            for (int i = 0; i < node.getChildCount(); i++) {
                visitForClass(node.getChild(i), src, classPrefix, currentClass, file, out);
            }
            classPrefix[0] = savedPrefix;
            currentClass[0] = savedClass;
            return;
        }

        if ("method_declaration".equals(type)) {
            String methodName = "";
            String methodAnnotation = "";
            String path = "";
            String httpMethod = "";
            int line = 0;

            for (int i = 0; i < node.getChildCount(); i++) {
                TSNode child = node.getChild(i);
                if ("modifiers".equals(child.getType())) {
                    // look for mapping annotation
                    for (int j = 0; j < child.getChildCount(); j++) {
                        TSNode ann = child.getChild(j);
                        if ("annotation".equals(ann.getType()) || "marker_annotation".equals(ann.getType())) {
                            String annName = getAnnotationName(ann, src);
                            if (MAPPING_ANNOTATIONS.contains(annName)) {
                                methodAnnotation = annName;
                                path = extractAnnotationPath(ann, src);
                                httpMethod = DEFAULT_METHOD.getOrDefault(annName, "GET");
                                line = ann.getStartPoint().getRow() + 1;
                            }
                        }
                    }
                } else if ("identifier".equals(child.getType())) {
                    methodName = text(src, child);
                }
            }

            if (!methodAnnotation.isEmpty()) {
                String fullPath = normalizePath(classPrefix[0], path);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("method", httpMethod);
                e.put("path", fullPath);
                e.put("handler", currentClass[0] + "." + methodName);
                Map<String, Object> prov = new LinkedHashMap<>();
                prov.put("file", file);
                prov.put("line", line);
                e.put("provenance", prov);
                out.add(e);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (!"class_declaration".equals(child.getType()) && !"interface_declaration".equals(child.getType())) {
                visitForClass(child, src, classPrefix, currentClass, file, out);
            }
        }
    }

    /** Searches a modifiers node for any Spring mapping annotation and returns its path. */
    private String extractMappingPath(TSNode modifiers, byte[] src) {
        for (int i = 0; i < modifiers.getChildCount(); i++) {
            TSNode ann = modifiers.getChild(i);
            if ("annotation".equals(ann.getType()) || "marker_annotation".equals(ann.getType())) {
                String name = getAnnotationName(ann, src);
                if (MAPPING_ANNOTATIONS.contains(name)) {
                    return extractAnnotationPath(ann, src);
                }
            }
        }
        return "";
    }

    private String getAnnotationName(TSNode ann, byte[] src) {
        for (int i = 0; i < ann.getChildCount(); i++) {
            TSNode child = ann.getChild(i);
            if ("type_identifier".equals(child.getType())) {
                return text(src, child);
            }
        }
        return "";
    }

    private String extractAnnotationPath(TSNode ann, byte[] src) {
        for (int i = 0; i < ann.getChildCount(); i++) {
            TSNode child = ann.getChild(i);
            if ("annotation_argument_list".equals(child.getType())) {
                return extractPathFromArgList(child, src);
            }
        }
        return "";
    }

    private String extractPathFromArgList(TSNode argList, byte[] src) {
        for (int i = 0; i < argList.getChildCount(); i++) {
            TSNode child = argList.getChild(i);
            String type = child.getType();
            if ("string_literal".equals(type)) {
                return stripQuotes(text(src, child));
            }
            if ("element_value_pair".equals(type)) {
                // e.g. value = "/path" or path = "/path"
                for (int j = 0; j < child.getChildCount(); j++) {
                    TSNode part = child.getChild(j);
                    if ("string_literal".equals(part.getType())) {
                        return stripQuotes(text(src, part));
                    }
                }
            }
        }
        return "";
    }

    private static String normalizePath(String prefix, String path) {
        if (prefix.isEmpty()) return path.isEmpty() ? "/" : path;
        if (path.isEmpty()) return prefix;
        String p = prefix.endsWith("/") ? prefix : prefix + "/";
        String s = path.startsWith("/") ? path.substring(1) : path;
        return p + s;
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String text(byte[] src, TSNode node) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start < 0 || end > src.length || start >= end) return "";
        return new String(src, start, end - start, StandardCharsets.UTF_8);
    }
}

