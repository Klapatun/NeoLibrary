package com.example.mylibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.scan.Formats;
import com.example.mylibrary.testutil.TestFixtures;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.BaseCursor;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Robolectric smoke tests for {@link MainActivity} — the three-stage "Scan → Catalog →
 * Enrich" workflow.
 *
 * <p>Stage 1 (the fast file scan) runs deterministically because
 * {@code ShadowEnvironment.setExternalStorageDirectory} points the scanner's default
 * root at a temp folder. Stage 2 (background metadata/covers) is driven by the real
 * {@code MetaEnricher} worker. The list itself is cursor-driven: stage-1 writes and
 * the enricher's {@code notifyChange} make the {@code CursorLoader} re-query, so the
 * tests wait on the adapter's count the same way a user would wait on the screen.</p>
 *
 * <p>The SAF import is driven by calling {@code onActivityResult} directly and feeding
 * the ContentResolver a fake cursor (display name) + input stream, exactly what a
 * picked document would yield. Run at {@code sdk = 19}.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class MainActivityTest {

    private static final int REQ_IMPORT = 100; // mirrors MainActivity's private constant

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Context app;
    private File storage;
    private BookDatabase db;

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("library.db");
        db = new BookDatabase(app);
        storage = folder.newFolder("external-storage");
        ShadowEnvironment.setExternalStorageDirectory(storage.toPath());
    }

    private MainActivity launchMain() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class);
        return c.setup().get();
    }

    private static final long WAIT_MS = 15000;

    /** Waits until the (background) scan has upserted at least {@code expected} books. */
    private void awaitCatalogSize(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        ShadowLooper looper = shadowOf(Looper.getMainLooper());
        while (System.currentTimeMillis() < deadline) {
            looper.idle();
            if (db.all(null).size() >= expected) break;
            Thread.sleep(10);
        }
        looper.idle();
    }

    /** Waits until the visible adapter shows exactly {@code expected} rows. The cursor
     *  updates asynchronously (notifyChange -> loader re-query -> onLoadFinished), so
     *  both the real worker threads and the main looper have to be given time. */
    private void awaitAdapterCount(ListView list, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        ShadowLooper looper = shadowOf(Looper.getMainLooper());
        while (System.currentTimeMillis() < deadline) {
            looper.idle();
            if (list.getAdapter().getCount() == expected) return;
            Thread.sleep(10);
        }
        looper.idle();
    }

    /** A lazily-evaluated condition, so the wait loop can re-check it every round. */
    private interface Cond {
        boolean holds();
    }

    /** Generic bounded wait for a condition (pumping the main looper each round). */
    private void awaitCondition(String what, Cond cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        ShadowLooper looper = shadowOf(Looper.getMainLooper());
        boolean ok = false;
        while (System.currentTimeMillis() < deadline) {
            looper.idle();
            if (cond.holds()) {
                ok = true;
                break;
            }
            Thread.sleep(10);
        }
        assertTrue("timed out waiting for: " + what, ok);
        looper.idle();
    }

    private boolean adapterHasCount(ListView list, int expected) {
        return list.getAdapter().getCount() == expected;
    }

    // ------------------------------------------------------------------
    // scan
    // ------------------------------------------------------------------

    @Test
    public void scanOnLaunchFindsBooksInExternalStorage() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");
        TestFixtures.writeBytes(new File(storage, "manual.pdf"), new byte[]{0x25, 0x25, 0x50, 0x44});
        TestFixtures.writeBytes(new File(storage, "archive.fb2.zip"), new byte[]{1, 2, 3});
        TestFixtures.writeText(new File(storage, "notes.md"), "not a book\n");

        MainActivity a = launchMain();
        awaitCatalogSize(4);

        // Catalog holds exactly the supported files; .md was skipped.
        assertEquals(4, db.all(null).size());
        Set<String> formats = new HashSet<String>();
        for (Book b : db.all(null)) formats.add(b.format);
        assertTrue(formats.contains("TXT"));
        assertTrue(formats.contains("PDF"));
        assertTrue(formats.contains("FB2ZIP"));
        assertEquals(3, formats.size()); // TXT counted once: {TXT, PDF, FB2ZIP}

        // The list is now driven by the cursor loader: wait until it caught up with
        // the stage-1 upserts (notifyChange -> re-query -> onLoadFinished).
        ListView list = a.findViewById(R.id.book_list);
        awaitAdapterCount(list, 4);
        assertEquals(4, list.getAdapter().getCount());
        assertEquals(View.GONE, a.findViewById(R.id.progress).getVisibility());
        assertTrue(ShadowToast.showedToast("Found 4 book(s)"));
    }

    @Test
    public void backgroundEnricherCompletesForAllBooks() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);

        // Stage 2 must drain its queue: every row becomes meta_done, and the
        // progress strip goes away with it.
        final MainActivity act = a;
        final BookDatabase dbLocal = db;
        awaitCondition("stage-2 to finish", new Cond() {
            public boolean holds() {
                return dbLocal.needMeta().isEmpty()
                        && act.findViewById(R.id.enrich_bar).getVisibility() == View.GONE;
            }
        });

        for (Book b : db.all(null)) {
            assertTrue("book must be enriched: " + b.path, b.metaDone);
        }
    }

    /** A configuration change (rotation) must NOT re-run the full storage scan —
     *  the catalog is already there — but it MUST resume the enrichment worker over
     *  the remaining queue (the previous worker was cancelled in onDestroy). */
    @Test
    public void recreationSkipsTheRescanButResumesEnrichment() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class);
        MainActivity a = c.setup().get();
        awaitCatalogSize(2);
        final BookDatabase dbLocal = db;
        awaitCondition("stage-2 to finish", new Cond() {
            public boolean holds() {
                return dbLocal.needMeta().isEmpty();
            }
        });

        // Simulate the worker having been cut off mid-work: make one row need
        // enrichment again.
        Book first = db.all(null).get(0);
        db.getWritableDatabase().execSQL("UPDATE books SET meta_done = 0 WHERE _id = " + first.id);
        assertFalse("precondition: one book is pending again", db.needMeta().isEmpty());

        // Simulate a rotation: save the instance state, destroy the activity, and
        // build a new one with the saved state.
        Bundle saved = new Bundle();
        c.saveInstanceState(saved);
        c.destroy();
        int toastsAfterColdStart = ShadowToast.shownToastCount();

        MainActivity a2 = Robolectric.buildActivity(MainActivity.class).setup(saved).get();

        // No rescan: the scan's progress bar is shown only while a scan is running.
        assertEquals("the full rescan must not restart on recreation",
                View.GONE, a2.findViewById(R.id.progress).getVisibility());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a second scan would toast 'Found N book(s)' again",
                toastsAfterColdStart, ShadowToast.shownToastCount());
        assertEquals(2, db.all(null).size());

        // The enrichment worker must have resumed over the remaining queue.
        awaitCondition("stage-2 to resume after recreation", new Cond() {
            public boolean holds() {
                return dbLocal.needMeta().isEmpty();
            }
        });
    }

    // ------------------------------------------------------------------
    // list / grid toggle
    // ------------------------------------------------------------------

    @Test
    public void toggleSwitchesBetweenListAndGridAndMovesTheAdapter() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);

        ListView list = a.findViewById(R.id.book_list);
        GridView grid = a.findViewById(R.id.book_grid);

        // Initial state: list mode, with the two books on the cursor.
        awaitAdapterCount(list, 2);
        assertEquals(View.VISIBLE, list.getVisibility());
        assertEquals(View.GONE, grid.getVisibility());
        assertNotNull("adapter attached to list", list.getAdapter());
        assertNull("adapter not yet attached to grid", grid.getAdapter());

        // Switch to grid: the adapter must move (a CursorAdapter cannot serve two views).
        ImageButton toggle = a.findViewById(R.id.toggle_view);
        toggle.performClick();
        assertEquals(View.GONE, list.getVisibility());
        assertEquals(View.VISIBLE, grid.getVisibility());
        assertNull("adapter must be detached from the list", list.getAdapter());
        assertNotNull("adapter must be attached to the grid", grid.getAdapter());
        assertEquals(2, grid.getAdapter().getCount());

        // Switch back to list.
        toggle.performClick();
        assertEquals(View.VISIBLE, list.getVisibility());
        assertEquals(View.GONE, grid.getVisibility());
        assertNotNull("adapter must be attached to the list again", list.getAdapter());
        assertNull("adapter must be detached from the grid", grid.getAdapter());
    }

    // ------------------------------------------------------------------
    // filter spinner
    // ------------------------------------------------------------------

    @Test
    public void filterSpinnerRestrictsTheVisibleBooks() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");
        TestFixtures.writeBytes(new File(storage, "manual.pdf"), new byte[]{0x25, 0x25});

        MainActivity a = launchMain();
        awaitCatalogSize(3);

        ListView list = a.findViewById(R.id.book_list);
        awaitAdapterCount(list, 3);
        Spinner spinner = a.findViewById(R.id.filter_spinner);
        final BookAdapter adapter = (BookAdapter) list.getAdapter();

        final int txtPos = 2 + Arrays.asList(Formats.ALL).indexOf("TXT");
        spinner.setSelection(txtPos);

        // The re-query for the filtered cursor is asynchronous; wait it out.
        final ListView listRef = list;
        awaitCondition("TXT filter to apply", new Cond() {
            public boolean holds() {
                return adapterHasCount(listRef, 2);
            }
        });
        for (int i = 0; i < adapter.getCount(); i++) {
            assertEquals("TXT", adapter.getItem(i).format);
        }

        // "Recently read" with nothing read yet: empty list + hint text.
        spinner.setSelection(1);
        final MainActivity act = a;
        awaitCondition("recent filter to apply", new Cond() {
            public boolean holds() {
                return adapterHasCount(listRef, 0)
                        && "No books read yet.".equals(
                        ((TextView) act.findViewById(R.id.empty_view)).getText().toString());
            }
        });
        assertEquals(0, adapter.getCount());
        TextView empty = a.findViewById(R.id.empty_view);
        assertEquals("No books read yet.", empty.getText().toString());
    }

    // ------------------------------------------------------------------
    // row click -> details
    // ------------------------------------------------------------------

    @Test
    public void clickingABookOpensDetailsWithItsId() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);

        Book target = null;
        for (Book b : db.all(null)) {
            if (b.path.endsWith("story_a.txt")) target = b;
        }
        assertNotNull(target);

        ListView list = a.findViewById(R.id.book_list);
        awaitAdapterCount(list, 2);
        BookAdapter adapter = (BookAdapter) list.getAdapter();
        int pos = -1;
        for (int i = 0; i < adapter.getCount(); i++) {
            Book item = adapter.getItem(i);
            if (item != null && item.id == target.id) pos = i;
        }
        assertTrue("target must be in the adapter", pos >= 0);

        list.performItemClick(new View(a), pos, adapter.getItemId(pos));

        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(DetailActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(target.id, started.getLongExtra(DetailActivity.EXTRA_BOOK_ID, -1));
    }

    // ------------------------------------------------------------------
    // import (SAF)
    // ------------------------------------------------------------------

    /**
     * A single-row fake cursor reporting the picked document's display name — stands
     * in for the DocumentsProvider query result of {@code _display_name}.
     */
    private static final class DisplayNameCursor extends BaseCursor {
        private final String name;

        DisplayNameCursor(String name) { this.name = name; }

        @Override public int getCount() { return 1; }
        @Override public int getPosition() { return 0; }
        @Override public boolean moveToPosition(int position) { return position == 0; }
        @Override public boolean moveToFirst() { return true; }
        @Override public boolean moveToNext() { return false; }
        @Override public boolean moveToPrevious() { return false; }
        @Override public boolean isBeforeFirst() { return false; }
        @Override public boolean isAfterLast() { return false; }
        @Override public boolean isFirst() { return true; }
        @Override public boolean isLast() { return true; }
        @Override public int getColumnIndex(String column) {
            return OpenableColumns.DISPLAY_NAME.equals(column) ? 0 : -1;
        }
        @Override public String getString(int column) { return name; }
        @Override public void close() {}
    }

    @Test
    public void importFromDocumentPickerCopiesFileIntoLibraryAndCatalog() throws Exception {
        // The "picked" file and the fake provider results for its content URI.
        File picked = new File(folder.getRoot(), "picked.txt");
        TestFixtures.writeText(picked, "imported text\n");
        Uri uri = Uri.parse("content://com.neo.librarytest.picked/1");
        ShadowContentResolver resolver = shadowOf(app.getContentResolver());
        resolver.setCursor(uri, new DisplayNameCursor("imported_book.txt"));
        FileInputStream in = new FileInputStream(picked);
        resolver.registerInputStream(uri, in);

        MainActivity a = launchMain();
        awaitCatalogSize(0); // empty storage: the launch scan finds nothing

        a.onActivityResult(REQ_IMPORT, Activity.RESULT_OK, new Intent().setData(uri));

        // The import copies + upserts + enriches on a background thread; wait for the
        // catalog row, then let the import's onPostExecute (toast + notifyChange) run
        // as well.
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline && db.all(null).isEmpty()) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        long toastDeadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < toastDeadline
                && !ShadowToast.showedToast("Imported imported_book.txt")) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }

        // Copied into our own external files dir...
        File dest = new File(app.getExternalFilesDir("books"), "imported_book.txt");
        assertTrue("imported file must exist: " + dest, dest.exists());
        assertEquals("imported text\n",
                new String(TestFixtures.readAll(dest), "UTF-8"));

        // ...and the catalog holds the scanned copy, enriched by stage 2.
        assertEquals(1, db.all(null).size());
        Book imported = db.all(null).get(0);
        assertEquals(dest.getAbsolutePath(), imported.path);
        assertEquals("TXT", imported.format);
        assertEquals("imported book", imported.title);
        assertTrue("the imported book must be enriched", imported.metaDone);
        assertTrue(ShadowToast.showedToast("Imported imported_book.txt"));

        // And the list picked it up through the loader.
        ListView list = a.findViewById(R.id.book_list);
        awaitAdapterCount(list, 1);
    }

    @Test
    public void importRejectsWhenThePickerYieldsNoUsableFile() throws Exception {
        // No fake results registered -> the display name is unresolvable -> the import
        // must be rejected and nothing may land on disk.
        MainActivity a = launchMain();

        a.onActivityResult(REQ_IMPORT, Activity.RESULT_OK,
                new Intent().setData(Uri.parse("content://no.such.provider/1")));
        shadowOf(Looper.getMainLooper()).idle();
        Thread.sleep(50); // let any (erroneous) async work have a chance to run

        assertTrue(ShadowToast.showedToast("That file type is not supported"));
        assertTrue("catalog must stay empty", db.all(null).isEmpty());
        File booksDir = app.getExternalFilesDir("books");
        String[] files = booksDir == null ? null : booksDir.list();
        assertTrue("no imported file may appear", files == null || files.length == 0);
    }
}
