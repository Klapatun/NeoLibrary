package com.example.mylibrary.db;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** All books; the optional {@code selection} narrows the result set —
     *  {@code "format=?"} (the original single-value filter) or
     *  {@code "format IN (?,…)} (the modal filter's set query, built by the
     *  caller; the provider's selection grammar, extended additively). */
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/books");
    /** The "Recently read" view (limit 200). */
    public static final Uri RECENT_URI = Uri.parse("content://" + AUTHORITY + "/books/recent");

    /** The modal filter's selection clause (the plan's step 2): the caller
     *  builds the string itself — {@code format IN (?,…)} with one {@code ?}
     *  per selected format, in {@code Formats.ALL} order — and this provider
     *  translates it to the format-set query. The grammar is extended
     *  ADDITIVELY: future filter types add their clause here, and the
     *  single-value {@code format=?} above keeps working as before. */
    private static final Pattern FORMAT_IN = Pattern.compile(
            "^format\\s+IN\\s*\\((\\?(?:\\s*,\\s*\\?)*?)\\)$");

    /**
     * Announces a catalog change to BOTH views at once. The all-books list and the
     * "recently read" view observe different URIs, so a change that affects both
     * (a delete, a metadata edit, a mark-read) must be announced on both —
     * {@code notifyChange(CONTENT_URI)} alone would leave the "recently read"
     * cursor stale (the local {@code SQLiteCursor} has no automatic refresh).
     */
    public static void notifyChangeAll(Context context) {
        ContentResolver resolver = context.getContentResolver();
        resolver.notifyChange(CONTENT_URI, null);
        resolver.notifyChange(RECENT_URI, null);
    }

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
        // Books root: the optional selection narrows the result set. The set
        // query first (the modal filter's "format IN (?,…)", the caller-built
        // string, one placeholder per selected format)…
        if (selection != null && selectionArgs != null) {
            Matcher m = FORMAT_IN.matcher(selection.trim());
            if (m.matches()) {
                String group = m.group(1);
                int placeholders = 0;
                for (int i = 0; i < group.length(); i++) {
                    if (group.charAt(i) == '?') placeholders++;
                }
                if (placeholders == selectionArgs.length) {
                    return db.cursorAll(selectionArgs);
                }
            }
        }
        // …then the original single-value "format=?" (as before).
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
