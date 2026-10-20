package com.example.mylibrary.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.mylibrary.testutil.TestFixtures;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * Boundary tests for the single-record MOBI cover cap. Metadata in record zero
 * remains usable even when the image record is deliberately too large.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class MobiParserTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void readRecordRejectsARecordAboveTheCoverLimitButKeepsMetadata() throws Exception {
        File mobi = folder.newFile("large-record.mobi");
        byte[] cover = new byte[CoverExtractor.MAX_COVER_BYTES + 1];
        cover[0] = (byte) 0xFF;
        cover[1] = (byte) 0xD8;
        cover[cover.length - 2] = (byte) 0xFF;
        cover[cover.length - 1] = (byte) 0xD9;
        TestFixtures.writeMobi(mobi, "Large Record", "Author", "Publisher",
                "Description", "en", cover, 2, 1);

        MobiParser parser = new MobiParser();
        try {
            assertTrue(parser.open(mobi));
            assertEquals("Large Record", parser.title);
            assertNotNull("header metadata must survive an oversized cover record",
                    parser.author);
            assertNull("the oversized record must not be copied", parser.readRecord(parser.coverRecord));
        } finally {
            parser.close();
        }
    }
}
