package com.example.mylibrary.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.example.mylibrary.model.Book;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.List;

/**
 * Robolectric tests for {@link BookProvider} — the read-only ContentProvider that gives
 * the UI's {@code CursorLoader} a real {@code ContentObserver} channel.
 *
 * <p>Rows are written through {@link BookDatabase} (the provider deliberately has no
 * CRUD); the provider's own connection opens the same file lazily on the first query.
 * Run at {@code sdk = 19}.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class BookProviderTest {

    private Context context;
    private BookDatabase db;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("library.db");
        db = new BookDatabase(context);
    }

    private long insert(String path, String format, String title) {
        Book b = new Book();
        b.path = path;
        b.format = format;
        b.title = title;
        b.sizeBytes = 10;
        return db.upsertBasic(b);
    }

    @Test
    public void queryReturnsAllBooks() {
        insert("/a/one.txt", "TXT", "One");
        insert("/b/two.pdf", "PDF", "Two");
        insert("/c/three.epub", "EPUB", "Three");

        Cursor c = context.getContentResolver().query(BookProvider.CONTENT_URI, null, null, null, null);
        try {
            assertEquals(3, c.getCount());
        } finally {
            if (c != null) c.close();
        }
    }

    @Test
    public void queryFiltersByFormat() {
        insert("/a/one.txt", "TXT", "One");
        insert("/b/two.txt", "TXT", "Two");
        insert("/c/three.pdf", "PDF", "Three");

        Cursor c = context.getContentResolver().query(BookProvider.CONTENT_URI, null,
                "format=?", new String[]{"TXT"}, null);
        try {
            assertEquals(2, c.getCount());
            // The filtered rows are the two TXT books, in the title sort order.
            List<String> titles = collectTitles(c);
            assertEquals("One", titles.get(0));
            assertEquals("Two", titles.get(1));
        } finally {
            if (c != null) c.close();
        }
    }

    @Test
    public void queryRecentReturnsOnlyReadBooksNewestFirst() throws Exception {
        long a = insert("/a/one.txt", "TXT", "One");
        long b = insert("/b/two.pdf", "PDF", "Two");
        // "Three" stays unread and must not appear.
        insert("/c/three.epub", "EPUB", "Three");

        db.markRead(a);
        // make the timestamps strictly ordered (markRead uses currentTimeMillis)
        Thread.sleep(5);
        db.markRead(b);

        Cursor c = context.getContentResolver().query(BookProvider.RECENT_URI, null, null, null, null);
        try {
            assertEquals(2, c.getCount());
            c.moveToLast();
            assertEquals("One", c.getString(c.getColumnIndexOrThrow("title")));
            c.moveToPosition(0);
            assertEquals("Two", c.getString(c.getColumnIndexOrThrow("title")));
        } finally {
            if (c != null) c.close();
        }
    }

    @Test
    public void notifyChangeReachesRegisteredObserver() {
        final boolean[] fired = {false};
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                fired[0] = true;
            }
        };
        context.getContentResolver().registerContentObserver(BookProvider.CONTENT_URI, true, observer);

        context.getContentResolver().notifyChange(BookProvider.CONTENT_URI, null);
        // The observer is posted to the main looper — idle it before asserting.
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue("the observer must receive the change", fired[0]);
        context.getContentResolver().unregisterContentObserver(observer);
    }

    @Test
    public void getTypeReportsTheCursorMimeType() {
        assertEquals("vnd.android.cursor.dir/book",
                context.getContentResolver().getType(BookProvider.CONTENT_URI));
        assertEquals("vnd.android.cursor.dir/book",
                context.getContentResolver().getType(BookProvider.RECENT_URI));
    }

    // ------------------------------------------------------------------

    private static List<String> collectTitles(Cursor c) {
        java.util.ArrayList<String> titles = new java.util.ArrayList<String>();
        int i = c.getColumnIndexOrThrow("title");
        if (c.moveToFirst()) {
            do {
                titles.add(c.getString(i));
            } while (c.moveToNext());
        }
        return titles;
    }
}
