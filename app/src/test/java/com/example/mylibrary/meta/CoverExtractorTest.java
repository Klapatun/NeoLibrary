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

import java.io.File;
import java.util.Base64;

/**
 * Robolectric tests for {@link CoverExtractor}. The FB2 path goes through the
 * platform {@code Base64}, so it needs the Android runtime; everything else is
 * zip/string parsing. Run at {@code sdk = 19}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class CoverExtractorTest {

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

    /** A small deterministic JPEG payload (starts FFD8, ends FFD9). */
    private static byte[] jpegBytes() {
        java.io.ByteArrayOutputStream j = new java.io.ByteArrayOutputStream();
        j.write(0xFF); j.write(0xD8);
        for (int i = 0; i < 32; i++) j.write(i * 7 + 3);
        j.write(0xFF); j.write(0xD9);
        return j.toByteArray();
    }

    // ------------------------------------------------------------------
    // canHaveCover
    // ------------------------------------------------------------------

    @Test
    public void canHaveCoverIsTrueForEpubFb2Fb2ZipAndMobi() {
        assertTrue(CoverExtractor.canHaveCover("EPUB"));
        assertTrue(CoverExtractor.canHaveCover("FB2"));
        assertTrue(CoverExtractor.canHaveCover("FB2ZIP"));
        assertTrue(CoverExtractor.canHaveCover("MOBI"));
        assertFalse(CoverExtractor.canHaveCover("PDF"));
        assertFalse(CoverExtractor.canHaveCover("DJVU"));
        assertFalse(CoverExtractor.canHaveCover("CHM"));
        assertFalse(CoverExtractor.canHaveCover(null));
        assertFalse(CoverExtractor.canHaveCover(""));
    }

    // ------------------------------------------------------------------
    // EPUB covers
    // ------------------------------------------------------------------

    @Test
    public void epubCoverViaEpub2MetaIsReturned() throws Exception {
        TestFixtures.writeEpub(epub, TestFixtures.OPF_WITH_COVER);

        byte[] cover = CoverExtractor.extract(epub);
        assertNotNull("cover must be found", cover);
        assertTrue("cover bytes must match the manifest entry",
                java.util.Arrays.equals(cover, TestFixtures.coverBytes()));
    }

    @Test
    public void epubCoverViaEpub3PropertyIsReturned() throws Exception {
        // EPUB3 property form; cover lives at the archive root (dir-relative resolve).
        TestFixtures.writeEpub3CoverCase(epub, TestFixtures.OPF3_WITH_COVER);

        byte[] cover = CoverExtractor.extract(epub);
        assertNotNull("cover must be found via property=\"cover-image\"", cover);
    }

    @Test
    public void epubWithoutCoverDeclarationReturnsNull() throws Exception {
        // OPF_FULL has no cover meta and no manifest item named *cover*.
        TestFixtures.writeEpub(epub, TestFixtures.OPF_FULL);
        assertNull(CoverExtractor.extract(epub));
    }

    @Test
    public void corruptEpubReturnsNullWithoutThrowing() throws Exception {
        TestFixtures.writeBytes(epub, new byte[]{(byte) 0x50, (byte) 0x4B, 0x03, 0x04, 0x01});
        assertNull(CoverExtractor.extract(epub));
    }

    // ------------------------------------------------------------------
    // FB2 covers
    // ------------------------------------------------------------------

    @Test
    public void fb2CoverBase64RoundTripsToOriginalBytes() throws Exception {
        byte[] cover = TestFixtures.coverBytes();
        String base64 = Base64.getEncoder().encodeToString(cover);
        TestFixtures.writeText(fb2, TestFixtures.buildFb2WithCover(base64));

        byte[] extracted = CoverExtractor.extract(fb2);
        assertNotNull("cover must be found", extracted);
        assertTrue("base64 payload must decode to the original bytes",
                java.util.Arrays.equals(extracted, cover));
    }

    @Test
    public void fb2WithoutCoverpageReturnsNull() throws Exception {
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);
        assertNull(CoverExtractor.extract(fb2));
    }

    @Test
    public void fb2CoverPageWithMissingBinaryReturnsNull() throws Exception {
        // coverpage references an id that has no <binary> entry.
        String xml = TestFixtures.buildFb2WithCover("QUJD").replace(
                "<binary id=\"coverimg\" content-type=\"image/jpeg\">QUJD</binary>", "");
        TestFixtures.writeText(fb2, xml);
        assertNull(CoverExtractor.extract(fb2));
    }

    /**
     * A real FB2's body (the bulk of the file) follows the binary block: the streaming
     * reader must stop at {@code <body} and still find the cover — and the metadata
     * path (separate pass, same {@code </description>} stop) must work on the same file.
     */
    @Test
    public void fb2WithLargeBodyAfterDescriptionStillYieldsCoverAndMeta() throws Exception {
        byte[] cover = TestFixtures.coverBytes();
        String base64 = Base64.getEncoder().encodeToString(cover);
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" "
                + "xmlns:l=\"http://www.w3.org/1999/xlink\">\n");
        sb.append("  <description>\n");
        sb.append("    <title-info>\n");
        sb.append("      <title>Big Body</title>\n");
        sb.append("    </title-info>\n");
        sb.append("    <coverpage>\n");
        sb.append("      <image l:href=\"#coverimg\"/>\n");
        sb.append("    </coverpage>\n");
        sb.append("  </description>\n");
        sb.append("  <binary id=\"coverimg\" content-type=\"image/jpeg\">")
                .append(base64).append("</binary>\n");
        sb.append("  <body>\n");
        for (int i = 0; i < 12000; i++) {
            sb.append("    <p>Filler paragraph number ").append(i).append(". </p>\n");
        }
        sb.append("  </body>\n");
        sb.append("</FictionBook>\n");
        TestFixtures.writeText(fb2, sb.toString());

        byte[] extracted = CoverExtractor.extract(fb2);
        assertNotNull("cover must be found before the body", extracted);
        assertTrue(java.util.Arrays.equals(extracted, cover));

        MetaData md = MetaExtractor.extract(fb2);
        assertTrue(md.found);
        assertEquals("Big Body", md.title);
    }

    /** base64 with newlines and tabs (wrapped lines are common in real FB2 files) must
     *  decode to exactly the original bytes. */
    @Test
    public void fb2CoverBase64WithLineBreaksAndTabsIsDecoded() throws Exception {
        byte[] cover = TestFixtures.coverBytes();
        String raw = Base64.getEncoder().encodeToString(cover);
        StringBuilder broken = new StringBuilder(raw.length() + 64);
        for (int i = 0; i < raw.length(); i++) {
            broken.append(raw.charAt(i));
            if (i % 20 == 0) broken.append('\n');
            else if (i % 7 == 0) broken.append('\t');
        }
        TestFixtures.writeText(fb2, TestFixtures.buildFb2WithCover(broken.toString()));

        byte[] extracted = CoverExtractor.extract(fb2);
        assertNotNull("cover must be found", extracted);
        assertTrue("whitespace in the base64 must be skipped",
                java.util.Arrays.equals(extracted, cover));
    }

    /**
     * The streaming reader reads in 8 KB chunks: when the closing {@code </description>}
     * tag is split across two reads (the marker straddles the chunk boundary) it must
     * still be found — the search tail is what makes that work. A ~100 KB body after
     * the binary block proves the reader actually stopped at {@code <body} (a reader
     * that missed the split tag would buffer the whole file).
     */
    @Test
    public void closingDescriptionSplitAcrossReadChunksIsStillFound() throws Exception {
        byte[] cover = TestFixtures.coverBytes();
        String base64 = Base64.getEncoder().encodeToString(cover);
        String prefix = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" "
                + "xmlns:l=\"http://www.w3.org/1999/xlink\">\n"
                + "  <description>\n"
                + "    <title-info>\n"
                + "      <title>Split Tag</title>\n"
                + "    </title-info>\n"
                + "    <coverpage>\n"
                + "      <image l:href=\"#coverimg\"/>\n"
                + "    </coverpage>\n"
                + "    <annotation>";
        // Pad the annotation so </description> starts 3 bytes before a chunk boundary
        // (it spans two 8 KB reads: 3 bytes in the first chunk, 11 in the next).
        int pad = (int) ((8189 - (prefix.length() % 8192) + 8192) % 8192);
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < pad; i++) sb.append('x');
        sb.append("</description>\n");
        sb.append("  <binary id=\"coverimg\" content-type=\"image/jpeg\">")
                .append(base64).append("</binary>\n");
        sb.append("  <body>\n");
        for (int i = 0; i < 12000; i++) sb.append("    <p>Filler paragraph ").append(i).append(". </p>\n");
        sb.append("  </body>\n");
        sb.append("</FictionBook>\n");
        String xml = sb.toString();
        // Sanity: the 14-byte marker really does straddle a chunk boundary.
        int at = xml.indexOf("</description>");
        assertTrue("test setup: the tag must straddle a chunk boundary",
                at % 8192 >= 8192 - 13 && at % 8192 < 8192);
        TestFixtures.writeText(fb2, xml);

        byte[] header = CoverExtractor.readFb2Header(fb2);
        assertNotNull(header);
        assertTrue("the reader must stop at <body> (a 100 KB body must not be buffered)",
                header.length < 100 * 1024);
        byte[] coverFromRegion = CoverExtractor.coverFromFb2Bytes(header);
        assertTrue("the cover must be found in the streamed region",
                java.util.Arrays.equals(coverFromRegion, cover));

        assertNotNull(CoverExtractor.extract(fb2));
    }

    // ------------------------------------------------------------------
    // FB2ZIP covers
    // ------------------------------------------------------------------

    @Test
    public void fb2ZipCoverFromInnerBinaryBlockIsReturned() throws Exception {
        File fz = folder.newFile("covered.fb2.zip");
        String base64 = Base64.getEncoder().encodeToString(TestFixtures.coverBytes());
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("covered.fb2", TestFixtures.buildFb2WithCover(base64).getBytes("UTF-8"));
        TestFixtures.writeZip(fz, entries);

        byte[] cover = CoverExtractor.extract(fz);
        assertNotNull("cover must be found in the inner FB2's binary block", cover);
        assertTrue(java.util.Arrays.equals(cover, TestFixtures.coverBytes()));
    }

    @Test
    public void fb2ZipCoverFromLooseCoverJpgEntryIsReturned() throws Exception {
        // Inner FB2 has no coverpage; the archive ships a loose cover.jpg next to it.
        File fz = folder.newFile("loose.fb2.zip");
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("loose.fb2", TestFixtures.FB2_FULL.getBytes("UTF-8"));
        entries.put("cover.jpg", TestFixtures.coverBytes());
        TestFixtures.writeZip(fz, entries);

        byte[] cover = CoverExtractor.extract(fz);
        assertNotNull("loose cover.jpg must be found", cover);
        assertTrue(java.util.Arrays.equals(cover, TestFixtures.coverBytes()));
    }

    @Test
    public void fb2ZipPrefersInnerBinaryBlockOverLooseImage() throws Exception {
        byte[] innerCover = jpegBytes();
        String base64 = Base64.getEncoder().encodeToString(innerCover);
        File fz = folder.newFile("both.fb2.zip");
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("both.fb2", TestFixtures.buildFb2WithCover(base64).getBytes("UTF-8"));
        entries.put("cover.jpg", TestFixtures.coverBytes());
        TestFixtures.writeZip(fz, entries);

        byte[] cover = CoverExtractor.extract(fz);
        assertNotNull(cover);
        assertTrue("the inner FB2 cover must take priority over the loose image",
                java.util.Arrays.equals(cover, innerCover));
    }

    @Test
    public void fb2ZipWithoutAnyCoverReturnsNull() throws Exception {
        File fz = folder.newFile("nobody.fb2.zip");
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("nobody.fb2", TestFixtures.FB2_FULL.getBytes("UTF-8"));
        TestFixtures.writeZip(fz, entries);

        assertNull(CoverExtractor.extract(fz));
    }

    @Test
    public void corruptFb2ZipReturnsNullWithoutThrowing() throws Exception {
        File fz = folder.newFile("broken.fb2.zip");
        TestFixtures.writeBytes(fz, new byte[]{(byte) 0x50, (byte) 0x4B, 0x03, 0x04, 0x01});
        assertNull(CoverExtractor.extract(fz));
    }

    // ------------------------------------------------------------------
    // MOBI covers
    // ------------------------------------------------------------------

    @Test
    public void mobiCoverJpegIsTrimmedAtEndOfImage() throws Exception {
        byte[] jpeg = jpegBytes();
        // firstImageIndex = 2, EXTH cover offset = 1 -> the cover lives in record 3,
        // which carries 4 bytes of trailing padding after the JPEG EOI marker.
        TestFixtures.writeMobi(mobi, "Cover Book", "A. Author", "Publisher",
                "A story.", "en", jpeg, 2, 1);

        byte[] cover = CoverExtractor.extract(mobi);
        assertNotNull("cover must be found", cover);
        assertTrue("trailing record padding must be trimmed at the JPEG EOI marker",
                java.util.Arrays.equals(cover, jpeg));
    }

    @Test
    public void mobiWithoutCoverRecordReturnsNull() throws Exception {
        // No EXTH 201 record -> no cover image can be located.
        TestFixtures.writeMobi(mobi, "No Cover", "A. Author", "Publisher",
                "A story.", "en", jpegBytes(), 2, -1);
        assertNull(CoverExtractor.extract(mobi));
    }

    @Test
    public void mobiCoverPointingAtNonImageRecordReturnsNull() throws Exception {
        // Cover record = 1 + 0 = record 1, the text record ("xxxxxxxx") — not an image.
        TestFixtures.writeMobi(mobi, "Bad Cover", "A. Author", "Publisher",
                "A story.", "en", jpegBytes(), 1, 0);
        assertNull(CoverExtractor.extract(mobi));
    }

    @Test
    public void corruptMobiReturnsNullWithoutThrowing() throws Exception {
        TestFixtures.writeBytes(mobi, new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05});
        assertNull(CoverExtractor.extract(mobi));
    }

    // ------------------------------------------------------------------
    // other formats
    // ------------------------------------------------------------------

    @Test
    public void unsupportedFormatsReturnNull() throws Exception {
        File pdf = folder.newFile("book.pdf");
        TestFixtures.writeBytes(pdf, new byte[]{0x25, 0x25, 0x50, 0x44});
        assertNull(CoverExtractor.extract(pdf));

        File unknown = folder.newFile("x.md");
        TestFixtures.writeText(unknown, "hi\n");
        assertNull(CoverExtractor.extract(unknown));
    }
}
