package dev.undertow.languages.java;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/** Literal POM inspection, not a replacement for Maven's effective/resolved model. */
public final class MavenInventory {
    private static final int MAX_PROPERTY_EXPANSIONS = 8;
    private static final int MAX_DEPENDENCIES = 5000;

    public record Dependency(
            String coordinate,
            String version,
            String scope,
            boolean resolved,
            boolean direct,
            List<String> path) {
        public Dependency {
            path = List.copyOf(path);
        }
    }

    public List<Dependency> literal(String xml) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document =
                    factory.newDocumentBuilder()
                            .parse(
                                    new ByteArrayInputStream(
                                            xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Element project = document.getDocumentElement();
            Map<String, String> properties = new HashMap<>();
            Element props = child(project, "properties");
            if (props != null) {
                for (Node n = props.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element e) {
                        properties.put(e.getTagName(), e.getTextContent().trim());
                    }
                }
            }
            List<Dependency> result = new ArrayList<>();
            Element deps = child(project, "dependencies");
            if (deps == null) {
                return List.of();
            }
            for (Node n = deps.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element e) || !e.getTagName().equals("dependency")) {
                    continue;
                }
                String coordinate = value(e, "groupId") + ":" + value(e, "artifactId");
                String version = resolveVersion(value(e, "version"), properties);
                String scope = value(e, "scope");
                result.add(
                        new Dependency(
                                coordinate,
                                version,
                                scope.isBlank() ? "compile" : scope,
                                false,
                                true,
                                List.of(coordinate)));
            }
            return List.copyOf(result);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Invalid or unsafe POM", e);
        }
    }

    private static String resolveVersion(String version, Map<String, String> properties) {
        for (int expansion = 0;
                expansion < MAX_PROPERTY_EXPANSIONS && version.contains("${");
                expansion++) {
            String previous = version;
            for (var property : properties.entrySet()) {
                version = version.replace("${" + property.getKey() + "}", property.getValue());
            }
            if (version.equals(previous)) {
                break;
            }
        }
        return version.isBlank() || version.contains("${") ? "UNRESOLVED" : version;
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static String value(Element e, String name) {
        Element c = child(e, name);
        return c == null ? "" : c.getTextContent().trim();
    }

    public static List<Dependency> resolved(JsonNode tree) {
        List<Dependency> result = new ArrayList<>();
        walk(tree, List.of(), result);
        return List.copyOf(result);
    }

    private static void walk(JsonNode node, List<String> parents, List<Dependency> result) {
        String coordinate = node.path("groupId").asText() + ":" + node.path("artifactId").asText();
        List<String> path = new ArrayList<>(parents);
        path.add(coordinate);
        if (!parents.isEmpty()) {
            String version = node.path("version").asText();
            if (!coordinate.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
                    || version.isBlank()
                    || version.contains("${")) {
                throw new IllegalArgumentException("Invalid resolved dependency");
            }
            result.add(
                    new Dependency(
                            coordinate,
                            version,
                            node.path("scope").asText("compile"),
                            true,
                            parents.size() == 1,
                            path));
        }
        for (JsonNode child : node.path("children")) {
            walk(child, path, result);
        }
        if (result.size() > MAX_DEPENDENCIES) {
            throw new IllegalArgumentException("Dependency inventory too large");
        }
    }

    public JsonNode compare(GitRepository git, JsonNode ci) throws IOException {
        var result = Json.MAPPER.createObjectNode();
        List<Dependency> base = inventory(git, "base", ci);
        List<Dependency> head = inventory(git, "head", ci);
        result.set("base", Json.MAPPER.valueToTree(base));
        result.set("head", Json.MAPPER.valueToTree(head));
        var changes = result.putArray("changes");
        Map<DependencyKey, Dependency> before = index(base);
        Map<DependencyKey, Dependency> after = index(head);
        Set<DependencyKey> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (DependencyKey key : keys) {
            Dependency oldDependency = before.get(key);
            Dependency newDependency = after.get(key);
            if (oldDependency == null
                    || newDependency == null
                    || !oldDependency.version().equals(newDependency.version())) {
                var change = changes.addObject();
                change.set("before", Json.MAPPER.valueToTree(oldDependency));
                change.set("after", Json.MAPPER.valueToTree(newDependency));
            }
        }
        boolean hasResolvedInventories =
                base.stream().allMatch(Dependency::resolved)
                        && head.stream().allMatch(Dependency::resolved)
                        && hasResolved(ci, "base")
                        && hasResolved(ci, "head");
        if (hasResolvedInventories) {
            return result.put("coverage", "resolved inventories");
        }
        return result.put(
                "coverage", "literal POM only; parent/BOM/profiles/transitives not resolved");
    }

    private record DependencyKey(String coordinate, String scope, List<String> path) {
        private DependencyKey {
            path = List.copyOf(path);
        }
    }

    private static Map<DependencyKey, Dependency> index(List<Dependency> dependencies) {
        Map<DependencyKey, Dependency> indexed = new LinkedHashMap<>();
        for (Dependency dependency : dependencies) {
            indexed.put(
                    new DependencyKey(
                            dependency.coordinate(), dependency.scope(), dependency.path()),
                    dependency);
        }
        return indexed;
    }

    private boolean hasResolved(JsonNode ci, String snapshot) {
        return ci.path(snapshot).has("dependency_tree");
    }

    private List<Dependency> inventory(GitRepository git, String snapshot, JsonNode ci)
            throws IOException {
        if (hasResolved(ci, snapshot)) {
            return resolved(ci.path(snapshot).path("dependency_tree"));
        }
        if (!git.files(snapshot).contains("pom.xml")) {
            return List.of();
        }
        return literal(git.source(snapshot, "pom.xml"));
    }
}
