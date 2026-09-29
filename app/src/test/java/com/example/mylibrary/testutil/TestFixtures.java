package com.example.mylibrary.testutil;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Shared fixture builders for the Robolectric unit tests (EPUB/FB2 archives, plain
 * files). Test-scope only — never referenced from production code.
 *
 * <p>Fixtures are built in-memory / in temp folders, so no binary book files are
 * stored in git.</p>
 */
public final class TestFixtures {

    private TestFixtures() {}

    // ------------------------------------------------------------------
    // EPUB
    // ------------------------------------------------------------------

    public static final String CONTAINER_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
          + "  <rootfiles>\n"
          + "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n"
          + "  </rootfiles>\n"
          + "</container>\n";

    /** OPF with the full Dublin-Core metadata set. */
    public static final String OPF_FULL =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
          + "  <metadata>\n"
          + "    <dc:title>Original Title</dc:title>\n"
          + "    <dc:creator>Original Author</dc:creator>\n"
          + "    <dc:publisher>Original Publisher</dc:publisher>\n"
          + "    <dc:description>An original story.</dc:description>\n"
          + "    <dc:language>en</dc:language>\n"
          + "  </metadata>\n"
          + "  <manifest>\n"
          + "    <item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
          + "  </manifest>\n"
          + "</package>\n";

    /** OPF without a title (only a creator) — used for the not-found path. */
    public static final String OPF_CREATOR_ONLY =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
          + "  <metadata>\n"
          + "    <dc:creator>Only Author</dc:creator>\n"
          + "  </metadata>\n"
          + "</package>\n";

    /** EPUB2 cover declaration: <meta name="cover" content="..."/> + manifest item. */
    public static final String OPF_WITH_COVER =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
          + "  <metadata>\n"
          + "    <dc:title>Covered Book</dc:title>\n"
          + "    <dc:creator>Author</dc:creator>\n"
          + "    <meta name=\"cover\" content=\"cover-image\"/>\n"
          + "  </metadata>\n"
          + "  <manifest>\n"
          + "    <item id=\"cover-image\" href=\"images/cover.jpg\" media-type=\"image/jpeg\"/>\n"
          + "    <item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
          + "  </manifest>\n"
          + "</package>\n";

    /** EPUB3 cover declaration: <meta property="cover-image" id="..."/> + manifest item. */
    public static final String OPF3_WITH_COVER =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"3.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
          + "  <metadata>\n"
          + "    <dc:title>Covered Book 3</dc:title>\n"
          + "    <dc:creator>Author</dc:creator>\n"
          + "    <meta property=\"cover-image\" id=\"cover-img\"/>\n"
          + "  </metadata>\n"
          + "  <manifest>\n"
          + "    <item id=\"cover-img\" href=\"cover.jpeg\" media-type=\"image/jpeg\"/>\n"
          + "  </manifest>\n"
          + "</package>\n";

    public static final String CHAPTER_XHTML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html><body><p>Hello world</p></body></html>\n";

    /** Deterministic pseudo-random bytes standing in for a JPEG cover. */
    public static byte[] coverBytes() {
        byte[] b = new byte[256];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 31 + 7);
        return b;
    }

    // ------------------------------------------------------------------
    // FB2
    // ------------------------------------------------------------------

    /** A realistic minimal FB2 document with a full description block. */
    public static final String FB2_FULL =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
          + "  <description>\n"
          + "    <title-info>\n"
          + "      <genre>prose</genre>\n"
          + "      <author>\n"
          + "        <first-name>Ivan</first-name>\n"
          + "        <middle-name>Ivanovich</middle-name>\n"
          + "        <last-name>Petrov</last-name>\n"
          + "      </author>\n"
          + "      <title>Original Title</title>\n"
          + "      <lang>en</lang>\n"
          + "    </title-info>\n"
          + "    <publish-info>\n"
          + "      <publisher>Original Publisher</publisher>\n"
          + "      <published>2020</published>\n"
          + "    </publish-info>\n"
          + "    <annotation>An original story.</annotation>\n"
          + "  </description>\n"
          + "  <body>\n"
          + "    <chapter>\n"
          + "      <p>Hello</p>\n"
          + "    </chapter>\n"
          + "  </body>\n"
          + "</FictionBook>\n";

    /** FB2 without a title — used for the not-found path. */
    public static final String FB2_NO_TITLE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
          + "  <description>\n"
          + "    <title-info>\n"
          + "      <genre>prose</genre>\n"
          + "    </title-info>\n"
          + "  </description>\n"
          + "</FictionBook>\n";

    /**
     * Builds a FB2 document whose coverpage references {@code #coverimg} and whose
     * binary entry carries the given base64 payload.
     */
    public static String buildFb2WithCover(String base64Cover) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" xmlns:l=\"http://www.w3.org/1999/xlink\">\n"
             + "  <description>\n"
             + "    <title-info>\n"
             + "      <title>With Cover</title>\n"
             + "    </title-info>\n"
             + "    <coverpage>\n"
             + "      <image l:href=\"#coverimg\"/>\n"
             + "    </coverpage>\n"
             + "  </description>\n"
             + "  <binary id=\"coverimg\" content-type=\"image/jpeg\">" + base64Cover + "</binary>\n"
             + "</FictionBook>\n";
    }

    // ------------------------------------------------------------------
    // MOBI / AZW
    // ------------------------------------------------------------------

    /**
     * Builds a structurally-valid, minimal MOBI (PalmDB) file in {@code f}. The file has
     * four records: record 0 (PalmDOC + MOBI header + EXTH + the full-name title), a text
     * record, a filler image record, and the cover image record. The cover is a raw JPEG
     * (the {@code coverJpeg} bytes, which must start FFD8 and end FFD9) with 4 bytes of
     * trailing record padding appended after its end-of-image marker, so that a correct
     * extractor must trim it.
     *
     * <p>All multi-byte fields are written big-endian, matching the real format. The EXTH
     * block carries author (100), publisher (101), description (103), language (524) and,
     * when {@code coverOffset >= 0}, the cover offset (201). The cover image then lives in
     * record {@code firstImageIndex + coverOffset}; pass a negative {@code coverOffset} to
     * omit the 201 record entirely (a book without a cover image record).
     */
    public static void writeMobi(File f, String title, String author, String publisher,
                                 String description, String language, byte[] coverJpeg,
                                 int firstImageIndex, int coverOffset)
            throws Exception {
        final int nrecs = 4;
        final int mobiHdrLen = 232;    // 0xE8, like real kindlegen output
        final boolean hasCover = coverOffset >= 0;

        byte[] titleBytes = title.getBytes("UTF-8");
        byte[] authorB = author.getBytes("UTF-8");
        byte[] pubB = publisher.getBytes("UTF-8");
        byte[] descB = description.getBytes("UTF-8");
        byte[] langB = language.getBytes("UTF-8");

        // --- EXTH record bodies (id, size, content) ---
        int recsLen = (8 + authorB.length) + (8 + pubB.length)
                     + (8 + descB.length) + (8 + langB.length)
                     + (hasCover ? 12 : 0);   // 201 -> 4-byte content
        int exthLen = 12 + recsLen;               // length field excludes padding
        int exthStart = 16 + mobiHdrLen;          // relative to record 0
        int pad = (4 - (exthLen % 4)) % 4;
        int exthTotal = exthLen + pad;
        int fullNameOffset = exthStart + exthTotal;
        int rec0Len = fullNameOffset + titleBytes.length;

        // --- assemble record 0 ---
        byte[] rec0 = new byte[rec0Len]; // zero-filled
        putU16(rec0, 0x00, 1);           // compression = none
        putU16(rec0, 0x08, 1);           // text record count
        putU16(rec0, 0x0A, 4096);        // record size
        putBytes(rec0, 0x10, "MOBI".getBytes("US-ASCII"));
        putU32(rec0, 0x14, mobiHdrLen);
        putU32(rec0, 0x18, 2);           // mobi type = book
        putU32(rec0, 0x1C, 65001);       // code page = UTF-8
        putU32(rec0, 0x68, 6);           // mobi version
        putU32(rec0, 0x6C, firstImageIndex);
        putU32(rec0, 0x80, 0x00000040);  // EXTH flag set
        putU32(rec0, 0x54, fullNameOffset);
        putU32(rec0, 0x58, titleBytes.length);

        // --- EXTH block ---
        int e = exthStart;
        putBytes(rec0, e, "EXTH".getBytes("US-ASCII")); e += 4;
        putU32(rec0, e, exthLen); e += 4;
        putU32(rec0, e, 4 + (hasCover ? 1 : 0)); e += 4; // EXTH record count
        writeExthRecord(rec0, e, 100, authorB); e += 8 + authorB.length;
        writeExthRecord(rec0, e, 101, pubB); e += 8 + pubB.length;
        writeExthRecord(rec0, e, 103, descB); e += 8 + descB.length;
        writeExthRecord(rec0, e, 524, langB); e += 8 + langB.length;
        if (hasCover) {
            byte[] covOff = be32Bytes(coverOffset);
            writeExthRecord(rec0, e, 201, covOff); e += 12;
        }
        // (padding bytes after e are already zero)

        // --- full name (title) ---
        putBytes(rec0, fullNameOffset, titleBytes);

        // --- record table + data layout ---
        int dataStart = 78 + nrecs * 8;      // 110
        int off0 = dataStart;
        int off1 = off0 + rec0Len;
        int off2 = off1 + 8;
        int off3 = off2 + 8;
        int tailPad = 4;
        int fileLen = off3 + coverJpeg.length + tailPad;

        byte[] out = new byte[fileLen];
        // PDB header
        byte[] name = title.getBytes("US-ASCII");
        int nlen = Math.min(31, name.length);
        System.arraycopy(name, 0, out, 0, nlen);
        putBytes(out, 60, "BOOK".getBytes("US-ASCII"));
        putBytes(out, 64, "MOBI".getBytes("US-ASCII"));
        putU16(out, 76, nrecs);
        // record table (8 bytes each: offset BE, flag, 3-byte value)
        int t = 78;
        putU32(out, t, off0); t += 8;
        putU32(out, t, off1); t += 8;
        putU32(out, t, off2); t += 8;
        putU32(out, t, off3); t += 8;
        // record 0
        System.arraycopy(rec0, 0, out, off0, rec0Len);
        // record 1: text dummy
        for (int i = 0; i < 8; i++) out[off1 + i] = (byte) 'x';
        // record 2: filler image
        for (int i = 0; i < 8; i++) out[off2 + i] = (byte) 0xAA;
        // record 3: cover JPEG + trailing padding
        System.arraycopy(coverJpeg, 0, out, off3, coverJpeg.length);
        for (int i = 0; i < tailPad; i++) out[off3 + coverJpeg.length + i] = 0x00;

        writeBytes(f, out);
    }

    private static void writeExthRecord(byte[] buf, int off, int id, byte[] content) {
        putU32(buf, off, id);
        putU32(buf, off + 4, content.length + 8);
        System.arraycopy(content, 0, buf, off + 8, content.length);
    }

    private static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    private static void putU32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static byte[] be32Bytes(int v) {
        byte[] b = new byte[4];
        putU32(b, 0, v);
        return b;
    }

    private static void putBytes(byte[] b, int off, byte[] data) {
        System.arraycopy(data, 0, b, off, data.length);
    }

    // ------------------------------------------------------------------
    // IO helpers
    // ------------------------------------------------------------------

    public static void writeText(File f, String content) throws Exception {
        writeBytes(f, content.getBytes("UTF-8"));
    }

    public static void writeBytes(File f, byte[] bytes) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(bytes); } finally { out.close(); }
    }

    /** Writes a zip archive with the given entry name -> bytes (insertion order kept). */
    public static void writeZip(File f, Map<String, byte[]> entries) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        ZipOutputStream out = new ZipOutputStream(fos);
        try {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }

    /** Reads every entry of a zip archive into a name -> bytes map (insertion order kept). */
    public static Map<String, byte[]> readZip(File f) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)));
        try {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = in.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                out.put(e.getName(), baos.toByteArray());
            }
        } finally {
            in.close();
        }
        return out;
    }

    public static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        } finally {
            in.close();
        }
        return baos.toByteArray();
    }

    /**
     * Builds an EPUB3-style zip (container + OPF + the cover at {@code OEBPS/cover.jpeg}).
     * Note: CoverExtractor resolves the manifest href relative to the OPF directory, so
     * the entry must live under {@code OEBPS/}, matching href="cover.jpeg".
     */
    public static void writeEpub3CoverCase(File f, String opf) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("META-INF/container.xml", opfBytes(CONTAINER_XML));
        entries.put("OEBPS/content.opf", opfBytes(opf));
        entries.put("OEBPS/cover.jpeg", coverBytes());
        writeZip(f, entries);
    }

    /** Builds a standard EPUB zip (container + the given OPF + chapter + cover). */
    public static void writeEpub(File f, String opf) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("META-INF/container.xml", opfBytes(CONTAINER_XML));
        entries.put("OEBPS/content.opf", opfBytes(opf));
        entries.put("OEBPS/ch1.xhtml", opfBytes(CHAPTER_XHTML));
        entries.put("OEBPS/images/cover.jpg", coverBytes());
        writeZip(f, entries);
    }

    private static byte[] opfBytes(String s) {
        try { return s.getBytes("UTF-8"); } catch (Exception e) { throw new AssertionError(e); }
    }
}
