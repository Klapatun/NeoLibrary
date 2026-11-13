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

        if (md.genres != null) {
            if (md.genres.isEmpty()) {
                opf = removeEveryDcSubject(opf);
            } else {
                String placed = placeEpubGenres(opf, md);
                if (placed == null) return false;
                opf = placed;
            }
        }
        opf = setDcElement(opf, "title", md.title);
        opf = setDcElement(opf, "creator", md.author);
        opf = setDcElement(opf, "publisher", md.publisher);
        opf = setDcElement(opf, "description", md.description);
        if (md.language != null && md.language.length() > 0) {
            opf = setDcElement(opf, "language", md.language);
        }
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

        if (md.genres != null) {
            if (md.genres.isEmpty()) {
                xml = removeEveryExactElement(xml, "genre");
            } else {
                String placed = placeFb2Genres(xml, md);
                if (placed == null) return false;
                xml = placed;
            }
        }
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

    private static final int TAG_ABSENT = 0;
    private static final int TAG_CLOSED = 1;
    private static final int TAG_SELF = 2;
    private static final int TAG_OPEN = 3;

    /**
     * Non-empty FB2 genres. Null when the list cannot be placed — the file
     * must stay byte for byte as it was.
     */
    private static String placeFb2Genres(String xml, MetaData md) {
        int titleKind = classifyTag(xml, "title-info");
        if (titleKind == TAG_CLOSED) {
            int open = openMatchingFirstClose(xml, "title-info");
            if (open < 0) return null;
            return replaceChildrenInFirst(xml, "title-info", prefixOf(xml, open), "genre", md.genres);
        }
        if (titleKind == TAG_SELF) {
            int open = indexOfSelfClosing(xml, "title-info", 0);
            if (open < 0) return null;
            return expandSelfClosing(xml, open, elementBlock(prefixOf(xml, open), "genre", md.genres));
        }
        if (titleKind == TAG_OPEN) return null;

        int descKind = classifyTag(xml, "description");
        if (descKind == TAG_CLOSED) {
            int open = openMatchingFirstClose(xml, "description");
            if (open < 0) return null;
            int gt = xml.indexOf('>', open);
            if (gt < 0) return null;
            return xml.substring(0, gt + 1) + buildTitleInfo(prefixOf(xml, open), md)
                    + xml.substring(gt + 1);
        }
        if (descKind == TAG_SELF) {
            int open = indexOfSelfClosing(xml, "description", 0);
            if (open < 0) return null;
            return expandSelfClosing(xml, open, buildTitleInfo(prefixOf(xml, open), md));
        }
        if (descKind == TAG_OPEN) return null;

        int root = indexOfOpenTag(xml, "FictionBook", 0);
        if (root < 0 || isSelfClosing(xml, root)) return null;
        int gt = xml.indexOf('>', root);
        if (gt < 0) return null;
        if (indexOfOpenTag(xml, "body", gt + 1) < 0) return null;
        String prefix = prefixOf(xml, root);
        String block = "<" + prefix + "description>" + buildTitleInfo(prefix, md)
                + "</" + prefix + "description>";
        return xml.substring(0, gt + 1) + block + xml.substring(gt + 1);
    }

    /**
     * Non-empty EPUB subjects. Null when the list cannot be placed — the
     * archive must stay byte for byte as it was.
     */
    private static String placeEpubGenres(String xml, MetaData md) {
        int kind = classifyTag(xml, "metadata");
        if (kind == TAG_CLOSED) {
            int open = openMatchingFirstClose(xml, "metadata");
            int close = indexOfCloseLocal(xml, "metadata", open < 0 ? 0 : open);
            if (open < 0 || close < open) return null;
            String dc = dcPrefixForClosed(xml, open, close);
            return replaceChildrenInFirst(xml, "metadata", dc, "subject", md.genres);
        }
        if (kind == TAG_SELF) {
            int open = indexOfSelfClosing(xml, "metadata", 0);
            if (open < 0) return null;
            String dc = dcPrefixDeclared(xml.substring(0, open));
            if (dc == null) dc = "dc:";
            return expandSelfClosing(xml, open, elementBlock(dc, "subject", md.genres));
        }
        if (kind == TAG_OPEN) return null;

        int pkg = indexOfOpenTag(xml, "package", 0);
        if (pkg < 0 || isSelfClosing(xml, pkg)) return null;
        int gt = xml.indexOf('>', pkg);
        if (gt < 0) return null;
        int manifest = indexOfOpenTag(xml, "manifest", gt + 1);
        int spine = indexOfOpenTag(xml, "spine", gt + 1);
        if (manifest < 0 && spine < 0) return null;
        String metaPrefix = prefixOf(xml, pkg);
        String dc = dcPrefixDeclared(xml.substring(0, gt + 1));
        boolean declare = dc == null;
        if (declare) dc = "dc:";
        return xml.substring(0, gt + 1) + buildMetadata(metaPrefix, dc, declare, md)
                + xml.substring(gt + 1);
    }

    private static String buildTitleInfo(String prefix, MetaData md) {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(prefix).append("title-info>");
        if (md.title != null && md.title.length() > 0) {
            sb.append("<").append(prefix).append("book-title>")
                    .append(escapeXml(md.title))
                    .append("</").append(prefix).append("book-title>");
        }
        if (md.author != null && md.author.trim().length() > 0) {
            appendAuthor(sb, prefix, md.author.trim());
        }
        sb.append(elementBlock(prefix, "genre", md.genres));
        sb.append("</").append(prefix).append("title-info>");
        return sb.toString();
    }

    private static void appendAuthor(StringBuilder sb, String prefix, String author) {
        String first = null;
        String last;
        int sp = author.lastIndexOf(' ');
        if (sp > 0) {
            first = author.substring(0, sp).trim();
            last = author.substring(sp + 1).trim();
        } else {
            last = author;
        }
        sb.append("<").append(prefix).append("author>");
        if (first != null && first.length() > 0) {
            sb.append("<").append(prefix).append("first-name>")
                    .append(escapeXml(first))
                    .append("</").append(prefix).append("first-name>");
        }
        sb.append("<").append(prefix).append("last-name>")
                .append(escapeXml(last))
                .append("</").append(prefix).append("last-name>");
        sb.append("</").append(prefix).append("author>");
    }

    private static String buildMetadata(String metaPrefix, String dcPrefix, boolean declareDc, MetaData md) {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(metaPrefix).append("metadata");
        if (declareDc) sb.append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"");
        sb.append(">");
        appendDc(sb, dcPrefix, "title", md.title);
        appendDc(sb, dcPrefix, "creator", md.author);
        appendDc(sb, dcPrefix, "publisher", md.publisher);
        appendDc(sb, dcPrefix, "description", md.description);
        sb.append(elementBlock(dcPrefix, "subject", md.genres));
        sb.append("</").append(metaPrefix).append("metadata>");
        return sb.toString();
    }

    private static void appendDc(StringBuilder sb, String prefix, String name, String value) {
        if (value == null || value.length() == 0) return;
        sb.append("<").append(prefix).append(name).append(">")
                .append(escapeXml(value))
                .append("</").append(prefix).append(name).append(">");
    }

    private static String replaceChildrenInFirst(String xml, String container, String childPrefix,
                                                  String childLocal, List<String> values) {
        int open = openMatchingFirstClose(xml, container);
        int close = indexOfCloseLocal(xml, container, open < 0 ? 0 : open);
        if (open < 0 || close < open) return null;
        String inner = removeLocalElements(xml.substring(open, close), childLocal);
        inner = inner + elementBlock(childPrefix, childLocal, values);
        return xml.substring(0, open) + inner + xml.substring(close);
    }

    private static String elementBlock(String prefix, String local, List<String> values) {
        StringBuilder sb = new StringBuilder();
        if (values == null) return "";
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            if (v == null) continue;
            sb.append("<").append(prefix).append(local).append(">")
                    .append(escapeXml(v))
                    .append("</").append(prefix).append(local).append(">");
        }
        return sb.toString();
    }

    private static String expandSelfClosing(String xml, int open, String inner) {
        int gt = xml.indexOf('>', open);
        if (gt <= open || xml.charAt(gt - 1) != '/') return null;
        String head = xml.substring(open, gt - 1);
        int end = head.length();
        while (end > 0) {
            char c = head.charAt(end - 1);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            end--;
        }
        head = head.substring(0, end);
        String rebuilt = head + ">" + inner + "</" + prefixOf(xml, open) + localOf(xml, open) + ">";
        return xml.substring(0, open) + rebuilt + xml.substring(gt + 1);
    }

    private static int classifyTag(String xml, String local) {
        if (indexOfCloseLocal(xml, local, 0) >= 0) return TAG_CLOSED;
        if (indexOfSelfClosing(xml, local, 0) >= 0) return TAG_SELF;
        if (indexOfOpenTag(xml, local, 0) >= 0) return TAG_OPEN;
        return TAG_ABSENT;
    }

    private static int indexOfSelfClosing(String xml, String local, int from) {
        int i = from;
        while (true) {
            int open = indexOfOpenTag(xml, local, i);
            if (open < 0) return -1;
            if (isSelfClosing(xml, open)) return open;
            i = open + 1;
        }
    }

    private static int openMatchingFirstClose(String xml, String local) {
        int close = indexOfCloseLocal(xml, local, 0);
        if (close < 0) return -1;
        int open = -1;
        int i = 0;
        while (i < close) {
            int next = indexOfOpenTag(xml, local, i);
            if (next < 0 || next >= close) break;
            if (!isSelfClosing(xml, next)) open = next;
            i = next + 1;
        }
        return open;
    }

    private static int indexOfCloseLocal(String xml, String local, int from) {
        String want = local.toLowerCase(Locale.US);
        int i = from;
        int len = xml.length();
        while (i < len) {
            int lt = xml.indexOf("</", i);
            if (lt < 0 || lt + 2 >= len) return -1;
            if (localNameAt(xml, lt + 2).toLowerCase(Locale.US).equals(want)) return lt;
            i = lt + 2;
        }
        return -1;
    }

    private static String removeLocalElements(String xml, String local) {
        String out = xml;
        int guard = 0;
        while (guard < 10000) {
            guard++;
            int open = indexOfOpenTag(out, local, 0);
            if (open < 0) return out;
            if (isSelfClosing(out, open)) {
                int gt = out.indexOf('>', open);
                if (gt < 0) return out;
                out = out.substring(0, open) + out.substring(gt + 1);
                continue;
            }
            int gt = out.indexOf('>', open);
            if (gt < 0) return out;
            int close = indexOfCloseLocal(out, local, gt + 1);
            if (close < 0) return out;
            int closeEnd = out.indexOf('>', close);
            if (closeEnd < 0) return out;
            out = out.substring(0, open) + out.substring(closeEnd + 1);
        }
        return out;
    }

    private static String dcPrefixForClosed(String xml, int open, int close) {
        String inBlock = dcPrefixOfElements(xml.substring(open, close));
        if (inBlock != null) return inBlock;
        String declared = dcPrefixDeclared(xml.substring(0, open));
        if (declared != null) return declared;
        return "dc:";
    }

    private static String dcPrefixOfElements(String block) {
        int i = 0;
        int len = block.length();
        while (i < len) {
            int lt = block.indexOf('<', i);
            if (lt < 0 || lt + 1 >= len) return null;
            char next = block.charAt(lt + 1);
            if (next == '/' || next == '?' || next == '!' || next == '>') {
                i = lt + 1;
                continue;
            }
            String prefix = prefixOf(block, lt);
            if (prefix.length() > 0 && isDcLocal(localOf(block, lt))) return prefix;
            i = lt + 1;
        }
        return null;
    }

    private static boolean isDcLocal(String local) {
        String n = local.toLowerCase(Locale.US);
        return n.equals("title") || n.equals("creator") || n.equals("subject")
                || n.equals("description") || n.equals("publisher")
                || n.equals("contributor") || n.equals("date") || n.equals("type")
                || n.equals("format") || n.equals("identifier") || n.equals("source")
                || n.equals("language") || n.equals("relation") || n.equals("coverage")
                || n.equals("rights");
    }

    /** Prefix including the colon, from {@code xmlns:NAME="http://purl.org/dc/elements/1.1/"}. */
    private static String dcPrefixDeclared(String scope) {
        int from = 0;
        String marker = "xmlns:";
        while (from < scope.length()) {
            int at = scope.toLowerCase(Locale.US).indexOf(marker, from);
            if (at < 0) return null;
            int nameStart = at + marker.length();
            int i = nameStart;
            while (i < scope.length() && isXmlNameChar(scope.charAt(i))) i++;
            if (i == nameStart) {
                from = nameStart;
                continue;
            }
            int j = skipXmlSpace(scope, i);
            if (j >= scope.length() || scope.charAt(j) != '=') {
                from = i;
                continue;
            }
            j = skipXmlSpace(scope, j + 1);
            if (j >= scope.length()) return null;
            char q = scope.charAt(j);
            if (q != '"' && q != '\'') {
                from = j + 1;
                continue;
            }
            int valEnd = scope.indexOf(q, j + 1);
            if (valEnd < 0) return null;
            if (scope.substring(j + 1, valEnd).equals("http://purl.org/dc/elements/1.1/")) {
                return scope.substring(nameStart, i) + ":";
            }
            from = valEnd + 1;
        }
        return null;
    }

    private static int skipXmlSpace(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            i++;
        }
        return i;
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
        return localNameAt(xml, openLt + 1);
    }

    private static String localNameAt(String xml, int nameStart) {
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
