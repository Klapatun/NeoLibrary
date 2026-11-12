package com.example.mylibrary.meta;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Writes metadata back into book files. Currently two formats can be safely edited
 * in place: <b>EPUB</b> (the OPF package document) and <b>FB2</b> (the XML
 * description block). All other formats either store catalog metadata in SQLite or,
 * when the user wants a persistent change, can be handled by exposing the metadata
 * through the reader's own library once the book is opened.
 *
 * <p>Both writers use a non-destructive "rebuild" strategy: the original file is
 * left untouched until the new version is fully written, and on any error the
 * original file is preserved (best effort).</p>
 */
public final class MetaWriter {

    private MetaWriter() {}

    /**
     * Writes the given metadata into the file according to its format.
     *
     * @return true on success.
     */
    public static boolean write(File file, MetaData md) {
        String format = com.example.mylibrary.scan.Formats.formatOf(file.getName());
        if (format == null) format = "";
        try {
            if (format.equals("EPUB")) return writeEpub(file, md);
            if (format.equals("FB2")) return writeFb2(file, md);
        } catch (Exception e) {
            return false;
        }
        return false; // other formats: not supported for in-file editing
    }

    // -------------------------------------------------------------------
    // EPUB
    // -------------------------------------------------------------------

    /**
     * Rebuilds the EPUB ZIP, replacing the OPF package document with one whose
     * Dublin-Core metadata elements carry the new values. A null string leaves
     * that element alone. A missing non-empty element is inserted into the
     * metadata section. An unchanged OPF is not repacked.
     */
    private static boolean writeEpub(File file, MetaData md) throws Exception {
        String opfPath = findOpfPath(file);
        if (opfPath == null) return false;

        byte[] raw = readZipEntryBytes(file, opfPath);
        if (raw == null) return false;
        String opf = XmlText.decode(raw);
        if (opf == null) return false;
        String original = opf;

        opf = setDcElement(opf, "title", md.title);
        opf = setDcElement(opf, "creator", md.author);
        opf = setDcElement(opf, "publisher", md.publisher);
        opf = setDcElement(opf, "description", md.description);
        if (md.language != null && md.language.length() > 0) {
            opf = setDcElement(opf, "language", md.language);
        }
        if (md.genres != null) opf = writeDcSubjects(opf, md.genres);
        if (opf.equals(original)) return true;

        byte[] encoded = XmlText.encode(opf, raw);
        if (encoded == null) return false;

        // Write rebuilt archive to a temp file, then move over the original.
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        boolean ok = rebuildZip(file, tmp, opfPath, encoded);
        if (!ok) return false;

        File backup = new File(file.getParentFile(), file.getName() + ".bak");
        try {
            if (file.exists() && !file.renameTo(backup)) {
                // fallback: try copy
                copyFile(file, backup);
            }
            boolean moved = tmp.renameTo(file);
            if (!moved) {
                // fallback: copy tmp over original
                copyFile(tmp, file);
            }
            backup.delete();
            tmp.delete();
            return true;
        } catch (Exception e) {
            // try to restore backup
            if (backup.exists()) {
                file.delete();
                backup.renameTo(file);
            }
            tmp.delete();
            return false;
        }
    }

    /**
     * Replaces the text of an existing {@code <dc:X>} element.
     * Null leaves the document alone. An empty string clears an existing
     * element and does not insert one. A non-empty missing element is inserted
     * before {@code </metadata>} (or {@code </opf:metadata>}).
     */
    private static String setDcElement(String xml, String name, String value) {
        if (value == null) return xml;
        String escaped = escapeXml(value);
        String lower = xml.toLowerCase(Locale.US);
        String openTag = "<dc:" + name;
        int tagStart = lower.indexOf(openTag);
        if (tagStart >= 0) {
            // find end of the opening tag's '>'
            int contentStart = xml.indexOf('>', tagStart);
            if (contentStart >= 0) contentStart++;
            // closing tag is always </dc:name> (no attributes allowed on closing tags);
            // look for the exact sequence to avoid partial matches.
            String closeTag = "</dc:" + name + ">";
            int close = lower.indexOf(closeTag, contentStart);
            if (close >= 0) {
                // Keep the closing tag in place: replace only the text between the
                // opening tag and the closing tag (substring(close) re-emits the
                // closing tag itself).
                return xml.substring(0, contentStart) + escaped + xml.substring(close);
            }
        } else if (value.length() > 0) {
            // insert before </metadata> or, failing that, </opf:metadata>
            int metaClose = lower.indexOf("</metadata>");
            if (metaClose < 0) metaClose = lower.indexOf("</opf:metadata>");
            if (metaClose >= 0) {
                String newEl = "\n        <dc:" + name + ">" + escaped + "</dc:" + name + ">";
                return xml.substring(0, metaClose) + newEl + xml.substring(metaClose);
            }
        }
        return xml;
    }

    /** Copies every entry from src into dst, replacing entry {@code replaceName} with {@code newContent}. */
    private static boolean rebuildZip(File src, File dst, String replaceName, byte[] newContent) {
        try {
            ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)));
            ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(dst)));
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals(replaceName)) {
                    ZipEntry ne = new ZipEntry(e.getName());
                    out.putNextEntry(ne);
                    out.write(newContent);
                } else {
                    ZipEntry ne = new ZipEntry(e.getName());
                    out.putNextEntry(ne);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                out.closeEntry();
                in.closeEntry();
            }
            in.close();
            out.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readZipEntryBytes(File file, String entryName) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals(entryName)) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zip.read(buf)) > 0) baos.write(buf, 0, n);
                    return baos.toByteArray();
                }
            }
        } finally {
            zip.close();
        }
        return null;
    }

    private static String findOpfPath(File file) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equalsIgnoreCase("META-INF/container.xml")) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = zip.read(buf)) > 0) baos.write(buf, 0, n);
                    String xml = new String(baos.toByteArray(), "UTF-8");
                    int idx = xml.indexOf("full-path");
                    if (idx < 0) return null;
                    int s = xml.indexOf('"', idx);
                    int en = xml.indexOf('"', s + 1);
                    return xml.substring(s + 1, en);
                }
            }
        } finally {
            zip.close();
        }
        return null;
    }

    // -------------------------------------------------------------------
    // FB2
    // -------------------------------------------------------------------

    /**
     * Edits the FB2 XML in place by rewriting the description block elements. Because
     * FB2 is a plain (single, non-container) XML file, we read it fully, patch the
     * relevant elements, and write it back in the same charset. A null string field
     * is left untouched. An unchanged document is not rewritten.
     */
    private static boolean writeFb2(File file, MetaData md) throws Exception {
        byte[] raw = readBytes(file);
        if (raw == null) return false;
        String xml = XmlText.decode(raw);
        if (xml == null) return false;
        String original = xml;

        if (md.title != null) xml = replaceTitle(xml, md.title);
        // author parts. If only the combined author string is supplied (as from the
        // edit form), split it heuristically: last word -> last-name, the rest ->
        // first-name. A null author leaves the name parts alone. An empty author
        // clears them inside the first title-info author and does not create one.
        if (md.author != null) {
            String trimmed = md.author.trim();
            if (trimmed.length() == 0) {
                xml = clearNamesInFirstAuthor(xml);
            } else {
                if (md.firstName == null && md.lastName == null) {
                    int sp = trimmed.lastIndexOf(' ');
                    if (sp > 0) {
                        md.firstName = trimmed.substring(0, sp).trim();
                        md.lastName = trimmed.substring(sp + 1).trim();
                    } else {
                        md.lastName = trimmed;
                    }
                }
                if (md.firstName != null) xml = replaceElementContent(xml, "first-name", md.firstName);
                if (md.middleName != null) xml = replaceElementContent(xml, "middle-name", md.middleName);
                if (md.lastName != null) xml = replaceElementContent(xml, "last-name", md.lastName);
            }
        }
        if (md.publisher != null) xml = replaceElementContent(xml, "publisher", md.publisher);
        if (md.language != null && md.language.length() > 0) {
            xml = replaceElementContent(xml, "lang", md.language);
        }
        if (md.genres != null) xml = writeFb2Genres(xml, md.genres);
        if (xml.equals(original)) return true;

        byte[] encoded = XmlText.encode(xml, raw);
        if (encoded == null) return false;

        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        writeBytes(tmp, encoded);
        File backup = new File(file.getParentFile(), file.getName() + ".bak");
        try {
            if (file.exists() && !file.renameTo(backup)) copyFile(file, backup);
            boolean moved = tmp.renameTo(file);
            if (!moved) copyFile(tmp, file);
            backup.delete();
            tmp.delete();
            return true;
        } catch (Exception e) {
            if (backup.exists()) {
                file.delete();
                backup.renameTo(file);
            }
            tmp.delete();
            return false;
        }
    }

    /**
     * Writes the full genre list into FB2 title-info.
     * An empty list removes every exact {@code genre} element. A non-empty list
     * leaves the file unchanged when there is no {@code </title-info>} to insert before.
     */
    private static String writeFb2Genres(String xml, List<String> genres) {
        if (!genres.isEmpty() && xml.toLowerCase(Locale.US).indexOf("</title-info>") < 0) {
            return xml;
        }
        xml = removeEveryExactElement(xml, "genre");
        if (genres.isEmpty()) return xml;
        int close = xml.toLowerCase(Locale.US).indexOf("</title-info>");
        if (close < 0) return xml;
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < genres.size(); i++) {
            String g = genres.get(i);
            if (g == null) continue;
            block.append("<genre>").append(escapeXml(g)).append("</genre>");
        }
        return xml.substring(0, close) + block.toString() + xml.substring(close);
    }

    /**
     * Replaces every {@code dc:subject} in the OPF metadata block.
     * An empty list removes every subject. A non-empty list leaves them unchanged
     * when neither {@code </metadata>} nor {@code </opf:metadata>} is present.
     */
    private static String writeDcSubjects(String opf, List<String> genres) {
        String lowerProbe = opf.toLowerCase(Locale.US);
        if (!genres.isEmpty()
                && lowerProbe.indexOf("</metadata>") < 0
                && lowerProbe.indexOf("</opf:metadata>") < 0) {
            return opf;
        }
        opf = removeEveryDcSubject(opf);
        if (genres.isEmpty()) return opf;
        String lower = opf.toLowerCase(Locale.US);
        int close = lower.indexOf("</metadata>");
        if (close < 0) close = lower.indexOf("</opf:metadata>");
        if (close < 0) return opf;
        String indent = subjectInsertIndent(opf, close);
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < genres.size(); i++) {
            String g = genres.get(i);
            if (g == null) continue;
            block.append(indent).append("<dc:subject>")
                    .append(escapeXml(g)).append("</dc:subject>\n");
        }
        return opf.substring(0, close) + block.toString() + opf.substring(close);
    }

  private static String subjectInsertIndent(String opf, int closeIndex) {
        int lineStart = opf.lastIndexOf('\n', closeIndex);
        if (lineStart < 0) return "    ";
        String line = opf.substring(lineStart + 1, closeIndex);
        int nonWs = 0;
        while (nonWs < line.length()) {
            char ch = line.charAt(nonWs);
            if (ch != ' ' && ch != '\t') break;
            nonWs++;
        }
        if (nonWs > 0) return line.substring(0, nonWs);
        return "    ";
    }

    private static String removeEveryDcSubject(String xml) {
        Pattern element = Pattern.compile("(?is)<dc:subject(?:\\s[^>]*)?>.*?</dc:subject>");
        Matcher m = element.matcher(xml);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, "");
        m.appendTail(sb);
        return sb.toString();
    }

    /** Removes every element whose name is exactly {@code tag}. */
    private static String removeEveryExactElement(String xml, String tag) {
        Pattern element = Pattern.compile("(?is)<" + Pattern.quote(tag) + "(?:\\s[^>]*)?>.*?</"
                + Pattern.quote(tag) + ">");
        Matcher m = element.matcher(xml);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, "");
        m.appendTail(sb);
        return sb.toString();
    }

    /** True when {@code xml} contains an element whose name is exactly {@code tag}. */
    private static boolean hasExactElement(String xml, String tag) {
        Pattern open = Pattern.compile("(?is)<" + Pattern.quote(tag) + "(?:\\s[^>]*)?>");
        return open.matcher(xml).find();
    }

    /** Drops the first {@code <tag>...</tag>} element. No-op when it is absent. */
    private static String removeFirstElement(String xml, String tag) {
        Pattern element = Pattern.compile("(?is)<" + Pattern.quote(tag) + "(?:\\s[^>]*)?>.*?</"
                + Pattern.quote(tag) + ">");
        Matcher m = element.matcher(xml);
        if (!m.find()) return xml;
        return xml.substring(0, m.start()) + xml.substring(m.end());
    }

    /** Inserts {@code <tag>value</tag>} immediately before {@code </parent>}. */
    private static String insertElementBefore(String xml, String parent, String tag, String value) {
        String lower = xml.toLowerCase(Locale.US);
        int close = lower.indexOf("</" + parent + ">");
        if (close < 0) return xml;
        String element = "<" + tag + ">" + escapeXml(value) + "</" + tag + ">";
        return xml.substring(0, close) + element + xml.substring(close);
    }

    /**
     * Writes {@code value} into an existing {@code book-title} (keeping that tag's
     * prefix). When there is no book-title, writes the exact {@code title} element
     * and does not touch {@code title-info}. Does not insert a title element and
     * does not rename one tag to the other.
     */
    private static String replaceTitle(String xml, String value) {
        int book = indexOfOpenTag(xml, "book-title", 0);
        if (book >= 0) {
            String replaced = replaceOpenElement(xml, book, value);
            return replaced != null ? replaced : xml;
        }
        int title = indexOfOpenTag(xml, "title", 0);
        if (title >= 0) {
            String replaced = replaceOpenElement(xml, title, value);
            return replaced != null ? replaced : xml;
        }
        return xml;
    }

    /** Clears first/middle/last name text inside the first author of the first title-info. */
    private static String clearNamesInFirstAuthor(String xml) {
        int ti = indexOfOpenTag(xml, "title-info", 0);
        if (ti < 0 || isSelfClosing(xml, ti)) return xml;
        int tiGt = xml.indexOf('>', ti);
        if (tiGt < 0) return xml;
        int tiClose = indexOfCloseTag(xml, prefixOf(xml, ti), localOf(xml, ti), tiGt + 1);
        if (tiClose < 0) return xml;
        int author = indexOfOpenTag(xml, "author", tiGt + 1);
        if (author < 0 || author >= tiClose || isSelfClosing(xml, author)) return xml;
        int authorGt = xml.indexOf('>', author);
        if (authorGt < 0 || authorGt >= tiClose) return xml;
        int authorClose = indexOfCloseTag(xml, prefixOf(xml, author), localOf(xml, author), authorGt + 1);
        if (authorClose < 0 || authorClose > tiClose) return xml;
        String inner = xml.substring(authorGt + 1, authorClose);
        inner = clearFirstLocal(inner, "first-name");
        inner = clearFirstLocal(inner, "middle-name");
        inner = clearFirstLocal(inner, "last-name");
        return xml.substring(0, authorGt + 1) + inner + xml.substring(authorClose);
    }

    private static String clearFirstLocal(String xml, String local) {
        int open = indexOfOpenTag(xml, local, 0);
        if (open < 0) return xml;
        String replaced = replaceOpenElement(xml, open, "");
        return replaced != null ? replaced : xml;
    }

    /** Index of {@code '<'} of the first open tag whose local name is exactly {@code local}. */
    private static int indexOfOpenTag(String xml, String local, int from) {
        String want = local.toLowerCase(Locale.US);
        int i = from;
        int len = xml.length();
        while (i < len) {
            int lt = xml.indexOf('<', i);
            if (lt < 0 || lt + 1 >= len) return -1;
            char next = xml.charAt(lt + 1);
            if (next == '/' || next == '?' || next == '!' || next == '>') {
                i = lt + 1;
                continue;
            }
            if (localOf(xml, lt).toLowerCase(Locale.US).equals(want)) return lt;
            i = lt + 1;
        }
        return -1;
    }

    private static int nameEnd(String xml, int nameStart) {
        int j = nameStart;
        int len = xml.length();
        while (j < len) {
            char c = xml.charAt(j);
            if (c == ':') {
                j++;
                continue;
            }
            if (isXmlNameChar(c)) {
                j++;
                continue;
            }
            break;
        }
        return j;
    }

    private static boolean isXmlNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
    }

    /** Prefix including the colon, or empty when the tag has none. */
    private static String prefixOf(String xml, int openLt) {
        int nameStart = openLt + 1;
        int end = nameEnd(xml, nameStart);
        int colon = -1;
        for (int i = nameStart; i < end; i++) {
            if (xml.charAt(i) == ':') colon = i;
        }
        if (colon < 0) return "";
        return xml.substring(nameStart, colon + 1);
    }

    private static String localOf(String xml, int openLt) {
        int nameStart = openLt + 1;
        int end = nameEnd(xml, nameStart);
        int colon = -1;
        for (int i = nameStart; i < end; i++) {
            if (xml.charAt(i) == ':') colon = i;
        }
        return xml.substring(colon >= 0 ? colon + 1 : nameStart, end);
    }

    private static boolean isSelfClosing(String xml, int openLt) {
        int gt = xml.indexOf('>', openLt);
        return gt > openLt && xml.charAt(gt - 1) == '/';
    }

    private static int indexOfCloseTag(String xml, String prefix, String local, int from) {
        String needle = ("</" + prefix + local + ">").toLowerCase(Locale.US);
        String lower = xml.toLowerCase(Locale.US);
        return lower.indexOf(needle, from);
    }

    /** Replaces the text of the open tag at {@code open}. Null when it is not a pair. */
    private static String replaceOpenElement(String xml, int open, String value) {
        if (isSelfClosing(xml, open)) return null;
        int gt = xml.indexOf('>', open);
        if (gt < 0) return null;
        int close = indexOfCloseTag(xml, prefixOf(xml, open), localOf(xml, open), gt + 1);
        if (close < 0) return null;
        return xml.substring(0, gt + 1) + escapeXml(value) + xml.substring(close);
    }

    /** Replaces content of the first {@code <tag>...</tag>} occurrence, escaping value. */
    private static String replaceElementContent(String xml, String tag, String value) {
        if (value == null) return xml;
        // The opening tag must be the exact tag name followed by whitespace, '/' or
        // '>'. A bare [^>]* after the name would also match longer names that share
        // the prefix — e.g. <title-info> for tag "title" — and corrupt the parent
        // block instead of the target element.
        Pattern open = Pattern.compile("(?is)(<" + Pattern.quote(tag) + "(?:\\s[^>]*)?>)(.*?)(</" + Pattern.quote(tag) + ">)");
        Matcher m = open.matcher(xml);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(m.group(1)) + escapeXml(value)
                    + Matcher.quoteReplacement(m.group(3)));
        }
        return xml;
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private static byte[] readBytes(File f) {
        try {
            InputStream in = new BufferedInputStream(new FileInputStream(f));
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                return baos.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeBytes(File f, byte[] content) throws Exception {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(f));
        out.write(content);
        out.close();
    }

    private static void copyFile(File from, File to) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(from));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(to));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
