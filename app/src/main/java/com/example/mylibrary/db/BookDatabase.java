package com.example.mylibrary.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.model.Book;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed catalog of books. Stores the display/catalog metadata for every book
 * (including the formats we do not rewrite in-file) and records "last read" state for
 * the recently-read view.
 */
public class BookDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "library.db";
    private static final int DB_VERSION = 2;

    /** Serializes all write operations. A static lock (not an instance monitor) so
     *  that concurrent writers from different BookDatabase instances (each Activity
     *  creates its own) still cannot interleave and lose updates. */
    private static final Object WRITE_LOCK = new Object();

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
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE);
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
    }

    /** Inserts the book if new, or updates its metadata fields if it already exists. The
     *  {@code last_read} timestamp is always preserved across rescans. The caller is
     *  expected to pass a model read fresh from this database (so the meta_done /
     *  user_edited flags are not clobbered). Returns the row id. */
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
            cv.put("meta_done", b.metaDone ? 1 : 0);
            cv.put("user_edited", b.userEdited ? 1 : 0);

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
            cur.close();

            ContentValues cv = new ContentValues();
            cv.put("meta_done", 1);
            if (md != null && md.found && fileReadable) {
                if (!userEdited || isBlankValue(db, id, "title")) cv.put("title", md.title);
                if (!userEdited || isBlankValue(db, id, "author")) cv.put("author", md.author);
                if (!userEdited || isBlankValue(db, id, "publisher")) cv.put("publisher", md.publisher);
                if (!userEdited || isBlankValue(db, id, "description")) cv.put("description", md.description);
                if (!userEdited || isBlankValue(db, id, "series")) cv.put("series", md.series);
            }
            // cv always carries at least meta_done, so the update is unconditional.
            db.update("books", cv, "_id=?", new String[]{String.valueOf(id)});
        }
    }

    private static boolean isBlankValue(SQLiteDatabase db, long id, String column) {
        Cursor c = db.query("books", new String[]{column}, "_id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            if (!c.moveToFirst()) return true;
            int i = c.getColumnIndexOrThrow(column);
            return c.isNull(i) || c.getString(i).trim().length() == 0;
        } finally {
            c.close();
        }
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
    }

    public void clear() {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("books", null, null);
        }
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
