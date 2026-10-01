package com.example.mylibrary.db;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Read-only content provider over the book catalog.
 *
 * <p>Its only purpose is to give the UI a real {@code ContentObserver} channel: the
 * catalog rows are written directly through {@link BookDatabase}, and after every
 * batch of work (stage-1 scan, background enrichment, import) the app calls
 * {@code getContentResolver().notifyChange(CONTENT_URI, null)} — the {@code
 * CursorLoader} listening to this URI then re-queries and the {@code CursorAdapter}
 * refreshes only the rows that changed, without blocking the UI thread.</p>
 *
 * <p>Writes are deliberately not implemented here; all catalog mutations go through
 * {@link BookDatabase} (which also owns the write lock).</p>
 */
public class BookProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.mylibrary.books";
    /** All books (optionally filtered by {@code selection = "format=?"}). */
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/books");
    /** The "Recently read" view (limit 200). */
    public static final Uri RECENT_URI = Uri.parse("content://" + AUTHORITY + "/books/recent");

    private BookDatabase db;

    @Override
    public boolean onCreate() {
        db = new BookDatabase(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        if (RECENT_URI.equals(uri)) {
            return db.cursorRecent(200);
        }
        // Books root: an optional "format=?" selection narrows the result set.
        String filter = null;
        if ("format=?".equals(selection) && selectionArgs != null && selectionArgs.length > 0) {
            filter = selectionArgs[0];
        }
        return db.cursorAll(filter);
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/book";
    }

    // ------------------------------------------------------------------
    // The catalog is written through BookDatabase, never through this provider.
    // ------------------------------------------------------------------

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("catalog writes go through BookDatabase");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("catalog writes go through BookDatabase");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("catalog writes go through BookDatabase");
    }
}
