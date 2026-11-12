package com.example.mylibrary.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.model.Book;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Contract tests for {@link Catalog}: every catalog write goes through the facade
 * and announces on both {@link BookProvider#CONTENT_URI} and
 * {@link BookProvider#RECENT_URI}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class CatalogTest {

    private Context context;
    private BookDatabase db;
    private Catalog catalog;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("library.db");
        db = new BookDatabase(context);
        catalog = new Catalog(context, db);
    }

    private static Book book(String path, String format, String title) {
        Book b = new Book();
        b.path = path;
        b.format = format;
        b.title = title;
        b.sizeBytes = 100;
        b.genres = new ArrayList<String>();
        return b;
    }

    private static ArrayList<String> genres(String... names) {
        ArrayList<String> g = new ArrayList<String>();
        for (int i = 0; i < names.length; i++) {
            g.add(names[i]);
        }
        return g;
    }

    private long lastReadOf(long workId) {
        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT last_read FROM works WHERE _id=?",
                new String[]{String.valueOf(workId)});
        try {
            c.moveToFirst();
            return c.isNull(0) ? 0 : c.getLong(0);
        } finally {
            c.close();
        }
    }

    private int workGenreRowCount(long workId) {
        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery(
                "SELECT COUNT(*) FROM work_genres WHERE work_id=?",
                new String[]{String.valueOf(workId)});
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private static class UriNotifyCounter {
        final AtomicInteger content = new AtomicInteger();
        final AtomicInteger recent = new AtomicInteger();
        ContentObserver contentObserver;
        ContentObserver recentObserver;

        void register(Context ctx) {
            contentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    content.incrementAndGet();
                }
            };
            recentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    recent.incrementAndGet();
                }
            };
            // RECENT_URI is under CONTENT_URI — descendants=false so one
            // notifyChangeAll yields one callback per registered observer.
            ctx.getContentResolver().registerContentObserver(
                    BookProvider.CONTENT_URI, false, contentObserver);
            ctx.getContentResolver().registerContentObserver(
                    BookProvider.RECENT_URI, false, recentObserver);
        }

        void unregister(Context ctx) {
            if (contentObserver != null) {
                ctx.getContentResolver().unregisterContentObserver(contentObserver);
            }
            if (recentObserver != null) {
                ctx.getContentResolver().unregisterContentObserver(recentObserver);
            }
        }

        void idle() {
            shadowOf(Looper.getMainLooper()).idle();
        }

        void assertBothFired() {
            idle();
            assertTrue("CONTENT_URI must be notified", content.get() >= 1);
            assertTrue("RECENT_URI must be notified", recent.get() >= 1);
        }
    }

    @Test
    public void saveUserEditNotifiesBothUrisAndSetsFlag() {
        Book b = book("/x/edit.epub", "EPUB", "Old");
        b.genres = genres("OldA", "OldB");
        long id = db.upsert(b);
        db.markRead(id);
        long lastRead = lastReadOf(id);

        UriNotifyCounter obs = new UriNotifyCounter();
        obs.register(context);

        Book edited = db.getById(id);
        edited.title = "New";
        edited.genres = genres("Alpha", "Beta");
        catalog.saveUserEdit(edited);

        obs.assertBothFired();
        obs.unregister(context);

        Book after = db.getById(id);
        assertTrue(after.userEdited);
        assertEquals("New", after.title);
        assertEquals(lastRead, lastReadOf(id));
        assertEquals(2, after.genres.size());
        assertEquals("alpha", after.genres.get(0));
        assertEquals("beta", after.genres.get(1));
        assertEquals(2, workGenreRowCount(id));
    }

    @Test
    public void saveUserEditIsOneNotify() {
        Book b = book("/x/once.fb2", "FB2", "T");
        long id = db.upsertBasic(b);
        UriNotifyCounter obs = new UriNotifyCounter();
        obs.register(context);

        Book edited = db.getById(id);
        edited.title = "Edited";
        catalog.saveUserEdit(edited);

        obs.idle();
        assertEquals(1, obs.content.get());
        assertEquals(1, obs.recent.get());
        obs.unregister(context);
    }

    @Test
    public void markReadNotifiesRecent() {
        long id = db.upsertBasic(book("/x/read.pdf", "PDF", "R"));
        UriNotifyCounter obs = new UriNotifyCounter();
        obs.register(context);

        catalog.markRead(id);

        obs.assertBothFired();
        assertTrue(lastReadOf(id) > 0);
        obs.unregister(context);
    }

    @Test
    public void importExistingClearsUserEditedAndKeepsLastRead() {
        Book b = book("/x/reimport.epub", "EPUB", "Name");
        b.genres = genres("stay");
        long id = db.upsert(b);
        db.markUserEdited(id);
        db.markRead(id);
        long lastRead = lastReadOf(id);
        assertEquals(1, workGenreRowCount(id));

        UriNotifyCounter obs = new UriNotifyCounter();
        obs.register(context);

        Book skeleton = book("/x/reimport.epub", "EPUB", "Name");
        skeleton.sizeBytes = 200;
        catalog.importFile(skeleton, true, id);

        obs.assertBothFired();
        obs.unregister(context);

        Book after = db.getById(id);
        assertFalse(after.userEdited);
        assertFalse(after.metaDone);
        assertFalse(after.metaFailed);
        assertEquals(lastRead, lastReadOf(id));
        assertEquals(1, workGenreRowCount(id));
    }

    @Test
    public void clearNotifiesAndEmpties() {
        db.upsertBasic(book("/a/x.txt", "TXT", "A"));
        UriNotifyCounter obs = new UriNotifyCounter();
        obs.register(context);

        catalog.clear();

        obs.assertBothFired();
        Cursor c = db.cursorAll((String[]) null);
        try {
            assertEquals(0, c.getCount());
        } finally {
            c.close();
        }
        obs.unregister(context);
    }

    @Test
    public void applyEnrichmentBatchNotifiesBothUris() {
        long id = db.upsertBasic(book("/x/batch.epub", "EPUB", "file.epub"));
        db.markRead(id);
        long lastRead = lastReadOf(id);

        UriNotifyCounter obs = new UriNotifyCounter();
        obs.register(context);

        MetaData md = new MetaData();
        md.title = "Real Title";
        md.author = "Author";
        md.found = true;
        List<BookDatabase.MetaUpdate> batch =
                Collections.singletonList(new BookDatabase.MetaUpdate(id, md, true));
        catalog.applyEnrichmentBatch(batch);

        obs.assertBothFired();
        obs.unregister(context);

        Book after = db.getById(id);
        assertTrue(after.metaDone);
        assertEquals("Real Title", after.title);
        assertEquals(lastRead, lastReadOf(id));
    }

    @Test
    public void deleteByPathRemovesRowAndNotifies() {
        Book b = book("/x/del.txt", "TXT", "Del");
        db.upsertBasic(b);
        UriNotifyCounter obs = new UriNotifyCounter();
        obs.register(context);

        catalog.deleteByPath(b.path);

        obs.assertBothFired();
        Cursor c = db.cursorAll((String[]) null);
        try {
            assertEquals(0, c.getCount());
        } finally {
            c.close();
        }
        obs.unregister(context);
    }

    @Test
    public void persistEnrichmentBatchDoesNotNotify() {
        long id = db.upsertBasic(book("/x/silent.epub", "EPUB", "file.epub"));
        UriNotifyCounter obs = new UriNotifyCounter();
        obs.register(context);

        MetaData md = new MetaData();
        md.title = "Real Title";
        md.found = true;
        List<BookDatabase.MetaUpdate> batch =
                Collections.singletonList(new BookDatabase.MetaUpdate(id, md, true));
        catalog.persistEnrichmentBatch(batch);

        obs.idle();
        assertEquals(0, obs.content.get());
        assertEquals(0, obs.recent.get());
        obs.unregister(context);

        Book after = db.getById(id);
        assertTrue(after.metaDone);
        assertEquals("Real Title", after.title);
    }

    @Test
    public void announceChangeAllNotifiesBothUrisOnce() {
        UriNotifyCounter obs = new UriNotifyCounter();
        obs.register(context);

        catalog.announceChangeAll();

        obs.idle();
        assertEquals(1, obs.content.get());
        assertEquals(1, obs.recent.get());
        obs.unregister(context);
    }
}
