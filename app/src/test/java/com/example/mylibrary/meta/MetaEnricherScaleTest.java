package com.example.mylibrary.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.testutil.TestFixtures;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scale test for stage 2: a real 400-book library — 100 books of each metadata-bearing
 * format (EPUB, FB2, FB2ZIP, MOBI) — where every book carries ITS OWN
 * title/author/publisher/description inside the file. The real worker
 * ({@link MetaEnricher#start}) is run over the whole queue with the production parse
 * task: the single-pass parsers, the real per-file time budget and the real batch
 * commits ({@code upsertBasicBatch} for stage 1, {@code updateMetadataBatch} for
 * stage 2). After the pass, every row must hold exactly its own file's values — a
 * field crossed between rows, or lost, fails the test.
 *
 * <p>The wait follows the {@code MetaEnricherTest} pattern (idle the main looper until
 * {@code onFinished}); the deadline is wider than the per-book tests because the pass
 * spawns 400 throwaway parse threads and the project's documented background-thread
 * starvation (loaded machine) reaches ~30 s. Run at {@code sdk = 19}.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class MetaEnricherScaleTest {

    private static final int BOOKS = 400;

    /** Generous drain deadline: the pass spawns 400 REAL parse threads behind one
     *  worker, and on a loaded machine any of them can starve for a long time (the
     *  project's documented background-thread starvation reaches ~30 s, and a
     *  400-thread pass multiplies the chances of hitting a bad window). A healthy
     *  machine drains the pass in ~15-30 s; the 5-minute cap is the margin. */
    private static final long WAIT_MS = 300000;

    private static final String[] FORMAT_NAMES = {"EPUB", "FB2", "FB2ZIP", "MOBI"};

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

    /** Writes one book of the scale library: a real file of the {@code fmt}-th format
     *  (EPUB/FB2/FB2ZIP/MOBI cycle) with the book's own metadata embedded. The
     *  fixtures deliberately carry NO cover (no OPF cover declaration, no FB2
     *  {@code <binary>}, no MOBI 201 record), so the pass exercises metadata reading
     *  only. */
    private File writeBook(int i, int fmt) throws Exception {
        String title = "Book " + i;
        String author = "Author " + i;
        String publisher = "Press " + i;
        String description = "Desc " + i;
        String fb2 = TestFixtures.buildFb2(title, "Author", Integer.toString(i),
                publisher, description, "en");
        switch (fmt) {
            case 0: {
                File f = new File(folder.getRoot(), "book_" + i + ".epub");
                TestFixtures.writeEpub(f, TestFixtures.buildOpf(
                        title, author, publisher, description, "en"));
                return f;
            }
            case 1: {
                File f = new File(folder.getRoot(), "book_" + i + ".fb2");
                TestFixtures.writeText(f, fb2);
                return f;
            }
            case 2: {
                File f = new File(folder.getRoot(), "book_" + i + ".fb2.zip");
                Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
                entries.put("inner.fb2", fb2.getBytes("UTF-8"));
                TestFixtures.writeZip(f, entries);
                return f;
            }
            default: {
                File f = new File(folder.getRoot(), "book_" + i + ".mobi");
                // A minimal SOI..EOI "jpeg" in the image record, coverOffset = -1
                // (the 201 EXTH record is not written: a book without a cover).
                byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
                TestFixtures.writeMobi(f, title, author, publisher, description, "en",
                        jpeg, 3, -1);
                return f;
            }
        }
    }

    @Test
    public void stageTwoReadsTheMetadataOfAllFourHundredBooks() throws Exception {
        // 1) The library on disk: 400 real files, 100 per format, each carrying its
        //    own metadata (the index in the values is what catches a row crossed
        //    with another row's data).
        File[] files = new File[BOOKS];
        String[] formats = new String[BOOKS];
        for (int i = 0; i < BOOKS; i++) {
            int fmt = i % 4;
            files[i] = writeBook(i, fmt);
            formats[i] = FORMAT_NAMES[fmt];
        }

        // 2) Stage 1, the way the scan does it: 400 skeleton rows (file-name title,
        //    meta_done = 0) in one batch commit.
        List<Book> skeletons = new ArrayList<Book>(BOOKS);
        for (int i = 0; i < BOOKS; i++) {
            Book b = new Book();
            b.path = files[i].getAbsolutePath();
            b.format = formats[i];
            b.title = "book " + i; // the fast scan's file-name title
            b.sizeBytes = files[i].length();
            skeletons.add(b);
        }
        db.upsertBasicBatch(skeletons);
        assertEquals(BOOKS, db.needMeta().size());

        // 3) Stage 2: the real worker — production parse task (single-pass parsers),
        //    the real per-file time budget, batch commits, cheapest-first order. The
        //    listener is kept in a STRONG local for the whole test: the enricher
        //    holds it only weakly (the production owner is the screen's field), and a
        //    400-book pass spans several GC cycles — an unheld listener would be
        //    collected mid-pass and the worker would finish without the callback.
        final AtomicBoolean workerDone = new AtomicBoolean(false);
        final MetaEnricher.OnProgress listener = new MetaEnricher.OnProgress() {
            @Override public void onProgress(int done, int total) { /* the drain is what we wait on */ }
            @Override public void onFinished() { workerDone.set(true); }
        };
        MetaEnricher.start(app, db, listener);

        ShadowLooper looper = shadowOf(Looper.getMainLooper());
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline && !workerDone.get()) {
            looper.idle();
            Thread.sleep(10);
        }
        looper.idle();
        MetaEnricher.cancel();
        if (!workerDone.get()) {
            // Diagnostics for a lost race (the project's documented background
            // starvation, or a genuinely stuck worker): the remaining queue and its
            // format mix show where the pass stopped.
            List<Book> left = db.needMeta();
            int epub = 0, fb2 = 0, fb2zip = 0, mobi = 0;
            for (Book b : left) {
                if ("EPUB".equals(b.format)) epub++;
                else if ("FB2".equals(b.format)) fb2++;
                else if ("FB2ZIP".equals(b.format)) fb2zip++;
                else if ("MOBI".equals(b.format)) mobi++;
            }
            fail("the worker did not finish the 400-book pass within " + WAIT_MS
                    + " ms: " + left.size() + " book(s) still pending (EPUB " + epub
                    + ", FB2 " + fb2 + ", FB2ZIP " + fb2zip + ", MOBI " + mobi + ")");
        }

        // 4) Every row holds exactly its own file's metadata.
        assertTrue("the stage-2 queue must be drained", db.needMeta().isEmpty());
        List<Book> all = db.all(null);
        assertEquals(BOOKS, all.size());
        Map<String, Book> byPath = new HashMap<String, Book>();
        for (Book b : all) byPath.put(b.path, b);
        for (int i = 0; i < BOOKS; i++) {
            Book b = byPath.get(files[i].getAbsolutePath());
            assertNotNull("the row of book " + i + " (" + formats[i] + ") must exist", b);
            assertEquals("the title of book " + i, "Book " + i, b.title);
            assertEquals("the author of book " + i, "Author " + i, b.author);
            assertEquals("the publisher of book " + i, "Press " + i, b.publisher);
            assertEquals("the description of book " + i, "Desc " + i, b.description);
            assertTrue("book " + i + " must be marked enriched", b.metaDone);
            assertFalse("book " + i + " must not be marked un-enriched", b.metaFailed);
        }
    }
}
