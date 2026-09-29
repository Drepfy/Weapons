package io.github.drepfy.vigil.compat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal line-based editor for block-style YAML files written by other software
 * (Paper's configuration). Only the lines that are changed are touched, so comments,
 * formatting and every other setting stay exactly as they were.
 */
final class YamlLineEditor {

    private static final Pattern KEY = Pattern.compile("^(\\s*)([A-Za-z0-9_.\\-]+|'[^']*'|\"[^\"]*\"):(\\s.*|)$");

    private record Key(int indent, String name) {
    }

    private final List<String> lines;

    YamlLineEditor(List<String> lines) {
        this.lines = new ArrayList<>(lines);
    }

    List<String> lines() {
        return lines;
    }

    /** Line index of the key at a dotted path such as {@code anticheat.anti-xray.enabled}, or -1. */
    int find(String path) {
        String[] wanted = path.split("\\.");
        Deque<Key> stack = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            Matcher matcher = KEY.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            int indent = matcher.group(1).length();
            String name = unquote(matcher.group(2));
            while (!stack.isEmpty() && stack.peekLast().indent() >= indent) {
                stack.removeLast();
            }
            stack.addLast(new Key(indent, name));
            if (stack.size() == wanted.length && matches(stack, wanted)) {
                return i;
            }
        }
        return -1;
    }

    /** The scalar value on a key line, without trailing comments or quotes. */
    String value(int index) {
        Matcher matcher = KEY.matcher(lines.get(index));
        if (!matcher.matches()) {
            return "";
        }
        String value = matcher.group(3).trim();
        int comment = value.indexOf(" #");
        if (comment >= 0) {
            value = value.substring(0, comment).trim();
        }
        return unquote(value);
    }

    void setScalar(int index, String value) {
        Matcher matcher = KEY.matcher(lines.get(index));
        if (matcher.matches()) {
            lines.set(index, matcher.group(1) + matcher.group(2) + ": " + value);
        }
    }

    /** Replaces the list under a key (block or inline style) with a block-style list. */
    void setList(int index, List<String> items) {
        Matcher matcher = KEY.matcher(lines.get(index));
        if (!matcher.matches()) {
            return;
        }
        String indent = matcher.group(1);
        int keyIndent = indent.length();
        int next = index + 1;
        while (next < lines.size()) {
            String line = lines.get(next);
            String trimmed = line.trim();
            int lineIndent = line.length() - line.stripLeading().length();
            boolean item = trimmed.startsWith("-") && lineIndent >= keyIndent;
            if (trimmed.isEmpty() || !(item || lineIndent > keyIndent)) {
                break;
            }
            lines.remove(next);
        }
        lines.set(index, indent + matcher.group(2) + ":");
        List<String> inserted = new ArrayList<>();
        for (String entry : items) {
            inserted.add(indent + "- " + entry);
        }
        lines.addAll(index + 1, inserted);
    }

    private static boolean matches(Deque<Key> stack, String[] wanted) {
        int i = 0;
        for (Key key : stack) {
            if (!key.name().equals(wanted[i++])) {
                return false;
            }
        }
        return true;
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && ((text.startsWith("'") && text.endsWith("'"))
                || (text.startsWith("\"") && text.endsWith("\"")))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
