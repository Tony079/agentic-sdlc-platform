package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the agents' length and count limits by shortening instead of rejecting a whole answer for one over-long
 * field (a rejected answer costs a repair call and, twice in a row, fails the analysis). Text is cut at a word
 * boundary and marked with an ellipsis; lists keep their first entries. Applied as the model output is parsed, so
 * the Bean Validation limits still hold for anything stored.
 */
final class Lenient {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Lenient() {
    }

    static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = max - 1;
        int space = text.lastIndexOf(' ', end);
        if (space > max * 3 / 4) {
            end = space;
        }
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + "…";
    }

    static <T> List<T> first(List<T> list, int max) {
        return list == null || list.size() <= max ? list : List.copyOf(list.subList(0, max));
    }

    static List<String> clipAll(List<String> list, int maxEntries, int maxLength) {
        if (list == null) {
            return null;
        }
        return first(list, maxEntries).stream().map(s -> clip(s, maxLength)).toList();
    }

    /**
     * A document version id must be a UUID. The model sometimes writes the file name or a label around it: the UUID is
     * taken from the text when present, otherwise the id is dropped (the citation then fails verification, which
     * marks only that statement, instead of failing the answer).
     */
    static String uuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = UUID.matcher(value);
        return m.find() ? m.group().toLowerCase(java.util.Locale.ROOT) : null;
    }
}
