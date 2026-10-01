package com.example.mylibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.util.Arrays;
import java.util.Map;

/**
 * Robolectric tests for {@link EditMetaActivity} — the two-layer metadata invariant:
 *
 * <ul>
 *   <li><b>EPUB / FB2</b> (inline-editable): the save must rewrite the <i>file</i>
 *       (verifiable by re-reading the archive/XML) AND persist to the SQLite catalog;</li>
 *   <li><b>everything else</b>: the catalog is updated, but the file's bytes must
 *       remain byte-for-byte identical.</li>
 * </ul>
 *
 * <p>The save runs on a background {@code AsyncTask} thread, so the tests poll the
 * catalog (the completion signal emitted from {@code doInBackground}) and then idle
 * the main looper to let {@code onPostExecute} finish the activity.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class EditMetaActivityTest {

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

    private long addBookToCatalog(File file, String format, String title, String author) {
        Book b = new Book();
        b.path = file.getAbsolutePath();
        b.format = format;
        b.title = title;
        b.author = author;
        b.publisher = "Original Publisher";
        b.description = "An original story.";
        b.sizeBytes = 4321;
        return db.upsert(b);
    }

    private EditMetaActivity launch(File file, String format, String title, String author) {
        long id = addBookToCatalog(file, format, title, author);
        Book b = db.getById(id);
        Intent i = new Intent();
        i.putExtra(EditMetaActivity.EXTRA_BOOK, b);
        return Robolectric.buildActivity(EditMetaActivity.class, i).setup().get();
    }

    /** Waits until the background save has upserted the new title into the catalog. */
    private static void awaitCatalogTitle(BookDatabase db, long id, String expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Book b = db.getById(id);
            if (b != null && expected.equals(b.title)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("catalog did not reach title='" + expected + "'");
    }

    @Test
    public void epubEditWritesBothTheFileAndTheCatalog() throws Exception {
        File epub = new File(folder.getRoot(), "book.epub");
        TestFixtures.writeEpub(epub, TestFixtures.OPF_FULL);

        EditMetaActivity a = launch(epub, "EPUB", "Original Title", "Original Author");
        long id = db.getIdForPath(epub.getAbsolutePath());

        // The hint must tell the user the file itself will be edited.
        TextView hint = a.findViewById(R.id.edit_format_hint);
        assertTrue(hint.getText().toString().contains("saved into the file itself"));

        ((EditText) a.findViewById(R.id.edit_title)).setText("New Title");
        ((EditText) a.findViewById(R.id.edit_author)).setText("New Author");
        ((EditText) a.findViewById(R.id.edit_publisher)).setText("New Publisher");
        ((EditText) a.findViewById(R.id.edit_description)).setText("New Description");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogTitle(db, id, "New Title");
        shadowOf(Looper.getMainLooper()).idle();

        // Layer 1 — the EPUB file itself carries the new Dublin-Core values.
        Map<String, byte[]> entries = TestFixtures.readZip(epub);
        String opf = new String(entries.get("OEBPS/content.opf"), "UTF-8");
        assertTrue(opf.contains("<dc:title>New Title</dc:title>"));
        assertTrue(opf.contains("<dc:creator>New Author</dc:creator>"));
        assertTrue(opf.contains("<dc:publisher>New Publisher</dc:publisher>"));
        assertTrue(opf.contains("<dc:description>New Description</dc:description>"));

        // Layer 2 — the catalog row matches, and the exported flag is set.
        Book got = db.getById(id);
        assertEquals("New Title", got.title);
        assertEquals("New Author", got.author);
        assertEquals("New Publisher", got.publisher);
        assertEquals("New Description", got.description);
        assertTrue("exported must be true after an in-file write", got.exported);

        // The activity closes with a positive result.
        assertTrue(a.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(a).getResultCode());
        assertEquals("Saved", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void fb2EditWritesBothTheFileAndTheCatalog() throws Exception {
        File fb2 = new File(folder.getRoot(), "book.fb2");
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);

        EditMetaActivity a = launch(fb2, "FB2", "Original Title", "Ivan Ivanovich Petrov");
        long id = db.getIdForPath(fb2.getAbsolutePath());

        assertTrue(((TextView) a.findViewById(R.id.edit_format_hint)).getText()
                .toString().contains("saved into the file itself"));

        ((EditText) a.findViewById(R.id.edit_title)).setText("New FB2 Title");
        ((EditText) a.findViewById(R.id.edit_author)).setText("Anna Akhmatova");
        ((EditText) a.findViewById(R.id.edit_publisher)).setText("New Publisher");
        ((EditText) a.findViewById(R.id.edit_description)).setText("New Description");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogTitle(db, id, "New FB2 Title");
        shadowOf(Looper.getMainLooper()).idle();

        // Layer 1 — the FB2 XML: title, the split first/last names, publisher.
        String xml = new String(TestFixtures.readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<title>New FB2 Title</title>"));
        assertTrue(xml.contains("<first-name>Anna</first-name>"));
        assertTrue(xml.contains("<last-name>Akhmatova</last-name>"));
        assertTrue(xml.contains("<publisher>New Publisher</publisher>"));

        // Layer 2 — the catalog row.
        Book got = db.getById(id);
        assertEquals("New FB2 Title", got.title);
        assertEquals("Anna Akhmatova", got.author);
        assertEquals("New Publisher", got.publisher);
        assertEquals("New Description", got.description);
        assertTrue(got.exported);

        assertTrue(a.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(a).getResultCode());
    }

    @Test
    public void nonRewritableFormatUpdatesCatalogOnlyAndLeavesFileUntouched() throws Exception {
        File pdf = new File(folder.getRoot(), "book.pdf");
        byte[] original = "%PDF-1.4 deliberately-not-a-real-pdf".getBytes("UTF-8");
        TestFixtures.writeBytes(pdf, original);

        EditMetaActivity a = launch(pdf, "PDF", "Old Title", "Old Author");
        long id = db.getIdForPath(pdf.getAbsolutePath());

        // The hint must say the catalog is the only destination.
        assertTrue(((TextView) a.findViewById(R.id.edit_format_hint)).getText()
                .toString().contains("app library only"));

        ((EditText) a.findViewById(R.id.edit_title)).setText("New PDF Title");
        ((EditText) a.findViewById(R.id.edit_author)).setText("New Person");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogTitle(db, id, "New PDF Title");
        shadowOf(Looper.getMainLooper()).idle();

        // The file must be byte-for-byte identical to what we wrote.
        assertTrue(Arrays.equals(original, TestFixtures.readAll(pdf)));

        // The catalog row is updated, but exported must stay false.
        Book got = db.getById(id);
        assertEquals("New PDF Title", got.title);
        assertEquals("New Person", got.author);
        assertTrue("no in-file write happened, so exported must be false", !got.exported);

        assertTrue(a.isFinishing());
        assertEquals("Saved", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void cancelFinishesWithoutSaving() throws Exception {
        File pdf = new File(folder.getRoot(), "book.pdf");
        TestFixtures.writeBytes(pdf, new byte[]{1, 2, 3});
        EditMetaActivity a = launch(pdf, "PDF", "Old Title", "Old Author");
        long id = db.getIdForPath(pdf.getAbsolutePath());

        ((Button) a.findViewById(R.id.btn_cancel)).performClick();

        assertTrue(a.isFinishing());
        assertEquals("Old Title", db.getById(id).title);
    }

    /** If the row is deleted while the editor is open, saving must not resurrect
     *  the book with a stale in-memory copy. */
    @Test
    public void saveDoesNotResurrectADeletedBook() throws Exception {
        File pdf = new File(folder.getRoot(), "book.pdf");
        TestFixtures.writeBytes(pdf, new byte[]{1, 2, 3});
        EditMetaActivity a = launch(pdf, "PDF", "Old Title", "Old Author");
        long id = db.getIdForPath(pdf.getAbsolutePath());
        assertTrue("precondition: the row exists", id >= 0);

        // The book is removed from the catalog while the editor is open.
        db.deleteByPath(pdf.getAbsolutePath());

        ((Button) a.findViewById(R.id.btn_save)).performClick();

        // Let the background save run to completion (the activity finishes with it).
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && !a.isFinishing()) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("the deleted row must not come back", 0, db.all(null).size());
        assertEquals(Activity.RESULT_OK, shadowOf(a).getResultCode());
    }

    /** The list is cursor-driven: a save must announce the change, otherwise the
     *  new title/author stay invisible in the catalog views. */
    @Test
    public void saveNotifiesTheCatalogObservers() throws Exception {
        File pdf = new File(folder.getRoot(), "book.pdf");
        TestFixtures.writeBytes(pdf, new byte[]{1, 2, 3});

        FiringObserver onBooks = new FiringObserver();
        FiringObserver onRecent = new FiringObserver();
        app.getContentResolver().registerContentObserver(BookProvider.CONTENT_URI, true, onBooks);
        app.getContentResolver().registerContentObserver(BookProvider.RECENT_URI, true, onRecent);

        EditMetaActivity a = launch(pdf, "PDF", "Old Title", "Old Author");
        long id = db.getIdForPath(pdf.getAbsolutePath());

        ((EditText) a.findViewById(R.id.edit_title)).setText("New Title");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogTitle(db, id, "New Title");
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue("the all-books cursor must be told about the new metadata", onBooks.fired);
        assertTrue("the recently-read cursor must be told as well", onRecent.fired);
        app.getContentResolver().unregisterContentObserver(onBooks);
        app.getContentResolver().unregisterContentObserver(onRecent);
    }

    /** Records whether the observer fired (for one registered URI). */
    private static final class FiringObserver extends ContentObserver {
        private boolean fired;
        FiringObserver() { super(new Handler(Looper.getMainLooper())); }
        @Override public void onChange(boolean selfChange, Uri uri) { fired = true; }
    }
}
