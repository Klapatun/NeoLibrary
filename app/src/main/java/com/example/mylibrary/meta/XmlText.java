package com.example.mylibrary.meta;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/**
 * Shared charset for FB2 and OPF bytes. One algorithm for both directions:
 * a BOM wins, otherwise the {@code encoding} attribute of an XML prologue
 * that starts in the first 512 bytes after the BOM, otherwise UTF-8.
 * A failure returns null — callers do not try a second charset. The
 * declaration text is never rewritten and the file is not converted to UTF-8.
 */
final class XmlText {

    private static final int PROLOGUE_WINDOW = 512;
    private static final int ENC_ABSENT = 0;
    private static final int ENC_VALUE = 1;
    private static final int ENC_BROKEN = 2;

    private XmlText() {}

    /** Decoded text, or null when the bytes are not that charset. */
    static String decode(byte[] raw) {
        Resolved resolved = resolve(raw);
        if (resolved == null) return null;
        Charset charset = charset(resolved.name);
        if (charset == null) return null;
        try {
            CharsetDecoder decoder = charset.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            int start = resolved.bomLength;
            CharBuffer out = decoder.decode(ByteBuffer.wrap(raw, start, raw.length - start));
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code text} in the charset of {@code raw}, with the original BOM
     * restored. Null when the charset is unknown or {@code text} does not fit.
     */
    static byte[] encode(String text, byte[] raw) {
        if (text == null) return null;
        Resolved resolved = resolve(raw);
        if (resolved == null) return null;
        Charset charset = charset(resolved.name);
        if (charset == null) return null;
        try {
            CharsetEncoder encoder = charset.newEncoder();
            encoder.onMalformedInput(CodingErrorAction.REPORT);
            encoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
            byte[] payload = new byte[encoded.remaining()];
            encoded.get(payload);
            if (resolved.bomLength == 0) return payload;
            byte[] out = new byte[resolved.bomLength + payload.length];
            System.arraycopy(raw, 0, out, 0, resolved.bomLength);
            System.arraycopy(payload, 0, out, resolved.bomLength, payload.length);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Null when a prologue names an encoding we cannot read. */
    private static Resolved resolve(byte[] raw) {
        if (raw == null) return null;
        if (raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            return new Resolved("UTF-8", 3);
        }
        if (raw.length >= 2
                && (raw[0] & 0xFF) == 0xFE
                && (raw[1] & 0xFF) == 0xFF) {
            return new Resolved("UTF-16BE", 2);
        }
        if (raw.length >= 2
                && (raw[0] & 0xFF) == 0xFF
                && (raw[1] & 0xFF) == 0xFE) {
            return new Resolved("UTF-16LE", 2);
        }
        EncAttr attr = encodingAttribute(raw, 0);
        if (attr.status == ENC_BROKEN) return null;
        if (attr.status == ENC_VALUE) return new Resolved(attr.name, 0);
        return new Resolved("UTF-8", 0);
    }

    /**
     * The prologue's encoding attribute. Absent when there is no prologue,
     * or a prologue with no attribute, inside the window. Broken when the
     * attribute is started but has no quoted ASCII name.
     */
    private static EncAttr encodingAttribute(byte[] raw, int start) {
        int from = start;
        int window = start + PROLOGUE_WINDOW;
        while (from < raw.length) {
            int decl = indexOfAscii(raw, from, raw.length, "<?xml");
            if (decl < 0 || decl >= window) return new EncAttr(ENC_ABSENT, null);
            int after = decl + 5;
            if (after >= raw.length) return new EncAttr(ENC_ABSENT, null);
            int mark = raw[after] & 0xFF;
            if (mark != ' ' && mark != '\t' && mark != '\n' && mark != '\r' && mark != '?') {
                from = after;
                continue;
            }
            int end = indexOfAscii(raw, decl, raw.length, "?>");
            if (end < 0) return new EncAttr(ENC_ABSENT, null);
            int enc = indexOfAscii(raw, decl, end, "encoding");
            if (enc < 0) return new EncAttr(ENC_ABSENT, null);
            int i = enc + "encoding".length();
            if (enc > decl && isAsciiNameChar(raw[enc - 1])) {
                return new EncAttr(ENC_BROKEN, null);
            }
            if (i < end && isAsciiNameChar(raw[i])) return new EncAttr(ENC_BROKEN, null);
            i = skipSpace(raw, i, end);
            if (i >= end || raw[i] != '=') return new EncAttr(ENC_BROKEN, null);
            i = skipSpace(raw, i + 1, end);
            if (i >= end) return new EncAttr(ENC_BROKEN, null);
            int quote = raw[i] & 0xFF;
            if (quote != '"' && quote != '\'') return new EncAttr(ENC_BROKEN, null);
            i++;
            int nameStart = i;
            while (i < end && (raw[i] & 0xFF) != quote) {
                int b = raw[i] & 0xFF;
                if (b < 0x20 || b >= 0x7F) return new EncAttr(ENC_BROKEN, null);
                i++;
            }
            if (i >= end || (raw[i] & 0xFF) != quote || i == nameStart) {
                return new EncAttr(ENC_BROKEN, null);
            }
            return new EncAttr(ENC_VALUE, ascii(raw, nameStart, i));
        }
        return new EncAttr(ENC_ABSENT, null);
    }

    private static int skipSpace(byte[] raw, int i, int end) {
        while (i < end) {
            int c = raw[i] & 0xFF;
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            i++;
        }
        return i;
    }

    private static boolean isAsciiNameChar(byte b) {
        int c = b & 0xFF;
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
    }

    private static String ascii(byte[] raw, int start, int end) {
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) sb.append((char) (raw[i] & 0xFF));
        return sb.toString();
    }

    private static int indexOfAscii(byte[] raw, int from, int to, String needle) {
        int n = needle.length();
        int last = to - n;
        if (from < 0) from = 0;
        for (int i = from; i <= last; i++) {
            boolean match = true;
            for (int j = 0; j < n; j++) {
                if (raw[i + j] != (byte) needle.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private static final class Resolved {
        final String name;
        final int bomLength;

        Resolved(String name, int bomLength) {
            this.name = name;
            this.bomLength = bomLength;
        }
    }

    private static final class EncAttr {
        final int status;
        final String name;

        EncAttr(int status, String name) {
            this.status = status;
            this.name = name;
        }
    }
}
