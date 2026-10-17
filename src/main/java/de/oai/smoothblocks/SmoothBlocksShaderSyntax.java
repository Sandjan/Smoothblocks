package de.oai.smoothblocks;

/** Masks comments and quoted text without shifting offsets used to patch the original GLSL source. */
final class SmoothBlocksShaderSyntax {
    private SmoothBlocksShaderSyntax() {}

    static String codeOnly(String source) {
        char[] code = source.toCharArray();
        int cursor = 0;
        while (cursor < code.length) {
            char current = source.charAt(cursor);
            char next = cursor + 1 < code.length ? source.charAt(cursor + 1) : '\0';
            if (current == '/' && next == '/') {
                cursor = maskLine(source, code, cursor);
            } else if (current == '/' && next == '*') {
                cursor = maskBlock(source, code, cursor);
            } else if (current == '"' || current == '\'') {
                cursor = maskQuoted(source, code, cursor, current);
            } else {
                cursor++;
            }
        }
        return new String(code);
    }

    private static int maskLine(String source, char[] code, int cursor) {
        while (cursor < code.length && source.charAt(cursor) != '\n') blank(code, cursor++);
        return cursor;
    }

    private static int maskBlock(String source, char[] code, int cursor) {
        blank(code, cursor++);
        blank(code, cursor++);
        while (cursor < code.length) {
            boolean closing = source.charAt(cursor) == '*'
                    && cursor + 1 < code.length && source.charAt(cursor + 1) == '/';
            blank(code, cursor++);
            if (closing) {
                blank(code, cursor++);
                break;
            }
        }
        return cursor;
    }

    private static int maskQuoted(String source, char[] code, int cursor, char quote) {
        blank(code, cursor++);
        while (cursor < code.length) {
            char current = source.charAt(cursor);
            blank(code, cursor++);
            if (current == quote) break;
            if (current == '\\' && cursor < code.length) blank(code, cursor++);
        }
        return cursor;
    }

    private static void blank(char[] code, int index) {
        if (code[index] != '\n' && code[index] != '\r') code[index] = ' ';
    }

    static int closingBrace(String code, int opening) {
        int depth = 0;
        for (int cursor = opening; cursor < code.length(); cursor++) {
            if (code.charAt(cursor) == '{') depth++;
            else if (code.charAt(cursor) == '}' && --depth == 0) return cursor + 1;
        }
        return -1;
    }
}
