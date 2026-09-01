package com.devlens.extract.extractor;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts Maven dependency facts from pom.xml. */
public final class MavenDepsExtractor {

    public List<Map<String, Object>> extract(Path repoRoot) throws IOException {
        Path pom = repoRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) return List.of();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = dbf.newDocumentBuilder().parse(pom.toFile());
            doc.getDocumentElement().normalize();
            NodeList deps = doc.getElementsByTagName("dependency");
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < deps.getLength(); i++) {
                Element dep = (Element) deps.item(i);
                String groupId = textOf(dep, "groupId");
                String artifactId = textOf(dep, "artifactId");
                String version = textOf(dep, "version");
                String scope = textOf(dep, "scope");
                if (groupId.isEmpty() || artifactId.isEmpty()) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", groupId + ":" + artifactId);
                if (!version.isEmpty()) e.put("version", version);
                e.put("scope", scope.isEmpty() ? "compile" : scope);
                out.add(e);
            }
            return out;
        } catch (Exception ex) {
            throw new IOException("Failed to parse pom.xml: " + ex.getMessage(), ex);
        }
    }

    private static String textOf(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }
}

