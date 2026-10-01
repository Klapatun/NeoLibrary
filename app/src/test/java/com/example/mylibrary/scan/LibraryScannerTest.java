package com.example.mylibrary.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.example.mylibrary.model.Book;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link LibraryScanner}. The scan tree lives in a temp folder; it
 * intentionally uses only formats whose metadata path is pure-JVM safe (TXT, HTML,
 * and formats with no embedded metadata such as PDF/RTF/DOC/FB2ZIP), so the suite
 * runs without the Android runtime. EPUB/FB2 content handling is covered by
 * MetaWriter/MetaExtractor tests (phase 2, Robolectric).
 */
public class LibraryScannerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File root;

    @Before
    public void buildTree() throws Exception {
        root = folder.newFolder("storage");
        write(new File(root, "novel.txt"), "Some plain text story.\nSecond line.\n");
        write(new File(root, "guide.html"),
                "<html><head><title>My Guide</title></head><body>hi</body></html>\n");
        write(new File(root, "archive.fb2.zip"), new byte[] {1, 2, 3, 4});
        write(new File(root, "doc.pdf"), new byte[] {0x25, 0x25, 0x50, 0x44});
        write(new File(root, "notes.md"), "# ignored\n");
        write(new File(root, "archive.zip"), new byte[] {1, 2, 3});
        write(new File(root, "noextension"), new byte[] {1});
        File inner = new File(root, "sub");
        assertTrue(inner.mkdirs());
        File deep = new File(inner, "inner");
        assertTrue(deep.mkdirs());
        write(new File(deep, "deep.rtf"), new byte[] {0x7B, 0x5C, 0x72, 0x74, 0x66});
        write(new File(deep, "my_cool_book.pdf"), new byte[] {0x25, 0x25, 0x50});
    }

    private static void write(File f, byte[] bytes) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(bytes); } finally { out.close(); }
    }

    private static void write(File f, String content) throws Exception {
        write(f, content.getBytes("UTF-8"));
    }

    private static List<Book> scan(File root) {
        return LibraryScanner.scan(Arrays.asList(root), null);
    }

    private static Book byName(List<Book> books, String fileName) {
        for (Book b : books) {
            if (new File(b.path).getName().equals(fileName)) return b;
        }
        return null;
    }

    @Test
    public void scanFindsSupportedBooksRecursivelyAndIgnoresTheRest() {
        List<Book> books = scan(root);

        Set<String> found = new HashSet<String>();
        for (Book b : books) found.add(new File(b.path).getName());

        assertTrue(found.contains("novel.txt"));
        assertTrue(found.contains("guide.html"));
        assertTrue(found.contains("archive.fb2.zip"));
        assertTrue(found.contains("doc.pdf"));
        assertTrue(found.contains("deep.rtf"));
        assertTrue(found.contains("my_cool_book.pdf"));

        assertFalse("plain .zip must be ignored", found.contains("archive.zip"));
        assertFalse(".md must be ignored", found.contains("notes.md"));
        assertFalse("extension-less file must be ignored", found.contains("noextension"));
        assertEquals("exactly the supported files", 6, books.size());
    }

    @Test
    public void scanReportsCorrectFormatAndSize() {
        List<Book> books = scan(root);

        Book fb2zip = byName(books, "archive.fb2.zip");
        assertNotNull(fb2zip);
        assertEquals("FB2ZIP", fb2zip.format);
        assertEquals(4, fb2zip.sizeBytes);

        Book txt = byName(books, "novel.txt");
        assertNotNull(txt);
        assertEquals("TXT", txt.format);
        assertEquals(new File(root, "novel.txt").length(), txt.sizeBytes);
        assertEquals(new File(root, "novel.txt").getAbsolutePath(), txt.path);
    }

    @Test
    public void scanUsesFileNameTitleInTheFastStage() {
        List<Book> books = scan(root);

        // HTML: the fast stage uses the file name; the embedded <title> is applied
        // later by the background enricher (covered by MetaEnricherTest).
        Book html = byName(books, "guide.html");
        assertEquals("guide", html.title);

        // TXT: file name without extension.
        Book txt = byName(books, "novel.txt");
        assertEquals("novel", txt.title);

        // PDF: underscores converted to spaces.
        Book pdf = byName(books, "my_cool_book.pdf");
        assertEquals("my cool book", pdf.title);
    }

    @Test
    public void scanInvokesProgressForEveryFoundFile() {
        final List<String> visited = new ArrayList<String>();
        List<Book> books = LibraryScanner.scan(Arrays.asList(root), new LibraryScanner.Progress() {
            @Override public void onScan(String path) {
                visited.add(path);
            }
        });
        assertEquals("one progress callback per found file", books.size(), visited.size());
        for (Book b : books) assertTrue(visited.contains(b.path));
    }

    @Test
    public void scanEmptyRootListReturnsEmptyList() {
        assertTrue(LibraryScanner.scan(new ArrayList<File>(), null).isEmpty());
    }

    @Test
    public void scanMissingRootReturnsEmptyList() {
        List<Book> books = LibraryScanner.scan(
                Arrays.asList(new File(root, "does-not-exist")), null);
        assertTrue(books.isEmpty());
    }

    @Test
    public void scanSingleAcceptsValidBookFile() {
        File txt = new File(root, "scan_me.txt");
        try {
            write(txt, "hello\n");
            Book b = LibraryScanner.scanSingle(txt);
            assertNotNull(b);
            assertEquals("TXT", b.format);
            assertEquals("scan me", b.title);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void scanSingleFallsBackToFileNameForFormatsWithoutMetadata() {
        File pdf = new File(root, "imported_book.pdf");
        try {
            write(pdf, new byte[] {0x25, 0x25});
            Book b = LibraryScanner.scanSingle(pdf);
            assertNotNull(b);
            assertEquals("imported book", b.title);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void scanSingleRejectsInvalidInput() {
        assertNull("null file", LibraryScanner.scanSingle(null));
        assertNull("missing file", LibraryScanner.scanSingle(new File(root, "missing.txt")));
        assertNull("directory", LibraryScanner.scanSingle(root));

        File md = new File(root, "bad.md");
        try {
            write(md, "x\n");
            assertNull("unsupported extension", LibraryScanner.scanSingle(md));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
