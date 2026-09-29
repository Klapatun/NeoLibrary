package com.example.mylibrary.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the pure-JVM surface of {@link Openers} (the MIME map).
 * The intent-building methods ({@code openFile}, {@code isNeoReaderInstalled})
 * need the Android runtime and are covered in phase 2 with Robolectric.
 */
public class OpenersTest {

    @Test
    public void mimeForMapsEverySupportedFormat() {
        assertEquals("application/pdf", Openers.mimeFor("a.pdf"));
        assertEquals("application/epub+zip", Openers.mimeFor("a.epub"));
        assertEquals("application/x-mobipocket-ebook", Openers.mimeFor("a.mobi"));
        assertEquals("application/x-mobipocket-ebook", Openers.mimeFor("a.prc"));
        assertEquals("application/x-mobipocket-ebook", Openers.mimeFor("a.azw"));
        assertEquals("application/x-fictionbook+xml", Openers.mimeFor("a.fb2"));
        assertEquals("application/x-zip-compressed-fb2", Openers.mimeFor("a.fb2.zip"));
        assertEquals("application/x-fictionbook3", Openers.mimeFor("a.fb3"));
        assertEquals("application/x-chm", Openers.mimeFor("a.chm"));
        assertEquals("application/msword", Openers.mimeFor("a.doc"));
        assertEquals(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Openers.mimeFor("a.docx"));
        assertEquals("image/vnd.djvu", Openers.mimeFor("a.djvu"));
        assertEquals("application/x-palm-database", Openers.mimeFor("a.pdb"));
        assertEquals("text/html", Openers.mimeFor("a.html"));
        assertEquals("text/html", Openers.mimeFor("a.htm"));
        assertEquals("application/rtf", Openers.mimeFor("a.rtf"));
        assertEquals("text/plain", Openers.mimeFor("a.txt"));
    }

    @Test
    public void mimeForIsCaseInsensitive() {
        assertEquals("application/pdf", Openers.mimeFor("A.PDF"));
        assertEquals("application/epub+zip", Openers.mimeFor("Book.EPub"));
        assertEquals("application/x-zip-compressed-fb2", Openers.mimeFor("B.FB2.ZIP"));
    }

    @Test
    public void mimeForCompoundExtensionWinsOverPlainOne() {
        // .fb2.zip must not be resolved as a bare .fb2
        assertEquals("application/x-zip-compressed-fb2", Openers.mimeFor("book.fb2.zip"));
    }

    @Test
    public void mimeForUnknownExtensionsReturnsNull() {
        assertNull(Openers.mimeFor("a.md"));
        assertNull(Openers.mimeFor("a.zip"));
        assertNull(Openers.mimeFor("noextension"));
    }
}
