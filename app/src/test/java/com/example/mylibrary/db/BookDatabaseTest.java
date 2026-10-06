package com.example.mylibrary.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.util.CoverCache;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
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

    /** Removing a book from the catalog must also drop its cached cover file —
     *  otherwise "covers/&lt;hash&gt;.img" orphans grow forever on disk. */
    @Test
    public void deleteByPathAlsoDropsTheCachedCover() {
        CoverCache.save(context, "/x/drop.epub", new byte[]{9, 9, 9});
        assertTrue(CoverCache.fileFor(context, "/x/drop.epub").exists());
        db.upsert(book("/x/drop.epub", "EPUB", "Drop", null));
        CoverCache.save(context, "/x/keep.epub", new byte[]{8, 8, 8});
        db.upsert(book("/x/keep.epub", "EPUB", "Keep", null));

        db.deleteByPath("/x/drop.epub");

        assertFalse("removed book's cover must be gone",
                CoverCache.fileFor(context, "/x/drop.epub").exists());
        assertNull(CoverCache.load(context, "/x/drop.epub"));
        assertNotNull("the other book's cover must survive",
                CoverCache.load(context, "/x/keep.epub"));
    }

    @Test
    public void clearRemovesEverything() {
        db.upsert(book("/x/a.pdf", "PDF", "A", null));
        db.upsert(book("/x/b.pdf", "PDF", "B", null));
        db.clear();
        assertTrue(db.all(null).isEmpty());
    }

    /** Wiping the catalog must wipe the whole cover cache with it (no orphan
     *  "covers/*.img" files left on disk). */
    @Test
    public void clearDropsTheWholeCoverCache() {
        CoverCache.save(context, "/x/a.epub", new byte[]{1});
        CoverCache.save(context, "/x/b.epub", new byte[]{2});
        db.upsert(book("/x/a.epub", "EPUB", "A", null));
        db.upsert(book("/x/b.epub", "EPUB", "B", null));

        db.clear();

        assertNull(CoverCache.load(context, "/x/a.epub"));
        assertNull(CoverCache.load(context, "/x/b.epub"));
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
        // The stage flags are row-owned state (upsert no longer writes them from the
        // model) — set them through their own channels, then verify the full row
        // round-trips through the cursor.
        db.markUserEdited(id);
        SQLiteDatabase raw = db.getWritableDatabase();
        raw.execSQL("UPDATE books SET meta_done = 1 WHERE _id = " + id);

        raw.execSQL("UPDATE books SET meta_failed = 1 WHERE _id = " + id);

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
        assertTrue("meta_done flag must round-trip", got.metaDone);
        assertTrue("meta_failed flag must round-trip", got.metaFailed);
        assertTrue("user_edited flag must round-trip", got.userEdited);
    }

    // ------------------------------------------------------------------
    // migration (v1 -> v2)
    // ------------------------------------------------------------------

    /** Old (v1) table definition, exactly as shipped in version 1. */
    private static final String V1_CREATE =
            "CREATE TABLE books ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "path TEXT UNIQUE NOT NULL, "
            + "format TEXT, "
            + "title TEXT, "
            + "author TEXT, "
            + "publisher TEXT, "
            + "description TEXT, "
            + "series TEXT, "
            + "size_bytes INTEGER, "
            + "exported INTEGER DEFAULT 0, "
            + "last_read INTEGER"
            + ")";

    /**
     * Opening a v1 database (created with the old schema, holding a row with a
     * last_read timestamp) must upgrade it in place: the two new columns appear and
     * the existing row, including last_read, survives. The old onUpgrade dropped the
     // table, so this test guards the invariant directly.
     */
    @Test
    public void openingAV1DatabaseUpgradesInPlacePreservingData() {
        db.close(); // release setUp()'s connection so the raw open below can proceed
        java.io.File f = context.getDatabasePath("library.db");
        // The helper normally creates this directory; do it explicitly for the raw open.
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        // Open the raw file with the PUBLIC API (no hidden classes) and create the
        // old v1 schema + a row, leaving user_version at 0.
        SQLiteDatabase rawDb = SQLiteDatabase.openOrCreateDatabase(f.getAbsolutePath(), null);
        try {
            rawDb.execSQL(V1_CREATE);
            rawDb.execSQL("INSERT INTO books (path, format, title, last_read) VALUES "
                    + "('/x/old.pdf', 'PDF', 'Old Title', 99999)");
            // The old app's helper would have stamped version 1; without it the new
            // helper treats the file as brand-new and runs onCreate (not onUpgrade).
            rawDb.execSQL("PRAGMA user_version = 1");
        } finally {
            rawDb.close();
        }

        // First open at the new version: onUpgrade(db, 0 -> 2) must ALTER, not DROP.
        BookDatabase upgraded = new BookDatabase(context);
        SQLiteDatabase raw = upgraded.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT title, last_read, meta_done, user_edited FROM books", null);
        try {
            assertTrue("row must survive the upgrade", c.moveToFirst());
            assertEquals("Old Title", c.getString(0));
            assertEquals(99999L, c.getLong(1));
            assertEquals(0, c.getInt(2)); // meta_done defaults to 0
            assertEquals(0, c.getInt(3)); // user_edited defaults to 0
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------
    // migration (v2 -> v3: indexes)
    // ------------------------------------------------------------------

    /** Old (v2) table definition, exactly as shipped in version 2 (with the
     *  meta_done / user_edited columns, no indexes). */
    private static final String V2_CREATE =
            "CREATE TABLE books ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "path TEXT UNIQUE NOT NULL, "
            + "format TEXT, "
            + "title TEXT, "
            + "author TEXT, "
            + "publisher TEXT, "
            + "description TEXT, "
            + "series TEXT, "
            + "size_bytes INTEGER, "
            + "exported INTEGER DEFAULT 0, "
            + "meta_done INTEGER NOT NULL DEFAULT 0, "
            + "user_edited INTEGER NOT NULL DEFAULT 0, "
            + "last_read INTEGER"
            + ")";

    /**
     * Opening a v2 database must upgrade it in place to v3: the existing row (with
     * last_read) survives and both hot-path indexes are created.
     */
    @Test
    public void openingAV2DatabaseUpgradesInPlaceAndAddsIndexes() {
        db.close(); // release setUp()'s connection so the raw open below can proceed
        java.io.File f = context.getDatabasePath("library.db");
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        SQLiteDatabase rawDb = SQLiteDatabase.openOrCreateDatabase(f.getAbsolutePath(), null);
        try {
            rawDb.execSQL(V2_CREATE);
            rawDb.execSQL("INSERT INTO books (path, format, title, last_read) VALUES "
                    + "('/x/v2.pdf', 'PDF', 'V2 Title', 42424)");
            rawDb.execSQL("PRAGMA user_version = 2");
        } finally {
            rawDb.close();
        }

        BookDatabase upgraded = new BookDatabase(context);
        SQLiteDatabase raw = upgraded.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT title, last_read FROM books", null);
        try {
            assertTrue("row must survive the upgrade", c.moveToFirst());
            assertEquals("V2 Title", c.getString(0));
            assertEquals(42424L, c.getLong(1));
        } finally {
            c.close();
        }
        List<String> indexes = indexNames(raw);
        assertTrue("meta_done index must be created on upgrade",
                indexes.contains("idx_books_meta_done"));
        assertTrue("last_read index must be created on upgrade",
                indexes.contains("idx_books_last_read"));
    }

    /** A brand-new database (onCreate path) must have both hot-path indexes. */
    @Test
    public void freshDatabaseHasIndexesOnMetaDoneAndLastRead() {
        List<String> indexes = indexNames(db.getReadableDatabase());
        assertTrue(indexes.contains("idx_books_meta_done"));
        assertTrue(indexes.contains("idx_books_last_read"));
    }

    /** The index names of the books table (PRAGMA index_list, name = column 1). */
    private static List<String> indexNames(SQLiteDatabase db) {
        List<String> names = new ArrayList<String>();
        Cursor c = db.rawQuery("PRAGMA index_list(books)", null);
        try {
            while (c.moveToNext()) names.add(c.getString(1));
        } finally {
            c.close();
        }
        return names;
    }

    // ------------------------------------------------------------------
    // migration (v3 -> v4: meta_failed column)
    // ------------------------------------------------------------------

    /** Old (v3) table definition, exactly as shipped in version 3 (with the
     *  meta_done / user_edited columns but no meta_failed column). */
    private static final String V3_CREATE =
            "CREATE TABLE books ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "path TEXT UNIQUE NOT NULL, "
            + "format TEXT, "
            + "title TEXT, "
            + "author TEXT, "
            + "publisher TEXT, "
            + "description TEXT, "
            + "series TEXT, "
            + "size_bytes INTEGER, "
            + "exported INTEGER DEFAULT 0, "
            + "meta_done INTEGER NOT NULL DEFAULT 0, "
            + "user_edited INTEGER NOT NULL DEFAULT 0, "
            + "last_read INTEGER"
            + ")";

    /**
     * Opening a v3 database must upgrade it in place to v4: the existing row
     * (including last_read) survives and the new {@code meta_failed} column
     * appears with a 0 default (no book is "un-enriched" by default).
     */
    @Test
    public void openingAV3DatabaseUpgradesInPlaceAndAddsMetaFailedColumn() {
        db.close(); // release setUp()'s connection so the raw open below can proceed
        java.io.File f = context.getDatabasePath("library.db");
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        SQLiteDatabase rawDb = SQLiteDatabase.openOrCreateDatabase(f.getAbsolutePath(), null);
        try {
            rawDb.execSQL(V3_CREATE);
            rawDb.execSQL("INSERT INTO books (path, format, title, last_read) VALUES "
                    + "('/x/v3.epub', 'EPUB', 'V3 Title', 55555)");
            rawDb.execSQL("PRAGMA user_version = 3");
        } finally {
            rawDb.close();
        }

        BookDatabase upgraded = new BookDatabase(context);
        SQLiteDatabase raw = upgraded.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT title, last_read, meta_failed FROM books", null);
        try {
            assertTrue("row must survive the upgrade", c.moveToFirst());
            assertEquals("V3 Title", c.getString(0));
            assertEquals(55555L, c.getLong(1));
            assertEquals("meta_failed must default to 0 on upgrade", 0, c.getInt(2));
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------
    // stage-1 / stage-2 methods
    // ------------------------------------------------------------------

    @Test
    public void upsertBasicInsertsSkeletonAndKeepsExistingMetadataOnRescan() {
        Book b = book("/x/a.epub", "EPUB", "Filename Title", null);
        long id = db.upsertBasic(b);
        Book got = db.getById(id);
        assertEquals("Filename Title", got.title);
        assertFalse(got.metaDone);

        // Simulate stage 2 having enriched the row...
        MetaData md = new MetaData();
        md.title = "Embedded Title";
        md.author = "Embedded Author";
        md.found = true;
        db.updateMetadata(id, md, true);
        Book enriched = db.getById(id);
        assertEquals("Embedded Title", enriched.title);
        assertTrue(enriched.metaDone);

        // ...then a rescan (upsertBasic with the skeleton again) must not clobber it.
        Book rescanned = book("/x/a.epub", "EPUB", "Filename Title", null);
        assertEquals(id, db.upsertBasic(rescanned));
        Book after = db.getById(id);
        assertEquals("Embedded Title", after.title);
        assertEquals("Embedded Author", after.author);
        assertTrue(after.metaDone);
    }

    @Test
    public void upsertBasicRefreshesOnlyFormatAndSizeOnExistingRow() {
        db.upsertBasic(book("/x/a.pdf", "PDF", "T", null));
        long id = db.getIdForPath("/x/a.pdf");
        db.markRead(id);

        Book b = book("/x/a.pdf", "PDF", "T", null);
        b.sizeBytes = 777;
        b.format = "PDF";
        db.upsertBasic(b);

        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT title, size_bytes, last_read FROM books WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            assertEquals("T", c.getString(0));
            assertEquals(777L, c.getLong(1));
            assertTrue("last_read must survive upsertBasic", c.getLong(2) > 0);
        } finally {
            c.close();
        }
    }

    @Test
    public void updateMetadataPreservesLastReadAndMarksDone() {
        long id = db.upsertBasic(book("/x/b.fb2", "FB2", "B", null));
        db.markRead(id);

        MetaData md = new MetaData();
        md.title = "Real Title";
        md.author = "Real Author";
        md.found = true;
        db.updateMetadata(id, md, true);

        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT title, author, last_read, meta_done FROM books WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            assertEquals("Real Title", c.getString(0));
            assertEquals("Real Author", c.getString(1));
            assertTrue("last_read must survive updateMetadata", c.getLong(2) > 0);
            assertEquals(1, c.getInt(3));
        } finally {
            c.close();
        }
    }

    @Test
    public void updateMetadataNeverClobbersUserEditsButFillsEmptyFields() {
        Book b = book("/x/c.epub", "EPUB", "User Title", "User Author");
        b.publisher = null;   // left empty by the user -> the enricher may fill it
        b.description = null; // left empty by the user -> the enricher may fill it
        long id = db.upsert(b);
        db.markUserEdited(id); // what the editor does after the upsert

        // Background extraction finds different in-file values.
        MetaData md = new MetaData();
        md.title = "File Title";
        md.author = "File Author";
        md.publisher = "File Publisher";
        md.description = "File Desc";
        md.found = true;
        db.updateMetadata(id, md, true);

        Book got = db.getById(id);
        assertEquals("User Title", got.title);          // user value kept
        assertEquals("User Author", got.author);        // user value kept
        assertEquals("File Publisher", got.publisher);  // empty field filled
        assertEquals("File Desc", got.description);     // empty field filled
        assertTrue(got.metaDone);
    }

    /** A whitespace-only catalog value counts as blank (same rule as before the
     *  single-cursor rewrite): the enricher may fill it even when user_edited=1. */
    @Test
    public void updateMetadataTreatsWhitespaceOnlyFieldsAsBlank() {
        Book b = book("/x/w.epub", "EPUB", "  ", "Real Author");
        long id = db.upsert(b);
        db.markUserEdited(id);

        MetaData md = new MetaData();
        md.title = "File Title";
        md.author = "File Author";
        md.found = true;
        db.updateMetadata(id, md, true);

        Book got = db.getById(id);
        assertEquals("whitespace-only title is filled by the enricher", "File Title", got.title);
        assertEquals("non-blank user author is kept", "Real Author", got.author);
        assertTrue(got.metaDone);
    }

    @Test
    public void updateMetadataWithoutFoundMetaLeavesTitleInPlaceAndMarksDone() {
        long id = db.upsertBasic(book("/x/d.pdf", "PDF", "d", null));
        MetaData md = new MetaData();
        md.found = false;
        db.updateMetadata(id, md, true);

        Book got = db.getById(id);
        assertEquals("d", got.title); // file-name title survives
        assertTrue(got.metaDone);     // but the book is marked enriched
    }

    /** upsert must not clobber the stage flags from a stale in-memory model:
     *  meta_done / user_edited are row-owned state, like last_read. */
    @Test
    public void upsertDoesNotTouchMetaFlagsOnExistingRow() {
        long id = db.upsertBasic(book("/x/a.epub", "EPUB", "A", null));
        // Simulate stage 2 having enriched the row and the user having edited it.
        MetaData md = new MetaData();
        md.title = "Embedded";
        md.found = true;
        db.updateMetadata(id, md, true);
        db.markUserEdited(id);

        // A STALE model (both flags false) must not reset them.
        Book stale = book("/x/a.epub", "EPUB", "A2", null);
        stale.metaDone = false;
        stale.userEdited = false;
        assertEquals(id, db.upsert(stale));

        Book got = db.getById(id);
        assertEquals("metadata fields did update", "A2", got.title);
        assertTrue("meta_done must stay 1", got.metaDone);
        assertTrue("user_edited must stay 1", got.userEdited);
    }

    @Test
    public void markUserEditedSetsTheFlag() {
        long id = db.upsertBasic(book("/x/a.epub", "EPUB", "A", null));
        assertFalse(db.getById(id).userEdited);
        db.markUserEdited(id);
        assertTrue(db.getById(id).userEdited);
    }

    /** After the underlying file is overwritten (an import with the same name) the
     *  row must be flagged for re-enrichment instead of trusting the old metadata. */
    @Test
    public void markMetaPendingClearsTheDoneFlag() {
        long id = db.upsertBasic(book("/x/a.epub", "EPUB", "A", null));
        MetaData md = new MetaData();
        md.title = "Embedded";
        md.found = true;
        db.updateMetadata(id, md, true);
        assertTrue(db.getById(id).metaDone);

        db.markMetaPending(id);
        assertFalse("the row must be pending enrichment again", db.getById(id).metaDone);
    }

    @Test
    public void needMetaReturnsOnlyNotYetEnrichedBooks() {
        long done = db.upsertBasic(book("/x/a.txt", "TXT", "A", null));
        long pending = db.upsertBasic(book("/x/b.txt", "TXT", "B", null));
        MetaData md = new MetaData();
        md.title = "A2";
        md.found = true;
        db.updateMetadata(done, md, true);

        List<Book> pendingBooks = db.needMeta();
        assertEquals(1, pendingBooks.size());
        assertEquals(pending, pendingBooks.get(0).id);
    }

    /** A timed-out parse must leave the book in the stage-2 queue (meta_done stays 0)
     *  so that every rescan retries it — the flag only changes its priority. */
    @Test
    public void markMetaFailedFlagsTheRowAndKeepsItPending() {
        long id = db.upsertBasic(book("/x/slow.epub", "EPUB", "Slow", null));
        assertFalse("a fresh row is not un-enriched", db.getById(id).metaFailed);

        db.markMetaFailed(id);

        Book b = db.getById(id);
        assertTrue("the un-enriched flag must round-trip", b.metaFailed);
        assertFalse("a timed-out book must stay pending (retried on rescan)", b.metaDone);
        assertEquals("the flagged book must still be in the stage-2 queue", 1,
                db.needMeta().size());
    }

    /** The rescan rule: within the stage-2 queue, books whose last parse overran the
     *  time budget ({@code meta_failed = 1}) must be taken last, normal books first. */
    @Test
    public void needMetaOrdersUnEnrichedBooksLast() {
        long a = db.upsertBasic(book("/x/a.txt", "TXT", "A", null));
        long b = db.upsertBasic(book("/x/b.txt", "TXT", "B", null));
        long c = db.upsertBasic(book("/x/c.txt", "TXT", "C", null));
        db.markMetaFailed(a);
        db.markMetaFailed(c);

        List<Book> queue = db.needMeta();
        assertEquals(3, queue.size());
        assertEquals("the un-flagged book must come first", b, queue.get(0).id);
        assertEquals("flagged books come after, in id order", a, queue.get(1).id);
        assertEquals("...and last", c, queue.get(2).id);
    }

    /** A parse that completes normally (the retry succeeded) must clear the flag. */
    @Test
    public void updateMetadataClearsTheUnEnrichedFlag() {
        long id = db.upsertBasic(book("/x/retry.epub", "EPUB", "Retry", null));
        db.markMetaFailed(id);
        assertTrue(db.getById(id).metaFailed);

        MetaData md = new MetaData();
        md.title = "Real Title";
        md.found = true;
        db.updateMetadata(id, md, true);

        Book got = db.getById(id);
        assertTrue(got.metaDone);
        assertFalse("a successful enrichment clears the un-enriched flag", got.metaFailed);
    }

    /** An import with the same name replaces the file contents, so the old
     *  "too slow to parse" verdict must not keep the new file at the end of the
     *  queue: re-queuing (markMetaPending) clears the flag. */
    @Test
    public void markMetaPendingClearsTheUnEnrichedFlag() {
        long id = db.upsertBasic(book("/x/imported.epub", "EPUB", "Imported", null));
        db.markMetaFailed(id);

        db.markMetaPending(id);

        assertFalse("new file contents get normal queue priority",
                db.getById(id).metaFailed);
    }
}
