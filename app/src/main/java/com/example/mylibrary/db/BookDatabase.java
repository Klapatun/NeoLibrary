package com.example.mylibrary.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.util.CoverCache;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed catalog of books. Stores the display/catalog metadata for every book
 * (including the formats we do not rewrite in-file) and records "last read" state for
 * the recently-read view.
 */
public class BookDatabase extends SQLiteOpenHelper {

    private static final String TAG = "BookDatabase";
    private static final String DB_NAME = "library.db";
    private static final int DB_VERSION = 5;

    /** How many books go into one batch commit (one SQLite transaction per group;
     *  see {@link #upsertBasicBatch} and {@link #updateMetadataBatch}): one commit
     *  per group instead of one per book. The enricher keeps the same cadence for
     *  its progress/notify steps (its own copy of the constant). */
    public static final int BATCH_SIZE = 5;

    /** Serializes ALL database access. A static lock (not an instance monitor) so
     *  that concurrent users of DIFFERENT BookDatabase instances (each Activity and
     *  the provider create their own — and so do the tests) cannot interleave:
     *  writers never lose updates to each other, and a one-shot read (or a fresh
     *  connection open) never lands inside another instance's write transaction —
     *  a cross-connection collision there is a hard SQLITE_BUSY ("database is
     *  locked"), not a wait. */
    private static final Object WRITE_LOCK = new Object();

    /** Kept for {@link CoverCache} calls in {@link #deleteByPath}/{@link #clear}
     *  (SQLiteOpenHelper exposes no getContext()). */
    private final Context context;

    private static final String CREATE =
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
            + "meta_failed INTEGER NOT NULL DEFAULT 0, "
            + "last_read INTEGER"
            + ")";

    public BookDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.context = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE);
        createIndexes(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // In-place upgrade: the old implementation dropped the table, which would
        // wipe last_read and user edits. ALTER TABLE ADD COLUMN is supported on
        // Android 4.4 (API 19); each statement is idempotency-guarded.
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE books ADD COLUMN meta_done INTEGER NOT NULL DEFAULT 0");
            } catch (Exception ignored) { // column already present (partial upgrade)
            }
            try {
                db.execSQL("ALTER TABLE books ADD COLUMN user_edited INTEGER NOT NULL DEFAULT 0");
            } catch (Exception ignored) {
            }
        }
        if (oldVersion < 3) {
            createIndexes(db);
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE books ADD COLUMN meta_failed INTEGER NOT NULL DEFAULT 0");
            } catch (Exception ignored) { // column already present (partial upgrade)
            }
        }
        if (oldVersion < 5) {
            // The v5 title index. createIndexes is idempotency-guarded, so it only
            // creates what is missing (the older indexes already exist).
            createIndexes(db);
        }
    }

    /** Indexes for the hot query paths: the stage-2 queue
     *  ({@link #needMeta()}, {@code WHERE meta_done = 0}), the "Recently read"
     *  view ({@code WHERE last_read IS NOT NULL ORDER BY last_read DESC}) and the
     *  catalog list (sorted by {@code title COLLATE NOCASE} — the main screen's
     *  default order). Without them all three walk the whole table on a large
     *  library. Non-unique on purpose (many books share a value); CREATE INDEX is
     *  idempotency-guarded like the ALTER statements above, so a re-run of a
     *  partial upgrade is safe. */
    private static void createIndexes(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE INDEX idx_books_meta_done ON books (meta_done)");
        } catch (Exception ignored) { // index already present (partial upgrade)
        }
        try {
            db.execSQL("CREATE INDEX idx_books_last_read ON books (last_read)");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("CREATE INDEX idx_books_title_nocase ON books (title COLLATE NOCASE)");
        } catch (Exception ignored) {
        }
    }

    /**
     * Opens a READ connection while holding {@link #WRITE_LOCK}. A fresh SQLite
     * connection executes {@code PRAGMA user_version} on open, and that read collides
     * (SQLITE_BUSY, "database is locked") when another connection of this same class
     * is mid-batch-write (the enricher's {@link #updateMetadataBatch} group, the scan's
     * {@link #upsertBasicBatch}): the static lock makes a fresh open wait for the group
     * to finish instead of racing it. The callers of this helper run their whole
     * one-shot read under the same lock; only the LIVE cursors handed out by
     * {@link #cursorAll}/{@link #cursorRecent} outlive it (a cursor keeps using its
     * handle freely after the lock is released).
     */
    private SQLiteDatabase readableDb() {
        synchronized (WRITE_LOCK) {
            return getReadableDatabase();
        }
    }

    /** Inserts the book if new, or updates its metadata fields if it already exists.
     *  The {@code last_read}, {@code meta_done} and {@code user_edited} columns are
     *  never written here: an existing row keeps them as-is (so a stale in-memory
     *  model can never clobber the enrichment state or the user-edited flag) and a
     *  new row gets the schema defaults (both 0). The user-edited flag is set
     *  explicitly via {@link #markUserEdited}. Returns the row id. */
    public long upsert(Book b) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("path", b.path);
            cv.put("format", b.format);
            cv.put("title", b.title);
            cv.put("author", b.author);
            cv.put("publisher", b.publisher);
            cv.put("description", b.description);
            cv.put("series", b.series);
            cv.put("size_bytes", b.sizeBytes);
            cv.put("exported", b.exported ? 1 : 0);

            Cursor c = db.rawQuery("SELECT _id FROM books WHERE path=?", new String[]{b.path});
            long existing = -1;
            try {
                if (c.moveToFirst()) existing = c.getLong(0);
            } finally {
                c.close();
            }
            if (existing >= 0) {
                db.update("books", cv, "_id=?", new String[]{String.valueOf(existing)});
                b.id = existing;
            } else {
                b.id = db.insert("books", null, cv);
            }
            return b.id;
        }
    }

    /** Stage-1 (fast scan) upsert: inserts a skeleton row (file name title, no in-file
     *  metadata yet) or, for an existing row, refreshes only {@code format} and
     *  {@code size_bytes} — title/author/etc, the meta_done / user_edited flags and
     *  last_read are all preserved, so a rescan never resets the enrichment state or
     *  the catalog metadata. */
    public long upsertBasic(Book b) {
        synchronized (WRITE_LOCK) {
            return upsertBasicOn(getWritableDatabase(), b);
        }
    }

    /** Stage-1 batch commit: applies the scanned skeleton rows in groups of
     *  {@link #BATCH_SIZE} books per SQLite transaction instead of one autocommit
     *  per row (one commit per group, not per book). A group is atomic — a row
     *  that fails rolls the whole group back (none of it is persisted) and the
     *  cause is logged; the unapplied rows are simply picked up again on the next
     *  scan (the upserts are idempotent). */
    public void upsertBasicBatch(List<Book> books) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            for (int i = 0; i < books.size(); ) {
                final int to = Math.min(i + BATCH_SIZE, books.size());
                db.beginTransaction();
                try {
                    for (int j = i; j < to; j++) upsertBasicOn(db, books.get(j));
                    db.setTransactionSuccessful();
                } catch (Exception e) {
                    // endTransaction() (without setTransactionSuccessful) rolls the
                    // whole group back; the scan is a full rescan, so the rows are
                    // simply upserted again next time.
                    Log.w(TAG, "Scan batch " + (i + 1) + ".." + to + " rolled back", e);
                } finally {
                    db.endTransaction();
                }
                i = to;
            }
        }
    }

    /** The stage-1 upsert itself (row lookup + insert/update), run against the
     *  given database so it joins the caller's transaction when batched. */
    private static long upsertBasicOn(SQLiteDatabase db, Book b) {
        Cursor c = db.rawQuery("SELECT _id FROM books WHERE path=?", new String[]{b.path});
        long existing = -1;
        try {
            if (c.moveToFirst()) existing = c.getLong(0);
        } finally {
            c.close();
        }
        if (existing >= 0) {
            ContentValues cv = new ContentValues();
            cv.put("format", b.format);
            cv.put("size_bytes", b.sizeBytes);
            db.update("books", cv, "_id=?", new String[]{String.valueOf(existing)});
            b.id = existing;
        } else {
            ContentValues cv = new ContentValues();
            cv.put("path", b.path);
            cv.put("format", b.format);
            cv.put("title", b.title);
            cv.put("size_bytes", b.sizeBytes);
            b.id = db.insert("books", null, cv);
        }
        return b.id;
    }

    /** Stage-2 (background enrichment) update. When the user has edited the catalog
     *  (user_edited=1) only still-empty fields are filled, so user values are never
     *  clobbered; otherwise the extracted values are written (a failed extraction
     *  leaves the file-name title in place). Always marks the book as enriched and
     *  clears the "un-enriched" flag ({@code meta_failed}, set by
     *  {@link #markMetaFailed} when a parse overran the enricher's time budget);
     *  last_read is never touched. */
    public void updateMetadata(long id, MetaData md, boolean fileReadable) {
        synchronized (WRITE_LOCK) {
            applyUpdate(getWritableDatabase(), id, md, fileReadable);
        }
    }

    /** One pre-parsed stage-2 result for {@link #updateMetadataBatch}: the book's
     *  row id and the metadata to persist for it. The parse itself happens OUTSIDE
     *  the database (on the enricher's throwaway thread, under its own time
     *  budget) — the batch only commits the finished results. */
    public static final class MetaUpdate {
        public final long id;
        public final MetaData meta;
        public final boolean fileReadable;

        public MetaUpdate(long id, MetaData meta, boolean fileReadable) {
            this.id = id;
            this.meta = meta;
            this.fileReadable = fileReadable;
        }
    }

    /** Stage-2 batch commit: applies a group of pre-parsed results in groups of
     *  {@link #BATCH_SIZE} books per SQLite transaction instead of one autocommit
     *  per book (the enricher's time budget goes into the file, not the database).
     *  A group is atomic — a statement that fails rolls the whole group back
     *  (none of it is persisted) and the cause is logged; the unapplied books keep
     *  {@code meta_done = 0} and are retried on the next rescan. */
    public void updateMetadataBatch(List<MetaUpdate> updates) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            for (int i = 0; i < updates.size(); ) {
                final int to = Math.min(i + BATCH_SIZE, updates.size());
                db.beginTransaction();
                try {
                    for (int j = i; j < to; j++) {
                        MetaUpdate u = updates.get(j);
                        applyUpdate(db, u.id, u.meta, u.fileReadable);
                    }
                    db.setTransactionSuccessful();
                } catch (Exception e) {
                    // endTransaction() (without setTransactionSuccessful) rolls the
                    // whole group back; the books stay in the stage-2 queue for the
                    // next rescan.
                    Log.w(TAG, "Metadata batch " + (i + 1) + ".." + to + " rolled back", e);
                } finally {
                    db.endTransaction();
                }
                i = to;
            }
        }
    }

    /** The stage-2 update itself (row lookup + user-edit merge + write), run
     *  against the given database so it joins the caller's transaction when
     *  batched. See {@link #updateMetadata} for the contract (user edits are never
     *  clobbered, last_read is never touched). */
    private static void applyUpdate(SQLiteDatabase db, long id, MetaData md,
                                     boolean fileReadable) {
        String[] cols = {"title", "author", "publisher", "description", "series",
                "user_edited"};
        Cursor cur = db.query("books", cols, "_id=?", new String[]{String.valueOf(id)},
                null, null, null);
        if (!cur.moveToFirst()) {
            cur.close();
            return; // row deleted meanwhile
        }
        boolean userEdited = cur.getInt(cur.getColumnIndexOrThrow("user_edited")) == 1;
        // The current field values come from this same cursor (already fetched
        // above), so "is this field blank?" is decided in Java — no extra
        // per-field SELECT on the hottest path of stage 2.
        String curTitle = cur.getString(cur.getColumnIndexOrThrow("title"));
        String curAuthor = cur.getString(cur.getColumnIndexOrThrow("author"));
        String curPublisher = cur.getString(cur.getColumnIndexOrThrow("publisher"));
        String curDescription = cur.getString(cur.getColumnIndexOrThrow("description"));
        String curSeries = cur.getString(cur.getColumnIndexOrThrow("series"));
        cur.close();

        ContentValues cv = new ContentValues();
        cv.put("meta_done", 1);
        // A parse that completed (in time) is no longer "un-enriched".
        cv.put("meta_failed", 0);
        if (md != null && md.found && fileReadable) {
            if (!userEdited || isBlank(curTitle)) cv.put("title", md.title);
            if (!userEdited || isBlank(curAuthor)) cv.put("author", md.author);
            if (!userEdited || isBlank(curPublisher)) cv.put("publisher", md.publisher);
            if (!userEdited || isBlank(curDescription)) cv.put("description", md.description);
            if (!userEdited || isBlank(curSeries)) cv.put("series", md.series);
        }
        // cv always carries at least meta_done, so the update is unconditional.
        db.update("books", cv, "_id=?", new String[]{String.valueOf(id)});
    }

    /** A field value is "blank" when it is NULL or empty/whitespace-only. */
    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    /** All books whose in-file metadata has not been extracted yet (the stage-2
     *  queue). Books whose last parse overran the enricher's time budget
     *  ({@code meta_failed = 1}, the "un-enriched" ones) are ordered last, so every
     *  rescan works through the normal books first and only retries the slow ones
     *  at the end of the pass. */
    public List<Book> needMeta() {
        synchronized (WRITE_LOCK) {
            List<Book> list = new ArrayList<Book>();
            SQLiteDatabase db = readableDb();
            Cursor c = db.query("books", null, "meta_done=0", null, null, null,
                    "meta_failed ASC, _id ASC");
            try {
                while (c.moveToNext()) list.add(fromCursor(c));
            } finally {
                c.close();
            }
            return list;
        }
    }

    /** Same query as {@link #all(String)} but returning a live cursor (for the
     *  ContentProvider / CursorAdapter). */
    public Cursor cursorAll(String formatFilter) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = readableDb();
            if (formatFilter == null || formatFilter.length() == 0) {
                return db.query("books", null, null, null, null, null,
                        "title COLLATE NOCASE ASC");
            }
            return db.query("books", null, "format=?", new String[]{formatFilter},
                    null, null, "title COLLATE NOCASE ASC");
        }
    }

    /** Same query as {@link #recent(int)} but returning a live cursor. */
    public Cursor cursorRecent(int limit) {
        synchronized (WRITE_LOCK) {
            if (limit <= 0) limit = 200;
            SQLiteDatabase db = readableDb();
            return db.query("books", null, "last_read IS NOT NULL", null, null, null,
                    "last_read DESC", String.valueOf(limit));
        }
    }

    /** Returns a fresh row id for a path that already exists (for updating other fields). */
    public long getIdForPath(String path) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = readableDb();
            Cursor c = db.rawQuery("SELECT _id FROM books WHERE path=?", new String[]{path});
            try {
                if (c.moveToFirst()) return c.getLong(0);
                return -1;
            } finally {
                c.close();
            }
        }
    }

    /** Returns all books, optionally filtered by a format id, ordered by title. */
    public List<Book> all(String formatFilter) {
        synchronized (WRITE_LOCK) {
            List<Book> list = new ArrayList<Book>();
            SQLiteDatabase db = readableDb();
            Cursor c;
            if (formatFilter == null || formatFilter.length() == 0) {
                c = db.query("books", null, null, null, null, null,
                        "title COLLATE NOCASE ASC");
            } else {
                c = db.query("books", null, "format=?", new String[]{formatFilter},
                        null, null, "title COLLATE NOCASE ASC");
            }
            try {
                while (c.moveToNext()) list.add(fromCursor(c));
            } finally {
                c.close();
            }
            return list;
        }
    }

    /** Returns the most recently read books (the "Recently read" tab). */
    public List<Book> recent(int limit) {
        synchronized (WRITE_LOCK) {
            if (limit <= 0) limit = 200;
            List<Book> list = new ArrayList<Book>();
            SQLiteDatabase db = readableDb();
            Cursor c = db.query("books", null, "last_read IS NOT NULL", null, null, null,
                    "last_read DESC", String.valueOf(limit));
            try {
                while (c.moveToNext()) list.add(fromCursor(c));
            } finally {
                c.close();
            }
            return list;
        }
    }

    public Book getById(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = readableDb();
            Cursor c = db.query("books", null, "_id=?", new String[]{String.valueOf(id)},
                    null, null, null);
            try {
                if (c.moveToFirst()) return fromCursor(c);
                return null;
            } finally {
                c.close();
            }
        }
    }

    /** Marks the row as needing stage-2 enrichment again. Used after the underlying
     *  file was overwritten (an import with the same name), so the old in-file
     *  metadata is re-extracted instead of being trusted. Also clears the
     *  "un-enriched" flag: the row now points at new file contents, so the old
     *  "this file was too slow to parse" verdict no longer applies. */
    public void markMetaPending(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("meta_done", 0);
            cv.put("meta_failed", 0);
            db.update("books", cv, "_id=?", new String[]{String.valueOf(id)});
        }
    }

    /** Marks the row as "un-enriched": its last parsing attempt overran the
     *  enricher's time budget (see {@code MetaEnricher}), which gave up instead of
     *  blocking the whole queue. The book stays in the stage-2 queue
     *  ({@code meta_done} is left at 0, so it is retried on every rescan) and the
     *  {@code meta_failed} flag makes {@link #needMeta()} take it last. */
    public void markMetaFailed(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("meta_failed", 1);
            db.update("books", cv, "_id=?", new String[]{String.valueOf(id)});
        }
    }

    /** Marks the row as user-edited (the enricher will then only fill still-blank
     *  fields and never clobber the user's values). Monotonic by design: once 1,
     *  it never goes back to 0. */
    public void markUserEdited(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("user_edited", 1);
            db.update("books", cv, "_id=?", new String[]{String.valueOf(id)});
        }
    }

    public void markRead(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("last_read", System.currentTimeMillis());
            db.update("books", cv, "_id=?", new String[]{String.valueOf(id)});
        }
    }

    public void deleteByPath(String path) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("books", "path=?", new String[]{path});
        }
        // Drop the book's cached cover too, so removal never leaves an orphan
        // "covers/<hash>.img" file on disk.
        CoverCache.delete(context, path);
    }

    public void clear() {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("books", null, null);
        }
        // The whole catalog is gone — wipe the whole cover cache with it.
        CoverCache.clear(context);
    }

    /** Maps a books-table cursor row to a {@link Book} (also used by the UI adapter). */
    public static Book fromCursor(Cursor c) {
        Book b = new Book();
        b.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        b.path = c.getString(c.getColumnIndexOrThrow("path"));
        b.format = c.getString(c.getColumnIndexOrThrow("format"));
        b.title = c.getString(c.getColumnIndexOrThrow("title"));
        b.author = c.getString(c.getColumnIndexOrThrow("author"));
        b.publisher = c.getString(c.getColumnIndexOrThrow("publisher"));
        b.description = c.getString(c.getColumnIndexOrThrow("description"));
        b.series = c.getString(c.getColumnIndexOrThrow("series"));
        b.sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes"));
        b.exported = c.getInt(c.getColumnIndexOrThrow("exported")) == 1;
        b.metaDone = c.getInt(c.getColumnIndexOrThrow("meta_done")) == 1;
        b.metaFailed = c.getInt(c.getColumnIndexOrThrow("meta_failed")) == 1;
        b.userEdited = c.getInt(c.getColumnIndexOrThrow("user_edited")) == 1;
        return b;
    }
}
