package com.example.mylibrary.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import com.example.mylibrary.testutil.TestFixtures;

import java.io.File;
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
        assertEquals("prose", md.genre);
    }

    @Test
    public void fb2WithoutTitleIsNotFound() throws Exception {
        TestFixtures.writeText(fb2, TestFixtures.FB2_NO_TITLE);
        MetaData md = MetaExtractor.extract(fb2);
        assertFalse(md.found);
    }

    @Test
    public void fb2CorruptXmlIsNotFoundWithoutThrowing() throws Exception {
        TestFixtures.writeBytes(fb2, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x02});
        MetaData md = MetaExtractor.extract(fb2);
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
        assertEquals("my_plain_novel", md.title);
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
        assertEquals("no_title_page", md.title);
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
}
