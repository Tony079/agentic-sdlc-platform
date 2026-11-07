package io.github.tony079.sdlc.sourcerepo.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

/**
 * Static Java symbol inventory (TDD 14.6) with JavaParser: types, methods, Spring stereotypes and request mappings.
 * Source is parsed only, never compiled, resolved or executed. Names are truncated to the column limits.
 */
final class JavaSymbolExtractor {

    record Symbol(String filePath, String kind, String packageName, String typeName, String memberName,
                  String stereotype, String httpMethod, String httpPath, int startLine, int endLine) {
    }

    private static final Map<String, String> STEREOTYPES = Map.of("RestController", "REST_CONTROLLER",
            "Controller", "CONTROLLER", "Service", "SERVICE", "Repository", "REPOSITORY", "Component", "COMPONENT",
            "Configuration", "CONFIGURATION", "Entity", "ENTITY");
    private static final Map<String, String> MAPPINGS = Map.of("GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "PatchMapping", "PATCH", "DeleteMapping", "DELETE", "RequestMapping", "ANY");

    private JavaSymbolExtractor() {
    }

    static List<Symbol> extract(String path, byte[] content) {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
        ParseResult<CompilationUnit> result = new JavaParser(configuration)
                .parse(new String(content, StandardCharsets.UTF_8));
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return List.of();
        }
        CompilationUnit unit = result.getResult().get();
        String pkg = unit.getPackageDeclaration().map(p -> limit(p.getNameAsString(), 500)).orElse(null);
        List<Symbol> out = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            visitType(path, pkg, type, null, out);
        }
        return out;
    }

    private static void visitType(String path, String pkg, TypeDeclaration<?> type, String outer, List<Symbol> out) {
        String name = limit(outer == null ? type.getNameAsString() : outer + "." + type.getNameAsString(), 500);
        String kind = type instanceof ClassOrInterfaceDeclaration c ? (c.isInterface() ? "INTERFACE" : "CLASS")
                : type instanceof EnumDeclaration ? "ENUM" : type instanceof RecordDeclaration ? "RECORD"
                : type instanceof AnnotationDeclaration ? "ANNOTATION" : "CLASS";
        String stereotype = stereotype(type);
        String basePath = mapping(type).map(m -> m[1]).orElse(null);
        out.add(new Symbol(path, kind, pkg, name, null, stereotype, null, basePath, begin(type), end(type)));
        for (var member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                visitType(path, pkg, nested, name, out);
            } else if (member instanceof MethodDeclaration || member instanceof ConstructorDeclaration) {
                CallableDeclaration<?> callable = (CallableDeclaration<?>) member;
                Optional<String[]> mapping = mapping(callable);
                String httpPath = mapping.map(m -> join(basePath, m[1])).orElse(null);
                out.add(new Symbol(path, member instanceof MethodDeclaration ? "METHOD" : "CONSTRUCTOR", pkg, name,
                        limit(callable.getNameAsString(), 500), null, mapping.map(m -> m[0]).orElse(null),
                        httpPath, begin(callable), end(callable)));
            }
        }
    }

    private static String stereotype(NodeWithAnnotations<?> node) {
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String value = STEREOTYPES.get(annotation.getName().getIdentifier());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** [httpMethod, path] of a Spring mapping annotation, if any. */
    private static Optional<String[]> mapping(NodeWithAnnotations<?> node) {
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String method = MAPPINGS.get(annotation.getName().getIdentifier());
            if (method != null) {
                return Optional.of(new String[] {method, limit(pathOf(annotation), 500)});
            }
        }
        return Optional.empty();
    }

    private static String pathOf(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return firstString(single.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    return firstString(pair.getValue());
                }
            }
        }
        return "";
    }

    private static String firstString(Expression expression) {
        if (expression instanceof StringLiteralExpr literal) {
            return literal.getValue();
        }
        if (expression instanceof ArrayInitializerExpr array && !array.getValues().isEmpty()) {
            return firstString(array.getValues().get(0));
        }
        return "";
    }

    private static String join(String base, String path) {
        if (base == null || base.isEmpty()) {
            return path;
        }
        if (path == null || path.isEmpty()) {
            return base;
        }
        return limit((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + (path.startsWith("/") ? path : "/" + path), 500);
    }

    private static int begin(Node node) {
        return node.getRange().map(r -> Math.max(1, r.begin.line)).orElse(1);
    }

    private static int end(Node node) {
        return node.getRange().map(r -> Math.max(begin(node), r.end.line)).orElse(begin(node));
    }

    static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
