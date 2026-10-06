package com.example.mylibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.BookProvider;
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
import org.robolectric.shadows.ShadowDialog;
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

    // The project's own flake notes document background-thread starvation of up
    // to ~30s on a loaded machine (Android Studio + Chrome + Steam): all the
    // app's background work runs on the shared AsyncTask pool, whose threads
    // simply do not get scheduled. The wait windows cover that documented worst
    // case; under heavier, ad-hoc load a healthy test can still lose (the
    // victims rotate) — re-run the failing test in isolation before acting.
    private static final long WAIT_MS = 30000;

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
        assertTrue("timed out waiting for the catalog to hold " + expected
                + " book(s), got " + db.all(null).size(), db.all(null).size() >= expected);
    }

    /** Waits until the visible adapter shows exactly {@code expected} rows. The cursor
     *  updates asynchronously (notifyChange -> loader re-query -> onLoadFinished), so
     *  both the real worker threads and the main looper have to be given time. */
    private void awaitAdapterCount(AbsListView view, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        ShadowLooper looper = shadowOf(Looper.getMainLooper());
        while (System.currentTimeMillis() < deadline) {
            looper.idle();
            if (view.getAdapter().getCount() == expected) return;
            Thread.sleep(10);
        }
        looper.idle();
        assertTrue("timed out waiting for the adapter to show " + expected
                + " row(s), got " + view.getAdapter().getCount(),
                view.getAdapter().getCount() == expected);
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

    private boolean adapterHasCount(AbsListView view, int expected) {
        return view.getAdapter().getCount() == expected;
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

        // The view is now driven by the cursor loader: wait until it caught up with
        // the stage-1 upserts (notifyChange -> re-query -> onLoadFinished). The
        // default (and initially visible) view is the tile grid.
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 4);
        assertEquals(4, grid.getAdapter().getCount());
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

    /** The reported bug scenario: the first load finds no books (storage was empty),
     *  the user then drops books onto storage while the app stays open, and picks
     *  "Rescan" from the menu. The new books must be DRAWN in the list, not just
     *  written to the catalog — without a manual app restart, and without relying on
     *  the CursorLoader's ContentObserver being alive (on API 19 it can be lost after
     *  loader cancel/restart cycles, which is why the rescan's own write must refresh
     *  the list directly). */
    @Test
    public void rescanAfterEmptyFirstScanDrawsTheNewBooks() throws Exception {
        MainActivity a = launchMain();
        final BookDatabase dbLocal = db;

        // First load: storage is empty -> the catalog and the list stay empty.
        awaitCondition("the first scan to finish on empty storage", new Cond() {
            public boolean holds() {
                return a.findViewById(R.id.progress).getVisibility() == View.GONE
                        && dbLocal.all(null).isEmpty();
            }
        });
        GridView grid = a.findViewById(R.id.book_grid);
        assertEquals("no books after the first (empty) scan", 0, grid.getAdapter().getCount());

        // The user drops two books onto storage while the app is still open...
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        // ...and picks "Rescan" from the header kebab. The kebab's menu pick calls
        // startScan() 1:1, so drive that same entry point (reflection: it is
        // private; the real kebab flow is covered separately in
        // headerKebabRescanPickRunsTheScanAndDrawsTheNewBooks).
        java.lang.reflect.Method rescan = MainActivity.class.getDeclaredMethod("startScan");
        rescan.setAccessible(true);
        rescan.invoke(a);

        // The new books land in the catalog...
        awaitCatalogSize(2);
        // ...and must be drawn in the visible view (the original bug: catalog
        // updated, the view stayed empty until the app was restarted).
        awaitAdapterCount(grid, 2);
        assertEquals(2, grid.getAdapter().getCount());
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

        // Initial state: tile (grid) mode — the default view — with the two books
        // on the cursor.
        awaitAdapterCount(grid, 2);
        assertEquals(View.GONE, list.getVisibility());
        assertEquals(View.VISIBLE, grid.getVisibility());
        assertNull("adapter not yet attached to the list", list.getAdapter());
        assertNotNull("adapter attached to grid", grid.getAdapter());

        // Switch to list: the adapter must move (a CursorAdapter cannot serve two views).
        ImageButton toggle = a.findViewById(R.id.toggle_view);
        toggle.performClick();
        assertEquals(View.VISIBLE, list.getVisibility());
        assertEquals(View.GONE, grid.getVisibility());
        assertNotNull("adapter must be attached to the list", list.getAdapter());
        assertNull("adapter must be detached from the grid", grid.getAdapter());
        assertEquals(2, list.getAdapter().getCount());

        // Switch back to the tiles.
        toggle.performClick();
        assertEquals(View.GONE, list.getVisibility());
        assertEquals(View.VISIBLE, grid.getVisibility());
        assertNotNull("adapter must be attached to the grid again", grid.getAdapter());
        assertNull("adapter must be detached from the list", list.getAdapter());
    }

    /** The user's view-mode choice (list/tiles) is stored in the phone's memory
     *  (SharedPreferences) and must be applied on the next launch — even a cold
     *  start with no saved instance state, i.e. the app was simply closed. */
    @Test
    public void viewModeChoiceIsPersistedAcrossAppRestarts() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class);
        MainActivity a = c.setup().get();

        ListView list = a.findViewById(R.id.book_list);
        GridView grid = a.findViewById(R.id.book_grid);

        // Initial state: tiles — the default view. Wait for the scan to load the
        // catalog first: with an EMPTY adapter the framework shows the empty view
        // instead of the list view (AbsListView.checkForDisabledView hides the view
        // itself), so the raw visibility is only meaningful once data is on screen.
        awaitAdapterCount(grid, 2);
        assertEquals(View.VISIBLE, grid.getVisibility());
        assertEquals(View.GONE, list.getVisibility());
        assertNotNull("adapter attached to the grid", grid.getAdapter());
        assertNull("adapter not attached to the list", list.getAdapter());

        // The user switches to the list...
        a.findViewById(R.id.toggle_view).performClick();
        assertEquals("the toggle switches to the list", View.VISIBLE, list.getVisibility());
        assertEquals(View.GONE, grid.getVisibility());
        assertNotNull("adapter attached to the list", list.getAdapter());
        assertNull("adapter not attached to the grid", grid.getAdapter());

        // ...and the choice is saved to the phone's memory (MainActivity's private
        // prefs file "library_prefs", key "view_mode" — mirrors the private
        // constants in MainActivity).
        SharedPreferences prefs = a.getSharedPreferences("library_prefs", Context.MODE_PRIVATE);
        assertEquals(BookAdapter.MODE_LIST, prefs.getInt("view_mode", -1));

        // "Close the app" and relaunch: the persisted choice, not the default,
        // must be the initial view mode.
        c.destroy();
        MainActivity a2 = Robolectric.buildActivity(MainActivity.class).setup().get();
        ListView list2 = a2.findViewById(R.id.book_list);
        GridView grid2 = a2.findViewById(R.id.book_grid);
        awaitAdapterCount(list2, 2);
        assertEquals("the list choice must survive the restart",
                View.VISIBLE, list2.getVisibility());
        assertEquals("the grid must stay hidden", View.GONE, grid2.getVisibility());
        assertNotNull("adapter attached to the list", list2.getAdapter());
        assertNull("adapter not attached to the grid", grid2.getAdapter());
    }

    // ------------------------------------------------------------------
    // header kebab (import / rescan)
    // ------------------------------------------------------------------

    /** The header kebab (the rightmost header button) opens the overflow menu
     *  (import + rescan), and picking "Rescan" runs the stage-1 scan — the same
     *  entry point the old header rescan button called. */
    @Test
    public void headerKebabRescanPickRunsTheScanAndDrawsTheNewBooks() throws Exception {
        MainActivity a = launchMain();
        final BookDatabase dbLocal = db;

        // First load: storage is empty -> the catalog and the view stay empty.
        awaitCondition("the first scan to finish on empty storage", new Cond() {
            public boolean holds() {
                return a.findViewById(R.id.progress).getVisibility() == View.GONE
                        && dbLocal.all(null).isEmpty();
            }
        });

        // The user drops two books onto storage while the app is still open...
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        // ...and picks "Rescan" from the header kebab. The kebab click opens the
        // popup; performIdentifierAction picks the item exactly like a tap on the
        // popup window would.
        a.findViewById(R.id.btn_menu).performClick();
        android.widget.PopupMenu menu = a.getLastHeaderMenu();
        assertNotNull("the kebab click must open the menu", menu);
        assertEquals("the menu must carry import, rescan and clear",
                3, menu.getMenu().size());
        menu.getMenu().performIdentifierAction(R.id.main_menu_rescan, 0);

        // The new books land in the catalog...
        awaitCatalogSize(2);
        // ...and must be drawn in the visible view (the grid, the default).
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 2);
        assertEquals(2, grid.getAdapter().getCount());
    }

    /** Picking "Import" from the header kebab launches the document picker. */
    @Test
    public void headerKebabImportPickLaunchesTheDocumentPicker() throws Exception {
        MainActivity a = launchMain();

        a.findViewById(R.id.btn_menu).performClick();
        android.widget.PopupMenu menu = a.getLastHeaderMenu();
        assertNotNull("the kebab click must open the menu", menu);
        menu.getMenu().performIdentifierAction(R.id.main_menu_import, 0);
        shadowOf(Looper.getMainLooper()).idle();

        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull("the Import pick must start an activity", started);
        // launchImport wraps the ACTION_OPEN_DOCUMENT intent in a system chooser.
        assertEquals(Intent.ACTION_CHOOSER, started.getAction());
    }

    /** The kebab's "Clear library" asks for confirmation; on confirm the whole
     *  catalog is wiped (rows + covers) while the on-disk files stay untouched,
     *  and the visible view settles on the empty state. */
    @Test
    public void headerKebabClearAsksForConfirmationAndWipesTheCatalogKeepingTheFiles()
            throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 2);
        final BookAdapter adapter = (BookAdapter) grid.getAdapter();

        FiringObserver onBooks = new FiringObserver();
        FiringObserver onRecent = new FiringObserver();
        app.getContentResolver().registerContentObserver(BookProvider.CONTENT_URI, true, onBooks);
        app.getContentResolver().registerContentObserver(BookProvider.RECENT_URI, true, onRecent);

        a.findViewById(R.id.btn_menu).performClick();
        android.widget.PopupMenu menu = a.getLastHeaderMenu();
        assertNotNull("the kebab click must open the menu", menu);
        menu.getMenu().performIdentifierAction(R.id.main_menu_clear, 0);

        // The pick must ask for confirmation first.
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("the Clear pick must ask for confirmation", d);
        assertEquals(a.getString(R.string.clear_confirm_title), shadowOf(d).getTitle());
        TextView msgView = d.getWindow().getDecorView().findViewById(android.R.id.message);
        assertNotNull("the dialog must carry a message view", msgView);
        String msg = msgView.getText().toString();
        assertTrue("the message must be the clear confirmation: " + msg,
                msg.contains(a.getString(R.string.clear_confirm_message)));
        assertTrue("the message must note the files stay: " + msg,
                msg.contains(a.getString(R.string.clear_file_note)));

        // Confirm ("Clear" — the positive button, the standard alert button-1 view).
        shadowOf(d).clickOn(android.R.id.button1);
        shadowOf(Looper.getMainLooper()).idle();

        // The catalog is empty...
        assertTrue("the catalog must be empty", db.all(null).isEmpty());
        // ...and the visible view has settled on the empty state.
        final BookAdapter adapterRef = adapter;
        awaitCondition("the view to settle on the empty state after the clear",
                new Cond() {
                    public boolean holds() {
                        return adapterRef.getCount() == 0;
                    }
                });
        assertTrue(ShadowToast.showedToast(a.getString(R.string.library_cleared)));

        // The files themselves must not be touched.
        assertTrue("the files must stay on disk",
                new File(storage, "story_a.txt").exists()
                        && new File(storage, "story_b.txt").exists());

        // Both catalog cursors must have been notified.
        assertTrue("the all-books cursor must be notified", onBooks.fired);
        assertTrue("the recently-read cursor must be notified", onRecent.fired);
        app.getContentResolver().unregisterContentObserver(onBooks);
        app.getContentResolver().unregisterContentObserver(onRecent);
    }

    /** Cancelling the "Clear library" confirmation must leave the catalog intact. */
    @Test
    public void cancellingTheClearConfirmationKeepsTheCatalog() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);

        a.findViewById(R.id.btn_menu).performClick();
        android.widget.PopupMenu menu = a.getLastHeaderMenu();
        assertNotNull("the kebab click must open the menu", menu);
        menu.getMenu().performIdentifierAction(R.id.main_menu_clear, 0);

        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("the Clear pick must ask for confirmation", d);
        // "Cancel" — the negative button (the standard alert button-2 view).
        shadowOf(d).clickOn(android.R.id.button2);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("the catalog must be untouched", 2, db.all(null).size());
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

        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 3);
        Spinner spinner = a.findViewById(R.id.filter_spinner);
        final BookAdapter adapter = (BookAdapter) grid.getAdapter();

        final int txtPos = 2 + Arrays.asList(Formats.ALL).indexOf("TXT");
        spinner.setSelection(txtPos);

        // The re-query for the filtered cursor is asynchronous; wait it out.
        final AbsListView gridRef = grid;
        awaitCondition("TXT filter to apply", new Cond() {
            public boolean holds() {
                return adapterHasCount(gridRef, 2);
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
                return adapterHasCount(gridRef, 0)
                        && "No books read yet.".equals(
                        ((TextView) act.findViewById(R.id.empty_view)).getText().toString());
            }
        });
        assertEquals(0, adapter.getCount());
        TextView empty = a.findViewById(R.id.empty_view);
        assertEquals("No books read yet.", empty.getText().toString());
    }

    // ------------------------------------------------------------------
    // tap -> open confirmation (Open / Cancel)
    // ------------------------------------------------------------------

    /** Finds a book in the catalog by the tail of its path, or null. */
    private Book findBook(String pathSuffix) {
        for (Book b : db.all(null)) {
            if (b.path.endsWith(pathSuffix)) return b;
        }
        return null;
    }

    /** The adapter position of {@code target}, or -1. */
    private int positionOf(BookAdapter adapter, Book target) {
        for (int i = 0; i < adapter.getCount(); i++) {
            Book item = adapter.getItem(i);
            if (item != null && item.id == target.id) {
                return i;
            }
        }
        return -1;
    }

    /** The laid-out row view of adapter position {@code pos}: AbsListView
     *  (ListView and GridView alike) materializes its rows on a layout pass, so
     *  pump the main looper until the row exists. */
    private View rowAt(AbsListView view, int pos) throws InterruptedException {
        View row = null;
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline && row == null) {
            shadowOf(Looper.getMainLooper()).idle();
            int index = pos - view.getFirstVisiblePosition();
            if (index >= 0 && index < view.getChildCount()) {
                row = view.getChildAt(index);
            } else {
                Thread.sleep(10);
            }
        }
        assertNotNull("the row view must be laid out", row);
        return row;
    }

    /**
     * Taps the row/tile of {@code target} in the visible view. The tap goes through
     * the row view's own OnClickListener (BookAdapter binds it, because a row with a
     * clickable kebab never fires ListView.onItemClick) — the same listener a real
     * finger triggers.
     */
    private void tapBook(AbsListView view, Book target) throws InterruptedException {
        BookAdapter adapter = (BookAdapter) view.getAdapter();
        int pos = positionOf(adapter, target);
        assertTrue("the book must be in the visible view", pos >= 0);
        rowAt(view, pos).performClick();
    }

    /** Opens the kebab menu for the book at adapter position {@code pos} (row/tile
     *  view built by the adapter, kebab click) and returns the popup — so the test
     *  can pick an item exactly like a tap on the popup window would. */
    private android.widget.PopupMenu openKebabFor(AbsListView view, int pos)
            throws InterruptedException {
        BookAdapter adapter = (BookAdapter) view.getAdapter();
        rowAt(view, pos).findViewById(R.id.book_more).performClick();
        android.widget.PopupMenu menu = adapter.getLastPopupMenu();
        assertNotNull("the kebab click must open the menu", menu);
        return menu;
    }

    /** Records whether the observer fired (for one registered URI). */
    private static final class FiringObserver extends ContentObserver {
        private boolean fired;
        FiringObserver() {
            super(new Handler(Looper.getMainLooper()));
        }
        @Override public void onChange(boolean selfChange, Uri uri) {
            fired = true;
        }
    }

    @Test
    public void tappingABookShowsTheOpenConfirmationAndStartsNothing() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 2);

        Book target = findBook("story_a.txt");
        assertNotNull(target);
        tapBook(grid, target);

        // The tap must not open anything — it only asks first.
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("the tap must show the confirmation dialog", d);
        // The framework Dialog/AlertDialog (API 19) have no public title/message
        // getters — read the window title via the shadow and the message via the
        // standard alert message view (android.R.id.message).
        assertEquals(a.getString(R.string.open_confirm_title),
                shadowOf(d).getTitle());
        TextView msgView = d.getWindow().getDecorView().findViewById(android.R.id.message);
        assertNotNull("the dialog must carry a message view", msgView);
        String msg = msgView.getText().toString();
        assertTrue("the dialog must name the book: " + msg, msg.contains(target.title));
        assertNull("no intent may be started by the tap",
                shadowOf(a).getNextStartedActivity());
    }

    @Test
    public void confirmingTheOpenConfirmationLaunchesTheReaderAndMarksTheBookRead()
            throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 2);

        Book target = findBook("story_a.txt");
        assertNotNull(target);
        tapBook(grid, target);
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("the tap must show the confirmation dialog", d);

        // "Open" — the positive button (the standard alert button-1 view).
        shadowOf(d).clickOn(android.R.id.button1);
        // The paused main looper must run: the activity launch is recorded when its
        // handler message is processed (Robolectric PAUSED-looper mode).
        shadowOf(Looper.getMainLooper()).idle();

        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull("the Open button must start the viewer intent", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("text/plain", started.getType());
        assertEquals(Uri.fromFile(new File(target.path)), started.getData());

        // The markRead side effect must have hit the catalog.
        android.database.Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT last_read FROM books WHERE _id=?",
                new String[]{String.valueOf(target.id)});
        assertTrue("row must exist", c.moveToFirst());
        assertTrue("last_read must be set", c.getLong(0) > 0);
    }

    @Test
    public void cancellingTheOpenConfirmationStartsNothingAndDoesNotMarkRead()
            throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 2);

        Book target = findBook("story_a.txt");
        assertNotNull(target);
        tapBook(grid, target);
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("the tap must show the confirmation dialog", d);

        // "Cancel" — the negative button (the standard alert button-2 view).
        shadowOf(d).clickOn(android.R.id.button2);
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse("the dialog must be dismissed", d.isShowing());
        assertNull("no intent may be started", shadowOf(a).getNextStartedActivity());

        android.database.Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT last_read FROM books WHERE _id=?",
                new String[]{String.valueOf(target.id)});
        assertTrue("row must exist", c.moveToFirst());
        assertEquals("last_read must stay 0", 0L, c.getLong(0));
    }

    // ------------------------------------------------------------------
    // kebab end-to-end (through the real screen: row -> kebab -> popup pick)
    // ------------------------------------------------------------------

    @Test
    public void kebabDetailsOpensTheDetailScreenForThatBook() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 2);
        BookAdapter adapter = (BookAdapter) grid.getAdapter();

        Book target = findBook("story_a.txt");
        assertNotNull(target);
        int pos = positionOf(adapter, target);
        assertTrue("the book must be in the visible list", pos >= 0);

        android.widget.PopupMenu menu = openKebabFor(grid, pos);
        // The framework MenuItem has no public click method — performIdentifierAction
        // goes through the same path a real tap on the popup item would.
        menu.getMenu().performIdentifierAction(R.id.book_menu_details, 0);
        // The paused main looper must run before the launch record is readable.
        shadowOf(Looper.getMainLooper()).idle();

        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull("the Details pick must start the detail screen", started);
        assertEquals(DetailActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(target.id, started.getLongExtra(DetailActivity.EXTRA_BOOK_ID, -1));
    }

    @Test
    public void kebabEditMetadataOpensTheEditorWithTheBookParcel() throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 2);
        BookAdapter adapter = (BookAdapter) grid.getAdapter();

        Book target = findBook("story_a.txt");
        assertNotNull(target);
        int pos = positionOf(adapter, target);
        assertTrue("the book must be in the visible list", pos >= 0);

        android.widget.PopupMenu menu = openKebabFor(grid, pos);
        menu.getMenu().performIdentifierAction(R.id.book_menu_edit, 0);
        shadowOf(Looper.getMainLooper()).idle();

        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull("the Edit metadata pick must start the editor", started);
        assertEquals(EditMetaActivity.class.getName(), started.getComponent().getClassName());
        Book parcel = started.getParcelableExtra(EditMetaActivity.EXTRA_BOOK);
        assertNotNull("the editor must receive the book parcel", parcel);
        assertEquals(target.id, parcel.id);
        assertEquals(target.path, parcel.path);
        assertEquals(target.title, parcel.title);
    }

    @Test
    public void kebabRemoveAsksForConfirmationAndRemovesTheRowKeepingTheFile()
            throws Exception {
        TestFixtures.writeText(new File(storage, "story_a.txt"), "alpha\n");
        TestFixtures.writeText(new File(storage, "story_b.txt"), "beta\n");

        MainActivity a = launchMain();
        awaitCatalogSize(2);
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 2);
        BookAdapter adapter = (BookAdapter) grid.getAdapter();

        Book target = findBook("story_a.txt");
        assertNotNull(target);
        File targetFile = new File(target.path);
        assertTrue("precondition: the file is on disk", targetFile.exists());
        int pos = positionOf(adapter, target);
        assertTrue("the book must be in the visible list", pos >= 0);

        FiringObserver onBooks = new FiringObserver();
        FiringObserver onRecent = new FiringObserver();
        app.getContentResolver().registerContentObserver(BookProvider.CONTENT_URI, true, onBooks);
        app.getContentResolver().registerContentObserver(BookProvider.RECENT_URI, true, onRecent);

        android.widget.PopupMenu menu = openKebabFor(grid, pos);
        menu.getMenu().performIdentifierAction(R.id.book_menu_remove, 0);

        // The Remove pick must ask for confirmation (the same texts the old
        // detail-screen dialog had, plus the "the file stays" note).
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("the Remove pick must ask for confirmation", d);
        assertEquals(a.getString(R.string.delete), shadowOf(d).getTitle());
        TextView msgView = d.getWindow().getDecorView().findViewById(android.R.id.message);
        assertNotNull("the dialog must carry a message view", msgView);
        String msg = msgView.getText().toString();
        assertTrue("the message must be the removal confirmation: " + msg,
                msg.contains(a.getString(R.string.delete_confirm)));
        assertTrue("the message must note the file stays: " + msg,
                msg.contains(a.getString(R.string.remove_file_note)));

        // Confirm ("Remove" — the positive button, the standard alert button-1 view).
        shadowOf(d).clickOn(android.R.id.button1);
        shadowOf(Looper.getMainLooper()).idle();

        // The row is gone from the catalog (the delete ran synchronously above).
        boolean stillThere = false;
        for (Book b : db.all(null)) {
            if (b.id == target.id) stillThere = true;
        }
        assertFalse("the book must be removed from the catalog", stillThere);

        // ...and from the visible list: the direct rebind removes the row
        // immediately, but on a loaded machine the loader may still deliver a
        // stale cursor in the gap, so wait for the list to settle on the one book
        // that remains (the same convergence wait the other list tests use).
        final BookAdapter adapterRef = adapter;
        final long removedId = target.id;
        awaitCondition("the list to settle on the remaining book after the remove",
                new Cond() {
                    public boolean holds() {
                        Book first = adapterRef.getItem(0);
                        return adapterRef.getCount() == 1
                                && first != null && first.id != removedId;
                    }
                });

        // The file itself must not be touched.
        assertTrue("the file must stay on disk", targetFile.exists());

        // Both catalog cursors must have been notified.
        assertTrue("the all-books cursor must be notified", onBooks.fired);
        assertTrue("the recently-read cursor must be notified", onRecent.fired);
        app.getContentResolver().unregisterContentObserver(onBooks);
        app.getContentResolver().unregisterContentObserver(onRecent);
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

        // And the view (the grid, the default) picked it up through the loader.
        GridView grid = a.findViewById(R.id.book_grid);
        awaitAdapterCount(grid, 1);
    }

    /** Re-importing a file with the same name overwrites it: the catalog row must be
     *  re-enriched with the NEW in-file metadata (not keep the old title from the
     *  previous file of the same name). */
    @Test
    public void reimportingOverAnExistingFileReEnrichesTheBook() throws Exception {
        ShadowContentResolver resolver = shadowOf(app.getContentResolver());
        File dest = new File(app.getExternalFilesDir("books"), "novel.fb2");

        // --- first edition ---
        File picked1 = new File(folder.getRoot(), "picked1.fb2");
        TestFixtures.writeText(picked1, TestFixtures.FB2_FULL);
        Uri uri1 = Uri.parse("content://com.neo.librarytest.picked/e1");
        resolver.setCursor(uri1, new DisplayNameCursor("novel.fb2"));
        resolver.registerInputStream(uri1, new FileInputStream(picked1));

        MainActivity a = launchMain();
        awaitCatalogSize(0);
        a.onActivityResult(REQ_IMPORT, Activity.RESULT_OK, new Intent().setData(uri1));

        final BookDatabase dbLocal = db;
        awaitCondition("first import to be enriched", new Cond() {
            public boolean holds() {
                Book b = dbLocal.getById(dbLocal.getIdForPath(dest.getAbsolutePath()));
                return b != null && b.metaDone && "Original Title".equals(b.title);
            }
        });

        // --- second edition: same name, different in-file title ---
        String secondEdition =
                TestFixtures.FB2_FULL.replace("Original Title", "Second Edition Title");
        File picked2 = new File(folder.getRoot(), "picked2.fb2");
        TestFixtures.writeText(picked2, secondEdition);
        Uri uri2 = Uri.parse("content://com.neo.librarytest.picked/e2");
        resolver.setCursor(uri2, new DisplayNameCursor("novel.fb2"));
        resolver.registerInputStream(uri2, new FileInputStream(picked2));

        a.onActivityResult(REQ_IMPORT, Activity.RESULT_OK, new Intent().setData(uri2));

        // The re-extraction must have picked up the new in-file title.
        awaitCondition("re-import to re-enrich the row", new Cond() {
            public boolean holds() {
                Book b = dbLocal.getById(dbLocal.getIdForPath(dest.getAbsolutePath()));
                return b != null && b.metaDone && "Second Edition Title".equals(b.title);
            }
        });
        assertEquals("the file on disk was overwritten",
                secondEdition, new String(TestFixtures.readAll(dest), "UTF-8"));
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
