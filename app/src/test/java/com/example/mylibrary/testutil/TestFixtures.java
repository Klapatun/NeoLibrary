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
