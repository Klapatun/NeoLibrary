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

    @Before
    public void setUp() throws Exception {
        epub = folder.newFile("book.epub");
        fb2 = folder.newFile("book.fb2");
    }

    // ------------------------------------------------------------------
    // canHaveCover
    // ------------------------------------------------------------------

    @Test
    public void canHaveCoverIsTrueOnlyForEpubAndFb2() {
        assertTrue(CoverExtractor.canHaveCover("EPUB"));
        assertTrue(CoverExtractor.canHaveCover("FB2"));
        assertFalse(CoverExtractor.canHaveCover("PDF"));
        assertFalse(CoverExtractor.canHaveCover("MOBI"));
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
