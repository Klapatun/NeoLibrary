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
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.testutil.TestFixtures;

import org.junit.After;
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
import java.util.ArrayList;
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

    @After
    public void tearDownDirectoryWritableShim() {
        EditMetaActivity.directoryWritableForTest = null;
    }

    private long addBookToCatalog(File file, String format, String title, String author) {
        return addBookToCatalog(file, format, title, author, null);
    }

    private long addBookToCatalog(File file, String format, String title, String author,
            ArrayList<String> genres) {
        Book b = new Book();
        b.path = file.getAbsolutePath();
        b.format = format;
        b.title = title;
        b.author = author;
        b.publisher = "Original Publisher";
        b.description = "An original story.";
        if (genres != null) {
            b.genres = new ArrayList<String>(genres);
        } else {
            b.genres = new ArrayList<String>();
        }
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

    /** Waits until {@code getById} returns the expected genre list (order matters). */
    private static void awaitCatalogGenres(BookDatabase db, long id, String... expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Book b = db.getById(id);
            if (b != null && b.genres != null && b.genres.size() == expected.length) {
                boolean match = true;
                for (int i = 0; i < expected.length; i++) {
                    if (!expected[i].equals(b.genres.get(i))) {
                        match = false;
                        break;
                    }
                }
                if (match) return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("catalog did not reach genres");
    }

    private static LinearLayout genreContainer(EditMetaActivity a) {
        return (LinearLayout) a.findViewById(R.id.edit_genres);
    }

    private static int genreFieldCount(EditMetaActivity a) {
        return genreContainer(a).getChildCount();
    }

    private static EditText genreFieldAt(EditMetaActivity a, int index) {
        View child = genreContainer(a).getChildAt(index);
        return (EditText) child;
    }

  private static void setGenreFieldCount(EditMetaActivity a, int count) {
    LinearLayout box = genreContainer(a);
    while (box.getChildCount() < count) {
      ((Button) a.findViewById(R.id.btn_add_genre)).performClick();
    }
    while (box.getChildCount() > count) {
      box.removeViewAt(box.getChildCount() - 1);
    }
  }

    private static void setGenreFields(EditMetaActivity a, String... texts) {
        setGenreFieldCount(a, texts.length);
        for (int i = 0; i < texts.length; i++) {
            genreFieldAt(a, i).setText(texts[i]);
        }
    }

    private long lastReadOf(long id) {
        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT last_read FROM works WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            return c.isNull(0) ? 0L : c.getLong(0);
        } finally {
            c.close();
        }
    }

    private String adoptedFromOf(long workId) {
        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT adopted_from FROM files WHERE work_id=?",
                new String[]{String.valueOf(workId)});
        try {
            c.moveToFirst();
            return c.isNull(0) ? null : c.getString(0);
        } finally {
            c.close();
        }
    }

    private int workCount() {
        SQLiteDatabase raw = db.getReadableDatabase();
        Cursor c = raw.rawQuery("SELECT COUNT(*) FROM works", null);
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private File booksDir() {
        File dir = app.getExternalFilesDir("books");
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static int countBookFilesIn(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (int i = 0; i < kids.length; i++) {
            String name = kids[i].getName().toLowerCase();
            if (kids[i].isFile()
                    && (name.endsWith(".epub") || name.endsWith(".fb2"))) {
                n++;
            }
        }
        return n;
    }

    private static boolean dirHasProbeFile(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (int i = 0; i < kids.length; i++) {
            if (kids[i].isFile() && kids[i].getName().startsWith(".neowrite-")) {
                return true;
            }
        }
        return false;
    }

    private EditMetaActivity openEditor(long id) {
        Intent i = new Intent();
        i.putExtra(EditMetaActivity.EXTRA_BOOK, db.getById(id));
        return Robolectric.buildActivity(EditMetaActivity.class, i).setup().get();
    }

    @Test
    public void editorFillsGenresFromCatalogWhenTheParcelListIsEmpty() throws Exception {
        File file = new File(folder.getRoot(), "book.txt");
        TestFixtures.writeText(file, "hello\n");
        ArrayList<String> genres = new ArrayList<String>();
        genres.add("prose");
        genres.add("mystery");
        long id = addBookToCatalog(file, "TXT", "T", "A", genres);

        Book parcel = db.getById(id);
        parcel.genres = new ArrayList<String>();
        Intent i = new Intent();
        i.putExtra(EditMetaActivity.EXTRA_BOOK, parcel);
        EditMetaActivity a = Robolectric.buildActivity(EditMetaActivity.class, i).setup().get();

        assertEquals(2, genreFieldCount(a));
        assertEquals("prose", genreFieldAt(a, 0).getText().toString());
        assertEquals("mystery", genreFieldAt(a, 1).getText().toString());
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

    /** Two catalog genres -> two fields; none -> one empty field (same order). */
    @Test
    public void editGenrePrefillsTheField() throws Exception {
        File withGenres = new File(folder.getRoot(), "with-genre.pdf");
        TestFixtures.writeBytes(withGenres, new byte[]{1});
        ArrayList<String> two = new ArrayList<String>();
        two.add("prose");
        two.add("mystery");
        long twoId = addBookToCatalog(withGenres, "PDF", "T", "A", two);
        EditMetaActivity twoFields = openEditor(twoId);
        assertEquals(2, genreFieldCount(twoFields));
        assertEquals("prose", genreFieldAt(twoFields, 0).getText().toString());
        assertEquals("mystery", genreFieldAt(twoFields, 1).getText().toString());

        File withoutGenre = new File(folder.getRoot(), "no-genre.pdf");
        TestFixtures.writeBytes(withoutGenre, new byte[]{2});
        long emptyId = addBookToCatalog(withoutGenre, "PDF", "T", "A", null);
        EditMetaActivity empty = openEditor(emptyId);
        assertEquals(1, genreFieldCount(empty));
        assertEquals("", genreFieldAt(empty, 0).getText().toString());
    }

    @Test
    public void addGenreButtonAppendsAnotherField() throws Exception {
        File pdf = new File(folder.getRoot(), "add-genre.pdf");
        TestFixtures.writeBytes(pdf, new byte[]{1});
        long id = addBookToCatalog(pdf, "PDF", "T", "A", null);
        EditMetaActivity a = openEditor(id);
        assertEquals(1, genreFieldCount(a));
        ((Button) a.findViewById(R.id.btn_add_genre)).performClick();
        assertEquals(2, genreFieldCount(a));
    }

    /** Trimming, normalization and catalog-only save for PDF (file bytes untouched). */
    @Test
    public void saveGenrePersistsInTheCatalogForAnyFormat() throws Exception {
        File pdf = new File(folder.getRoot(), "genre.pdf");
        byte[] original = "%PDF-1.4 deliberately-not-a-real-pdf".getBytes("UTF-8");
        TestFixtures.writeBytes(pdf, original);

        long id = addBookToCatalog(pdf, "PDF", "Old Title", "Old Author", null);
        db.markRead(id);
        long lastRead = lastReadOf(id);

        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "  Mystery  ", "prose", "");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "mystery", "prose");
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(Arrays.equals(original, TestFixtures.readAll(pdf)));
        Book got = db.getById(id);
        assertGenres("mystery", "prose", got.genres);
        assertTrue(got.userEdited);
        assertTrue("no in-file write happened, so exported must be false", !got.exported);
        assertEquals(lastRead, lastReadOf(id));
    }

    @Test
    public void clearingGenreStoresEmptyList() throws Exception {
        File pdf = new File(folder.getRoot(), "clear-genre.pdf");
        byte[] original = new byte[]{9, 8, 7};
        TestFixtures.writeBytes(pdf, original);

        ArrayList<String> one = new ArrayList<String>();
        one.add("prose");
        long id = addBookToCatalog(pdf, "PDF", "Old Title", "Old Author", one);
        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "   ");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id);
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(db.getById(id).genres);
        assertTrue(db.getById(id).genres.isEmpty());
        assertTrue(Arrays.equals(original, TestFixtures.readAll(pdf)));
    }

    @Test
    public void epubEditWritesGenreIntoTheFileAndTheCatalog() throws Exception {
        File epub = new File(folder.getRoot(), "genre.epub");
        TestFixtures.writeEpub(epub, TestFixtures.OPF_FULL);

        long id = addBookToCatalog(epub, "EPUB", "Original Title", "Original Author", null);
        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "Mystery", "Фэнтези");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "mystery", "фэнтези");
        shadowOf(Looper.getMainLooper()).idle();

        Map<String, byte[]> entries = TestFixtures.readZip(epub);
        String opf = new String(entries.get("OEBPS/content.opf"), "UTF-8");
        assertTrue(opf.contains("<dc:subject>mystery</dc:subject>"));
        assertTrue(opf.contains("<dc:subject>фэнтези</dc:subject>"));

        Book got = db.getById(id);
        assertGenres("mystery", "фэнтези", got.genres);
        assertTrue(got.exported);
    }

    @Test
    public void fb2EditGenreOnlyLeavesTitleAndAuthorUntouchedInFileAndCatalog() throws Exception {
        File fb2 = new File(folder.getRoot(), "genre-only.fb2");
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);

        EditMetaActivity a = launch(fb2, "FB2", "Original Title", "Ivan Ivanovich Petrov");
        long id = db.getIdForPath(fb2.getAbsolutePath());

        setGenreFields(a, "SF_History");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "sf_history");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Saved", ShadowToast.getTextOfLatestToast());
        Book got = db.getById(id);
        assertEquals("Original Title", got.title);
        assertEquals("Ivan Ivanovich Petrov", got.author);

        String xml = new String(TestFixtures.readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<title>Original Title</title>"));
        assertTrue(xml.contains("<first-name>Ivan</first-name>"));
        assertTrue(xml.contains("<last-name>Petrov</last-name>"));
        assertTrue(xml.contains("<genre>sf_history</genre>"));
        assertTrue(got.exported);
        assertTrue(a.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(a).getResultCode());
    }

    @Test
    public void fb2EditWritesGenreIntoTheFileAndTheCatalog() throws Exception {
        File fb2 = new File(folder.getRoot(), "genre.fb2");
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);

        ArrayList<String> one = new ArrayList<String>();
        one.add("prose");
        long id = addBookToCatalog(fb2, "FB2", "Original Title", "Ivan Ivanovich Petrov", one);
        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "SF_History");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "sf_history");
        shadowOf(Looper.getMainLooper()).idle();

        String xml = new String(TestFixtures.readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<genre>sf_history</genre>"));

        Book got = db.getById(id);
        assertGenres("sf_history", got.genres);
        assertTrue(got.exported);
    }

    @Test
    public void fb2EditClearRemovesGenreFromFileAndCatalog() throws Exception {
        File fb2 = new File(folder.getRoot(), "clear-genre.fb2");
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);

        ArrayList<String> one = new ArrayList<String>();
        one.add("prose");
        long id = addBookToCatalog(fb2, "FB2", "Original Title", "Ivan Ivanovich Petrov", one);
        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "", "");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(db.getById(id).genres.isEmpty());
        String xml = new String(TestFixtures.readAll(fb2), "UTF-8");
        assertFalse("all genre elements must be gone", xml.contains("<genre>"));
    }

    @Test
    public void saveDeduplicatesGenresAfterNormalization() throws Exception {
        File pdf = new File(folder.getRoot(), "dedupe-genre.pdf");
        TestFixtures.writeBytes(pdf, new byte[]{1});
        long id = addBookToCatalog(pdf, "PDF", "T", "A", null);
        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "Фэнтези", "фэнтези");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "фэнтези");
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Writable catalog: in-place EPUB edit, no private copy (fix edit-private-copy). */
    @Test
    public void epubEditWritableCatalogKeepsPathAndWritesGenreInPlace() throws Exception {
        File epub = new File(folder.getRoot(), "writable.epub");
        TestFixtures.writeEpub(epub, TestFixtures.OPF_FULL);
        long id = addBookToCatalog(epub, "EPUB", "Title", "Author", null);
        db.markRead(id);
        long lastRead = lastReadOf(id);

        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "Mystery");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "mystery");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Saved", ShadowToast.getTextOfLatestToast());
        Book got = db.getById(id);
        assertEquals(epub.getAbsolutePath(), got.path);
        assertNull(adoptedFromOf(id));
        assertEquals(0, countBookFilesIn(booksDir()));
        assertEquals(lastRead, lastReadOf(id));

        Map<String, byte[]> entries = TestFixtures.readZip(epub);
        String opf = new String(entries.get("OEBPS/content.opf"), "UTF-8");
        assertTrue(opf.contains("<dc:subject>mystery</dc:subject>"));
        assertFalse(new File(epub.getParentFile(), epub.getName() + ".tmp").exists());
        assertFalse(new File(epub.getParentFile(), epub.getName() + ".bak").exists());
    }

    @Test
    public void fb2EditWritableCatalogKeepsPathAndWritesGenreInPlace() throws Exception {
        File fb2 = new File(folder.getRoot(), "writable.fb2");
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);
        long id = addBookToCatalog(fb2, "FB2", "Title", "Ivan Ivanovich Petrov", null);
        db.markRead(id);
        long lastRead = lastReadOf(id);

        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "SF_History");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "sf_history");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Saved", ShadowToast.getTextOfLatestToast());
        Book got = db.getById(id);
        assertEquals(fb2.getAbsolutePath(), got.path);
        assertNull(adoptedFromOf(id));
        assertEquals(0, countBookFilesIn(booksDir()));
        assertEquals(lastRead, lastReadOf(id));

        String xml = new String(TestFixtures.readAll(fb2), "UTF-8");
        assertTrue(xml.contains("<genre>sf_history</genre>"));
    }

    @Test
    public void epubEditCopiesToPrivateWhenDirectoryNotWritable() throws Exception {
        File epub = new File(folder.getRoot(), "blocked.epub");
        TestFixtures.writeEpub(epub, TestFixtures.OPF_FULL);
        byte[] before = TestFixtures.readAll(epub);
        long id = addBookToCatalog(epub, "EPUB", "Title", "Author", null);
        db.markRead(id);
        long lastRead = lastReadOf(id);

        EditMetaActivity.directoryWritableForTest = Boolean.FALSE;
        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "Mystery");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "mystery");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Saved", ShadowToast.getTextOfLatestToast());
        File copy = new File(booksDir(), "blocked.epub");
        Book got = db.getById(id);
        assertEquals(id, got.id);
        assertEquals(copy.getAbsolutePath(), got.path);
        assertEquals(epub.getAbsolutePath(), adoptedFromOf(id));
        assertTrue(Arrays.equals(before, TestFixtures.readAll(epub)));
        assertEquals(1, workCount());
        assertEquals(lastRead, lastReadOf(id));
        assertTrue(copy.exists());

        Map<String, byte[]> entries = TestFixtures.readZip(copy);
        String opf = new String(entries.get("OEBPS/content.opf"), "UTF-8");
        assertTrue(opf.contains("<dc:subject>mystery</dc:subject>"));
    }

    @Test
    public void fb2EditCopiesToPrivateWhenDirectoryNotWritable() throws Exception {
        File fb2 = new File(folder.getRoot(), "blocked.fb2");
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);
        byte[] before = TestFixtures.readAll(fb2);
        long id = addBookToCatalog(fb2, "FB2", "Title", "Ivan Ivanovich Petrov", null);
        db.markRead(id);
        long lastRead = lastReadOf(id);

        EditMetaActivity.directoryWritableForTest = Boolean.FALSE;
        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "SF_History");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "sf_history");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Saved", ShadowToast.getTextOfLatestToast());
        File copy = new File(booksDir(), "blocked.fb2");
        Book got = db.getById(id);
        assertEquals(copy.getAbsolutePath(), got.path);
        assertEquals(fb2.getAbsolutePath(), adoptedFromOf(id));
        assertTrue(Arrays.equals(before, TestFixtures.readAll(fb2)));
        assertEquals(lastRead, lastReadOf(id));

        String xml = new String(TestFixtures.readAll(copy), "UTF-8");
        assertTrue(xml.contains("<genre>sf_history</genre>"));
    }

    @Test
    public void editAfterPrivateAdoptUpdatesInPlaceWithoutSecondCopy() throws Exception {
        File epub = new File(folder.getRoot(), "adopted.epub");
        TestFixtures.writeEpub(epub, TestFixtures.OPF_FULL);
        long id = addBookToCatalog(epub, "EPUB", "First", "Author", null);

        EditMetaActivity.directoryWritableForTest = Boolean.FALSE;
        EditMetaActivity first = openEditor(id);
        setGenreFields(first, "Mystery");
        ((Button) first.findViewById(R.id.btn_save)).performClick();
        awaitCatalogGenres(db, id, "mystery");
        shadowOf(Looper.getMainLooper()).idle();

        Book mid = db.getById(id);
        String copyPath = mid.path;
        String originalAdopted = adoptedFromOf(id);
        EditMetaActivity.directoryWritableForTest = null;

        EditMetaActivity second = openEditor(id);
        ((EditText) second.findViewById(R.id.edit_title)).setText("Second Title");
        ((Button) second.findViewById(R.id.btn_save)).performClick();
        awaitCatalogTitle(db, id, "Second Title");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, countBookFilesIn(booksDir()));
        Book after = db.getById(id);
        assertEquals(copyPath, after.path);
        assertEquals(originalAdopted, adoptedFromOf(id));
        assertEquals(id, after.id);
        assertTrue(new File(epub.getAbsolutePath()).exists());
        File stemId = new File(booksDir(), "adopted-" + id + ".epub");
        assertFalse(stemId.exists());
    }

    @Test
    public void editWhenBooksDirIsNotADirectoryLeavesNoPartialFile() throws Exception {
        File fb2 = new File(folder.getRoot(), "fail-copy.fb2");
        TestFixtures.writeText(fb2, TestFixtures.FB2_FULL);
        byte[] before = TestFixtures.readAll(fb2);
        long id = addBookToCatalog(fb2, "FB2", "Old", "Ivan Ivanovich Petrov", null);

        File books = booksDir();
        if (books != null) {
            deleteRecursive(books);
        }
        File blocker = books;
        TestFixtures.writeText(blocker, "not a directory\n");

        EditMetaActivity.directoryWritableForTest = Boolean.FALSE;
        EditMetaActivity a = openEditor(id);
        ((EditText) a.findViewById(R.id.edit_title)).setText("New Title");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogTitle(db, id, "New Title");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(
                "Could not write into the file. Changes saved to the library only.",
                ShadowToast.getTextOfLatestToast());
        Book got = db.getById(id);
        assertEquals(fb2.getAbsolutePath(), got.path);
        assertNull(adoptedFromOf(id));
        assertEquals(id, got.id);
        assertTrue(Arrays.equals(before, TestFixtures.readAll(fb2)));
        assertFalse(new File(folder.getRoot(), "fail-copy.fb2.copying").exists());
    }

    @Test
    public void editAdoptsWithSuffixedNameWhenForeignFileOccupiesTarget() throws Exception {
        File foreign = new File(booksDir(), "book.fb2");
        TestFixtures.writeText(foreign, TestFixtures.FB2_FULL);
        long foreignId = addBookToCatalog(foreign, "FB2", "Foreign", "A", null);

        File original = new File(folder.getRoot(), "book.fb2");
        TestFixtures.writeText(original, TestFixtures.FB2_FULL);
        long id = addBookToCatalog(original, "FB2", "Mine", "B", null);

        EditMetaActivity.directoryWritableForTest = Boolean.FALSE;
        EditMetaActivity a = openEditor(id);
        setGenreFields(a, "Mystery");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogGenres(db, id, "mystery");
        shadowOf(Looper.getMainLooper()).idle();

        File expectedCopy = new File(booksDir(), "book-" + id + ".fb2");
        Book got = db.getById(id);
        assertEquals(expectedCopy.getAbsolutePath(), got.path);
        assertEquals(original.getAbsolutePath(), adoptedFromOf(id));
        assertEquals(foreignId, db.getIdForPath(foreign.getAbsolutePath()));
        assertEquals("Foreign", db.getById(foreignId).title);
    }

    @Test
    public void pdfEditNonWritableCatalogDoesNotCopy() throws Exception {
        File pdf = new File(folder.getRoot(), "read-only.pdf");
        byte[] before = "%PDF-fake".getBytes("UTF-8");
        TestFixtures.writeBytes(pdf, before);
        long id = addBookToCatalog(pdf, "PDF", "Old", "Author", null);

        EditMetaActivity.directoryWritableForTest = Boolean.FALSE;
        EditMetaActivity a = openEditor(id);
        ((EditText) a.findViewById(R.id.edit_title)).setText("New PDF");
        ((Button) a.findViewById(R.id.btn_save)).performClick();

        awaitCatalogTitle(db, id, "New PDF");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Saved", ShadowToast.getTextOfLatestToast());
        assertEquals(pdf.getAbsolutePath(), db.getById(id).path);
        assertEquals(0, countBookFilesIn(booksDir()));
        assertTrue(Arrays.equals(before, TestFixtures.readAll(pdf)));
    }

    @Test
    public void directoryAcceptsWritableTempDirReturnsTrueAndLeavesNoProbe() throws Exception {
        File dir = folder.newFolder("probe-ok");
        assertTrue(EditMetaActivity.directoryAcceptsNewFile(dir));
        assertFalse(dirHasProbeFile(dir));
    }

    @Test
    public void directoryAcceptsNewFileRejectsFileAndNull() throws Exception {
        File file = new File(folder.getRoot(), "not-a-dir.txt");
        TestFixtures.writeText(file, "x\n");
        assertFalse(EditMetaActivity.directoryAcceptsNewFile(file));
        assertFalse(EditMetaActivity.directoryAcceptsNewFile(null));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    deleteRecursive(kids[i]);
                }
            }
        }
        f.delete();
    }

    private static void assertGenres(String a, String b, ArrayList<String> actual) {
        assertGenres(new String[]{a, b}, actual);
    }

    private static void assertGenres(String a, ArrayList<String> actual) {
        assertGenres(new String[]{a}, actual);
    }

    private static void assertGenres(String[] expected, ArrayList<String> actual) {
        assertNotNull(actual);
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual.get(i));
        }
    }

    /** Records whether the observer fired (for one registered URI). */
    private static final class FiringObserver extends ContentObserver {
        private boolean fired;
        FiringObserver() { super(new Handler(Looper.getMainLooper())); }
        @Override public void onChange(boolean selfChange, Uri uri) { fired = true; }
    }
}
