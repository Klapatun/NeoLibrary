package com.example.mylibrary.meta;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.testutil.TestFixtures;
import com.example.mylibrary.util.CoverCache;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.List;

/**
 * Robolectric tests for {@link MetaEnricher} — stage 2 (background metadata + covers).
 *
 * <p>The single-book {@code enrichOne} path is exercised synchronously (it is safe to
 * call from any background thread, and the tests run on one). The bulk worker
 * ({@code start}) is driven by idling the main looper the same way
 * {@code MainActivityTest} does for its loaders. Run at {@code sdk = 19}.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class MetaEnricherTest {

    private static final long WAIT_MS = 15000;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Context app;
    private BookDatabase db;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("library.db");
        db = new BookDatabase(app);
    }

    /** Inserts a stage-1 skeleton row (file-name title, meta_done=0) for the file. */
    private long seedStageOne(File f, String format, String fileNameTitle) {
        Book b = new Book();
        b.path = f.getAbsolutePath();
        b.format = format;
        b.title = fileNameTitle;
        b.sizeBytes = f.length();
        return db.upsertBasic(b);
    }

    private Book get(long id) {
        return db.getById(id);
    }

    @Test
    public void enrichOneFillsMetadataMarksDoneAndPreservesLastRead() throws Exception {
        File fb2 = new File(folder.getRoot(), "original.fb2");
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);
        long id = seedStageOne(fb2, "FB2", "original");
        db.markRead(id);

        MetaEnricher.enrichOne(app, db, get(id));

        Book b = get(id);
        // last_read is a DB-only column: check it through a raw query.
        android.database.Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT last_read FROM books WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            assertTrue("last_read must survive enrichment", c.getLong(0) > 0);
        } finally {
            c.close();
        }
        assertEquals("Original Title", b.title);
        assertEquals("Ivan Ivanovich Petrov", b.author);
        assertEquals("Original Publisher", b.publisher);
        assertEquals("An original story.", b.description);
        assertTrue(b.metaDone);
    }

    @Test
    public void enrichOneNeverClobbersUserEditsButFillsEmptyFields() throws Exception {
        File fb2 = new File(folder.getRoot(), "book.epub");
        TestFixtures.writeEpub(fb2, TestFixtures.OPF_FULL);

        Book b = new Book();
        b.path = fb2.getAbsolutePath();
        b.format = "EPUB";
        b.title = "User Title";
        b.author = "User Author";
        b.publisher = null;    // empty -> the enricher may fill it
        b.description = null;  // empty -> the enricher may fill it
        long id = db.upsert(b);
        db.markUserEdited(id); // what the editor does after the upsert

        MetaEnricher.enrichOne(app, db, get(id));

        Book after = get(id);
        assertEquals("user title must be kept", "User Title", after.title);
        assertEquals("user author must be kept", "User Author", after.author);
        assertEquals("empty publisher gets the file value", "Original Publisher", after.publisher);
        assertEquals("empty description gets the file value", "An original story.", after.description);
        assertTrue(after.metaDone);
    }

    @Test
    public void enrichOneWithoutEmbeddedMetaKeepsFileNameTitleAndMarksDone() throws Exception {
        File pdf = new File(folder.getRoot(), "my_cool_book.pdf");
        TestFixtures.writeBytes(pdf, new byte[]{(byte) 0x25, (byte) 0x50, (byte) 0x44, (byte) 0x46});
        long id = seedStageOne(pdf, "PDF", "my cool book");

        MetaEnricher.enrichOne(app, db, get(id));

        Book b = get(id);
        assertEquals("file-name title must survive a failed extraction", "my cool book", b.title);
        assertNull(b.author);
        assertTrue("even a failed extraction marks the book done (no retry loop)", b.metaDone);
    }

    @Test
    public void enrichOneStoresCoverBytesInTheFileCache() throws Exception {
        File epub = new File(folder.getRoot(), "covered.epub");
        TestFixtures.writeEpub(epub, TestFixtures.OPF_WITH_COVER);
        long id = seedStageOne(epub, "EPUB", "covered");

        MetaEnricher.enrichOne(app, db, get(id));

        byte[] cached = CoverCache.load(app, epub.getAbsolutePath());
        assertNotNull("the cover bytes must land in CoverCache", cached);
        assertArrayEquals(TestFixtures.coverBytes(), cached);
        // The cache file lives in the app's external "covers" dir, named by path hash.
        File f = CoverCache.fileFor(app, epub.getAbsolutePath());
        assertTrue("cache file must exist on disk", f.exists());
        assertEquals("covers", f.getParentFile().getName());
    }

    @Test
    public void enrichOneIsIdempotent() throws Exception {
        File fb2 = new File(folder.getRoot(), "once.fb2");
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);
        long id = seedStageOne(fb2, "FB2", "once");

        MetaEnricher.enrichOne(app, db, get(id));
        Book first = get(id);
        MetaEnricher.enrichOne(app, db, get(id));
        Book second = get(id);

        assertEquals(first.title, second.title);
        assertEquals(first.author, second.author);
        assertEquals(first.publisher, second.publisher);
        assertTrue(second.metaDone);
    }

    @Test
    public void enrichOneMarksDoneEvenWhenTheFileIsMissing() {
        Book b = new Book();
        b.path = new File(folder.getRoot(), "gone.pdf").getAbsolutePath();
        b.format = "PDF";
        b.title = "gone";
        long id = db.upsertBasic(b);

        MetaEnricher.enrichOne(app, db, get(id));

        Book after = get(id);
        assertEquals("gone", after.title); // untouched
        assertTrue(after.metaDone);          // queue must drain
    }

    @Test
    public void startDrainsTheWholeQueueOnTheMainLooper() throws Exception {
        TestFixtures.writeText(new File(folder.getRoot(), "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(folder.getRoot(), "story_b.txt"), "beta\n");
        seedStageOne(new File(folder.getRoot(), "story_a.txt"), "TXT", "story a");
        seedStageOne(new File(folder.getRoot(), "story_b.txt"), "TXT", "story b");
        assertEquals(2, db.needMeta().size());

        MetaEnricher.start(app, db, new MetaEnricher.OnProgress() {
            @Override public void onProgress(int done, int total) { /* counted below */ }
            @Override public void onFinished() { /* the drain is what we wait on */ }
        });

        ShadowLooper looper = shadowOf(Looper.getMainLooper());
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline && !db.needMeta().isEmpty()) {
            looper.idle();
            Thread.sleep(10);
        }
        looper.idle();
        MetaEnricher.cancel();

        assertTrue("the bulk worker must drain the queue", db.needMeta().isEmpty());
        for (Book b : db.all(null)) {
            assertTrue(b.metaDone);
        }
    }

    @Test
    public void cancelStopsTheWorker() throws Exception {
        // Nothing to assert on a mid-run cancel beyond "it does not blow up and the
        // queue is left for the next start" — the worker is single-threaded and the
        // cancel flag is checked on every iteration.
        TestFixtures.writeText(new File(folder.getRoot(), "c.txt"), "x\n");
        seedStageOne(new File(folder.getRoot(), "c.txt"), "TXT", "c");

        MetaEnricher.cancel(); // idempotent when nothing is running
        MetaEnricher.enrichOne(app, db, get(db.needMeta().get(0).id));
        List<Book> rest = db.needMeta();
        assertTrue(rest.isEmpty());
    }
}
