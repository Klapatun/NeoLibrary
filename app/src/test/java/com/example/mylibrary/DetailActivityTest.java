package com.example.mylibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.util.ArrayList;

/**
 * Robolectric smoke tests for {@link DetailActivity}: the fields it shows, the
 * on-screen "Edit metadata" button, resume refresh, and the "open in reader"
 * intent (+ mark-as-read side effect). Kebab-driven edit/remove on the main
 * screen remain covered by {@link MainActivityTest}.
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
        bookId = db.upsert(b);
        db.markUserEdited(bookId); // what the editor does after the upsert
    }

    private DetailActivity launch(long id) {
        Intent i = new Intent();
        i.putExtra(DetailActivity.EXTRA_BOOK_ID, id);
        return Robolectric.buildActivity(DetailActivity.class, i).setup().get();
    }

    private void markMetaDone(long workId) {
        db.getWritableDatabase().execSQL(
                "UPDATE files SET meta_done = 1 WHERE work_id = " + workId);
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
                "SELECT last_read FROM works WHERE _id=?", new String[]{String.valueOf(bookId)});
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

        android.database.Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT last_read FROM works WHERE _id=?", new String[]{String.valueOf(bookId)});
        try {
            assertTrue("row must exist", c.moveToFirst());
            assertEquals("last_read must stay 0", 0L, c.getLong(0));
        } finally {
            c.close();
        }
    }

    private void setAvailabilityForWork(long workId, String availability) {
        db.getWritableDatabase().execSQL(
                "UPDATE files SET availability='" + availability + "' WHERE work_id=" + workId);
    }

    @Test
    public void openButtonOnDownloadingEditionDoesNotLaunchViewerOrMarkRead() {
        setAvailabilityForWork(bookId, "downloading");
        DetailActivity a = launch(bookId);

        ((Button) a.findViewById(R.id.btn_open)).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(a.getString(R.string.file_downloading), ShadowToast.getTextOfLatestToast());
        assertNull("no viewer intent may be started", shadowOf(a).getNextStartedActivity());

        android.database.Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT last_read FROM works WHERE _id=?", new String[]{String.valueOf(bookId)});
        try {
            assertTrue("row must exist", c.moveToFirst());
            assertEquals("last_read must stay 0", 0L, c.getLong(0));
        } finally {
            c.close();
        }
    }

    @Test
    public void openButtonOnFailedEditionDoesNotLaunchViewerOrMarkRead() {
        setAvailabilityForWork(bookId, "failed");
        DetailActivity a = launch(bookId);

        ((Button) a.findViewById(R.id.btn_open)).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(a.getString(R.string.file_download_failed), ShadowToast.getTextOfLatestToast());
        assertNull("no viewer intent may be started", shadowOf(a).getNextStartedActivity());

        android.database.Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT last_read FROM works WHERE _id=?", new String[]{String.valueOf(bookId)});
        try {
            assertTrue("row must exist", c.moveToFirst());
            assertEquals("last_read must stay 0", 0L, c.getLong(0));
        } finally {
            c.close();
        }
    }

    @Test
    public void unknownBookIdFinishesImmediately() {
        DetailActivity a = launch(424242);
        assertTrue(a.isFinishing());
    }

    @Test
    public void editMetadataButtonOpensTheEditorWithTheBookParcel() {
        DetailActivity a = launch(bookId);
        Book expected = db.getById(bookId);

        Button edit = (Button) a.findViewById(R.id.btn_edit_meta);
        assertEquals(View.VISIBLE, edit.getVisibility());
        assertEquals(a.getString(R.string.edit_meta), edit.getText().toString());

        edit.performClick();
        shadowOf(Looper.getMainLooper()).idle();

        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull("Edit metadata must start the editor", started);
        assertEquals(EditMetaActivity.class.getName(), started.getComponent().getClassName());
        Book parcel = started.getParcelableExtra(EditMetaActivity.EXTRA_BOOK);
        assertNotNull("the editor must receive the book parcel", parcel);
        assertEquals(expected.id, parcel.id);
        assertEquals(expected.path, parcel.path);
        assertEquals(expected.title, parcel.title);

        assertNotNull("Open in reader must stay on the detail screen", a.findViewById(R.id.btn_open));
    }

    @Test
    public void detailReloadsTitleAndGenresOnResume() {
        markMetaDone(bookId);

        Intent i = new Intent();
        i.putExtra(DetailActivity.EXTRA_BOOK_ID, bookId);
        ActivityController<DetailActivity> c = Robolectric.buildActivity(DetailActivity.class, i);
        DetailActivity a = c.setup().get();

        String titleBefore = ((TextView) a.findViewById(R.id.detail_title)).getText().toString();
        assertEquals("A Plain Book", titleBefore);

        Book updated = db.getById(bookId);
        updated.title = "Renamed On Catalog";
        updated.genres = new ArrayList<String>();
        updated.genres.add("Science Fiction");
        db.upsert(updated);

        c.pause();
        c.resume();

        assertEquals("Renamed On Catalog",
                ((TextView) a.findViewById(R.id.detail_title)).getText().toString());
        String other = ((TextView) a.findViewById(R.id.detail_other)).getText().toString();
        assertTrue("detail_other must show the normalized genre",
                other.contains("science fiction"));
    }

    @Test
    public void detailFinishesOnResumeWhenTheRowIsGone() {
        markMetaDone(bookId);

        Intent i = new Intent();
        i.putExtra(DetailActivity.EXTRA_BOOK_ID, bookId);
        ActivityController<DetailActivity> c = Robolectric.buildActivity(DetailActivity.class, i);
        DetailActivity a = c.setup().get();

        db.deleteByPath(bookFile.getAbsolutePath());
        c.pause();
        c.resume();

        assertTrue("the detail screen must close when the catalog row is gone", a.isFinishing());
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
}
