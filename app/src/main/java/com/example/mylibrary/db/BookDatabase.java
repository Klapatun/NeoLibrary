package com.example.mylibrary.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.util.CoverCache;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQLite-backed catalog of books. Stores the display/catalog metadata for every book
 * (including the formats we do not rewrite in-file) and records "last read" state for
 * the recently-read view.
 */
public class BookDatabase extends SQLiteOpenHelper {

    private static final String TAG = "BookDatabase";
    private static final String DB_NAME = "library.db";
    private static final int DB_VERSION = 9;

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

  private static final String BOOKS_CREATE_V1 =
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

    private static final String WORKS_CREATE =
            "CREATE TABLE works ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "title TEXT, "
            + "author TEXT, "
            + "publisher TEXT, "
            + "description TEXT, "
            + "series TEXT, "
            + "user_edited INTEGER NOT NULL DEFAULT 0, "
            + "last_read INTEGER, "
            + "remote_id TEXT, "
            + "revision INTEGER NOT NULL DEFAULT 0, "
            + "deleted INTEGER NOT NULL DEFAULT 0"
            + ")";

    private static final String FILES_CREATE =
            "CREATE TABLE files ("
            + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "work_id INTEGER NOT NULL REFERENCES works(_id), "
            + "format TEXT, "
            + "path TEXT UNIQUE, "
            + "remote_key TEXT, "
            + "size_bytes INTEGER, "
            + "content_hash TEXT, "
            + "exported INTEGER DEFAULT 0, "
            + "meta_done INTEGER NOT NULL DEFAULT 0, "
            + "meta_failed INTEGER NOT NULL DEFAULT 0, "
            + "availability TEXT NOT NULL DEFAULT 'local', "
            + "adopted_from TEXT"
            + ")";

    private static final String WORK_GENRES_CREATE =
            "CREATE TABLE work_genres ("
            + "work_id INTEGER NOT NULL REFERENCES works(_id), "
            + "position INTEGER NOT NULL, "
            + "name TEXT NOT NULL"
            + ")";

    private static final String LIST_SELECT =
            "SELECT w._id AS _id, f.path AS path, f.format AS format, "
            + "w.title, w.author, w.publisher, w.description, w.series, "
            + "f.size_bytes, f.exported, f.meta_done, f.meta_failed, w.user_edited ";

    private static final String LIST_FROM =
            "FROM works w INNER JOIN files f ON f.work_id = w._id WHERE w.deleted = 0";

    public BookDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.context = context;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createV7Schema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 7) {
            if (oldVersion < 1) {
                db.execSQL(BOOKS_CREATE_V1);
                oldVersion = 1;
            }
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE books ADD COLUMN meta_done INTEGER NOT NULL DEFAULT 0");
                } catch (Exception ignored) {
                }
                try {
                    db.execSQL("ALTER TABLE books ADD COLUMN user_edited INTEGER NOT NULL DEFAULT 0");
                } catch (Exception ignored) {
                }
            }
            if (oldVersion < 3) {
                createLegacyBooksIndexes(db);
            }
            if (oldVersion < 4) {
                try {
                    db.execSQL("ALTER TABLE books ADD COLUMN meta_failed INTEGER NOT NULL DEFAULT 0");
                } catch (Exception ignored) {
                }
            }
            if (oldVersion < 5) {
                createLegacyBooksIndexes(db);
            }
            if (oldVersion < 6) {
                try {
                    db.execSQL("ALTER TABLE books ADD COLUMN genre TEXT");
                } catch (Exception ignored) {
                }
            }
            migrateBooksToV7(db);
        }
        if (oldVersion < 8) {
            try {
                db.execSQL("ALTER TABLE files ADD COLUMN adopted_from TEXT");
            } catch (Exception ignored) {
            }
        }
        if (oldVersion < 9) {
            // No new column: send EPUB/FB2/FB2ZIP with no genres and no user edit
            // back through enrichment so genres from the file reach the catalog.
            db.execSQL("UPDATE files SET meta_done=0, meta_failed=0"
                    + " WHERE format IN ('EPUB', 'FB2', 'FB2ZIP')"
                    + " AND work_id IN ("
                    + " SELECT w._id FROM works w"
                    + " WHERE w.deleted=0 AND w.user_edited=0"
                    + " AND NOT EXISTS ("
                    + " SELECT 1 FROM work_genres g WHERE g.work_id = w._id"
                    + " ))");
        }
    }

    private static void createV7Schema(SQLiteDatabase db) {
        db.execSQL(WORKS_CREATE);
        db.execSQL(FILES_CREATE);
        db.execSQL(WORK_GENRES_CREATE);
        createV7Indexes(db);
    }

    private static void createLegacyBooksIndexes(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE INDEX idx_books_meta_done ON books (meta_done)");
        } catch (Exception ignored) {
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

    private static void createV7Indexes(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE UNIQUE INDEX idx_files_work_format ON files (work_id, format)");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("CREATE INDEX idx_files_work_id ON files (work_id)");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("CREATE INDEX idx_files_meta_done ON files (meta_done)");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("CREATE INDEX idx_works_last_read ON works (last_read)");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("CREATE INDEX idx_works_title_nocase ON works (title COLLATE NOCASE)");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("CREATE UNIQUE INDEX idx_work_genres_work_pos ON work_genres (work_id, position)");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("CREATE UNIQUE INDEX idx_work_genres_work_name ON work_genres (work_id, name)");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("CREATE INDEX idx_work_genres_name ON work_genres (name)");
        } catch (Exception ignored) {
        }
    }

    private static void migrateBooksToV7(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            createV7Schema(db);
            Cursor c = db.rawQuery(
                    "SELECT _id, path, format, title, author, publisher, description, series, "
                    + "size_bytes, exported, meta_done, user_edited, meta_failed, last_read, genre "
                    + "FROM books", null);
            try {
                while (c.moveToNext()) {
                    long bookId = c.getLong(0);
                    Cursor exists = db.rawQuery(
                            "SELECT _id FROM works WHERE _id=?", new String[]{String.valueOf(bookId)});
                    try {
                        if (!exists.moveToFirst()) {
                            ContentValues wv = new ContentValues();
                            wv.put("_id", bookId);
                            wv.put("title", c.getString(3));
                            wv.put("author", c.getString(4));
                            wv.put("publisher", c.getString(5));
                            wv.put("description", c.getString(6));
                            wv.put("series", c.getString(7));
                            wv.put("user_edited", c.getInt(11));
                            if (c.isNull(13)) {
                                wv.putNull("last_read");
                            } else {
                                wv.put("last_read", c.getLong(13));
                            }
                            wv.putNull("remote_id");
                            wv.put("revision", 0);
                            wv.put("deleted", 0);
                            db.insert("works", null, wv);
                        }
                    } finally {
                        exists.close();
                    }

                    Cursor fileExists = db.rawQuery(
                            "SELECT _id FROM files WHERE work_id=?",
                            new String[]{String.valueOf(bookId)});
                    try {
                        if (!fileExists.moveToFirst()) {
                            ContentValues fv = new ContentValues();
                            fv.put("work_id", bookId);
                            fv.put("format", c.getString(2));
                            fv.put("path", c.getString(1));
                            fv.putNull("remote_key");
                            fv.put("size_bytes", c.getLong(8));
                            fv.putNull("content_hash");
                            fv.put("exported", c.getInt(9));
                            fv.put("meta_done", c.getInt(10));
                            fv.put("meta_failed", c.getInt(12));
                            fv.put("availability", "local");
                            db.insert("files", null, fv);
                        }
                    } finally {
                        fileExists.close();
                    }

                    Cursor genreExists = db.rawQuery(
                            "SELECT COUNT(*) FROM work_genres WHERE work_id=?",
                            new String[]{String.valueOf(bookId)});
                    try {
                        genreExists.moveToFirst();
                        if (genreExists.getInt(0) == 0) {
                            String genre = c.getString(14);
                            if (genre != null && genre.trim().length() > 0) {
                                ArrayList<String> one = new ArrayList<String>();
                                one.add(genre);
                                replaceGenresOn(db, bookId, MetaData.normalizeGenres(one));
                            }
                        }
                    } finally {
                        genreExists.close();
                    }
                }
            } finally {
                c.close();
            }
            db.execSQL("DROP TABLE IF EXISTS books");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private SQLiteDatabase readableDb() {
        synchronized (WRITE_LOCK) {
            return getReadableDatabase();
        }
    }

    public long upsert(Book b) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                long workId = upsertWorkAndFile(db, b, true);
                db.setTransactionSuccessful();
                b.id = workId;
                return workId;
            } finally {
                db.endTransaction();
            }
        }
    }

    private static long upsertWorkAndFile(SQLiteDatabase db, Book b, boolean fullMetadata) {
        long workId = -1;
        long fileId = -1;
        Cursor c = db.rawQuery("SELECT _id, work_id FROM files WHERE path=?",
                new String[]{b.path});
        try {
            if (c.moveToFirst()) {
                fileId = c.getLong(0);
                workId = c.getLong(1);
            }
        } finally {
            c.close();
        }

        ArrayList<String> genreList = b.genres == null
                ? new ArrayList<String>() : b.genres;

        if (workId >= 0) {
            if (fullMetadata) {
                ContentValues wv = new ContentValues();
                wv.put("title", b.title);
                wv.put("author", b.author);
                wv.put("publisher", b.publisher);
                wv.put("description", b.description);
                wv.put("series", b.series);
                db.update("works", wv, "_id=?", new String[]{String.valueOf(workId)});
                replaceGenresOn(db, workId, MetaData.normalizeGenres(genreList));
            }
            ContentValues fv = new ContentValues();
            fv.put("format", b.format);
            fv.put("size_bytes", b.sizeBytes);
            if (fullMetadata) fv.put("exported", b.exported ? 1 : 0);
            db.update("files", fv, "_id=?", new String[]{String.valueOf(fileId)});
        } else {
            ContentValues wv = new ContentValues();
            wv.put("title", b.title);
            wv.put("author", b.author);
            wv.put("publisher", b.publisher);
            wv.put("description", b.description);
            wv.put("series", b.series);
            workId = db.insert("works", null, wv);
            ContentValues fv = new ContentValues();
            fv.put("work_id", workId);
            fv.put("path", b.path);
            fv.put("format", b.format);
            fv.put("size_bytes", b.sizeBytes);
            fv.put("exported", b.exported ? 1 : 0);
            fv.put("availability", "local");
            db.insert("files", null, fv);
            replaceGenresOn(db, workId, MetaData.normalizeGenres(genreList));
        }
        b.id = workId;
        return workId;
    }

    public long upsertBasic(Book b) {
        synchronized (WRITE_LOCK) {
            return upsertBasicOn(getWritableDatabase(), b);
        }
    }

    /**
     * Re-import skeleton in one transaction: {@link #upsertBasicOn} plus, when the
     * path was already in the catalog, the same flag reset as {@link #markMetaPending}.
     */
    public long importSkeleton(Book b, boolean alreadyInCatalog) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                long workId = upsertBasicOn(db, b);
                if (alreadyInCatalog) {
                    ContentValues fv = new ContentValues();
                    fv.put("meta_done", 0);
                    fv.put("meta_failed", 0);
                    db.update("files", fv, "work_id=?", new String[]{String.valueOf(workId)});
                    ContentValues wv = new ContentValues();
                    wv.put("user_edited", 0);
                    db.update("works", wv, "_id=?", new String[]{String.valueOf(workId)});
                }
                db.setTransactionSuccessful();
                b.id = workId;
                return workId;
            } finally {
                db.endTransaction();
            }
        }
    }

    /** Full metadata upsert plus {@code user_edited=1} in one transaction. */
    public void saveUserEdit(Book b) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                long workId = upsertWorkAndFile(db, b, true);
                ContentValues cv = new ContentValues();
                cv.put("user_edited", 1);
                db.update("works", cv, "_id=?", new String[]{String.valueOf(workId)});
                db.setTransactionSuccessful();
                b.id = workId;
            } finally {
                db.endTransaction();
            }
        }
    }

    /**
     * Same catalog edit as {@link #saveUserEdit}, but updates the existing
     * {@code files} row (matched by work id and the book's current path) to
     * {@code newPath} and records the previous path in {@code adopted_from}.
     * Does not insert a work or a file. On success {@code book.path} becomes
     * {@code newPath}.
     */
    public boolean saveUserEditAtNewPath(Book book, String newPath) {
        if (book == null || newPath == null || newPath.length() == 0
                || newPath.equals(book.path)) {
            return false;
        }
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            boolean committed = false;
            try {
                long fileId = -1;
                Cursor c = db.rawQuery(
                        "SELECT _id FROM files WHERE work_id=? AND path=?",
                        new String[]{String.valueOf(book.id), book.path});
                try {
                    if (c.moveToFirst()) fileId = c.getLong(0);
                } finally {
                    c.close();
                }
                if (fileId < 0) return false;

                ContentValues wv = new ContentValues();
                wv.put("title", book.title);
                wv.put("author", book.author);
                wv.put("publisher", book.publisher);
                wv.put("description", book.description);
                wv.put("series", book.series);
                wv.put("user_edited", 1);
                db.update("works", wv, "_id=?", new String[]{String.valueOf(book.id)});

                ArrayList<String> genreList = book.genres == null
                        ? new ArrayList<String>() : book.genres;
                replaceGenresOn(db, book.id, MetaData.normalizeGenres(genreList));

                ContentValues fv = new ContentValues();
                fv.put("path", newPath);
                fv.put("adopted_from", book.path);
                fv.put("format", book.format);
                fv.put("size_bytes", book.sizeBytes);
                fv.put("exported", book.exported ? 1 : 0);
                db.update("files", fv, "_id=?", new String[]{String.valueOf(fileId)});

                db.setTransactionSuccessful();
                committed = true;
            } catch (SQLiteConstraintException ignored) {
                committed = false;
            } finally {
                db.endTransaction();
            }
            if (committed) book.path = newPath;
            return committed;
        }
    }

    /** Absolute {@code files.adopted_from} values. Empty, never null. Does not write. */
    public Set<String> adoptedFromPaths() {
        synchronized (WRITE_LOCK) {
            HashSet<String> paths = new HashSet<String>();
            Cursor c = readableDb().rawQuery(
                    "SELECT adopted_from FROM files WHERE adopted_from IS NOT NULL", null);
            try {
                while (c.moveToNext()) {
                    if (c.isNull(0)) continue;
                    paths.add(new File(c.getString(0)).getAbsolutePath());
                }
            } finally {
                c.close();
            }
            return paths;
        }
    }

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
                    Log.w(TAG, "Scan batch " + (i + 1) + ".." + to + " rolled back", e);
                } finally {
                    db.endTransaction();
                }
                i = to;
            }
        }
    }

    private static long upsertBasicOn(SQLiteDatabase db, Book b) {
        long workId = -1;
        Cursor c = db.rawQuery("SELECT _id, work_id FROM files WHERE path=?",
                new String[]{b.path});
        try {
            if (c.moveToFirst()) {
                long fileId = c.getLong(0);
                workId = c.getLong(1);
                ContentValues cv = new ContentValues();
                cv.put("format", b.format);
                cv.put("size_bytes", b.sizeBytes);
                db.update("files", cv, "_id=?", new String[]{String.valueOf(fileId)});
                b.id = workId;
                return workId;
            }
        } finally {
            c.close();
        }

        ContentValues wv = new ContentValues();
        wv.put("title", b.title);
        workId = db.insert("works", null, wv);
        ContentValues fv = new ContentValues();
        fv.put("work_id", workId);
        fv.put("path", b.path);
        fv.put("format", b.format);
        fv.put("size_bytes", b.sizeBytes);
        fv.put("availability", "local");
        db.insert("files", null, fv);
        b.id = workId;
        return workId;
    }

    public void updateMetadata(long id, MetaData md, boolean fileReadable) {
        synchronized (WRITE_LOCK) {
            applyUpdate(getWritableDatabase(), id, md, fileReadable);
        }
    }

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
                    Log.w(TAG, "Metadata batch " + (i + 1) + ".." + to + " rolled back", e);
                } finally {
                    db.endTransaction();
                }
                i = to;
            }
        }
    }

    private static void applyUpdate(SQLiteDatabase db, long workId, MetaData md,
                                    boolean fileReadable) {
        Cursor cur = db.query("works",
                new String[]{"title", "author", "publisher", "description", "series", "user_edited"},
                "_id=? AND deleted=0", new String[]{String.valueOf(workId)},
                null, null, null);
        if (!cur.moveToFirst()) {
            cur.close();
            return;
        }
        boolean userEdited = cur.getInt(cur.getColumnIndexOrThrow("user_edited")) == 1;
        String curTitle = cur.getString(cur.getColumnIndexOrThrow("title"));
        String curAuthor = cur.getString(cur.getColumnIndexOrThrow("author"));
        String curPublisher = cur.getString(cur.getColumnIndexOrThrow("publisher"));
        String curDescription = cur.getString(cur.getColumnIndexOrThrow("description"));
        String curSeries = cur.getString(cur.getColumnIndexOrThrow("series"));
        cur.close();

        List<String> curGenres = readGenresForWork(db, workId);

        ContentValues wv = new ContentValues();
        if (fileReadable && md != null) {
            if (md.found && (!userEdited || isBlank(curTitle))) wv.put("title", md.title);
            if (md.author != null && (!userEdited || isBlank(curAuthor))) {
                wv.put("author", md.author);
            }
            if (md.publisher != null && (!userEdited || isBlank(curPublisher))) {
                wv.put("publisher", md.publisher);
            }
            if (md.description != null && (!userEdited || isBlank(curDescription))) {
                wv.put("description", md.description);
            }
            if (md.series != null && (!userEdited || isBlank(curSeries))) {
                wv.put("series", md.series);
            }
        }
        if (wv.size() > 0) {
            db.update("works", wv, "_id=?", new String[]{String.valueOf(workId)});
        }

        ContentValues fv = new ContentValues();
        fv.put("meta_done", 1);
        fv.put("meta_failed", 0);
        db.update("files", fv, "work_id=?", new String[]{String.valueOf(workId)});

        if (fileReadable && md != null && md.genres != null) {
            boolean genresEmpty = curGenres.isEmpty();
            if (!userEdited || genresEmpty) {
                replaceGenresOn(db, workId, MetaData.normalizeGenres(md.genres));
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    private static List<String> readGenresForWork(SQLiteDatabase db, long workId) {
        List<String> names = new ArrayList<String>();
        Cursor g = db.rawQuery(
                "SELECT name FROM work_genres WHERE work_id=? ORDER BY position",
                new String[]{String.valueOf(workId)});
        try {
            while (g.moveToNext()) names.add(g.getString(0));
        } finally {
            g.close();
        }
        return names;
    }

    private static void replaceGenresOn(SQLiteDatabase db, long workId, List<String> names) {
        db.delete("work_genres", "work_id=?", new String[]{String.valueOf(workId)});
        for (int i = 0; i < names.size(); i++) {
            ContentValues cv = new ContentValues();
            cv.put("work_id", workId);
            cv.put("position", i);
            cv.put("name", names.get(i));
            db.insert("work_genres", null, cv);
        }
    }

    private void attachGenres(SQLiteDatabase db, List<Book> books) {
        if (books.isEmpty()) return;
        StringBuilder q = new StringBuilder(
                "SELECT work_id, name FROM work_genres ORDER BY work_id, position");
        Cursor c = db.rawQuery(q.toString(), null);
        Map<Long, ArrayList<String>> byWork = new HashMap<Long, ArrayList<String>>();
        try {
            while (c.moveToNext()) {
                long wid = c.getLong(0);
                ArrayList<String> list = byWork.get(wid);
                if (list == null) {
                    list = new ArrayList<String>();
                    byWork.put(wid, list);
                }
                list.add(c.getString(1));
            }
        } finally {
            c.close();
        }
        for (int i = 0; i < books.size(); i++) {
            Book b = books.get(i);
            ArrayList<String> g = byWork.get(b.id);
            if (g != null) {
                b.genres = g;
            } else {
                b.genres = new ArrayList<String>();
            }
        }
    }

    public List<Book> needMeta() {
        synchronized (WRITE_LOCK) {
            List<Book> list = new ArrayList<Book>();
            SQLiteDatabase db = readableDb();
            String sql = LIST_SELECT + LIST_FROM + " AND f.meta_done=0 "
                    + "ORDER BY f.meta_failed ASC, w._id ASC";
            Cursor c = db.rawQuery(sql, null);
            try {
                while (c.moveToNext()) list.add(fromCursor(c));
            } finally {
                c.close();
            }
            attachGenres(db, list);
            return list;
        }
    }

    public Cursor cursorAll(String formatFilter) {
        if (formatFilter == null || formatFilter.length() == 0) {
            return cursorAll((String[]) null);
        }
        return cursorAll(new String[]{formatFilter});
    }

    public Cursor cursorAll(String[] formatFilter) {
        return cursorAll(formatFilter, null);
    }

    public Cursor cursorAll(String[] formatFilter, String[] genreFilter) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = readableDb();
            StringBuilder sql = new StringBuilder(LIST_SELECT);
            sql.append(LIST_FROM);
            List<String> args = new ArrayList<String>();
            if (formatFilter != null && formatFilter.length > 0) {
                sql.append(" AND f.format IN (");
                for (int i = 0; i < formatFilter.length; i++) {
                    if (i > 0) sql.append(",");
                    sql.append("?");
                    args.add(formatFilter[i]);
                }
                sql.append(")");
            }
            if (genreFilter != null && genreFilter.length > 0) {
                sql.append(" AND EXISTS (SELECT 1 FROM work_genres g WHERE g.work_id = w._id AND g.name IN (");
                for (int i = 0; i < genreFilter.length; i++) {
                    if (i > 0) sql.append(",");
                    sql.append("?");
                    args.add(genreFilter[i]);
                }
                sql.append("))");
            }
            sql.append(" ORDER BY w.title COLLATE NOCASE ASC");
            return db.rawQuery(sql.toString(),
                    args.isEmpty() ? null : args.toArray(new String[0]));
        }
    }

    public List<String> distinctGenres() {
        synchronized (WRITE_LOCK) {
            List<String> genres = new ArrayList<String>();
            SQLiteDatabase helperDb = getWritableDatabase();
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    helperDb.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor c = null;
            try {
                c = db.rawQuery(
                        "SELECT DISTINCT g.name FROM work_genres g "
                        + "INNER JOIN works w ON w._id = g.work_id WHERE w.deleted = 0 "
                        + "ORDER BY g.name", null);
                while (c.moveToNext()) genres.add(c.getString(0));
            } finally {
                if (c != null) c.close();
                db.close();
            }
            return genres;
        }
    }

    public Cursor cursorRecent(int limit) {
        synchronized (WRITE_LOCK) {
            if (limit <= 0) limit = 200;
            SQLiteDatabase db = readableDb();
            String sql = LIST_SELECT + LIST_FROM + " AND w.last_read IS NOT NULL "
                    + "ORDER BY w.last_read DESC LIMIT " + limit;
            return db.rawQuery(sql, null);
        }
    }

    public long getIdForPath(String path) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = readableDb();
            Cursor c = db.rawQuery("SELECT work_id FROM files WHERE path=?", new String[]{path});
            try {
                if (c.moveToFirst()) return c.getLong(0);
                return -1;
            } finally {
                c.close();
            }
        }
    }

    public List<Book> all(String formatFilter) {
        synchronized (WRITE_LOCK) {
            List<Book> list = new ArrayList<Book>();
            SQLiteDatabase db = readableDb();
            Cursor c;
            if (formatFilter == null || formatFilter.length() == 0) {
                c = db.rawQuery(LIST_SELECT + LIST_FROM + " ORDER BY w.title COLLATE NOCASE ASC", null);
            } else {
                c = db.rawQuery(LIST_SELECT + LIST_FROM
                        + " AND f.format=? ORDER BY w.title COLLATE NOCASE ASC",
                        new String[]{formatFilter});
            }
            try {
                while (c.moveToNext()) list.add(fromCursor(c));
            } finally {
                c.close();
            }
            attachGenres(db, list);
            return list;
        }
    }

    public List<Book> recent(int limit) {
        synchronized (WRITE_LOCK) {
            if (limit <= 0) limit = 200;
            List<Book> list = new ArrayList<Book>();
            SQLiteDatabase db = readableDb();
            String sql = LIST_SELECT + LIST_FROM + " AND w.last_read IS NOT NULL "
                    + "ORDER BY w.last_read DESC LIMIT " + limit;
            Cursor c = db.rawQuery(sql, null);
            try {
                while (c.moveToNext()) list.add(fromCursor(c));
            } finally {
                c.close();
            }
            attachGenres(db, list);
            return list;
        }
    }

    public Book getById(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = readableDb();
            String sql = LIST_SELECT + LIST_FROM + " AND w._id=?";
            Cursor c = db.rawQuery(sql, new String[]{String.valueOf(id)});
            try {
                if (!c.moveToFirst()) return null;
                Book b = fromCursor(c);
                attachGenres(db, listOf(b));
                return b;
            } finally {
                c.close();
            }
        }
    }

    /**
     * One {@code files} row as the locator reads it: id, path, availability,
     * remote key, format. These columns stay off {@link Book} and off the list
     * cursor. Read-only — nothing here writes the row or queues a download.
     */
    public static final class FileEdition {
        public final long id;
        public final String path;
        public final String availability;
        public final String remoteKey;
        public final String format;

        public FileEdition(long id, String path, String availability,
                           String remoteKey, String format) {
            this.id = id;
            this.path = path;
            this.availability = availability;
            this.remoteKey = remoteKey;
            this.format = format;
        }
    }

    private static final String[] FILE_EDITION_COLUMNS = new String[] {
            "_id", "path", "availability", "remote_key", "format"
    };

    /** The edition with this {@code files._id}, or null when that row is gone. */
    public FileEdition getFileEdition(long fileId) {
        synchronized (WRITE_LOCK) {
            Cursor c = readableDb().query("files", FILE_EDITION_COLUMNS,
                    "_id=?", new String[]{String.valueOf(fileId)},
                    null, null, null);
            try {
                if (!c.moveToFirst()) return null;
                return readFileEdition(c);
            } finally {
                c.close();
            }
        }
    }

    /** Editions of one work, smallest {@code files._id} first. */
    public List<FileEdition> listFileEditions(long workId) {
        synchronized (WRITE_LOCK) {
            List<FileEdition> list = new ArrayList<FileEdition>();
            Cursor c = readableDb().query("files", FILE_EDITION_COLUMNS,
                    "work_id=?", new String[]{String.valueOf(workId)},
                    null, null, "_id ASC");
            try {
                while (c.moveToNext()) list.add(readFileEdition(c));
            } finally {
                c.close();
            }
            return list;
        }
    }

    private static FileEdition readFileEdition(Cursor c) {
        return new FileEdition(
                c.getLong(c.getColumnIndexOrThrow("_id")),
                c.getString(c.getColumnIndexOrThrow("path")),
                c.getString(c.getColumnIndexOrThrow("availability")),
                c.getString(c.getColumnIndexOrThrow("remote_key")),
                c.getString(c.getColumnIndexOrThrow("format")));
    }

    private static List<Book> listOf(Book b) {
        List<Book> list = new ArrayList<Book>();
        list.add(b);
        return list;
    }

    public void markMetaPending(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues fv = new ContentValues();
                fv.put("meta_done", 0);
                fv.put("meta_failed", 0);
                db.update("files", fv, "work_id=?", new String[]{String.valueOf(id)});
                ContentValues wv = new ContentValues();
                wv.put("user_edited", 0);
                db.update("works", wv, "_id=?", new String[]{String.valueOf(id)});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    public void markMetaFailed(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("meta_failed", 1);
            db.update("files", cv, "work_id=?", new String[]{String.valueOf(id)});
        }
    }

    public void markUserEdited(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("user_edited", 1);
            db.update("works", cv, "_id=?", new String[]{String.valueOf(id)});
        }
    }

    public void markRead(long id) {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("last_read", System.currentTimeMillis());
            db.update("works", cv, "_id=?", new String[]{String.valueOf(id)});
        }
    }

    public void deleteByPath(String path) {
        long workId = -1;
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            Cursor c = db.rawQuery("SELECT _id, work_id FROM files WHERE path=?",
                    new String[]{path});
            try {
                if (!c.moveToFirst()) return;
                long fileId = c.getLong(0);
                workId = c.getLong(1);
                db.delete("files", "_id=?", new String[]{String.valueOf(fileId)});
                Cursor remaining = db.rawQuery(
                        "SELECT COUNT(*) FROM files WHERE work_id=?",
                        new String[]{String.valueOf(workId)});
                try {
                    remaining.moveToFirst();
                    if (remaining.getInt(0) == 0) {
                        db.delete("work_genres", "work_id=?", new String[]{String.valueOf(workId)});
                        db.delete("works", "_id=?", new String[]{String.valueOf(workId)});
                    }
                } finally {
                    remaining.close();
                }
            } finally {
                c.close();
            }
        }
        CoverCache.delete(context, path);
    }

    public void removeMissingUnderRoot(String root, Set<String> foundPaths) {
        String normalizedRoot = new File(root).getAbsolutePath();
        Set<String> found = new HashSet<String>();
        for (String path : foundPaths) found.add(new File(path).getAbsolutePath());
        List<String> missing = new ArrayList<String>();
        synchronized (WRITE_LOCK) {
            Cursor c = getReadableDatabase().query("files", new String[]{"path"},
                    null, null, null, null, null);
            try {
                while (c.moveToNext()) {
                    String path = c.getString(0);
                    if (isUnderRoot(path, normalizedRoot) && !found.contains(path)) {
                        File file = new File(path);
                        if (!file.exists() || !file.isFile()) missing.add(path);
                    }
                }
            } finally {
                c.close();
            }
            for (String path : missing) deleteByPath(path);
        }
    }

    static boolean isUnderRoot(String path, String root) {
        String normalizedPath = new File(path).getAbsolutePath();
        String normalizedRoot = new File(root).getAbsolutePath();
        return normalizedPath.equals(normalizedRoot)
                || normalizedPath.startsWith(normalizedRoot + File.separator);
    }

    public void clear() {
        synchronized (WRITE_LOCK) {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("work_genres", null, null);
            db.delete("files", null, null);
            db.delete("works", null, null);
        }
        CoverCache.clear(context);
    }

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
