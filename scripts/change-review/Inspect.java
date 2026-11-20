import com.sun.source.doctree.DocTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Reads Java syntax without building the project or running annotation processors. */
class Inspect {
    private static final Set<String> ASSERTIONS =
            Set.of("assertEquals", "assertNull", "assertTrue", "assertFalse", "assertThrows");
    private static final Set<String> JUNIT =
            Set.of("org.junit.Assert", "org.junit.jupiter.api.Assertions");

    public static void main(String[] args) throws IOException {
        var files = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"),
                    null, manager.getJavaFileObjectsFromStrings(files));
            var units = new ArrayList<CompilationUnitTree>();
            task.parse().forEach(units::add);
            var invalid = new LinkedHashSet<JavaFileObject>();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    invalid.add(diagnostic.getSource());
                    System.err.println(diagnostic);
                }
            }
            var docs = DocTrees.instance(task);
            for (var unit : units) {
                if (!invalid.contains(unit.getSourceFile())) {
                    inspect(unit, docs);
                }
            }
        }
    }

    private static void inspect(CompilationUnitTree unit, DocTrees docs) {
        var file = Path.of(unit.getSourceFile().toUri()).toAbsolutePath();
        var path = Path.of("").toAbsolutePath().relativize(file).toString();
        var pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        var imports = new HashMap<String, String>();
        var staticImports = new LinkedHashSet<String>();
        var junit = "";
        for (var entry : unit.getImports()) {
            var qualified = entry.getQualifiedIdentifier().toString();
            if (entry.isStatic()) {
                staticImports.add(qualified);
            } else {
                imports.put(qualified.substring(qualified.lastIndexOf('.') + 1), qualified);
                if (qualified.equals("org.junit.Test") || qualified.equals("org.junit.*")) {
                    junit = "4";
                } else if (qualified.equals("org.junit.jupiter.api.Test")
                        || qualified.equals("org.junit.jupiter.api.*")
                        || qualified.equals("org.junit.jupiter.params.ParameterizedTest")) {
                    junit = "5";
                }
            }
        }
        for (var declaration : unit.getTypeDecls()) {
            if (declaration instanceof ClassTree type) {
                String owner = qualify(pkg, type.getSimpleName().toString());
                var fields = new HashMap<String, String>();
                for (var member : type.getMembers()) {
                    if (member instanceof VariableTree field && field.getType() != null) {
                        fields.put(field.getName().toString(), field.getType().toString());
                    }
                }
                for (var member : type.getMembers()) {
                    if (member instanceof MethodTree method && method.getBody() != null) {
                        var name = method.getName().toString();
                        var signature = String.join(",",
                                method.getParameters().stream().map(p -> p.getType().toString()).toList());
                        var doc = docs.getDocCommentTree(TreePath.getPath(unit, method));
                        var summary = doc == null ? "" : String.join(" ",
                                doc.getFirstSentence().stream().map(DocTree::toString).toList());
                        long line = line(unit, docs, method);
                        long end = unit.getLineMap().getLineNumber(
                                docs.getSourcePositions().getEndPosition(unit, method) - 1);
                        boolean test = method.getModifiers().getAnnotations().stream()
                                .map(annotation -> annotation.getAnnotationType().toString())
                                .anyMatch(annotation -> annotation.matches("(.*\\.)?(Test|ParameterizedTest|RepeatedTest)"));
                        if (test && junit.isEmpty()) {
                            junit = method.getModifiers().toString().contains("org.junit.jupiter") ? "5" : "";
                        }

                        var scan = new Calls(unit, docs, path, pkg, owner, name, test ? junit : "",
                                imports, staticImports);
                        fields.forEach((field, fieldType) -> scan.variables.put(field, scan.typeName(fieldType)));
                        method.getParameters().forEach(parameter -> scan.visitVariable(parameter, null));
                        scan.scan(method.getBody(), null);
                        row("M", path, owner, name, signature, Long.toString(line), Long.toString(end),
                                method.getModifiers().getFlags().toString(), method.getBody().toString(),
                                summary, test ? junit : "", String.join("\n", scan.references));
                    }
                }
            }
        }
    }

    private static final class Calls extends TreeScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final DocTrees docs;
        private final String path;
        private final String pkg;
        private final String owner;
        private final String method;
        private final String junit;
        private final Map<String, String> imports;
        private final Set<String> staticImports;
        private final Set<String> references = new LinkedHashSet<>();
        private final Map<String, String> variables = new HashMap<>();

        Calls(CompilationUnitTree unit, DocTrees docs, String path, String pkg, String owner,
                String method, String junit, Map<String, String> imports, Set<String> staticImports) {
            this.unit = unit;
            this.docs = docs;
            this.path = path;
            this.pkg = pkg;
            this.owner = owner;
            this.method = method;
            this.junit = junit;
            this.imports = imports;
            this.staticImports = staticImports;
        }

        @Override
        public Void visitBlock(BlockTree block, Void unused) {
            var previous = new HashMap<>(variables);
            try {
                return super.visitBlock(block, unused);
            } finally {
                variables.clear();
                variables.putAll(previous);
            }
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree ref, Void unused) {
            String owner = receiverType(ref.getQualifierExpression());
            if (owner != null && ref.getMode() == MemberReferenceTree.ReferenceMode.INVOKE) {
                references.add(owner + "." + ref.getName());
            }
            return super.visitMemberReference(ref, unused);
        }

        @Override
        public Void visitVariable(VariableTree variable, Void unused) {
            String type = variable.getType() == null ? "var" : variable.getType().toString();
            if (type.equals("var") && variable.getInitializer() instanceof NewClassTree creation) {
                type = creation.getIdentifier().toString();
            }
            variables.put(variable.getName().toString(), typeName(type));
            return super.visitVariable(variable, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            String target = target(call);
            if (target != null) {
                references.add(target);
            }
            var assertion = assertion(call);
            if (!junit.isEmpty() && assertion != null) {
                List<? extends ExpressionTree> args = call.getArguments();
                if (assertion.equals("assertThrows") && args.size() >= 2
                        && args.get(0) instanceof MemberSelectTree exception
                        && exception.getIdentifier().contentEquals("class")
                        && args.get(1) instanceof LambdaExpressionTree lambda
                        && lambda.getBody() instanceof ExpressionTree expression) {
                    String qualifier = exception.getExpression().toString();
                    probe(call, expression, "throws " + imports.getOrDefault(qualifier, qualifier));
                } else if (assertion.equals("assertEquals") && args.size() == 2) {
                    probe(call, args.get(0), args.get(1));
                    probe(call, args.get(1), args.get(0));
                } else if (args.size() == 1 && !assertion.equals("assertThrows")) {
                    probe(call, args.get(0), switch (assertion) {
                        case "assertTrue" -> "true";
                        case "assertFalse" -> "false";
                        default -> "null";
                    });
                }
            }
            return super.visitMethodInvocation(call, unused);
        }

        private String assertion(MethodInvocationTree call) {
            String select = call.getMethodSelect().toString();
            int dot = select.lastIndexOf('.');
            String name = select.substring(dot + 1);
            if (!ASSERTIONS.contains(name)) {
                return null;
            }
            if (dot >= 0) {
                String qualifier = select.substring(0, dot);
                return JUNIT.contains(imports.getOrDefault(qualifier, qualifier)) ? name : null;
            }
            return JUNIT.stream().anyMatch(api -> staticImports.contains(api + "." + name)
                    || staticImports.contains(api + ".*")) ? name : null;
        }

        private void probe(MethodInvocationTree assertion, ExpressionTree expression, ExpressionTree expected) {
            if (expected instanceof LiteralTree || expected instanceof UnaryTree && literal(expected)) {
                probe(assertion, expression, expected.toString());
            }
        }

        private void probe(MethodInvocationTree assertion, ExpressionTree expression, String expected) {
            if (!(expression instanceof MethodInvocationTree call)
                    || !(call.getMethodSelect() instanceof MemberSelectTree member)
                    || !call.getArguments().stream().allMatch(Inspect::literal)) {
                return;
            }
            String receiver = "";
            ExpressionTree expressionTree = member.getExpression();
            if (expressionTree instanceof NewClassTree creation) {
                if (creation.getClassBody() != null || creation.getEnclosingExpression() != null
                        || !creation.getArguments().stream().allMatch(Inspect::literal)) {
                    return;
                }
                receiver = "new " + typeName(creation.getIdentifier().toString()) + "("
                        + String.join(", ", creation.getArguments().stream().map(Tree::toString).toList()) + ")";
            } else if (variables.containsKey(expressionTree.toString()) || typeName(expressionTree.toString()) == null) {
                return;
            }
            String operation = target(call);
            if (operation != null) {
                var arguments = call.getArguments().stream().map(Tree::toString).toList();
                row("P", path, pkg, owner, method, Long.toString(line(unit, docs, assertion)),
                        junit, operation, expected, String.join("\u001f", arguments), receiver);
            }
        }

        private String target(MethodInvocationTree call) {
            if (call.getMethodSelect() instanceof IdentifierTree identifier) {
                String name = identifier.getName().toString();
                var imported = staticImports.stream()
                        .filter(entry -> entry.endsWith("." + name)).findFirst();
                return imported.orElse(owner + "." + name);
            }
            if (call.getMethodSelect() instanceof MemberSelectTree member) {
                String resolved = receiverType(member.getExpression());
                return resolved == null ? null : resolved + "." + member.getIdentifier();
            }
            return null;
        }

        private String receiverType(ExpressionTree expression) {
            if (expression instanceof NewClassTree creation) {
                return typeName(creation.getIdentifier().toString());
            }
            String qualifier = expression.toString();
            if (qualifier.equals("this")) {
                return owner;
            }
            qualifier = qualifier.startsWith("this.") ? qualifier.substring(5) : qualifier;
            return variables.containsKey(qualifier) ? variables.get(qualifier) : typeName(qualifier);
        }

        private String typeName(String qualifier) {
            int parameters = qualifier.indexOf('<');
            if (parameters >= 0 && qualifier.endsWith(">")) {
                qualifier = qualifier.substring(0, parameters);
            }
            if (!qualifier.matches("(?:[a-zA-Z_$][a-zA-Z_$0-9]*\\.)*[A-Z][a-zA-Z_$0-9]*")) {
                return null;
            }
            return imports.getOrDefault(qualifier, qualifier.contains(".") ? qualifier : qualify(pkg, qualifier));
        }
    }

    private static boolean literal(ExpressionTree expression) {
        if (expression instanceof LiteralTree) {
            return true;
        }
        if (expression instanceof UnaryTree unary) {
            return (unary.getKind() == Tree.Kind.UNARY_MINUS || unary.getKind() == Tree.Kind.UNARY_PLUS)
                    && unary.getExpression() instanceof LiteralTree;
        }
        if (expression instanceof NewArrayTree array && array.getType() != null) {
            return array.getType().toString().matches("String|java\\.lang\\.String|boolean|byte|short|int|long|char|float|double")
                    && array.getDimensions().stream().allMatch(
                            d -> d instanceof LiteralTree size && size.getValue() instanceof Integer n && n >= 0 && n <= 64)
                    && (array.getInitializers() == null || array.getInitializers().stream().allMatch(Inspect::literal));
        }
        return false;
    }

    private static String qualify(String pkg, String name) {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    private static long line(CompilationUnitTree unit, DocTrees docs, Tree tree) {
        return unit.getLineMap().getLineNumber(docs.getSourcePositions().getStartPosition(unit, tree));
    }

    private static void row(String tag, String... fields) {
        var encoded = new ArrayList<String>();
        encoded.add(tag);
        for (String field : fields) {
            encoded.add(Base64.getEncoder().encodeToString(field.getBytes(StandardCharsets.UTF_8)));
        }
        System.out.println(String.join("\t", encoded));
    }
}
