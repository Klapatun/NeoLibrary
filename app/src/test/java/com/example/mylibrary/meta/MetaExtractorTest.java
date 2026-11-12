package com.example.mylibrary.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import com.example.mylibrary.testutil.TestFixtures;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**
 * Robolectric tests for {@link MetaExtractor} — the EPUB/FB2 paths need the real
 * platform {@code XmlPullParser} (plain JVM unit tests can't run them). Run at
 * {@code sdk = 19}, the project's API floor.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class MetaExtractorTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File epub;
    private File fb2;
    private File mobi;

    @Before
    public void setUp() throws Exception {
        epub = folder.newFile("book.epub");
        fb2 = folder.newFile("book.fb2");
        mobi = folder.newFile("book.mobi");
    }

    /** A small deterministic JPEG payload (starts FFD8, ends FFD9) for the MOBI fixture. */
    private static byte[] jpegBytes() {
        java.io.ByteArrayOutputStream j = new java.io.ByteArrayOutputStream();
        j.write(0xFF); j.write(0xD8);
        for (int i = 0; i < 32; i++) j.write(i * 7 + 3);
        j.write(0xFF); j.write(0xD9);
        return j.toByteArray();
    }

    private File epubWith(String opf) throws Exception {
        TestFixtures.writeEpub(epub, opf);
        return epub;
    }

    // ------------------------------------------------------------------
    // EPUB
    // ------------------------------------------------------------------

    @Test
    public void epubExtractsFullDublinCoreSet() throws Exception {
        epubWith(TestFixtures.OPF_FULL);
        MetaData md = MetaExtractor.extract(epub);

        assertTrue(md.found);
        assertEquals("Original Title", md.title);
        assertEquals("Original Author", md.author);
        assertEquals("Original Publisher", md.publisher);
        assertEquals("An original story.", md.description);
        assertEquals("en", md.language);
    }

    @Test
    public void epubWithoutTitleIsNotFound() throws Exception {
        epubWith(TestFixtures.OPF_CREATOR_ONLY);
        MetaData md = MetaExtractor.extract(epub);

        assertFalse("found requires a title", md.found);
        assertEquals(null, md.title);
    }

    @Test
    public void epubWithoutContainerIsNotFound() throws Exception {
        // A zip that has no META-INF/container.xml at all.
        Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("OEBPS/ch1.xhtml", TestFixtures.CHAPTER_XHTML.getBytes("UTF-8"));
        TestFixtures.writeZip(epub, entries);

        MetaData md = MetaExtractor.extract(epub);
        assertFalse(md.found);
    }

    // ------------------------------------------------------------------
    // FB2
    // ------------------------------------------------------------------

    @Test
    public void fb2ExtractsTitleAuthorPublisherAnnotationAndLang() throws Exception {
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);
        MetaData md = MetaExtractor.extract(fb2);

        assertTrue(md.found);
        assertEquals("Original Title", md.title);
        assertEquals("Ivan Ivanovich Petrov", md.author);
        assertEquals("Ivan", md.firstName);
        assertEquals("Ivanovich", md.middleName);
        assertEquals("Petrov", md.lastName);
        assertEquals("Original Publisher", md.publisher);
        assertEquals("An original story.", md.description);
        assertEquals("en", md.language);
        assertGenres("prose", md.genres);
    }

    @Test
    public void fb2WithoutTitleIsNotFound() throws Exception {
        TestFixtures.writeText(fb2, TestFixtures.FB2_NO_TITLE);
        MetaData md = MetaExtractor.extract(fb2);
        assertFalse(md.found);
    }

    @Test
    public void fb2BookTitleOnlyYieldsFoundTitleAndGenres() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <description>\n"
                + "    <title-info>\n"
                + "      <book-title>Война и мир</book-title>\n"
                + "      <genre>sf</genre>\n"
                + "    </title-info>\n"
                + "  </description>\n"
                + "</FictionBook>\n";
        TestFixtures.writeText(fb2, xml);
        MetaData md = MetaExtractor.extract(fb2);
        assertTrue(md.found);
        assertEquals("Война и мир", md.title);
        assertGenres("sf", md.genres);

        byte[] raw = TestFixtures.readAll(fb2);
        MetaData parsed = MetaExtractor.parseFb2Xml(new ByteArrayInputStream(raw));
        assertTrue(parsed.found);
        assertEquals("Война и мир", parsed.title);
        assertGenres("sf", parsed.genres);

        String prefixed = xml.replace(
                "<book-title>Война и мир</book-title>",
                "<fb:book-title xmlns:fb=\"http://www.gribuser.ru/xml/fictionbook/2.0\""
                        + ">Война и мир</fb:book-title>");
        TestFixtures.writeText(fb2, prefixed);
        md = MetaExtractor.extract(fb2);
        assertEquals("Война и мир", md.title);
    }

    @Test
    public void fb2BookTitleWinsWhenTitleComesFirstInTitleInfo() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <description>\n"
                + "    <title-info>\n"
                + "      <title>Из title</title>\n"
                + "      <book-title>Из book-title</book-title>\n"
                + "      <book-title>Second book-title</book-title>\n"
                + "    </title-info>\n"
                + "  </description>\n"
                + "</FictionBook>\n";
        TestFixtures.writeText(fb2, xml);
        MetaData md = MetaExtractor.extract(fb2);
        assertTrue(md.found);
        assertEquals("Из book-title", md.title);
    }

    @Test
    public void fb2WhitespaceBookTitleFallsBackToTitleInTitleInfo() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <description>\n"
                + "    <title-info>\n"
                + "      <book-title>   </book-title>\n"
                + "      <title>Из title</title>\n"
                + "    </title-info>\n"
                + "  </description>\n"
                + "</FictionBook>\n";
        TestFixtures.writeText(fb2, xml);
        MetaData md = MetaExtractor.extract(fb2);
        assertTrue(md.found);
        assertEquals("Из title", md.title);
    }

    @Test
    public void fb2IgnoresBodySectionTitleAfterClosedTitleInfo() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <description>\n"
                + "    <title-info>\n"
                + "      <genre>sf</genre>\n"
                + "    </title-info>\n"
                + "    <body><title><p>Глава</p></title></body>\n"
                + "  </description>\n"
                + "</FictionBook>\n";
        byte[] raw = xml.getBytes("UTF-8");
        MetaData parsed = MetaExtractor.parseFb2Xml(new ByteArrayInputStream(raw));
        assertFalse(parsed.found);
        assertNull(parsed.title);
        assertGenres("sf", parsed.genres);

        TestFixtures.writeText(fb2, xml);
        MetaData md = MetaExtractor.extract(fb2);
        assertFalse(md.found);
        assertEquals("book", md.title);
    }

    @Test
    public void fb2IgnoresBodyTitleBeforeDescriptionEndsWhenTitleInfoOpen() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <description>\n"
                + "    <title-info>\n"
                + "      <genre>sf</genre>\n"
                + "    <body>\n"
                + "      <title><p>Глава</p></title>\n"
                + "    </body>\n"
                + "  </description>\n"
                + "</FictionBook>\n";
        TestFixtures.writeText(fb2, xml);
        MetaData md = MetaExtractor.extract(fb2);
        assertFalse(md.found);
        assertGenres("sf", md.genres);
        assertFalse("Глава".equals(md.title));
    }

    @Test
    public void fb2IgnoresBookTitleInSrcTitleInfo() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
                + "  <description>\n"
                + "    <title-info>\n"
                + "      <genre>prose</genre>\n"
                + "    </title-info>\n"
                + "    <src-title-info>\n"
                + "      <book-title>Источник</book-title>\n"
                + "    </src-title-info>\n"
                + "  </description>\n"
                + "</FictionBook>\n";
        byte[] raw = xml.getBytes("UTF-8");
        MetaData parsed = MetaExtractor.parseFb2Xml(new ByteArrayInputStream(raw));
        assertFalse(parsed.found);
        assertNull(parsed.title);
        assertGenres("prose", parsed.genres);

        File novel = folder.newFile("my_novel.fb2");
        TestFixtures.writeText(novel, xml);
        MetaData md = MetaExtractor.extract(novel);
        assertFalse(md.found);
        assertEquals("my novel", md.title);
    }

    @Test
    public void fb2NoTitleExtractFillsFileNameButParseStaysNotFound() throws Exception {
        TestFixtures.writeText(fb2, TestFixtures.FB2_NO_TITLE);
        MetaData md = MetaExtractor.extract(fb2);
        assertFalse(md.found);
        assertEquals("book", md.title);
        assertGenres("prose", md.genres);

        byte[] raw = TestFixtures.readAll(fb2);
        MetaData parsed = MetaExtractor.parseFb2Xml(new ByteArrayInputStream(raw));
        assertFalse(parsed.found);
        assertNull(parsed.title);
        assertGenres("prose", parsed.genres);
    }

    @Test
    public void fb2FullKeepsTagTitleDespiteFileName() throws Exception {
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);
        MetaData md = MetaExtractor.extract(fb2);
        assertTrue(md.found);
        assertEquals("Original Title", md.title);
    }

    @Test
    public void fb2CorruptXmlIsNotFoundWithoutThrowing() throws Exception {
        TestFixtures.writeBytes(fb2, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x02});
        MetaData md = MetaExtractor.extract(fb2);
        assertFalse(md.found);
    }

    @Test
    public void fb2Windows1251ExtractAndParseFb2XmlAgreeOnTitle() throws Exception {
        String xml = TestFixtures.FB2_FULL
                .replace("encoding=\"UTF-8\"", "encoding=\"windows-1251\"")
                .replace("Original Title", "Война и мир");
        byte[] raw = xml.getBytes("windows-1251");
        TestFixtures.writeBytes(fb2, raw);
        assertTrue(Arrays.equals(raw, TestFixtures.readAll(fb2)));

        MetaData md = MetaExtractor.extract(fb2);
        assertTrue(md.found);
        assertEquals("Война и мир", md.title);

        MetaData parsed = MetaExtractor.parseFb2Xml(new ByteArrayInputStream(raw));
        assertEquals("Война и мир", parsed.title);
    }

    @Test
    public void epubWindows1251OpfYieldsTitleFromExtract() throws Exception {
        String opf = TestFixtures.OPF_FULL
                .replace("encoding=\"UTF-8\"", "encoding=\"windows-1251\"")
                .replace("Original Title", "Война и мир");
        Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("META-INF/container.xml", TestFixtures.CONTAINER_XML.getBytes("UTF-8"));
        entries.put("OEBPS/content.opf", opf.getBytes("windows-1251"));
        entries.put("OEBPS/ch1.xhtml", TestFixtures.CHAPTER_XHTML.getBytes("UTF-8"));
        TestFixtures.writeZip(epub, entries);

        MetaData md = MetaExtractor.extract(epub);
        assertTrue(md.found);
        assertEquals("Война и мир", md.title);
    }

    @Test
    public void fb2UnknownEncodingIsNotFoundAndLeavesBytes() throws Exception {
        String xml = TestFixtures.FB2_FULL.replace("encoding=\"UTF-8\"", "encoding=\"x-not-a-charset\"");
        byte[] raw = xml.getBytes("UTF-8");
        TestFixtures.writeBytes(fb2, raw);

        MetaData md = MetaExtractor.extract(fb2);
        assertFalse(md.found);
        assertNull(md.title);
        assertTrue(Arrays.equals(raw, TestFixtures.readAll(fb2)));
    }

    // ------------------------------------------------------------------
    // FB2ZIP
    // ------------------------------------------------------------------

    @Test
    public void fb2ZipExtractsTitleAuthorPublisherAnnotationAndLang() throws Exception {
        File fz = folder.newFile("archive.fb2.zip");
        Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("archive.fb2", TestFixtures.FB2_FULL.getBytes("UTF-8"));
        TestFixtures.writeZip(fz, entries);

        MetaData md = MetaExtractor.extract(fz);

        assertTrue(md.found);
        assertEquals("Original Title", md.title);
        assertEquals("Ivan Ivanovich Petrov", md.author);
        assertEquals("Ivan", md.firstName);
        assertEquals("Ivanovich", md.middleName);
        assertEquals("Petrov", md.lastName);
        assertEquals("Original Publisher", md.publisher);
        assertEquals("An original story.", md.description);
        assertEquals("en", md.language);
        assertGenres("prose", md.genres);
    }

  @Test
  public void fb2ExtractsGenresBeforeTitleInfoInOrder() throws Exception {
    String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
            + "  <description>\n"
            + "    <title-info>\n"
            + "      <genre>sf</genre>\n"
            + "      <genre>prose</genre>\n"
            + "      <title>T</title>\n"
            + "    </title-info>\n"
            + "    <src-title-info>\n"
            + "      <genre>poetry</genre>\n"
            + "    </src-title-info>\n"
            + "  </description>\n"
            + "</FictionBook>\n";
    TestFixtures.writeText(fb2, xml);
    MetaData md = MetaExtractor.extract(fb2);
    assertGenres("sf", "prose", md.genres);
  }

  @Test
  public void fb2ExtractWithoutTitleInfoCloseKeepsGenresUntilDescription() throws Exception {
    String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n"
            + "  <description>\n"
            + "    <title-info>\n"
            + "      <genre>sf</genre>\n"
            + "      <genre>prose</genre>\n"
            + "    <title>T</title>\n"
            + "    <publish-info><publisher>P</publisher></publish-info>\n"
            + "  </description>\n"
            + "</FictionBook>\n";
    TestFixtures.writeText(fb2, xml);
    MetaData md = MetaExtractor.extract(fb2);
    assertGenres("sf", "prose", md.genres);
  }

  @Test
  public void epubExtractsEveryDcSubjectInOrder() throws Exception {
    String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<package xmlns=\"http://www.idpf.org/2009/opf\" version=\"2.0\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
            + "  <metadata>\n"
            + "    <dc:title>Original Title</dc:title>\n"
            + "    <dc:creator>Original Author</dc:creator>\n"
            + "    <dc:subject>Mystery</dc:subject>\n"
            + "    <dc:subject>prose</dc:subject>\n"
            + "  </metadata>\n"
            + "</package>\n";
    epubWith(opf);
    MetaData md = MetaExtractor.extract(epub);
    assertTrue(md.found);
    assertEquals("Original Title", md.title);
    assertGenres("mystery", "prose", md.genres);
  }

  @Test
  public void normalizeGenresFoldsCaseAndKeepsYo() {
    ArrayList<String> in = new ArrayList<String>();
    in.add("Фэнтези");
    in.add("фэнтези");
    in.add("Ёлка");
    in.add("елка");
    ArrayList<String> out = MetaData.normalizeGenres(in);
    assertEquals(3, out.size());
    assertEquals("фэнтези", out.get(0));
    assertEquals("ёлка", out.get(1));
    assertEquals("елка", out.get(2));
  }

  @Test
  public void epubWithoutSubjectHasEmptyGenreList() throws Exception {
    epubWith(TestFixtures.OPF_FULL);
    MetaData md = MetaExtractor.extract(epub);
    assertNotNull(md.genres);
    assertTrue(md.genres.isEmpty());
  }

    @Test
    public void fb2ZipFindsFb2InSubfolderAndIgnoresOtherEntries() throws Exception {
        File fz = folder.newFile("nested.fb2.zip");
        Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("README.txt", "just a readme\n".getBytes("UTF-8"));
        entries.put("books/story.fb2", TestFixtures.FB2_FULL.getBytes("UTF-8"));
        TestFixtures.writeZip(fz, entries);

        MetaData md = MetaExtractor.extract(fz);
        assertTrue(md.found);
        assertEquals("Original Title", md.title);
    }

    @Test
    public void fb2ZipWithoutFb2EntryIsNotFound() throws Exception {
        File fz = folder.newFile("empty.fb2.zip");
        Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("notes.txt", "no book here\n".getBytes("UTF-8"));
        TestFixtures.writeZip(fz, entries);

        MetaData md = MetaExtractor.extract(fz);
        assertFalse(md.found);
    }

    @Test
    public void corruptFb2ZipIsNotFoundWithoutThrowing() throws Exception {
        File fz = folder.newFile("broken.fb2.zip");
        TestFixtures.writeBytes(fz, new byte[]{0x00, 0x01, 0x02, 0x03});
        MetaData md = MetaExtractor.extract(fz);
        assertFalse(md.found);
    }

    // ------------------------------------------------------------------
    // MOBI
    // ------------------------------------------------------------------

    @Test
    public void mobiExtractsTitleAuthorPublisherDescriptionAndLanguage() throws Exception {
        // Non-ASCII (Polish) title and author to exercise the UTF-8 code-page path.
        TestFixtures.writeMobi(mobi, "Czterysta: Zbiór opowiadań", "Jan Kowalski",
                "Wydawnictwo Testowe", "Historia o przygodach.", "pl", jpegBytes(), 2, 1);
        MetaData md = MetaExtractor.extract(mobi);

        assertTrue(md.found);
        assertEquals("Czterysta: Zbiór opowiadań", md.title);
        assertEquals("Jan Kowalski", md.author);
        assertEquals("Wydawnictwo Testowe", md.publisher);
        assertEquals("Historia o przygodach.", md.description);
        assertEquals("pl", md.language);
    }

    @Test
    public void mobiWithAzwExtensionIsParsedTheSameWay() throws Exception {
        File azw = folder.newFile("book.azw");
        TestFixtures.writeMobi(azw, "Azw Title", "Author", "Pub", "Desc.", "en",
                jpegBytes(), 2, 1);

        MetaData md = MetaExtractor.extract(azw);
        assertTrue(md.found);
        assertEquals("Azw Title", md.title);
        assertEquals("Author", md.author);
    }

    @Test
    public void corruptMobiReportsNotFound() throws Exception {
        TestFixtures.writeBytes(mobi, new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05});
        assertFalse(MetaExtractor.extract(mobi).found);
    }

    // ------------------------------------------------------------------
    // TXT / HTML
    // ------------------------------------------------------------------

    @Test
    public void txtTitleComesFromFileName() throws Exception {
        File txt = folder.newFile("my_plain_novel.txt");
        TestFixtures.writeText(txt, "Some plain text story.\n");

        MetaData md = MetaExtractor.extract(txt);
        assertTrue(md.found);
        assertEquals("my plain novel", md.title);
    }

    @Test
    public void htmlTitleComesFromTitleTag() throws Exception {
        File html = folder.newFile("guide.html");
        TestFixtures.writeText(html,
                "<html><head><title>My Guide</title></head><body>hi</body></html>\n");

        MetaData md = MetaExtractor.extract(html);
        assertTrue(md.found);
        assertEquals("My Guide", md.title);
    }

    @Test
    public void htmlWithoutTitleTagFallsBackToFileName() throws Exception {
        File html = folder.newFile("no_title_page.html");
        TestFixtures.writeText(html, "<html><body>just text</body></html>\n");

        MetaData md = MetaExtractor.extract(html);
        assertTrue(md.found);
        assertEquals("no title page", md.title);
    }

    // ------------------------------------------------------------------
    // everything else
    // ------------------------------------------------------------------

    @Test
    public void unsupportedContainersReportNotFound() throws Exception {
        File pdf = folder.newFile("book.pdf");
        TestFixtures.writeBytes(pdf, new byte[]{0x25, 0x25, 0x50, 0x44});
        assertFalse(MetaExtractor.extract(pdf).found);

        File doc = folder.newFile("book.doc");
        TestFixtures.writeBytes(doc, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
        assertFalse(MetaExtractor.extract(doc).found);
    }

    @Test
    public void unknownExtensionReportsNotFound() throws Exception {
        File mdFile = folder.newFile("notes.md");
        TestFixtures.writeText(mdFile, "# nothing\n");
        MetaData md = MetaExtractor.extract(mdFile);
        assertNotNull(md);
        assertFalse(md.found);
    }

  private static void assertGenres(String first, ArrayList<String> actual) {
    assertGenres(new String[]{first}, actual);
  }

  private static void assertGenres(String a, String b, ArrayList<String> actual) {
    assertGenres(new String[]{a, b}, actual);
  }

  private static void assertGenres(String[] expected, ArrayList<String> actual) {
    assertNotNull(actual);
    assertEquals(expected.length, actual.size());
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], actual.get(i));
    }
  }
}
