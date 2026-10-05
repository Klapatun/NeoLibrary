package com.example.mylibrary.meta;

import android.util.Base64;
import android.util.Log;

import com.example.mylibrary.scan.Formats;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts a cover image (as raw image bytes) from a book file.
 *
 * <p>EPUB, FB2 and MOBI have a well-defined, easily reachable cover image:
 * <ul>
 *   <li><b>EPUB</b> — the OPF package document refers to a cover image via a
 *       {@code <meta name="cover" content="..."/>} (or the EPUB3 property form) whose
 *       id maps to a manifest item with an {@code href} inside the ZIP.</li>
 *   <li><b>FB2</b> — the {@code description} has a {@code <coverpage><image l:href="#id"/>}
 *       element pointing at a {@code <binary id="...">} entry (base64-encoded).</li>
 *   <li><b>FB2.ZIP</b> — a ZIP whose payload is an FB2 document: the cover comes from
 *       the inner FB2's {@code <binary>} block (same path as a plain FB2), or, when the
 *       inner document has no coverpage, from a loose image entry (e.g.
 *       {@code cover.jpg}) shipped next to the {@code .fb2} inside the archive.</li>
 *   <li><b>MOBI/AZW</b> — the EXTH block's {@code 201} record gives an offset that,
 *       added to the header's "first image" record index, locates the cover image
 *       record (a raw JPEG/PNG stored in a single PalmDB record).</li>
 * </ul>
 *
 * <p>The other binary formats (CHM, DjVu, PDB, PDF, DOCX...) embed covers behind much
 * more complex structures that need dedicated libraries, so they return {@code null}
 * and the UI shows a letter placeholder instead.</p>
 *
 * <p>All parsing uses the platform {@link java.util.zip}, plain string/regex matching
 * and raw byte reads so it stays dependency-free and compatible with API 19.</p>
 */
public final class CoverExtractor {

    private static final String TAG = "CoverExtractor";

    private CoverExtractor() {}

    /**
     * Returns the cover image bytes for the given file, or {@code null} if the format
     * isn't covered-sourced or no cover could be located/read.
     *
     * <p>Never throws: a malformed file (corrupt archive, undecodable image) is logged
     * with its cause and simply yields no cover, so one bad file can never take the
     * enricher down.</p>
     */
    public static byte[] extract(File file) {
        String format = Formats.formatOf(file.getName());
        if (format == null) return null;
        try {
            if (format.equals("EPUB")) return extractEpub(file);
            if (format.equals("FB2")) return extractFb2(file);
            if (format.equals("FB2ZIP")) return extractFb2Zip(file);
            if (format.equals("MOBI")) return extractMobi(file);
        } catch (Exception e) {
            // Any malformed file simply yields no cover (log the cause for logcat).
            Log.w(TAG, "Could not extract cover of " + file, e);
        }
        return null;
    }

    /** Returns true if the format is expected to be able to carry a cover. */
    public static boolean canHaveCover(String format) {
        return "EPUB".equals(format) || "FB2".equals(format)
                || "FB2ZIP".equals(format) || "MOBI".equals(format);
    }

    // -------------------------------------------------------------------
    // EPUB
    // -------------------------------------------------------------------

    private static byte[] extractEpub(File file) throws Exception {
        String opfPath = findOpfPath(file);
        if (opfPath == null) return null;

        // Read the OPF once and parse out: (a) cover-image specification, (b) manifest
        // items (id -> href) so we can resolve the cover's href inside the archive.
        String opf = readZipEntry(file, opfPath);
        if (opf == null) return null;

        String coverId = null;
        // EPUB2: <meta name="cover" content="id"/>
        int idx = opf.indexOf("name=\"cover\"");
        if (idx >= 0) {
            int c = opf.indexOf("content=\"", idx);
            if (c >= 0) {
                int s = c + "content=\"".length();
                int e = opf.indexOf('\"', s);
                if (e > s) coverId = opf.substring(s, e);
            }
        }
        // EPUB3: <meta property="cover-image" id="..." /> (the property element itself
        // often IS the manifest item, or references one).
        if (coverId == null) {
            int p = opf.indexOf("property=\"cover-image\"");
            if (p >= 0) {
                int idAt = opf.indexOf("id=\"", p);
                if (idAt >= 0) {
                    int s = idAt + "id=\"".length();
                    int e = opf.indexOf('\"', s);
                    if (e > s) coverId = opf.substring(s, e);
                }
            }
        }

        // Map manifest id -> href.
        Map<String, String> manifest = parseManifest(opf);
        String href = coverId != null ? manifest.get(coverId) : null;
        // Fallback: if the OPF3 property element was the item itself, its href may already
        // be resolvable; otherwise guess by id/href containing "cover".
        if (href == null) {
            for (Map.Entry<String, String> e : manifest.entrySet()) {
                String v = (e.getKey() + " " + e.getValue()).toLowerCase();
                if (v.contains("cover")) {
                    href = e.getValue();
                    break;
                }
            }
        }
        if (href == null) return null;

        // Resolve href relative to the OPF directory inside the archive.
        String dir = "";
        int sl = opfPath.lastIndexOf('/');
        if (sl >= 0) dir = opfPath.substring(0, sl + 1);
        String entry = dir + href;
        // Normalise "../" and "./" segments best-effort.
        entry = normalise(entry);
        return readZipEntryBytes(file, entry);
    }

    private static Map<String, String> parseManifest(String opf) {
        Map<String, String> map = new HashMap<String, String>();
        int from = 0;
        int m = 0;
        while ((m = opf.indexOf("<item", m)) >= 0) {
            int close = opf.indexOf('>', m);
            if (close < 0) break;
            String tag = opf.substring(m, close);
            String id = attr(tag, "id");
            String href = attr(tag, "href");
            if (id != null && href != null) map.put(id, href);
            m = close + 1;
            from++;
            if (from > 2000) break; // safety
        }
        return map;
    }

    private static String attr(String tag, String name) {
        int i = tag.indexOf(name + "=\"");
        if (i < 0) return null;
        int s = i + (name + "=\"").length();
        int e = tag.indexOf('\"', s);
        return e > s ? tag.substring(s, e) : null;
    }

    private static String normalise(String entry) {
        // Collapse "./" and resolve "../" segments as far as feasible for a zip path.
        String[] parts = entry.split("/");
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String p : parts) {
            if (p.equals(".") || p.length() == 0) {
                continue;
            } else if (p.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : out) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------
    // FB2
    // -------------------------------------------------------------------

    private static byte[] extractFb2(File file) throws Exception {
        String xml = readTextFile(file);
        if (xml == null) return null;
        return coverFromFb2Xml(xml);
    }

    /**
     * FB2ZIP is a ZIP container whose main content is an FB2 document. Two cover
     * locations are recognised, mirroring what Neo Reader looks at: first the inner
     * FB2's own {@code <coverpage>}/{@code <binary>} block (the same path as a plain
     * FB2), then — when the inner document carries no coverpage — a loose image entry
     * (e.g. {@code cover.jpg}) shipped next to the {@code .fb2} inside the archive.
     */
    private static byte[] extractFb2Zip(File file) throws Exception {
        byte[] xml = readFb2Entry(file);
        if (xml != null) {
            byte[] inner = coverFromFb2Xml(new String(xml, "UTF-8"));
            if (inner != null) return inner;
        }
        return findLooseImageEntry(file);
    }

    /**
     * The shared FB2 cover lookup: finds {@code <coverpage>} →
     * {@code href="#id"} → {@code <binary id="...">base64</binary>} in the given XML
     * text and returns the decoded image bytes, or {@code null} when absent.
     */
    private static byte[] coverFromFb2Xml(String xml) {
        // Find <coverpage> ... <image l:href="#someId" /> ... </coverpage>
        int coverStart = xml.indexOf("<coverpage");
        if (coverStart < 0) coverStart = xml.indexOf("<cover-page");
        if (coverStart < 0) return null;
        int coverEnd = xml.indexOf("</coverpage", coverStart);
        if (coverEnd < 0) coverEnd = xml.indexOf("</cover-page", coverStart);
        if (coverEnd < 0) coverEnd = Math.min(xml.length(), coverStart + 2000);
        String coverSection = xml.substring(coverStart, coverEnd);

        // Extract href="#id" (allow l:href, href, xlink:href with or without quotes/space).
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:l:)?href=\\s*[\"']#([^\"'#]+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(coverSection);
        String binaryId = null;
        if (m.find()) binaryId = m.group(1);
        if (binaryId == null) {
            // fallback: href="cover.jpg" style (bare, not #id)
            m = java.util.regex.Pattern
                    .compile("(?:l:)?href=\\s*[\"'](?:#)?([^\"'#]+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(coverSection);
            if (m.find()) binaryId = m.group(1);
        }
        if (binaryId == null) return null;

        // Find <binary id="binaryId" content-type="...">base64</binary>
        String idQuoted = "id=\"" + binaryId + "\"";
        int b = xml.indexOf(idQuoted);
        if (b < 0) b = xml.indexOf("id='" + binaryId + "'");
        if (b < 0) return null;
        int gt = xml.indexOf('>', b);
        if (gt < 0) return null;
        int endTag = xml.indexOf("</binary", gt);
        if (endTag < 0) return null;
        String base64 = xml.substring(gt + 1, endTag).trim();
        // If the binary content itself starts with "base64,-" or has HTML-unescaped
        // entities, handle a couple of commonisations.
        base64 = base64.replaceAll("\\s", "");
        if (base64.length() == 0) return null;
        return Base64.decode(base64, Base64.DEFAULT);
    }

    /**
     * Returns the bytes of the first {@code .fb2} file entry in the given ZIP archive
     * (entry names matched case-insensitively, so a book tucked into a subfolder is
     * found too), or {@code null} if the archive contains no FB2 document.
     */
    private static byte[] readFb2Entry(File file) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().toLowerCase(Locale.US).endsWith(".fb2")) {
                    return readToEndBytes(zip);
                }
            }
        } finally {
            zip.close();
        }
        return null;
    }

    /**
     * Scans the archive for a loose image entry: an entry whose name ends with a common
     * image extension. An entry whose name also contains "cover" (the typical
     * {@code cover.jpg} layout of FB2.ZIP packages) wins outright; otherwise the first
     * image entry in archive order is returned. {@code null} when there is no image.
     */
    private static byte[] findLooseImageEntry(File file) throws Exception {
        byte[] fallback = null;
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().toLowerCase(Locale.US);
                if (!isImageName(name)) continue;
                if (name.contains("cover")) return readToEndBytes(zip);
                if (fallback == null) fallback = readToEndBytes(zip);
            }
        } finally {
            zip.close();
        }
        return fallback;
    }

    /** True if the (lower-cased) entry name ends with a common image extension. */
    private static boolean isImageName(String lowerName) {
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".gif")
                || lowerName.endsWith(".bmp");
    }

    // -------------------------------------------------------------------
    // MOBI / AZW
    // -------------------------------------------------------------------

    /**
     * Locates the cover image record via the EXTH block and returns its bytes. The cover
     * lives in a single PalmDB record whose data may extend a few bytes past the image,
     * so JPEG payloads are trimmed at their end-of-image marker.
     */
    private static byte[] extractMobi(File file) throws Exception {
        MobiParser p = new MobiParser();
        try {
            if (!p.open(file)) return null;
            if (p.coverRecord < 0) return null;
            byte[] raw = p.readRecord(p.coverRecord);
            if (raw == null || raw.length == 0) return null;
            return trimToImage(raw);
        } finally {
            p.close();
        }
    }

    /**
     * Returns the image bytes out of a raw MOBI cover record, or {@code null} if the bytes
     * don't look like a decodable image. JPEG payloads are trimmed at their EOI marker so
     * trailing record padding is not handed to the bitmap decoder.
     */
    private static byte[] trimToImage(byte[] raw) {
        int b0 = raw[0] & 0xFF;
        if (raw.length > 2 && b0 == 0xFF && (raw[1] & 0xFF) == 0xD8) {
            // JPEG: keep everything up to and including the end-of-image (FF D9) marker.
            int eoi = lastEoi(raw);
            int end = (eoi >= 0) ? eoi + 2 : raw.length;
            if (end < 2) return null;
            byte[] out = new byte[end];
            System.arraycopy(raw, 0, out, 0, end);
            return out;
        }
        // PNG / BMP / GIF (and anything else with a recognizable signature): pass through.
        return looksLikeImage(raw) ? raw : null;
    }

    /** Index of the last JPEG end-of-image marker (FF D9), or -1 if absent. */
    private static int lastEoi(byte[] b) {
        for (int i = b.length - 2; i >= 0; i--) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD9) return i;
        }
        return -1;
    }

    /** True if the leading bytes match a common image signature. */
    private static boolean looksLikeImage(byte[] b) {
        if (b.length < 4) return false;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF, b3 = b[3] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) return true;                                 // JPEG
        if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') return true;        // PNG
        if (b0 == 'B' && b1 == 'M') return true;                                   // BMP
        if (b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8') return true;         // GIF
        return false;
    }

    // -------------------------------------------------------------------
    // shared helpers
    // -------------------------------------------------------------------

    private static String findOpfPath(File file) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equalsIgnoreCase("META-INF/container.xml")) {
                    String xml = readToEnd(zip);
                    int idx = xml.indexOf("full-path");
                    if (idx < 0) return null;
                    int s = xml.indexOf('\"', idx);
                    int en = xml.indexOf('\"', s + 1);
                    return xml.substring(s + 1, en);
                }
            }
        } finally {
            zip.close();
        }
        return null;
    }

    private static String readZipEntry(File file, String entryName) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals(entryName)) {
                    byte[] data = readToEndBytes(zip);
                    return new String(data, "UTF-8");
                }
            }
        } finally {
            zip.close();
        }
        return null;
    }

    private static byte[] readZipEntryBytes(File file, String entryName) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals(entryName)) {
                    return readToEndBytes(zip);
                }
            }
        } finally {
            zip.close();
        }
        return null;
    }

    private static String readToEnd(ZipInputStream zip) throws Exception {
        return new String(readToEndBytes(zip), "UTF-8");
    }

    private static byte[] readToEndBytes(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private static String readTextFile(File f) {
        try {
            InputStream in = new BufferedInputStream(new FileInputStream(f));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            in.close();
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
