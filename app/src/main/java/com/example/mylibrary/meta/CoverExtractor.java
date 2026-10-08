package com.example.mylibrary.meta;

import android.util.Log;

import com.example.mylibrary.scan.Formats;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    /** The closing tag of the FB2 description block (all metadata and the cover
     *  reference live before it; per the FB2 XSD the {@code <binary>} blocks sit
     *  between the description and the body, and the body is the bulk of a file). */
    private static final byte[] FB2_DESC_END = ascii("</description>");
    /** The start of the book body: the header region (description + binary blocks)
     *  ends where the body begins, so the streaming read stops there. */
    private static final byte[] FB2_BODY_START = ascii("<body");
    /** Hard cap on the header region buffered for the cover: beyond it the cover is
     *  abandoned (logged) rather than risking an OOM on a pathological document. */
    static final int FB2_HEADER_CAP = 32 * 1024 * 1024;
    /** Streaming read size for the FB2 header region. */
    private static final int FB2_READ_CHUNK = 8192;

    private static final byte[] TAG_COVERPAGE_OPEN = ascii("<coverpage");
    private static final byte[] TAG_COVERPAGE_OPEN_HYPHEN = ascii("<cover-page");
    private static final byte[] TAG_COVERPAGE_CLOSE = ascii("</coverpage");
    private static final byte[] TAG_COVERPAGE_CLOSE_HYPHEN = ascii("</cover-page");
    private static final byte[] TAG_BINARY_CLOSE = ascii("</binary");

    /**
     * base64 decode table (one entry per input byte value; -1 = not a base64 data
     * character — whitespace, padding '=' and anything else is simply skipped).
     */
    private static final int[] B64 = new int[256];
    static {
        Arrays.fill(B64, -1);
        for (int i = 0; i < 26; i++) {
            B64['A' + i] = i;
            B64['a' + i] = 26 + i;
        }
        for (int i = 0; i < 10; i++) B64['0' + i] = 52 + i;
        B64['+'] = 62;
        B64['/'] = 63;
    }

    private static byte[] extractFb2(File file) throws Exception {
        // One streaming pass over the header region only: the <description> block and
        // the <binary> blocks that follow it (both precede the body). The book body —
        // the bulk of a real FB2 — is never read, and the data never becomes a String
        // (no char[] copies): a 100 MB book costs a few MB of buffer, not hundreds.
        byte[] header = readFb2Header(file);
        return coverFromFb2Bytes(header);
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
            byte[] inner = coverFromFb2Bytes(xml);
            if (inner != null) return inner;
        }
        return findLooseImageEntry(file);
    }

    /**
     * Streams an FB2 file up to the end of its header region and returns the region's
     * bytes: from the start of the file through {@code </description>} and on to
     * {@code <body} (or end-of-file). The {@code <binary>} blocks — where the cover's
     * base64 lives — sit between the two, per the FB2 XSD order.
     *
     * <p>The read is bounded by {@link #FB2_HEADER_CAP}: when a (pathological or
     * non-conformant) file's header region exceeds it, a warning is logged and the
     * cover is abandoned — the partial bytes are still returned, so a caller that only
     * needs the metadata can use whatever is complete.</p>
     *
     * <p>Package-private so the single-pass enricher (and the tests) share the reader.</p>
     */
    static byte[] readFb2Header(File file) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        try {
            byte[] chunk = new byte[FB2_READ_CHUNK];
            byte[] data = new byte[16 * 1024]; // grown as needed; the region is small in practice
            int size = 0;
            // One streaming pass with a two-phase marker search: first the end of the
            // description block, then the start of the body. Each chunk is scanned only
            // once (from the last searched offset, plus the overlap a marker split across
            // the chunk seam needs), so a 100 MB book costs the header region, not the file.
            byte[] marker = FB2_DESC_END;
            int searchFrom = 0;
            boolean descClosed = false;
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (size + n > data.length) {
                    data = Arrays.copyOf(data, Math.max(data.length * 2, size + n));
                }
                int before = size;
                System.arraycopy(chunk, 0, data, size, n);
                size += n;
                // Scan the freshly added region: a marker straddling the seam with the
                // previous data starts within its last (marker.length - 1) bytes.
                int from = Math.max(searchFrom, before - (marker.length - 1));
                int at = indexOf(data, from, size, marker);
                if (at >= 0) {
                    if (!descClosed) {
                        // The description is over: the binary blocks (if any) follow,
                        // and the region ends where the body starts.
                        descClosed = true;
                        marker = FB2_BODY_START;
                        searchFrom = at + FB2_DESC_END.length;
                        if (indexOf(data, searchFrom, size, FB2_BODY_START) >= 0) break;
                    } else {
                        break; // the body has started: the header region is complete
                    }
                }
                if (size > FB2_HEADER_CAP) {
                    Log.w(TAG, "FB2 header of " + file + " exceeds " + (FB2_HEADER_CAP >> 20)
                            + " MB; giving up on the cover");
                    break;
                }
            }
            return Arrays.copyOf(data, size);
        } finally {
            in.close();
        }
    }

    /**
     * The shared FB2 cover lookup on raw XML bytes (no String, no regex): finds
     * {@code <coverpage>} → the {@code href="#id"} reference → the matching
     * {@code <binary id="...">} block and decodes its base64 payload straight off the
     * bytes (whitespace skipped, 768-character chunks into a growing buffer). Returns
     * the decoded image bytes, or {@code null} when there is no coverpage, no matching
     * binary block, or an empty payload.
     */
    static byte[] coverFromFb2Bytes(byte[] xml) {
        if (xml == null) return null;
        int len = xml.length;

        // 1) The <coverpage> section (the cover reference lives in the description).
        int coverStart = indexOf(xml, 0, len, TAG_COVERPAGE_OPEN);
        if (coverStart < 0) coverStart = indexOf(xml, 0, len, TAG_COVERPAGE_OPEN_HYPHEN);
        if (coverStart < 0) return null;
        int coverEnd = indexOf(xml, coverStart, len, TAG_COVERPAGE_CLOSE);
        if (coverEnd < 0) coverEnd = indexOf(xml, coverStart, len, TAG_COVERPAGE_CLOSE_HYPHEN);
        if (coverEnd < 0) coverEnd = Math.min(len, coverStart + 2000);

        // 2) The binary id from the section's href attribute (any (l:)href, either
        //    quote style, optional '#' — the bare form names a file, never a binary).
        String binaryId = findHrefValue(xml, coverStart, coverEnd);
        if (binaryId == null || binaryId.length() == 0) return null;
        if (binaryId.charAt(0) == '#') binaryId = binaryId.substring(1);
        if (binaryId.length() == 0) return null;

        // 3) The <binary> block carrying that id. The search spans the whole region:
        //    a base64 payload cannot contain a quote character, so the quoted id can
        //    only ever match an attribute, even with several binary blocks present.
        int b = indexOf(xml, 0, len, ascii("id=\"" + binaryId + "\""));
        if (b < 0) b = indexOf(xml, 0, len, ascii("id='" + binaryId + "'"));
        if (b < 0) return null;
        int gt = nextByte(xml, b, '>');
        if (gt < 0) return null;
        int endTag = indexOf(xml, gt, len, TAG_BINARY_CLOSE);
        if (endTag < 0) return null;

        // 4) The base64 payload, decoded directly from the XML bytes (no String).
        byte[] decoded = decodeBase64(xml, gt + 1, endTag - gt - 1);
        return decoded.length > 0 ? decoded : null;
    }

    /**
     * Extracts the first {@code href} attribute value in {@code xml[from..to)} —
     * matching {@code (?:l:)?href="..."} with either quote style and optional
     * whitespace around {@code =} (case-insensitively, as a byte scan — no regex) —
     * or {@code null} when the section carries no href.
     */
    private static String findHrefValue(byte[] xml, int from, int to) {
        for (int i = from; i + 4 <= to; i++) {
            if (!wordEquals(xml, i, "href")) continue;
            // Not a suffix of a longer attribute name (the ':' of l:href is allowed).
            if (i > 0 && isNameByte(xml[i - 1])) continue;
            int j = i + 4;
            while (j < to && isSpace(xml[j])) j++;
            if (j >= to || xml[j] != '=') continue;
            j++;
            while (j < to && isSpace(xml[j])) j++;
            if (j >= to) continue;
            int q = xml[j] & 0xFF;
            if (q != '"' && q != '\'') continue;
            int end = j + 1;
            while (end < to && xml[end] != (byte) q) end++;
            if (end >= to) continue;
            return new String(xml, j + 1, end - j - 1, StandardCharsets.US_ASCII);
        }
        return null;
    }

    /**
     * Decodes the base64 payload straight from {@code src[off, off+len)} — the raw XML
     * bytes (the payload is ASCII): whitespace and padding are skipped, and each 768
     * data characters yield 576 output bytes written into a growing buffer, so no
     * intermediate String (char[]) ever exists.
     */
    private static byte[] decodeBase64(byte[] src, int off, int len) {
        int[] q = new int[4];
        int qi = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int end = off + len;
        for (int i = off; i < end; i++) {
            int v = B64[src[i] & 0xFF];
            if (v < 0) continue; // whitespace, '=' padding, anything non-base64
            q[qi] = v;
            if (++qi == 4) {
                out.write((q[0] << 2) | (q[1] >> 4));
                out.write(((q[1] & 0xF) << 4) | (q[2] >> 2));
                out.write(((q[2] & 0x3) << 6) | q[3]);
                qi = 0;
            }
        }
        if (qi == 2) {
            out.write((q[0] << 2) | (q[1] >> 4));
        } else if (qi == 3) {
            out.write((q[0] << 2) | (q[1] >> 4));
            out.write(((q[1] & 0xF) << 4) | (q[2] >> 2));
        }
        return out.toByteArray();
    }

    // -------------------------------------------------------------------
    // small byte-level helpers (no String / regex on the hot path)
    // -------------------------------------------------------------------

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** True when the letters at {@code b[off]} equal {@code w} (case-insensitively). */
    private static boolean wordEquals(byte[] b, int off, String w) {
        for (int i = 0; i < w.length(); i++) {
            int c = b[off + i] & 0xFF;
            if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
            if (c != w.charAt(i)) return false;
        }
        return true;
    }

    private static boolean isNameByte(byte c) {
        c &= 0xFF;
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static boolean isSpace(byte c) {
        c &= 0xFF;
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** Finds {@code marker} in {@code buf[off, off+len)} with a plain byte scan; -1 if absent. */
    private static int indexOf(byte[] buf, int off, int len, byte[] marker) {
        if (len < marker.length) return -1;
        int last = off + len - marker.length;
        for (int i = off; i <= last; i++) {
            int j;
            for (j = 0; j < marker.length; j++) {
                if (buf[i + j] != marker[j]) break;
            }
            if (j == marker.length) return i;
        }
        return -1;
    }

    /** The index of the first {@code c} at or after {@code from}; -1 when absent. */
    private static int nextByte(byte[] b, int from, int c) {
        for (int i = from; i < b.length; i++) {
            if ((b[i] & 0xFF) == (c & 0xFF)) return i;
        }
        return -1;
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
            int len = imageLen(raw);
            if (len < 0) return null;
            if (len == raw.length) return raw; // nothing to trim: no copy
            byte[] out = new byte[len];
            System.arraycopy(raw, 0, out, 0, len);
            return out;
        } finally {
            p.close();
        }
    }

    /**
     * The length of the decodable image at the start of a raw MOBI cover record: a JPEG
     * is trimmed at its (last) end-of-image marker, every other recognizable image
     * passes through whole. Returns {@code -1} when the bytes do not look like a
     * decodable image.
     *
     * <p>The boundary is reported IN PLACE — the image spans {@code raw[0 .. len)} and
     * nothing is copied: the single-pass enricher hands that range straight to
     * {@code CoverCache.save(ctx, path, raw, 0, len)} instead of forking the array
     * (a cover record can be many MB, and the padding past the EOI is small).</p>
     *
     * <p>Package-private so the tests (and the single-pass enricher) share the logic.</p>
     */
    static int imageLen(byte[] raw) {
        if (raw == null || raw.length == 0) return -1;
        int b0 = raw[0] & 0xFF;
        if (raw.length > 2 && b0 == 0xFF && (raw[1] & 0xFF) == 0xD8) {
            // JPEG: keep everything up to and including the end-of-image (FF D9) marker.
            int eoi = lastEoi(raw);
            int end = (eoi >= 0) ? eoi + 2 : raw.length;
            return (end >= 2) ? end : -1;
        }
        // PNG / BMP / GIF (and anything else with a recognizable signature): pass through.
        return looksLikeImage(raw) ? raw.length : -1;
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
}
