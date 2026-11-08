package io.github.tony079.sdlc.securityscan.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.securityscan.api.SecurityScanProvider;

/**
 * A small, real secret detector shipped with the platform (provider "builtin-secrets"). It reads text files of the
 * snapshot through the static target only, matches well-known credential formats line by line, and reports the path,
 * line and a masked excerpt. It is not a replacement for a dedicated secret-scanning product; its rule set is listed
 * here and in the runbook so its coverage is explicit.
 */
class BuiltinSecretScanner implements SecurityScanProvider {

    private record Rule(String id, Severity severity, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("private-key", Severity.CRITICAL, Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----")),
            new Rule("aws-access-key-id", Severity.HIGH, Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("github-token", Severity.HIGH, Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,255}\\b")),
            new Rule("slack-token", Severity.HIGH, Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,72}\\b")),
            new Rule("google-api-key", Severity.HIGH, Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
            new Rule("generic-password-assignment", Severity.MEDIUM, Pattern.compile(
                    "(?i)\\b(password|passwd|secret|api[_-]?key|token)\\s*[:=]\\s*[\"']([^\"'\\s$%{}<>]{8,})[\"']")));

    private static final long MAX_FINDINGS = 1000;

    @Override
    public String name() {
        return "builtin-secrets";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Kind kind() {
        return Kind.SECRET;
    }

    @Override
    public Result scan(Target target) {
        List<Finding> findings = new ArrayList<>();
        try {
            int files = target.forEachFile(BuiltinSecretScanner::textual, (path, content) -> {
                String text = new String(content, StandardCharsets.UTF_8);
                String[] lines = text.split("\n", -1);
                for (int i = 0; i < lines.length && findings.size() < MAX_FINDINGS; i++) {
                    for (Rule rule : RULES) {
                        Matcher m = rule.pattern().matcher(lines[i]);
                        if (m.find()) {
                            String value = m.groupCount() >= 2 && m.group(2) != null ? m.group(2) : m.group();
                            findings.add(new Finding(rule.severity(), rule.id(), "SECRET", path, i + 1, mask(value),
                                    sha256(value)));
                        }
                    }
                }
            });
            return Result.completed(files, findings);
        } catch (IOException e) {
            return Result.failed("SNAPSHOT_UNREADABLE", 0);
        }
    }

    static boolean textual(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return !(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".class") || lower.endsWith(".pdf")
                || lower.endsWith(".ico") || lower.endsWith(".woff") || lower.endsWith(".woff2"));
    }

    /** First four characters, then the length: enough to locate, never enough to use. */
    static String mask(String value) {
        String head = value.length() <= 4 ? "" : value.substring(0, 4);
        return head + "…(" + value.length() + " chars)";
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
