package com.example.mylibrary.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

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

    private static final String DB_NAME = "library.db";
    private static final int DB_VERSION = 3;

    /** Serializes all write operations. A static lock (not an instance monitor) so
     *  that concurrent writers from different BookDatabase instances (each Activity
     *  creates its own) still cannot interleave and lose updates. */
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
    }

    /** Indexes for the two hot query paths: the stage-2 queue
     *  ({@link #needMeta()}, {@code WHERE meta_done = 0}) and the "Recently read"
     *  view ({@code WHERE last_read IS NOT NULL ORDER BY last_read DESC}). Without
     *  them both walk the whole table on a large library. Non-unique on purpose
     *  (many books share a value); CREATE INDEX is idempotency-guarded like the
     *  ALTER statements above, so a re-run of a partial upgrade is safe. */
    private static void createIndexes(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE INDEX idx_books_meta_done ON books (meta_done)");
        } catch (Exception ignored) { // index already present (partial upgrade)
        }
        try {
            db.execSQL("CREATE INDEX idx_books_last_read ON books (last_read)");
        } catch (Exception ignored) {
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
            SQLiteDatabase db = getWritableDatabase();
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
    }

    /** Stage-2 (background enrichment) update. When the user has edited the catalog
     *  (user_edited=1) only still-empty fields are filled, so user values are never
     *  clobbered; otherwise the extracted values are written (a failed extraction
     *  leaves the file-name title in place). Always marks the book as enriched;
     *  last_read is never touched. */
    public void updateMetadata(long id, MetaData md, boolean fileReadable) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
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
    }

    /** A field value is "blank" when it is NULL or empty/whitespace-only. */
    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    /** All books whose in-file metadata has not been extracted yet (stage-2 queue). */
    public List<Book> needMeta() {
        List<Book> list = new ArrayList<Book>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("books", null, "meta_done=0", null, null, null, null);
        try {
            while (c.moveToNext()) list.add(fromCursor(c));
        } finally {
            c.close();
        }
        return list;
    }

    /** Same query as {@link #all(String)} but returning a live cursor (for the
     *  ContentProvider / CursorAdapter). */
    public Cursor cursorAll(String formatFilter) {
        SQLiteDatabase db = getReadableDatabase();
        if (formatFilter == null || formatFilter.length() == 0) {
            return db.query("books", null, null, null, null, null, "title COLLATE NOCASE ASC");
        }
        return db.query("books", null, "format=?", new String[]{formatFilter},
                null, null, "title COLLATE NOCASE ASC");
    }

    /** Same query as {@link #recent(int)} but returning a live cursor. */
    public Cursor cursorRecent(int limit) {
        if (limit <= 0) limit = 200;
        SQLiteDatabase db = getReadableDatabase();
        return db.query("books", null, "last_read IS NOT NULL", null, null, null,
                "last_read DESC", String.valueOf(limit));
    }

    /** Returns a fresh row id for a path that already exists (for updating other fields). */
    public long getIdForPath(String path) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT _id FROM books WHERE path=?", new String[]{path});
        try {
            if (c.moveToFirst()) return c.getLong(0);
            return -1;
        } finally {
            c.close();
        }
    }

    /** Returns all books, optionally filtered by a format id, ordered by title. */
    public List<Book> all(String formatFilter) {
        List<Book> list = new ArrayList<Book>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c;
        if (formatFilter == null || formatFilter.length() == 0) {
            c = db.query("books", null, null, null, null, null, "title COLLATE NOCASE ASC");
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

    /** Returns the most recently read books (the "Recently read" tab). */
    public List<Book> recent(int limit) {
        if (limit <= 0) limit = 200;
        List<Book> list = new ArrayList<Book>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("books", null, "last_read IS NOT NULL", null, null, null,
                "last_read DESC", String.valueOf(limit));
        try {
            while (c.moveToNext()) list.add(fromCursor(c));
        } finally {
            c.close();
        }
        return list;
    }

    public Book getById(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("books", null, "_id=?", new String[]{String.valueOf(id)},
                null, null, null);
        try {
            if (c.moveToFirst()) return fromCursor(c);
            return null;
        } finally {
            c.close();
        }
    }

    /** Marks the row as needing stage-2 enrichment again. Used after the underlying
     *  file was overwritten (an import with the same name), so the old in-file
     *  metadata is re-extracted instead of being trusted. */
    public void markMetaPending(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("meta_done", 0);
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
        b.userEdited = c.getInt(c.getColumnIndexOrThrow("user_edited")) == 1;
        return b;
    }
}
