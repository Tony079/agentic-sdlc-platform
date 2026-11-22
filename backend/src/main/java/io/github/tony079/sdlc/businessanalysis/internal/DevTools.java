package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.tony079.sdlc.modelgateway.api.ModelTool;
import io.github.tony079.sdlc.sandbox.api.Devbox;

/**
 * GD-36: the Developer agent's tools. Unlike the read-only analysis tools, these act on one story's sandbox workspace
 * (a copy of the client's repository in the devbox), never on Garuda's data or the network: they read and write files
 * there and run allowlisted build and test programs. Arguments come from the model and are untrusted; the devbox checks
 * paths and commands and its refusals are returned to the model as fixed codes.
 */
final class DevTools {

    private DevTools() {
    }

    static List<ModelTool> forWorkspace(Devbox devbox, String workspaceId) {
        return List.of(
                tool(devbox, workspaceId, "list_files", "List directories and files. Arguments: path (relative, \"\" "
                        + "for the repository root), depth (1 to 4).", "list", 12 * 1024, List.of("path", "depth"),
                        a -> Map.of("path", text(a, "path"), "depth", number(a, "depth", 2))),
                tool(devbox, workspaceId, "read_file", "Read part of a text file. Arguments: path, start_line "
                        + "(default 1), max_lines (default 400, at most 2000).", "read", 32 * 1024,
                        List.of("path", "start_line", "max_lines"),
                        a -> Map.of("path", text(a, "path"), "startLine", number(a, "start_line", 1),
                                "maxLines", number(a, "max_lines", 400))),
                tool(devbox, workspaceId, "search", "Find lines that contain a text. Arguments: pattern, path "
                        + "(default the root), regex (\"true\" for a regular expression).", "search", 16 * 1024,
                        List.of("pattern", "path", "regex"),
                        a -> Map.of("pattern", text(a, "pattern"), "path", text(a, "path"),
                                "regex", "true".equalsIgnoreCase(text(a, "regex")))),
                tool(devbox, workspaceId, "write_file", "Create a new file, or replace a whole small file. Arguments: path, "
                        + "content. To change part of an existing file use edit_file instead.",
                        "write", 1024, List.of("path", "content"),
                        a -> Map.of("path", text(a, "path"), "content", text(a, "content"))),
                tool(devbox, workspaceId, "edit_file", "Change part of an existing file: replace old_text, which must occur "
                        + "exactly once in the file (copy it exactly, with its indentation; include enough lines to make it "
                        + "unique), with new_text. Arguments: path, old_text, new_text (may be empty to remove the text).",
                        "edit", 1024, List.of("path", "old_text", "new_text"),
                        a -> Map.of("path", text(a, "path"), "old", text(a, "old_text"), "new", text(a, "new_text"))),
                tool(devbox, workspaceId, "delete_file", "Delete a file. Arguments: path.", "delete", 1024,
                        List.of("path"), a -> Map.of("path", text(a, "path"))),
                tool(devbox, workspaceId, "run_command", "Run one program with its arguments in the repository, "
                        + "without a shell. Arguments: command (for example \"mvn -q -B test\" or \"npm test\"), "
                        + "cwd (optional, relative directory), timeout_seconds (optional, at most 900).", "exec",
                        16 * 1024, List.of("command", "cwd", "timeout_seconds"), a -> {
                            Map<String, Object> args = new LinkedHashMap<>();
                            args.put("argv", split(text(a, "command")));
                            args.put("cwd", text(a, "cwd"));
                            args.put("timeoutSeconds", number(a, "timeout_seconds", 600));
                            return args;
                        }));
    }

    private interface Mapper {
        Map<String, Object> map(Map<String, String> arguments) throws ModelTool.Refused;
    }

    private static ModelTool tool(Devbox devbox, String workspaceId, String name, String description, String action,
                                  int maxBytes, List<String> parameters, Mapper mapper) {
        return new ModelTool() {
            @Override
            public List<String> parameters() {
                return parameters;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public int maxResultBytes() {
                return maxBytes;
            }

            @Override
            public String invoke(Map<String, String> arguments) throws Refused {
                try {
                    String result = devbox.action(workspaceId, action, mapper.map(arguments));
                    // The devbox answers a refusal (a path outside the repository, a program not allowed, an edit
                    // whose text is not found) as {"code": ...}: say what it means.
                    java.util.regex.Matcher refused = REFUSAL.matcher(result);
                    if (refused.matches()) {
                        throw new Refused(refusal("DEVBOX_" + refused.group(1)));
                    }
                    return result;
                } catch (Devbox.DevboxException e) {
                    throw new Refused(refusal(e.code()));
                }
            }
        };
    }

    private static final java.util.regex.Pattern REFUSAL = java.util.regex.Pattern.compile(
            "^\\{\"code\":\"([A-Z_]{1,40})\"\\}$");

    /** What a devbox refusal means, for the model (fixed text). */
    static String refusal(String code) {
        return switch (code) {
            case "DEVBOX_COMMAND_NOT_ALLOWED" -> "that program is not allowed (there is no shell; see the allowed "
                    + "programs). To change a file use edit_file or write_file, not a command";
            case "DEVBOX_COMMAND_INVALID" -> "the command is empty or too long";
            case "DEVBOX_EDIT_TEXT_NOT_FOUND" -> "old_text does not occur in the file: read the file again and copy the "
                    + "text exactly, with its indentation";
            case "DEVBOX_EDIT_TEXT_NOT_UNIQUE" -> "old_text occurs more than once in the file: include more surrounding "
                    + "lines so it is unique";
            case "DEVBOX_EDIT_INVALID" -> "old_text must be non-empty, and both texts at most 1 MB";
            case "DEVBOX_CONTENT_INVALID" -> "the content is missing or larger than 1 MB";
            case "DEVBOX_PATH_INVALID" -> "the path is outside the repository or not allowed";
            case "DEVBOX_PATH_NOT_FOUND" -> "no such file or directory";
            case "DEVBOX_NOT_A_FILE" -> "that path is a directory, not a file";
            case "DEVBOX_PATTERN_INVALID" -> "the search pattern must be 1 to 200 characters";
            default -> "the workspace refused it (" + code + ")";
        };
    }

    private static String text(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        return value == null ? "" : value;
    }

    private static int number(Map<String, String> arguments, String name, int fallback) throws ModelTool.Refused {
        String value = arguments.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new ModelTool.Refused(name + " must be a whole number");
        }
    }

    /** Splits a command line into arguments: whitespace separates, single or double quotes group. No shell. */
    static List<String> split(String command) throws ModelTool.Refused {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (char c : command.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (quote != 0) {
            throw new ModelTool.Refused("unbalanced quotes in command");
        }
        if (inToken) {
            out.add(current.toString());
        }
        if (out.isEmpty() || out.size() > 40) {
            throw new ModelTool.Refused("command must have 1 to 40 arguments");
        }
        return out;
    }
}
