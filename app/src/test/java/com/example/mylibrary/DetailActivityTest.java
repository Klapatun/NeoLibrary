package com.example.mylibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.testutil.TestFixtures;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.File;

/**
 * Robolectric smoke tests for {@link DetailActivity}: the fields it shows, the
 * "open in reader" intent (+ mark-as-read side effect), the two-layer delete
 * confirmation (catalog row gone, file untouched), and the hand-off to
 * {@link EditMetaActivity}.
 *
 * <p>No viewer app is installed in the Robolectric environment, but Robolectric's
 * shadow {@code startActivity} is lenient: it records the intent instead of throwing
 * {@code ActivityNotFoundException}, so the happy path (ACTION_VIEW + markRead) is
 * what runs here. Run at {@code sdk = 19}.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class DetailActivityTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Context app;
    private BookDatabase db;
    private File bookFile;
    private long bookId;

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("library.db");
        db = new BookDatabase(app);

        bookFile = new File(folder.getRoot(), "reader.txt");
        TestFixtures.writeText(bookFile, "hello\n");

        Book b = new Book();
        b.path = bookFile.getAbsolutePath();
        b.format = "TXT";
        b.title = "A Plain Book";
        b.author = "Some One";
        b.publisher = "A Publisher";
        b.description = "a test book";
        b.sizeBytes = 1234;
        // A title/author that the user set through the editor: the fast-path
        // enrichment on open must not clobber them (see MetaEnricher's contract).
        b.userEdited = true;
        bookId = db.upsert(b);
    }

    private DetailActivity launch(long id) {
        Intent i = new Intent();
        i.putExtra(DetailActivity.EXTRA_BOOK_ID, id);
        return Robolectric.buildActivity(DetailActivity.class, i).setup().get();
    }

    @Test
    public void detailShowsBookFieldsAndBadge() {
        DetailActivity a = launch(bookId);

        assertEquals("A Plain Book",
                ((TextView) a.findViewById(R.id.detail_title)).getText().toString());
        assertEquals("Some One",
                ((TextView) a.findViewById(R.id.detail_author)).getText().toString());
        assertEquals(bookFile.getAbsolutePath(),
                ((TextView) a.findViewById(R.id.detail_file)).getText().toString());

        // TXT cannot carry a cover: badge visible with the initial, cover gone.
        TextView initial = a.findViewById(R.id.detail_initial);
        ImageView cover = a.findViewById(R.id.detail_cover);
        assertEquals("A", initial.getText().toString());
        assertEquals(View.VISIBLE, initial.getVisibility());
        assertEquals(View.GONE, cover.getVisibility());
    }

    @Test
    public void fastPathEnrichesAnUnenrichedBookOnOpen() throws Exception {
        // A fresh stage-1 row: file-name title, meta_done=0. Opening the detail must
        // run the single-book enrichment on a background thread and mark the row done.
        File f = new File(folder.getRoot(), "my_novel.txt");
        TestFixtures.writeText(f, "story text\n");
        Book b = new Book();
        b.path = f.getAbsolutePath();
        b.format = "TXT";
        b.title = "my novel"; // what the fast stage stores (file name, pretty-printed)
        long id = db.upsertBasic(b);
        assertFalse("precondition: not yet enriched", db.getById(id).metaDone);

        launch(id);

        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && !db.getById(id).metaDone) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        Book fresh = db.getById(id);
        assertTrue("the fast path must mark the book enriched", fresh.metaDone);
        assertEquals("my novel", fresh.title); // TXT: file-name title is preserved
    }

    @Test
    public void openButtonStartsViewIntentAndMarksBookRead() {
        DetailActivity a = launch(bookId);

        ((Button) a.findViewById(R.id.btn_open)).performClick();

        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull("a viewer intent must be started", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("text/plain", started.getType());
        assertEquals(Uri.fromFile(bookFile), started.getData());

        // The mark-as-read side effect must have hit the catalog.
        android.database.Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT last_read FROM books WHERE _id=?", new String[]{String.valueOf(bookId)});
        assertTrue("row must exist", c.moveToFirst());
        assertTrue("last_read must be set", c.getLong(0) > 0);
    }

    @Test
    public void openButtonWithMissingFileShowsToastInstead() {
        assertTrue(bookFile.delete()); // make the file vanish after the row is created
        DetailActivity a = launch(bookId);

        ((Button) a.findViewById(R.id.btn_open)).performClick();

        assertEquals("File not found", ShadowToast.getTextOfLatestToast());
        assertNull("no viewer intent may be started", shadowOf(a).getNextStartedActivity());
    }

    @Test
    public void deleteFlowRemovesCatalogRowFinishesAndKeepsTheFile() {
        DetailActivity a = launch(bookId);

        ((Button) a.findViewById(R.id.btn_delete)).performClick();

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("a confirmation dialog must be shown", dialog);
        assertEquals("Remove from library", shadowOf(dialog).getTitle().toString());
        // The AlertDialog wires its button listeners from runnables posted to the main
        // looper; in Robolectric those only run when the looper idles (and the wiring
        // may take a couple of idle passes). So: idle, click, and repeat until the
        // handler actually ran.
        for (int attempt = 0; attempt < 5 && !a.isFinishing(); attempt++) {
            shadowOf(Looper.getMainLooper()).idle();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        }

        assertNull("catalog row must be gone", db.getById(bookId));
        assertTrue(a.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(a).getResultCode());
        assertTrue("the file itself must not be deleted", bookFile.exists());
    }

    @Test
    public void unknownBookIdFinishesImmediately() {
        DetailActivity a = launch(424242);
        assertTrue(a.isFinishing());
    }

    // ------------------------------------------------------------------
    // cursor notifications (the list only refreshes on notifyChange)
    // ------------------------------------------------------------------

    /** Records whether the observer fired (for one registered URI). */
    private static final class FiringObserver extends ContentObserver {
        private boolean fired;
        FiringObserver() { super(new Handler(Looper.getMainLooper())); }
        @Override public void onChange(boolean selfChange, Uri uri) { fired = true; }
    }

    /** A delete must announce the change to the cursors of BOTH views (the row may
     *  be visible in the list or in the "recently read" view). */
    @Test
    public void deleteNotifiesTheCatalogObservers() {
        FiringObserver onBooks = new FiringObserver();
        FiringObserver onRecent = new FiringObserver();
        app.getContentResolver().registerContentObserver(BookProvider.CONTENT_URI, true, onBooks);
        app.getContentResolver().registerContentObserver(BookProvider.RECENT_URI, true, onRecent);

        DetailActivity a = launch(bookId);
        ((Button) a.findViewById(R.id.btn_delete)).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        for (int attempt = 0; attempt < 5 && !a.isFinishing(); attempt++) {
            shadowOf(Looper.getMainLooper()).idle();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        }
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue("the all-books cursor must be told the row is gone", onBooks.fired);
        assertTrue("the recently-read cursor must be told as well", onRecent.fired);
        app.getContentResolver().unregisterContentObserver(onBooks);
        app.getContentResolver().unregisterContentObserver(onRecent);
    }

    /** Opening a book (markRead) must announce the change too — otherwise the
     *  "recently read" view never re-sorts and the book doesn't rise to the top. */
    @Test
    public void openingABookNotifiesTheCatalogObservers() {
        FiringObserver onBooks = new FiringObserver();
        FiringObserver onRecent = new FiringObserver();
        app.getContentResolver().registerContentObserver(BookProvider.CONTENT_URI, true, onBooks);
        app.getContentResolver().registerContentObserver(BookProvider.RECENT_URI, true, onRecent);

        DetailActivity a = launch(bookId);
        ((Button) a.findViewById(R.id.btn_open)).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue("markRead must announce the change to the all-books cursor", onBooks.fired);
        assertTrue("... and to the recently-read cursor", onRecent.fired);
        app.getContentResolver().unregisterContentObserver(onBooks);
        app.getContentResolver().unregisterContentObserver(onRecent);
    }

    @Test
    public void editButtonLaunchesEditorWithTheBookExtra() {
        DetailActivity a = launch(bookId);

        ((Button) a.findViewById(R.id.btn_edit)).performClick();

        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(EditMetaActivity.class.getName(), started.getComponent().getClassName());
        Book extra = started.getParcelableExtra(EditMetaActivity.EXTRA_BOOK);
        assertNotNull(extra);
        assertEquals(bookId, extra.id);
    }
}
