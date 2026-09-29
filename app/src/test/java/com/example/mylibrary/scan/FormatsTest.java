package com.example.mylibrary.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link Formats}. This class is the single source of truth for
 * scanning, filtering and the format badge, so the tests pin down:
 *  - every canonical id is reachable from at least one extension,
 *  - the tricky cases: the compound {@code .fb2.zip}, aliased extensions,
 *    case-insensitivity,
 *  - unsupported / extension-less names are rejected (and a bare {@code .zip}
 *    is never treated as a book).
 */
public class FormatsTest {

    /** One representative file name per canonical id in {@link Formats#ALL}. */
    private static final String[] REPR = {
            "book.chm", "book.doc", "book.docx", "book.djvu", "book.epub",
            "book.fb2", "book.fb2.zip", "book.fb3", "book.html", "book.mobi",
            "book.pdb", "book.pdf", "book.prc", "book.rtf", "book.txt"
    };

    /** Every extension the app is expected to understand. */
    private static final String[] ALL_EXTS = {
            "chm", "doc", "docx", "djvu", "epub", "fb2", "fb2.zip",
            "fb3", "htm", "html", "mobi", "azw", "pdb", "pdf", "prc", "rtf", "txt"
    };

    @Test
    public void isSupportedAcceptsEveryCanonicalFormat() {
        for (String name : REPR) {
            assertTrue("expected supported: " + name, Formats.isSupported(name));
        }
    }

    @Test
    public void formatOfMapsEveryCanonicalFormat() {
        assertEquals("CHM", Formats.formatOf("book.chm"));
        assertEquals("DOC", Formats.formatOf("book.doc"));
        assertEquals("DOCX", Formats.formatOf("book.docx"));
        assertEquals("DJVU", Formats.formatOf("book.djvu"));
        assertEquals("EPUB", Formats.formatOf("book.epub"));
        assertEquals("FB2", Formats.formatOf("book.fb2"));
        assertEquals("FB2ZIP", Formats.formatOf("book.fb2.zip"));
        assertEquals("FB3", Formats.formatOf("book.fb3"));
        assertEquals("HTML", Formats.formatOf("book.html"));
        assertEquals("MOBI", Formats.formatOf("book.mobi"));
        assertEquals("PDB", Formats.formatOf("book.pdb"));
        assertEquals("PDF", Formats.formatOf("book.pdf"));
        assertEquals("PRC", Formats.formatOf("book.prc"));
        assertEquals("RTF", Formats.formatOf("book.rtf"));
        assertEquals("TXT", Formats.formatOf("book.txt"));
    }

    @Test
    public void aliasedExtensionsMapToTheRightFormat() {
        assertEquals("HTML", Formats.formatOf("page.htm"));
        assertEquals("MOBI", Formats.formatOf("book.azw"));
        assertTrue(Formats.isSupported("page.htm"));
        assertTrue(Formats.isSupported("book.azw"));
    }

    @Test
    public void matchingIsCaseInsensitive() {
        assertTrue(Formats.isSupported("BOOK.EPUB"));
        assertTrue(Formats.isSupported("Book.Fb2.Zip"));
        assertEquals("EPUB", Formats.formatOf("BOOK.EPub"));
        assertEquals("FB2ZIP", Formats.formatOf("BOOK.FB2.ZIP"));
    }

    @Test
    public void dottedFileNamesMatchOnTheLastExtension() {
        assertEquals("TXT", Formats.formatOf("My Book (1st ed.).TXT"));
        assertEquals("EPUB", Formats.formatOf("a.b.c.epub"));
    }

    @Test
    public void plainZipIsNotABook() {
        assertFalse(Formats.isSupported("archive.zip"));
        assertNull(Formats.formatOf("archive.zip"));
    }

    @Test
    public void unknownExtensionsAreRejected() {
        assertFalse(Formats.isSupported("notes.md"));
        assertNull(Formats.formatOf("notes.md"));
        assertFalse(Formats.isSupported("archive.7z"));
        assertNull(Formats.formatOf("archive.7z"));
    }

    @Test
    public void namesWithoutExtensionAreRejected() {
        assertFalse(Formats.isSupported("README"));
        assertNull(Formats.formatOf("README"));
        assertFalse(Formats.isSupported(""));
        assertNull(Formats.formatOf(""));
    }

    /**
     * Invariant: {@code Formats.ALL} is the single source of truth. Every canonical
     * id must be reachable via at least one extension, and no extension may map to an
     * id that is not in {@code ALL} (that would create an orphan filter/badge value).
     */
    @Test
    public void everyCanonicalIdIsReachableAndNoExtensionEscapesTheCanonicalSet() {
        Set<String> all = new HashSet<String>(Arrays.asList(Formats.ALL));

        for (String id : Formats.ALL) {
            boolean reached = false;
            for (String ext : ALL_EXTS) {
                if (id.equals(Formats.formatOf("x." + ext))) {
                    reached = true;
                    break;
                }
            }
            assertTrue("no extension maps to " + id, reached);
        }

        for (String ext : ALL_EXTS) {
            String id = Formats.formatOf("x." + ext);
            assertTrue("extension ."+ext+" maps to unknown id "+id, all.contains(id));
        }
    }
}
