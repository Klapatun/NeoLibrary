package com.example.mylibrary.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * Robolectric tests for the Android-facing part of {@link Openers} (intent building
 * and viewer probing). {@code mimeFor} is plain string logic and is covered by the
 * plain-JVM {@link OpenersTest}.
 *
 * <p>The positive path of {@code isNeoReaderInstalled} (a real Neo Reader activity
 * resolving the intent) needs an installed app on a device — that is phase 3
 * (instrumented). Here we pin the honest negative path: no viewer installed -> false.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class OpenersIntentTest {

    @Test
    public void openFileBuildsViewIntentWithFileUriAndMime() {
        File f = new File("/storage/emulated/0/Books/a.pdf");
        Intent i = Openers.openFile(f);

        assertEquals(Intent.ACTION_VIEW, i.getAction());
        assertEquals(Uri.fromFile(f), i.getData());
        assertEquals("application/pdf", i.getType());
        // getFlags() & mask: the API-19-safe form of the hasFlag() check (API 23+).
        assertTrue((i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void openFileCarriesExpectedMimeForEverySupportedFormat() {
        String[] names = {
                "a.pdf", "a.epub", "a.mobi", "a.prc", "a.azw", "a.fb2", "a.fb2.zip",
                "a.fb3", "a.chm", "a.doc", "a.docx", "a.djvu", "a.pdb",
                "a.html", "a.htm", "a.rtf", "a.txt"
        };
        for (String name : names) {
            File f = new File("/storage/emulated/0/" + name);
            Intent i = Openers.openFile(f);
            assertEquals("mime for " + name, Openers.mimeFor(name), i.getType());
            assertEquals("data for " + name, Uri.fromFile(f), i.getData());
            assertEquals("action for " + name, Intent.ACTION_VIEW, i.getAction());
        }
    }

    @Test
    public void openFileForUnknownExtensionSetsDataWithoutType() {
        File f = new File("/storage/emulated/0/notes.md");
        Intent i = Openers.openFile(f);

        assertNull("no MIME known for .md", i.getType());
        assertEquals(Uri.fromFile(f), i.getData());
        assertEquals(Intent.ACTION_VIEW, i.getAction());
    }

    @Test
    public void isNeoReaderInstalledIsFalseWhenNoViewerIsPresent() {
        Context ctx = RuntimeEnvironment.getApplication();
        // The Robolectric app manifest has no ACTION_VIEW filters, so the probe finds
        // no reader -> the method must report false (not throw, not guess).
        assertFalse(Openers.isNeoReaderInstalled(ctx));
    }
}
