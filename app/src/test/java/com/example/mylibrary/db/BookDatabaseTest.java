package com.example.mylibrary.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.mylibrary.model.Book;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * Robolectric tests for {@link BookDatabase} (real SQLite in-process).
 *
 * <p>The headline invariant: {@code upsert} must preserve {@code last_read} across
 * rescans — the "Recently read" view depends on it. Run at {@code sdk = 19}, the
 * project's API floor.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class BookDatabaseTest {

    private Context context;
    private BookDatabase db;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("library.db");
        db = new BookDatabase(context);
    }

    private static Book book(String path, String format, String title, String author) {
        Book b = new Book();
        b.path = path;
        b.format = format;
        b.title = title;
        b.author = author;
        b.publisher = "Some Publisher";
        b.description = "A description";
        b.series = "A Series";
        b.sizeBytes = 12345;
        b.exported = true;
        return b;
    }

    // ------------------------------------------------------------------
    // upsert
    // ------------------------------------------------------------------

    @Test
    public void upsertInsertsNewBookAndReturnsId() {
        Book b = book("/storage/emulated/0/a.epub", "EPUB", "Title A", "Author A");
        long id = db.upsert(b);
        assertTrue("row id must be positive", id > 0);
        assertEquals(id, b.id);

        Book got = db.getById(id);
        assertNotNull(got);
        assertEquals("/storage/emulated/0/a.epub", got.path);
        assertEquals("EPUB", got.format);
        assertEquals("Title A", got.title);
        assertEquals("Author A", got.author);
    }

    @Test
    public void upsertUpdatesExistingBookKeepingSameId() {
        Book b = book("/storage/emulated/0/a.epub", "EPUB", "Title A", "Author A");
        long id = db.upsert(b);

        Book rescanned = book("/storage/emulated/0/a.epub", "EPUB", "Title A2", "Author A2");
        long id2 = db.upsert(rescanned);

        assertEquals("same path -> same row", id, id2);
        assertEquals("exactly one row", 1, db.all(null).size());

        Book got = db.getById(id);
        assertEquals("Title A2", got.title);
        assertEquals("Author A2", got.author);
    }

    /**
     * The key invariant: after {@code markRead}, any number of rescans (upserts) must
     * not wipe the {@code last_read} timestamp — otherwise "Recently read" empties on
     * every rescan.
     */
    @Test
    public void upsertPreservesLastReadAcrossRescans() {
        Book b = book("/storage/emulated/0/b.fb2", "FB2", "Title", "Author");
        long id = db.upsert(b);
        db.markRead(id);

        // Simulate two rescan cycles (metadata may change, path stays).
        Book r1 = book("/storage/emulated/0/b.fb2", "FB2", "Title v2", "Author v2");
        assertEquals(id, db.upsert(r1));
        Book r2 = book("/storage/emulated/0/b.fb2", "FB2", "Title v3", "Author v3");
        assertEquals(id, db.upsert(r2));

        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT last_read FROM books WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue("row must exist", c.moveToFirst());
            long lastRead = c.getLong(0);
            assertTrue("last_read must survive upserts", lastRead > 0);
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    @Test
    public void allWithoutFilterReturnsEverythingOrderedByTitleCaseInsensitively() {
        db.upsert(book("/x/zebra.pdf", "PDF", "zeta", null));
        db.upsert(book("/x/apple.pdf", "PDF", "Apple", null));
        db.upsert(book("/x/mango.pdf", "PDF", "mango", null));

        List<Book> all = db.all(null);
        assertEquals(3, all.size());
        assertEquals("Apple", all.get(0).title);
        assertEquals("mango", all.get(1).title);
        assertEquals("zeta", all.get(2).title);
    }

    @Test
    public void allWithFormatFilterReturnsOnlyThatFormat() {
        db.upsert(book("/x/a.epub", "EPUB", "Epub Book", null));
        db.upsert(book("/x/b.epub", "EPUB", "Another Epub", null));
        db.upsert(book("/x/c.pdf", "PDF", "Pdf Book", null));

        List<Book> epubs = db.all("EPUB");
        assertEquals(2, epubs.size());
        for (Book b : epubs) assertEquals("EPUB", b.format);

        assertEquals(1, db.all("PDF").size());
        assertEquals(0, db.all("DOCX").size());
    }

    @Test
    public void recentReturnsNewestFirstAndHonoursLimit() {
        Book a = book("/x/a.txt", "TXT", "A", null);
        Book b = book("/x/b.txt", "TXT", "B", null);
        Book c = book("/x/c.txt", "TXT", "C", null);
        long idA = db.upsert(a);
        long idB = db.upsert(b);
        long idC = db.upsert(c);

        // Give each row a distinct, explicit timestamp (newest = C, then B, then A).
        setLastRead(idA, 1000L);
        setLastRead(idB, 2000L);
        setLastRead(idC, 3000L);

        List<Book> top2 = db.recent(2);
        assertEquals(2, top2.size());
        assertEquals("C", top2.get(0).title);
        assertEquals("B", top2.get(1).title);

        List<Book> all = db.recent(0); // limit<=0 -> default
        assertEquals(3, all.size());
        assertEquals("C", all.get(0).title);
        assertEquals("A", all.get(2).title);
    }

    private void setLastRead(long id, long ts) {
        SQLiteDatabase raw = db.getWritableDatabase();
        raw.execSQL("UPDATE books SET last_read = " + ts + " WHERE _id = " + id);
    }

    @Test
    public void markReadSetsTimestamp() {
        long id = db.upsert(book("/x/a.txt", "TXT", "A", null));
        assertNullLastRead(id);
        db.markRead(id);
        assertNonNullLastRead(id);
    }

    private void assertNullLastRead(long id) {
        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT last_read FROM books WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            assertTrue("last_read must be NULL initially", c.isNull(0));
        } finally {
            c.close();
        }
    }

    private void assertNonNullLastRead(long id) {
        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT last_read FROM books WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            assertTrue("last_read must be set", !c.isNull(0) && c.getLong(0) > 0);
        } finally {
            c.close();
        }
    }

    @Test
    public void deleteByPathRemovesOnlyThatRow() {
        long keep = db.upsert(book("/x/keep.pdf", "PDF", "Keep", null));
        long drop = db.upsert(book("/x/drop.pdf", "PDF", "Drop", null));

        db.deleteByPath("/x/drop.pdf");

        assertNull(db.getById(drop));
        assertNotNull(db.getById(keep));
        assertEquals(1, db.all(null).size());
    }

    @Test
    public void clearRemovesEverything() {
        db.upsert(book("/x/a.pdf", "PDF", "A", null));
        db.upsert(book("/x/b.pdf", "PDF", "B", null));
        db.clear();
        assertTrue(db.all(null).isEmpty());
    }

    @Test
    public void getIdForPathReturnsRowIdOrMinusOne() {
        long id = db.upsert(book("/x/a.pdf", "PDF", "A", null));
        assertEquals(id, db.getIdForPath("/x/a.pdf"));
        assertEquals(-1, db.getIdForPath("/x/unknown.pdf"));
    }

    // ------------------------------------------------------------------
    // round-trip
    // ------------------------------------------------------------------

    @Test
    public void allFieldsRoundTripThroughCursor() {
        Book b = book("/storage/emulated/0/cyr.epub", "EPUB", "Мастер и Маргарита", "М. А. Булгаков");
        long id = db.upsert(b);

        Book got = db.getById(id);
        assertEquals(b.path, got.path);
        assertEquals(b.format, got.format);
        assertEquals("Мастер и Маргарита", got.title);
        assertEquals("М. А. Булгаков", got.author);
        assertEquals("Some Publisher", got.publisher);
        assertEquals("A description", got.description);
        assertEquals("A Series", got.series);
        assertEquals(12345, got.sizeBytes);
        assertTrue("exported flag must round-trip", got.exported);
    }
}
