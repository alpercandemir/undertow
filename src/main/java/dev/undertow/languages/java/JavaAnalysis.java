package dev.undertow.languages.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import dev.undertow.reporting.Json;
import dev.undertow.tools.GitRepository;
import java.io.IOException;

public final class JavaAnalysis {
    private static final int MAX_FILES = 100;
    private static final int MAX_REFERENCES = 100;
    private final JavaParser parser =
            new JavaParser(
                    new ParserConfiguration()
                            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public JsonNode references(GitRepository git, String snapshot, String symbol)
            throws IOException {
        var result = Json.MAPPER.createObjectNode();
        var refs = result.putArray("references");
        var unresolved = result.putArray("unresolved_files");
        int scanned = 0;
        for (String file : git.files(snapshot)) {
            if (!file.endsWith(".java")
                    || file.contains("/target/")
                    || file.contains("/generated/")) {
                continue;
            }
            if (++scanned > MAX_FILES || refs.size() >= MAX_REFERENCES) {
                unresolved.add("Index truncated after bounded scan");
                break;
            }
            try {
                var parsed = parser.parse(git.source(snapshot, file));
                if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                    unresolved.add(file);
                    continue;
                }
                var unit = parsed.getResult().orElseThrow();
                unit.walk(
                        node -> {
                            if (matchesSymbol(node, symbol) && refs.size() < MAX_REFERENCES) {
                                refs.addObject()
                                        .put("path", file)
                                        .put("line", node.getBegin().orElseThrow().line)
                                        .put("kind", node.getClass().getSimpleName())
                                        .put(
                                                "resolution",
                                                "syntactic; overload/receiver not resolved");
                            }
                        });
            } catch (IOException e) {
                unresolved.add(file);
            }
        }
        return result.put("symbol_resolution", "unresolved: classpath evidence not provided")
                .put("commit", git.commit(snapshot))
                .put("files_scanned", Math.min(scanned, MAX_FILES));
    }

    private static boolean matchesSymbol(Node node, String symbol) {
        return switch (node) {
            case MethodCallExpr call -> call.getNameAsString().equals(symbol);
            case MethodDeclaration method -> method.getNameAsString().equals(symbol);
            case NameExpr name -> name.getNameAsString().equals(symbol);
            case TypeDeclaration<?> type -> type.getNameAsString().equals(symbol);
            case AnnotationExpr annotation -> annotation.getName().getIdentifier().equals(symbol);
            default -> false;
        };
    }
}
