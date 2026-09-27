package com.example.mylibrary.meta;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
     * Dublin-Core metadata elements carry the new values. If an element is absent it
     * is inserted into the metadata section.
     */
    private static boolean writeEpub(File file, MetaData md) throws Exception {
        String opfPath = findOpfPath(file);
        if (opfPath == null) return false;

        String opf = readZipEntry(file, opfPath);
        if (opf == null) return false;

        opf = setDcElement(opf, "title", md.title);
        opf = setDcElement(opf, "creator", md.author);
        opf = setDcElement(opf, "publisher", md.publisher);
        opf = setDcElement(opf, "description", md.description);
        opf = setDcElement(opf, "language", md.language);

        // Write rebuilt archive to a temp file, then move over the original.
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        boolean ok = rebuildZip(file, tmp, opfPath, opf);
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

    /** Replaces the text content of a {@code <dc:X>} element. Inserts if missing. */
    private static String setDcElement(String xml, String name, String value) {
        if (value == null) value = "";
        String escaped = escapeXml(value);
        String metaStart = "<dc:" + name;
        String lower = xml.toLowerCase(Locale.US);
        int tagStart = lower.indexOf(metaStart);
        if (tagStart >= 0) {
            // find end of this element's closing tag by scanning
            int contentStart = xml.indexOf('>', tagStart);
            if (contentStart >= 0) contentStart++;
            int close = xml.indexOf("</dc:" + name, contentStart);
            if (close >= 0) {
                int closeEnd = xml.indexOf('>', close);
                if (closeEnd >= 0) closeEnd++;
                return xml.substring(0, contentStart) + escaped + xml.substring(closeEnd);
            }
        } else {
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
    private static boolean rebuildZip(File src, File dst, String replaceName, String newContent) {
        try {
            ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)));
            ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(dst)));
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals(replaceName)) {
                    ZipEntry ne = new ZipEntry(e.getName());
                    out.putNextEntry(ne);
                    out.write(newContent.getBytes("UTF-8"));
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

    private static String readZipEntry(File file, String entryName) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals(entryName)) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zip.read(buf)) > 0) baos.write(buf, 0, n);
                    return new String(baos.toByteArray(), "UTF-8");
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
     * relevant elements with regex, and write it back with a backup.
     */
    private static boolean writeFb2(File file, MetaData md) throws Exception {
        String xml = readTextFile(file);
        if (xml == null) return false;

        // title
        xml = replaceElementContent(xml, "title", md.title);
        // author parts. If only the combined author string is supplied (as from the
        // edit form), split it heuristically: last word -> last-name, the rest ->
        // first-name. This lets FB2 author edits actually persist to the file.
        if (md.firstName == null && md.lastName == null && md.author != null && md.author.length() > 0) {
            String a = md.author.trim();
            int sp = a.lastIndexOf(' ');
            if (sp > 0) {
                md.firstName = a.substring(0, sp).trim();
                md.lastName = a.substring(sp + 1).trim();
            } else {
                md.lastName = a;
            }
        }
        if (md.firstName != null) xml = replaceElementContent(xml, "first-name", md.firstName);
        if (md.middleName != null) xml = replaceElementContent(xml, "middle-name", md.middleName);
        if (md.lastName != null) xml = replaceElementContent(xml, "last-name", md.lastName);
        // publisher
        if (md.publisher != null) xml = replaceElementContent(xml, "publisher", md.publisher);
        if (md.language != null) xml = replaceElementContent(xml, "lang", md.language);

        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        writeTextFile(tmp, xml);
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

    /** Replaces content of the first {@code <tag>...</tag>} occurrence, escaping value. */
    private static String replaceElementContent(String xml, String tag, String value) {
        if (value == null) value = "";
        Pattern open = Pattern.compile("(?is)(<" + Pattern.quote(tag) + "[^>]*>)(.*?)(</" + Pattern.quote(tag) + ">)");
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

    private static String readTextFile(File f) {
        try {
            InputStream in = new BufferedInputStream(new FileInputStream(f));
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            in.close();
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeTextFile(File f, String content) throws Exception {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(f));
        out.write(content.getBytes("UTF-8"));
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
