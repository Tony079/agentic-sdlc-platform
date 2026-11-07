package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Dependency coordinates from Maven and Gradle build files (TDD 14.6). Text only: no build is run, no plugin is
 * resolved. pom.xml is read with a hardened StAX parser (no DTD, no external entities, no entity expansion).
 */
final class BuildFileParser {

    record Dependency(String filePath, String buildTool, String groupId, String artifactId, String version,
                      String scope) {
    }

    private static final Pattern GRADLE = Pattern.compile(
            "\\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor"
                    + "|developmentOnly|kapt)\\s*\\(?\\s*[\"']([^:\"'\\s]{1,300}):([^:\"'\\s]{1,300})(?::([^\"'\\s]{1,200}))?[\"']");
    private static final int MAX_DEPENDENCIES = 2_000;

    private BuildFileParser() {
    }

    static List<Dependency> maven(String path, byte[] content) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        List<Dependency> out = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        String group = null;
        String artifact = null;
        String version = null;
        String scope = null;
        StringBuilder text = new StringBuilder();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(content));
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) {
                        return List.of();
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        stack.push(reader.getLocalName());
                        text.setLength(0);
                        if (inDependency(stack)) {
                            group = null;
                            artifact = null;
                            version = null;
                            scope = null;
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                        if (text.length() < 400) {
                            text.append(reader.getText());
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String name = stack.pop();
                        if (!stack.isEmpty() && stack.peek().equals("dependency") && isDependencyPath(stack)) {
                            String value = text.toString().strip();
                            switch (name) {
                                case "groupId" -> group = value;
                                case "artifactId" -> artifact = value;
                                case "version" -> version = value;
                                case "scope" -> scope = value;
                                default -> { }
                            }
                        } else if (name.equals("dependency") && isDependencyList(stack) && artifact != null
                                && !artifact.isEmpty() && out.size() < MAX_DEPENDENCIES) {
                            out.add(new Dependency(path, "MAVEN", limit(group, 300), limit(artifact, 300),
                                    limit(version, 200), limit(scope, 100)));
                        }
                        text.setLength(0);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException | RuntimeException e) {
            return out;
        }
        return out;
    }

    /** Stack (innermost first) is [dependency, dependencies, project] or [.., dependencyManagement, project]. */
    private static boolean inDependency(Deque<String> stack) {
        return stack.peek().equals("dependency") && isDependencyList(new ArrayDeque<>(stack) {
            {
                pop();
            }
        });
    }

    private static boolean isDependencyPath(Deque<String> stack) {
        Deque<String> copy = new ArrayDeque<>(stack);
        copy.pop();
        return isDependencyList(copy);
    }

    private static boolean isDependencyList(Deque<String> stack) {
        List<String> path = new ArrayList<>(stack);
        if (path.size() == 2) {
            return path.get(0).equals("dependencies") && path.get(1).equals("project");
        }
        return path.size() == 3 && path.get(0).equals("dependencies") && path.get(1).equals("dependencyManagement")
                && path.get(2).equals("project");
    }

    static List<Dependency> gradle(String path, byte[] content) {
        List<Dependency> out = new ArrayList<>();
        Matcher matcher = GRADLE.matcher(new String(content, StandardCharsets.UTF_8));
        while (matcher.find() && out.size() < MAX_DEPENDENCIES) {
            out.add(new Dependency(path, "GRADLE", matcher.group(1), matcher.group(2), matcher.group(3), null));
        }
        return out;
    }

    private static String limit(String value, int max) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
