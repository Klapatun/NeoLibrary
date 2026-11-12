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
 * catalog rows are written through {@link Catalog} (which delegates to
 * {@link BookDatabase} and calls {@link #notifyChangeAll(Context)}). The {@code
 * CursorLoader} listening to this URI then re-queries and the {@code CursorAdapter}
 * refreshes only the rows that changed, without blocking the UI thread.</p>
 *
 * <p>Writes are deliberately not implemented here; all catalog mutations go through
 * {@link Catalog}.</p>
 */
public class BookProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.mylibrary.books";
    /** All books; the optional {@code selection} narrows the result set —
     *  {@code "format=?"} (the original single-value filter),
     *  {@code "format IN (?,…)"} / {@code "genre IS NOT NULL AND genre
     *  IN (?,…)"} or the two AND-ed (the modal filter's set queries, built
     *  by the caller; the provider's selection grammar, extended
     *  additively — see the {@code FORMAT_IN}/{@code GENRE_IN} patterns). */
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/books");
    /** The "Recently read" view (limit 200). */
    public static final Uri RECENT_URI = Uri.parse("content://" + AUTHORITY + "/books/recent");

    /** The modal filter's selection clause (the plan's step 2): the caller
     *  builds the string itself — {@code format IN (?,…)} with one {@code ?}
     *  per selected format, in {@code Formats.ALL} order — and this provider
     *  translates it to the format-set query. The grammar is extended
     *  ADDITIVELY: the genre filter adds {@code genre IS NOT NULL AND genre
     *  IN (?,…)} (one clause per selected dimension, AND-ed in the
     *  {@code FilterState} order — formats first, then genres), and the
     *  single-value {@code format=?} above keeps working as before. */
    private static final Pattern FORMAT_IN = Pattern.compile(
            "^format\\s+IN\\s*\\((\\?(?:\\s*,\\s*\\?)*?)\\)$");
    private static final Pattern GENRE_IN = Pattern.compile(
            "^genre\\s+IS\\s+NOT\\s+NULL\\s+AND\\s+genre\\s+IN\\s*\\("
                    + "(\\?(?:\\s*,\\s*\\?)*?)\\)$");
    private static final Pattern FORMAT_AND_GENRE_IN = Pattern.compile(
            "^format\\s+IN\\s*\\((\\?(?:\\s*,\\s*\\?)*?)\\)"
                    + "\\s+AND\\s+genre\\s+IS\\s+NOT\\s+NULL\\s+AND\\s+genre\\s+IN\\s*"
                    + "\\((\\?(?:\\s*,\\s*\\?)*?)\\)$");

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
        // queries first (the modal filter's caller-built strings, one
        // placeholder per selected id): "format IN (?,…)" (the format set),
        // "genre IS NOT NULL AND genre IN (?,…)" (the genre set), and the two
        // AND-ed (formats first, in FilterState's queryArgs order)…
        if (selection != null && selectionArgs != null) {
            String sel = selection.trim();
            Matcher m = FORMAT_IN.matcher(sel);
            if (m.matches()) {
                if (countPlaceholders(m.group(1)) == selectionArgs.length) {
                    return db.cursorAll(selectionArgs, null);
                }
            }
            Matcher gm = GENRE_IN.matcher(sel);
            if (gm.matches()) {
                if (countPlaceholders(gm.group(1)) == selectionArgs.length) {
                    return db.cursorAll(null, selectionArgs);
                }
            }
            Matcher cg = FORMAT_AND_GENRE_IN.matcher(sel);
            if (cg.matches()) {
                int formats = countPlaceholders(cg.group(1));
                int genres = countPlaceholders(cg.group(2));
                if (formats + genres == selectionArgs.length) {
                    String[] fmtArgs = new String[formats];
                    String[] genreArgs = new String[genres];
                    System.arraycopy(selectionArgs, 0, fmtArgs, 0, formats);
                    System.arraycopy(selectionArgs, formats, genreArgs, 0, genres);
                    return db.cursorAll(fmtArgs, genreArgs);
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

    /** How many {@code ?} placeholders a captured IN-clause group holds. */
    private static int countPlaceholders(String group) {
        int count = 0;
        for (int i = 0; i < group.length(); i++) {
            if (group.charAt(i) == '?') count++;
        }
        return count;
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
