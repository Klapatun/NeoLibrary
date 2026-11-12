package com.example.mylibrary.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Unit tests for {@link MetaWriter} — the "never leave a book file half-written"
 * invariant lives here, so the suite is heavy on:
 *  - round-trip correctness (edited values really land in the file),
 *  - non-destructiveness (every other zip entry / XML block stays byte-identical),
 *  - failure paths (corrupt input -> {@code false} + original file untouched,
 *    no {@code .tmp}/{@code .bak} leftovers),
 *  - XML escaping.
 *
 * Fixtures are built programmatically in a temp folder — no binary files in git.
 */
public class MetaWriterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static final String CONTAINER_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
          + "  <rootfiles>\n"
          + "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n"
          + "  </rootfiles>\n"
          + "</container>\n";

    private static final String OPF_FULL =
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

    /** OPF missing publisher/description/language — used for the insertion test. */
    private static final String OPF_MINIMAL =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
          + "  <metadata>\n"
          + "    <dc:title>Original Title</dc:title>\n"
          + "    <dc:creator>Original Author</dc:creator>\n"
          + "  </metadata>\n"
          + "</package>\n";

    private static final String CHAPTER_XHTML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html><body><p>Hello world</p></body></html>\n";

    /** A realistic minimal FB2 document with a description block. */
    private static final String FB2_FIXTURE =
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

    /** Deterministic pseudo-random bytes standing in for a JPEG cover. */
    private static byte[] coverBytes() {
        byte[] b = new byte[256];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 31 + 7);
        return b;
    }

    private static byte[] text(String s) throws Exception {
        return s.getBytes("UTF-8");
    }

    private File writeEpubFixture(String opf) throws Exception {
        File epub = folder.newFile("book.epub");
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("META-INF/container.xml", text(CONTAINER_XML));
        entries.put("OEBPS/content.opf", text(opf));
        entries.put("OEBPS/ch1.xhtml", text(CHAPTER_XHTML));
        entries.put("OEBPS/images/cover.jpg", coverBytes());
        writeZip(epub, entries);
        return epub;
    }

    private File writeZip(File file, Map<String, byte[]> entries) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
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
        return file;
    }

    /** Reads every entry of a zip archive into a name -> bytes map. */
    private static Map<String, byte[]> readZip(File file) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
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

    private static byte[] readAll(File f) throws Exception {
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

    private static void assertBytesEqual(byte[] expected, byte[] actual) {
        assertTrue("file bytes changed", Arrays.equals(expected, actual));
    }

    private static void assertSubsequencePresent(byte[] haystack, byte[] needle) {
        assertTrue("missing byte sequence", indexOf(haystack, needle) >= 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) return -1;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int countUtf8Bom(byte[] bytes) {
        int n = 0;
        for (int i = 0; i + 2 < bytes.length; i++) {
            if (bytes[i] == (byte) 0xEF && bytes[i + 1] == (byte) 0xBB && bytes[i + 2] == (byte) 0xBF) {
                n++;
            }
        }
        return n;
    }

    private static MetaData metaAllStringsNull() {
        MetaData md = new MetaData();
        md.title = null;
        md.author = null;
        md.publisher = null;
        md.description = null;
        md.language = null;
        md.genres = null;
        return md;
    }

    private static File writeFb2Bytes(File fb2, byte[] bytes) throws Exception {
        FileOutputStream out = new FileOutputStream(fb2);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        return fb2;
    }

  /** Windows-1251 FB2 with closed title-info, shared title word in body. */
    private static String fb2Windows1251Source() {
        return "<?xml version=\"1.0\" encoding=\"windows-1251\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <description>\n"
                + "    <title-info>\n"
                + "      <genre>prose</genre>\n"
                + "      <title>Война и мир</title>\n"
                + "      <lang>ru</lang>\n"
                + "    </title-info>\n"
                + "  </description>\n"
                + "  <body><p>Война и мир</p></body>\n"
                + "</FictionBook>\n";
    }

    /** FB2_FIXTURE without title-info; publish-info and body stay. */
    private static String fb2WithoutTitleInfo() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <description>\n"
                + "    <publish-info>\n"
                + "      <publisher>Original Publisher</publisher>\n"
                + "      <published>2020</published>\n"
                + "    </publish-info>\n"
                + "    <annotation>An original story.</annotation>\n"
                + "  </description>\n"
                + "  <body>\n"
                + "    <chapter><p>Hello</p></chapter>\n"
                + "  </body>\n"
                + "</FictionBook>\n";
    }

    private static void assertWellFormedXml(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilder b =
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
        b.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    /** No .tmp / .bak leftovers in the book's directory.
     *  On Windows a freshly created file can be briefly locked (antivirus/indexer)
     *  during long test runs, so poll a few seconds before failing. */
    private static void assertNoLeftovers(File dir) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        String[] leftovers = listLeftovers(dir);
        while (leftovers.length > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            leftovers = listLeftovers(dir);
        }
        assertEquals("expected no .tmp/.bak leftovers", 0, leftovers.length);
    }

    private static String[] listLeftovers(File dir) {
        String[] leftovers = dir.list(new java.io.FilenameFilter() {
            @Override public boolean accept(File d, String name) {
                return name.endsWith(".tmp") || name.endsWith(".bak");
            }
        });
        return leftovers == null ? new String[0] : leftovers;
    }

    // ------------------------------------------------------------------
    // EPUB
    // ------------------------------------------------------------------

    @Test
    public void epubWriteKeepsOtherEntriesByteIdentical() throws Exception {
        final File epub = writeEpubFixture(OPF_FULL);
        Map<String, byte[]> before = readZip(epub);

        MetaData md = new MetaData();
        md.title = "New Title";
        md.author = "New Author";
        md.publisher = "New Publisher";
        md.description = "A new description";
        md.language = "ru";
        assertTrue(MetaWriter.write(epub, md));

        Map<String, byte[]> after = readZip(epub);
        assertEquals("entry set must be preserved", before.keySet(), after.keySet());
        assertTrue("chapter bytes changed",
                java.util.Arrays.equals(before.get("OEBPS/ch1.xhtml"), after.get("OEBPS/ch1.xhtml")));
        assertTrue("cover bytes changed",
                java.util.Arrays.equals(before.get("OEBPS/images/cover.jpg"), after.get("OEBPS/images/cover.jpg")));
        assertTrue("container.xml changed",
                java.util.Arrays.equals(before.get("META-INF/container.xml"), after.get("META-INF/container.xml")));

        String opf = new String(after.get("OEBPS/content.opf"), "UTF-8");
        assertTrue(opf.contains("<dc:title>New Title</dc:title>"));
        assertTrue(opf.contains("<dc:creator>New Author</dc:creator>"));
        assertTrue(opf.contains("<dc:publisher>New Publisher</dc:publisher>"));
        assertTrue(opf.contains("<dc:description>A new description</dc:description>"));
        assertTrue(opf.contains("<dc:language>ru</dc:language>"));
        assertFalse("old title must be gone", opf.contains("Original Title"));

        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void epubWriteInsertsMissingDcElementsBeforeMetadataClose() throws Exception {
        final File epub = writeEpubFixture(OPF_MINIMAL);

        MetaData md = new MetaData();
        md.title = "New Title";
        md.author = "New Author";
        md.publisher = "Inserted Publisher";
        md.description = "Inserted description";
        md.language = "fr";
        assertTrue(MetaWriter.write(epub, md));

        String opf = new String(readZip(epub).get("OEBPS/content.opf"), "UTF-8");
        int metaClose = opf.indexOf("</metadata>");
        assertTrue("</metadata> must exist", metaClose >= 0);
        for (String el : new String[]{
                "<dc:title>New Title</dc:title>",
                "<dc:creator>New Author</dc:creator>",
                "<dc:publisher>Inserted Publisher</dc:publisher>",
                "<dc:description>Inserted description</dc:description>",
                "<dc:language>fr</dc:language>"}) {
            int at = opf.indexOf(el);
            assertTrue("missing " + el, at >= 0);
            assertTrue(el + " must sit inside <metadata>", at < metaClose);
        }
        assertWellFormedXml(opf);
    }

    @Test
    public void epubWriteEscapesXmlEntities() throws Exception {
        final File epub = writeEpubFixture(OPF_FULL);

        MetaData md = new MetaData();
        md.title = "A & B <c> \"q\" 'x'";
        md.author = "New Author";
        md.publisher = "New Publisher";
        md.description = "d";
        md.language = "en";
        assertTrue(MetaWriter.write(epub, md));

        String opf = new String(readZip(epub).get("OEBPS/content.opf"), "UTF-8");
        assertTrue(opf.contains("<dc:title>A &amp; B &lt;c&gt; &quot;q&quot; &apos;x&apos;</dc:title>"));
        assertWellFormedXml(opf);
    }

    @Test
    public void epubWriteOnCorruptArchiveReturnsFalseAndLeavesFileIntact() throws Exception {
        File broken = folder.newFile("broken.epub");
        byte[] garbage = {(byte) 0x50, (byte) 0x4B, 0x03, 0x04, 0x01, 0x02, 0x03};
        FileOutputStream out = new FileOutputStream(broken);
        try { out.write(garbage); } finally { out.close(); }

        MetaData md = new MetaData();
        md.title = "T";
        md.author = "A";
        assertFalse("corrupt input must not report success", MetaWriter.write(broken, md));
        assertTrue("original bytes must be intact",
                java.util.Arrays.equals(readAll(broken), garbage));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void epubWriteWithoutContainerReturnsFalseAndLeavesFileIntact() throws Exception {
        File epub = folder.newFile("nocontainer.epub");
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("OEBPS/ch1.xhtml", text(CHAPTER_XHTML));
        writeZip(epub, entries);
        byte[] original = readAll(epub);

        MetaData md = new MetaData();
        md.title = "T";
        md.author = "A";
        assertFalse(MetaWriter.write(epub, md));
        assertTrue(java.util.Arrays.equals(readAll(epub), original));
        assertNoLeftovers(folder.getRoot());
    }

    // ------------------------------------------------------------------
    // FB2
    // ------------------------------------------------------------------

    private File writeFb2Fixture() throws Exception {
        File fb2 = folder.newFile("book.fb2");
        FileOutputStream out = new FileOutputStream(fb2);
        try { out.write(text(FB2_FIXTURE)); } finally { out.close(); }
        return fb2;
    }

    @Test
    public void fb2WriteReplacesTitleAndSplitsCombinedAuthor() throws Exception {
        final File fb2 = writeFb2Fixture();

        MetaData md = new MetaData();
        md.title = "New Title";
        md.author = "Anna Akhmatova"; // combined form from the edit form

        assertTrue("write() must succeed", MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        assertTrue(xml.contains("<title>New Title</title>"));
        // "Anna Akhmatova" -> first-name = "Anna", last-name = "Akhmatova"
        assertTrue(xml.contains("<first-name>Anna</first-name>"));
        assertTrue(xml.contains("<last-name>Akhmatova</last-name>"));
        // untouched fields stay as they were
        assertTrue(xml.contains("<middle-name>Ivanovich</middle-name>"));
        assertTrue(xml.contains("<genre>prose</genre>"));
        assertTrue(xml.contains("<publisher>Original Publisher</publisher>"));
        assertTrue(xml.contains("<lang>en</lang>"));
        assertTrue(xml.contains("<annotation>An original story.</annotation>"));
        assertWellFormedXml(xml);
        assertNoLeftovers(folder.getRoot());
    }

    /**
     * Regression: {@code <title-info>} is the parent element of {@code <title>} in FB2.
     * A tag matcher that accepts any characters after the tag name would match
     * {@code <title-info>} first and corrupt the whole description block. The title
     * must be edited in place, leaving the rest of {@code <title-info>} intact.
     */
    @Test
    public void fb2WriteDoesNotCorruptTheTitleInfoBlock() throws Exception {
        final File fb2 = writeFb2Fixture();

        MetaData md = new MetaData();
        md.title = "Only The Title";
        assertTrue("write() must succeed", MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        assertTrue("new title must be present", xml.contains("<title>Only The Title</title>"));
        assertTrue("<title-info> must survive", xml.contains("<title-info>"));
        assertTrue("</title-info> must survive", xml.contains("</title-info>"));
        assertTrue("author block must survive", xml.contains("<last-name>Petrov</last-name>"));
        assertTrue("genre must survive", xml.contains("<genre>prose</genre>"));
        assertWellFormedXml(xml);
    }

    @Test
    public void fb2WriteSingleWordAuthorGoesToLastNameOnly() throws Exception {
        final File fb2 = writeFb2Fixture();

        MetaData md = new MetaData();
        md.title = "T2";
        md.author = "Tolstoy"; // single word
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        assertTrue(xml.contains("<last-name>Tolstoy</last-name>"));
        assertTrue("first-name must not be clobbered", xml.contains("<first-name>Ivan</first-name>"));
        assertWellFormedXml(xml);
    }

    @Test
    public void fb2WriteEscapesXmlEntities() throws Exception {
        final File fb2 = writeFb2Fixture();

        MetaData md = new MetaData();
        md.title = "T & T <sub> \"q\"";
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<title>T &amp; T &lt;sub&gt; &quot;q&quot;</title>"));
        assertWellFormedXml(xml);
    }

    @Test
    public void fb2WriteReplacesTheWholeGenreList() throws Exception {
        File fb2 = folder.newFile("twogenre.fb2");
        String source = FB2_FIXTURE.replace("<genre>prose</genre>",
                "<genre>prose</genre>\n      <genre>poetry</genre>");
        FileOutputStream out = new FileOutputStream(fb2);
        try { out.write(text(source)); } finally { out.close(); }

        MetaData md = new MetaData();
        md.title = "Original Title";
        md.genres = genres("sf_history");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        assertTrue(xml.contains("<genre>sf_history</genre>"));
        assertFalse(xml.contains("<genre>prose</genre>"));
        assertFalse(xml.contains("<genre>poetry</genre>"));
        assertWellFormedXml(xml);
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2WriteInsertsGenresWhenMissing() throws Exception {
        File fb2 = folder.newFile("nogenre.fb2");
        String source = FB2_FIXTURE.replace("      <genre>prose</genre>\n", "");
        FileOutputStream out = new FileOutputStream(fb2);
        try { out.write(text(source)); } finally { out.close(); }

        MetaData md = new MetaData();
        md.genres = genres("prose", "sf");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        int proseAt = xml.indexOf("<genre>prose</genre>");
        int sfAt = xml.indexOf("<genre>sf</genre>");
        int titleInfoClose = xml.indexOf("</title-info>");
        assertTrue("prose must be inserted", proseAt >= 0);
        assertTrue("sf must be inserted", sfAt >= 0);
        assertTrue("genres sit before </title-info>", sfAt < titleInfoClose);
        assertTrue("prose comes before sf", proseAt < sfAt);
        assertWellFormedXml(xml);
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2WriteEmptyGenreListRemovesEveryGenreElement() throws Exception {
        File fb2 = folder.newFile("twogenre.fb2");
        String source = FB2_FIXTURE.replace("<genre>prose</genre>",
                "<genre>prose</genre>\n      <genre>poetry</genre>");
        FileOutputStream out = new FileOutputStream(fb2);
        try { out.write(text(source)); } finally { out.close(); }

        MetaData md = new MetaData();
        md.genres = new ArrayList<String>();
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        assertFalse(xml.contains("<genre>"));
        assertWellFormedXml(xml);
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2WriteKeepsGenresWhenTitleInfoIsUnclosed() throws Exception {
        File fb2 = folder.newFile("unclosed.fb2");
        String source = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook>\n"
                + "  <description>\n"
                + "    <title-info>\n"
                + "      <genre>prose</genre>\n"
                + "  </description>\n"
                + "</FictionBook>\n";
        byte[] before = text(source);
        writeFb2Bytes(fb2, before);

        MetaData md = new MetaData();
        md.genres = genres("sf");
        md.title = "Also A New Title";
        assertFalse(MetaWriter.write(fb2, md));
        assertBytesEqual(before, readAll(fb2));
        String xml = new String(readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<genre>prose</genre>"));
        assertFalse(xml.contains("<genre>sf</genre>"));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2WriteEscapesGenreText() throws Exception {
        final File fb2 = writeFb2Fixture();

        MetaData md = new MetaData();
        md.genres = genres("A & B <C>");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        assertTrue(xml.contains("<genre>A &amp; B &lt;C&gt;</genre>"));
        assertWellFormedXml(xml);
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void epubWriteSetsEveryDcSubject() throws Exception {
        String opfWithSubject = OPF_FULL.replace("    <dc:language>en</dc:language>\n",
                "    <dc:language>en</dc:language>\n"
              + "    <dc:subject>Old</dc:subject>\n");
        final File epub = writeEpubFixture(opfWithSubject);

        MetaData md = new MetaData();
        md.title = "Original Title";
        md.author = "Original Author";
        md.publisher = "Original Publisher";
        md.description = "An original story.";
        md.language = "en";
        md.genres = genres("Mystery", "prose");
        assertTrue(MetaWriter.write(epub, md));

        String opf = new String(readZip(epub).get("OEBPS/content.opf"), "UTF-8");
        int mAt = opf.indexOf("<dc:subject>Mystery</dc:subject>");
        int pAt = opf.indexOf("<dc:subject>prose</dc:subject>");
        assertTrue(mAt >= 0);
        assertTrue(pAt >= 0);
        assertTrue("subjects stay ordered", mAt < pAt);
        assertFalse(opf.contains("<dc:subject>Old</dc:subject>"));
        assertWellFormedXml(opf);
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void epubWriteLeavesDcSubjectUntouchedWhenGenresIsNull() throws Exception {
        String opfWithSubject = OPF_FULL.replace("    <dc:language>en</dc:language>\n",
                "    <dc:language>en</dc:language>\n"
              + "    <dc:subject>Keep</dc:subject>\n");
        final File epub = writeEpubFixture(opfWithSubject);

        MetaData md = new MetaData();
        md.title = "New Title";
        assertTrue(MetaWriter.write(epub, md));

        String opf = new String(readZip(epub).get("OEBPS/content.opf"), "UTF-8");
        assertTrue("an omitted genre must not rewrite dc:subject", opf.contains("<dc:subject>Keep</dc:subject>"));
        assertTrue(opf.contains("<dc:title>New Title</dc:title>"));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void epubWriteKeepsDcSubjectWhenMetadataIsUnclosed() throws Exception {
        String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "  <dc:subject>Keep</dc:subject>\n"
                + "</package>\n";
        final File epub = writeEpubFixture(opf);
        Map<String, byte[]> before = readZip(epub);

        MetaData md = new MetaData();
        md.genres = genres("mystery");
        assertFalse(MetaWriter.write(epub, md));

        Map<String, byte[]> after = readZip(epub);
        assertEquals(before.keySet(), after.keySet());
        for (Map.Entry<String, byte[]> e : before.entrySet()) {
            assertTrue(e.getKey() + " changed",
                    Arrays.equals(e.getValue(), after.get(e.getKey())));
        }
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void epubWriteEmptyGenreListRemovesEveryDcSubject() throws Exception {
        String opfWithSubject = OPF_FULL.replace("    <dc:language>en</dc:language>\n",
                "    <dc:language>en</dc:language>\n"
              + "    <dc:subject>One</dc:subject>\n"
              + "    <dc:subject>Two</dc:subject>\n");
        final File epub = writeEpubFixture(opfWithSubject);

        MetaData md = new MetaData();
        md.title = "Original Title";
        md.author = "Original Author";
        md.publisher = "Original Publisher";
        md.description = "An original story.";
        md.language = "en";
        md.genres = new ArrayList<String>();
        assertTrue(MetaWriter.write(epub, md));

        String opf = new String(readZip(epub).get("OEBPS/content.opf"), "UTF-8");
        assertFalse(opf.contains("One"));
        assertFalse(opf.contains("Two"));
        assertFalse(opf.contains("<dc:subject>"));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2Windows1251NullFieldsLeaveFileBytesUntouched() throws Exception {
        File fb2 = folder.newFile("w1251.fb2");
        byte[] before = fb2Windows1251Source().getBytes("windows-1251");
        writeFb2Bytes(fb2, before);

        assertTrue(MetaWriter.write(fb2, metaAllStringsNull()));
        assertBytesEqual(before, readAll(fb2));
        String declared = new String(readAll(fb2), "windows-1251");
        assertTrue(declared.contains("encoding=\"windows-1251\""));
    }

    @Test
    public void fb2Windows1251GenreWriteKeepsTitleBytesAndDeclaration() throws Exception {
        File fb2 = folder.newFile("w1251-genre.fb2");
        byte[] before = fb2Windows1251Source().getBytes("windows-1251");
        writeFb2Bytes(fb2, before);
        byte[] titleRunes = "Война и мир".getBytes("windows-1251");

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertTrue(MetaWriter.write(fb2, md));

        byte[] after = readAll(fb2);
        assertSubsequencePresent(after, titleRunes);
        String xml = new String(after, "windows-1251");
        assertTrue(xml.contains("encoding=\"windows-1251\""));
        assertTrue(xml.contains("<genre>sf</genre>"));
        assertFalse(xml.contains("<genre>prose</genre>"));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2Windows1251RejectsTitleOutsideCharsetAndLeavesBytes() throws Exception {
        File fb2 = folder.newFile("w1251-outside.fb2");
        byte[] before = fb2Windows1251Source().getBytes("windows-1251");
        writeFb2Bytes(fb2, before);

        MetaData md = metaAllStringsNull();
        md.title = "\u4E00";
        md.genres = genres("sf");
        assertFalse(MetaWriter.write(fb2, md));
        assertBytesEqual(before, readAll(fb2));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2WriteReturnsFalseOnUnknownEncodingDeclaration() throws Exception {
        File fb2 = folder.newFile("bad-encoding.fb2");
        String source = fb2Windows1251Source().replace("windows-1251", "x-not-a-charset");
        byte[] before = source.getBytes("UTF-8");
        writeFb2Bytes(fb2, before);

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertFalse(MetaWriter.write(fb2, md));
        assertBytesEqual(before, readAll(fb2));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2WriteUtf8BomSurvivesGenreInsert() throws Exception {
        File fb2 = folder.newFile("bom.fb2");
        byte[] body = text(FB2_FIXTURE);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        writeFb2Bytes(fb2, withBom);

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertTrue(MetaWriter.write(fb2, md));

        byte[] after = readAll(fb2);
        assertEquals("BOM must appear exactly once", 1, countUtf8Bom(after));
        String xml = new String(after, "UTF-8");
        assertTrue(xml.contains("encoding=\"UTF-8\""));
        assertTrue(xml.contains("<genre>sf</genre>"));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2WriteCreatesTitleInfoInsideDescription() throws Exception {
        File fb2 = folder.newFile("create-title-info.fb2");
        FileOutputStream out = new FileOutputStream(fb2);
        try {
            out.write(text(fb2WithoutTitleInfo()));
        } finally {
            out.close();
        }

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        int titleInfoOpen = xml.indexOf("<title-info>");
        int publishOpen = xml.indexOf("<publish-info>");
        assertTrue(titleInfoOpen >= 0);
        assertTrue(publishOpen > titleInfoOpen);
        assertTrue(xml.contains("<genre>sf</genre>"));
        assertFalse(xml.contains("<book-title>"));
        assertFalse(xml.contains("<author>"));
        assertFalse(xml.contains("<document-info>"));
        int langAt = xml.indexOf("<lang>");
        int titleInfoClose = xml.indexOf("</title-info>");
        assertTrue(langAt < 0 || langAt > titleInfoClose);
        assertWellFormedXml(xml);
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2WriteCreatesTitleInfoWithBookTitleAndAuthor() throws Exception {
        File fb2 = folder.newFile("create-title-info-filled.fb2");
        FileOutputStream out = new FileOutputStream(fb2);
        try {
            out.write(text(fb2WithoutTitleInfo()));
        } finally {
            out.close();
        }

        MetaData md = metaAllStringsNull();
        md.title = "Anna Akhmatova";
        md.author = "Anna Akhmatova";
        md.genres = genres("sf");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        assertTrue(xml.contains("<book-title>Anna Akhmatova</book-title>"));
        assertFalse(xml.contains("<title>Anna Akhmatova</title>"));
        assertTrue(xml.contains("<first-name>Anna</first-name>"));
        assertTrue(xml.contains("<last-name>Akhmatova</last-name>"));
        assertWellFormedXml(xml);
    }

    @Test
    public void fb2WriteCreatesDescriptionBeforeBody() throws Exception {
        File fb2 = folder.newFile("create-description.fb2");
        String source = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <body><p>Hi</p></body>\n"
                + "</FictionBook>\n";
        writeFb2Bytes(fb2, text(source));

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");

        int rootClose = xml.indexOf("<FictionBook");
        int bodyOpen = xml.indexOf("<body>");
        int descOpen = xml.indexOf("<description>");
        assertTrue(rootClose >= 0);
        assertTrue(descOpen > rootClose);
        assertTrue(bodyOpen > descOpen);
        assertTrue(xml.contains("<genre>sf</genre>"));
        assertWellFormedXml(xml);
    }

    @Test
    public void fb2WriteCreatesPrefixedTitleInfoInsidePrefixedDescription() throws Exception {
        File fb2 = folder.newFile("fb-prefix.fb2");
        String source = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<fb:FictionBook xmlns:fb=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <fb:description>\n"
                + "    <fb:publish-info><publisher>P</publisher></fb:publish-info>\n"
                + "  </fb:description>\n"
                + "  <fb:body><p>x</p></fb:body>\n"
                + "</fb:FictionBook>\n";
        writeFb2Bytes(fb2, text(source));

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<fb:title-info>"));
        assertTrue(xml.contains("<fb:genre>sf</fb:genre>"));
        assertWellFormedXml(xml);
    }

    @Test
    public void fb2WriteReturnsFalseWhenThereIsNoFictionBookRoot() throws Exception {
        File fb2 = folder.newFile("not-fb2.fb2");
        byte[] before = text("<p>hi</p>");
        writeFb2Bytes(fb2, before);

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertFalse(MetaWriter.write(fb2, md));
        assertBytesEqual(before, readAll(fb2));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void fb2WriteExpandsSelfClosingTitleInfoWithGenre() throws Exception {
        File fb2 = folder.newFile("self-close-title-info.fb2");
        String source = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook>\n"
                + "  <description>\n"
                + "    <title-info/>\n"
                + "    <publish-info/>\n"
                + "  </description>\n"
                + "  <body/>\n"
                + "</FictionBook>\n";
        writeFb2Bytes(fb2, text(source));

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");
        assertEquals(1, countOccurrences(xml, "<title-info"));
        assertEquals(1, countOccurrences(xml, "</title-info>"));
        assertTrue(xml.contains("<genre>sf</genre>"));
        assertWellFormedXml(xml);
    }

    @Test
    public void fb2WriteExpandsSelfClosingDescriptionWithTitleInfo() throws Exception {
        File fb2 = folder.newFile("self-close-description.fb2");
        String source = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook>\n"
                + "  <description/>\n"
                + "  <body/>\n"
                + "</FictionBook>\n";
        writeFb2Bytes(fb2, text(source));

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");
        assertEquals(1, countOccurrences(xml, "<description"));
        assertEquals(1, countOccurrences(xml, "</description>"));
        assertTrue(xml.contains("<title-info>"));
        assertTrue(xml.contains("<genre>sf</genre>"));
        assertWellFormedXml(xml);
    }

    @Test
    public void fb2WriteKeepsTitleTagWhenOnlyGenreChangesAndTitleIsNull() throws Exception {
        final File fb2 = writeFb2Fixture();

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<title>Original Title</title>"));
        assertFalse(xml.contains("<book-title>"));
        assertTrue(xml.contains("<last-name>Petrov</last-name>"));
    }

    @Test
    public void fb2WriteStillUsesTitleTagWhenTitleIsSent() throws Exception {
        final File fb2 = writeFb2Fixture();

        MetaData md = metaAllStringsNull();
        md.title = "Renamed In Place";
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<title>Renamed In Place</title>"));
        assertFalse(xml.contains("<book-title>Renamed In Place</book-title>"));
    }

    @Test
    public void fb2WriteUpdatesBookTitleWhenThereIsNoTitleTag() throws Exception {
        File fb2 = folder.newFile("book-title-only.fb2");
        String source = FB2_FIXTURE.replace("<title>Original Title</title>",
                "<book-title>Original Title</book-title>");
        writeFb2Bytes(fb2, text(source));

        MetaData md = metaAllStringsNull();
        md.title = "New Book Title";
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<book-title>New Book Title</book-title>"));
        assertFalse(xml.contains("<title>New Book Title</title>"));
    }

    @Test
    public void fb2WriteReplacesGenresOnlyInsideTitleInfoNotSrcTitleInfo() throws Exception {
        File fb2 = folder.newFile("src-title-info.fb2");
        String source = FB2_FIXTURE.replace("</title-info>",
                "    </title-info>\n"
                        + "    <src-title-info>\n"
                        + "      <genre>poetry</genre>\n"
                        + "    </src-title-info>");
        writeFb2Bytes(fb2, text(source));

        MetaData md = metaAllStringsNull();
        md.genres = genres("sf", "history");
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<genre>poetry</genre>"));
        assertTrue(xml.contains("<genre>sf</genre>"));
        assertTrue(xml.contains("<genre>history</genre>"));
        assertFalse(xml.contains("<genre>prose</genre>"));
    }

    @Test
    public void fb2WriteLeavesGenresUntouchedWhenGenresIsNull() throws Exception {
        final File fb2 = writeFb2Fixture();

        MetaData md = metaAllStringsNull();
        md.title = "New Title";
        assertTrue(MetaWriter.write(fb2, md));
        String xml = new String(readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<genre>prose</genre>"));
        assertTrue(xml.contains("<title>New Title</title>"));
    }

    @Test
    public void epubWriteGenreOnlyLeavesOtherDcFieldsUntouched() throws Exception {
        final File epub = writeEpubFixture(OPF_FULL);
        Map<String, byte[]> before = readZip(epub);

        MetaData md = metaAllStringsNull();
        md.genres = genres("Mystery");
        assertTrue(MetaWriter.write(epub, md));

        Map<String, byte[]> after = readZip(epub);
        assertTrue(Arrays.equals(before.get("OEBPS/ch1.xhtml"), after.get("OEBPS/ch1.xhtml")));
        assertTrue(Arrays.equals(before.get("OEBPS/images/cover.jpg"),
                after.get("OEBPS/images/cover.jpg")));
        assertTrue(Arrays.equals(before.get("META-INF/container.xml"),
                after.get("META-INF/container.xml")));

        String opf = new String(after.get("OEBPS/content.opf"), "UTF-8");
        assertTrue(opf.contains("<dc:title>Original Title</dc:title>"));
        assertTrue(opf.contains("<dc:language>en</dc:language>"));
        assertFalse(opf.contains("<dc:language></dc:language>"));
        assertFalse(opf.contains("<dc:language/>"));
        assertTrue(opf.contains("<dc:subject>Mystery</dc:subject>"));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void epubWriteCreatesMetadataBetweenPackageAndManifest() throws Exception {
        String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"2.0\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "  <manifest>\n"
                + "    <item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                + "  </manifest>\n"
                + "</package>\n";
        final File epub = writeEpubFixture(opf);
        String packageOpen = opf.substring(opf.indexOf("<package"), opf.indexOf(">") + 1);

        MetaData md = metaAllStringsNull();
        md.genres = genres("mystery");
        assertTrue(MetaWriter.write(epub, md));

        String written = new String(readZip(epub).get("OEBPS/content.opf"), "UTF-8");
        assertTrue(written.startsWith(packageOpen));
        int metaAt = written.indexOf("<metadata>");
        int manifestAt = written.indexOf("<manifest>");
        assertTrue(metaAt > 0);
        assertTrue(manifestAt > metaAt);
        assertTrue(written.contains("<dc:subject>mystery</dc:subject>"));
        assertEquals(1, countOccurrences(written, "xmlns:dc=\"http://purl.org/dc/elements/1.1/\""));
    }

    @Test
    public void epubWriteCreatesMetadataWithXmlnsDcWhenPackageLacksIt() throws Exception {
        String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"2.0\">\n"
                + "  <spine toc=\"ncx\">\n"
                + "    <itemref idref=\"ch1\"/>\n"
                + "  </spine>\n"
                + "</package>\n";
        final File epub = writeEpubFixture(opf);
        String packageTag = opf.substring(opf.indexOf("<package"), opf.indexOf(">") + 1);

        MetaData md = metaAllStringsNull();
        md.genres = genres("prose");
        assertTrue(MetaWriter.write(epub, md));

        String written = new String(readZip(epub).get("OEBPS/content.opf"), "UTF-8");
        assertTrue(written.contains(packageTag));
        assertTrue(written.contains("xmlns:dc=\"http://purl.org/dc/elements/1.1/\""));
        assertTrue(written.contains("<dc:subject>prose</dc:subject>"));
    }

    @Test
    public void epubWriteReturnsFalseWhenMetadataTagIsUnclosed() throws Exception {
        String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"2.0\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "  <metadata>\n"
                + "    <dc:title>T</dc:title>\n"
                + "  <manifest>\n"
                + "    <item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                + "  </manifest>\n"
                + "</package>\n";
        final File epub = writeEpubFixture(opf);
        Map<String, byte[]> before = readZip(epub);

        MetaData md = metaAllStringsNull();
        md.genres = genres("mystery");
        assertFalse(MetaWriter.write(epub, md));
        assertZipEntriesByteIdentical(before, readZip(epub));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void epubWriteExpandsSelfClosingMetadataWithSubject() throws Exception {
        String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"2.0\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "  <metadata/>\n"
                + "  <manifest>\n"
                + "    <item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                + "  </manifest>\n"
                + "</package>\n";
        final File epub = writeEpubFixture(opf);

        MetaData md = metaAllStringsNull();
        md.genres = genres("mystery");
        assertTrue(MetaWriter.write(epub, md));

        String written = new String(readZip(epub).get("OEBPS/content.opf"), "UTF-8");
        assertEquals(1, countOccurrences(written, "<metadata"));
        assertEquals(1, countOccurrences(written, "</metadata>"));
        assertTrue(written.contains("<dc:subject>mystery</dc:subject>"));
    }

    @Test
    public void epubWriteReturnsFalseWhenPackageRootIsMissing() throws Exception {
        String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<dc:subject>Keep</dc:subject>\n";
        final File epub = writeEpubFixture(opf);
        Map<String, byte[]> before = readZip(epub);

        MetaData md = metaAllStringsNull();
        md.genres = genres("mystery");
        assertFalse(MetaWriter.write(epub, md));
        assertZipEntriesByteIdentical(before, readZip(epub));
        assertNoLeftovers(folder.getRoot());
    }

    @Test
    public void epubWriteLeavesDcSubjectAndTitleUntouchedWhenGenresAndStringsAreNull() throws Exception {
        String opfWithSubject = OPF_FULL.replace("    <dc:language>en</dc:language>\n",
                "    <dc:language>en</dc:language>\n"
                        + "    <dc:subject>Keep</dc:subject>\n");
        final File epub = writeEpubFixture(opfWithSubject);

        MetaData md = metaAllStringsNull();
        assertTrue(MetaWriter.write(epub, md));

        String opf = new String(readZip(epub).get("OEBPS/content.opf"), "UTF-8");
        assertTrue(opf.contains("<dc:subject>Keep</dc:subject>"));
        assertTrue(opf.contains("<dc:title>Original Title</dc:title>"));
    }

    private static void assertZipEntriesByteIdentical(Map<String, byte[]> before,
            Map<String, byte[]> after) {
        assertEquals(before.keySet(), after.keySet());
        for (Map.Entry<String, byte[]> e : before.entrySet()) {
            assertTrue(e.getKey() + " changed",
                    Arrays.equals(e.getValue(), after.get(e.getKey())));
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = 0;
        while (true) {
            int found = haystack.indexOf(needle, at);
            if (found < 0) break;
            count++;
            at = found + needle.length();
        }
        return count;
    }

    private static ArrayList<String> genres(String... names) {
        ArrayList<String> g = new ArrayList<String>();
        for (int i = 0; i < names.length; i++) g.add(names[i]);
        return g;
    }

    @Test
    public void writeOnUnsupportedFormatReturnsFalseAndLeavesFileIntact() throws Exception {
        File pdf = folder.newFile("book.pdf");
        byte[] content = "%PDF-1.4 fake binary".getBytes("UTF-8");
        FileOutputStream out = new FileOutputStream(pdf);
        try { out.write(content); } finally { out.close(); }

        MetaData md = new MetaData();
        md.title = "T";
        assertFalse("PDF is not editable in-file", MetaWriter.write(pdf, md));
        assertTrue(java.util.Arrays.equals(readAll(pdf), content));
    }
}
