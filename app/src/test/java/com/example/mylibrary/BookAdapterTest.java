package com.example.mylibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

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

import java.io.File;
import java.util.Arrays;

/**
 * Tests for {@link BookAdapter}: binding in both view modes and — the part that
 * broke in the past — re-inflating recycled views whose {@code view_mode} tag no
 * longer matches after a list/grid toggle.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class BookAdapterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    private static Book book(String path, String format, String title, String author) {
        Book b = new Book();
        b.path = path;
        b.format = format;
        b.title = title;
        b.author = author;
        b.id = 1;
        return b;
    }

    @Test
    public void listModeBindsTitleFormatAndLetterBadge() {
        BookAdapter adapter = new BookAdapter(app);
        adapter.setBooks(Arrays.asList(book("/sdcard/a.pdf", "PDF", "Alpha", "The Author")));

        View v = adapter.getView(0, null, new FrameLayout(app));

        assertEquals("Alpha", ((TextView) v.findViewById(R.id.book_title)).getText().toString());
        assertEquals("PDF", ((TextView) v.findViewById(R.id.book_format)).getText().toString());
        assertEquals("The Author", ((TextView) v.findViewById(R.id.book_subtitle)).getText().toString());

        // PDF cannot carry a cover: badge visible with the initial, cover gone.
        TextView initial = v.findViewById(R.id.book_initial);
        ImageView cover = v.findViewById(R.id.book_cover);
        assertEquals("A", initial.getText().toString());
        assertEquals(View.VISIBLE, initial.getVisibility());
        assertEquals(View.GONE, cover.getVisibility());
    }

    @Test
    public void listModeSubtitleFallsBackToPathAndUntitledToPlaceholder() {
        BookAdapter adapter = new BookAdapter(app);
        Book noAuthor = book("/sdcard/b.pdf", "PDF", "Beta", null);
        Book untitled = book("/sdcard/c.epub", "EPUB", null, "Ghost");
        adapter.setBooks(Arrays.asList(noAuthor, untitled));

        View v0 = adapter.getView(0, null, new FrameLayout(app));
        assertEquals("/sdcard/b.pdf",
                ((TextView) v0.findViewById(R.id.book_subtitle)).getText().toString());

        // An EPUB row kicks off an async cover load; only assert the synchronous text.
        View v1 = adapter.getView(1, null, new FrameLayout(app));
        assertEquals("(untitled)",
                ((TextView) v1.findViewById(R.id.book_title)).getText().toString());
        assertEquals("EPUB", ((TextView) v1.findViewById(R.id.book_format)).getText().toString());
    }

    @Test
    public void gridModeReinflatesListRowAndBack() {
        BookAdapter adapter = new BookAdapter(app);
        adapter.setBooks(Arrays.asList(book("/sdcard/a.pdf", "PDF", "Alpha", null)));
        FrameLayout parent = new FrameLayout(app);

        View listRow = adapter.getView(0, null, parent);
        assertEquals(BookAdapter.MODE_LIST, adapter.getMode());

        adapter.setMode(BookAdapter.MODE_GRID);
        View tile = adapter.getView(0, listRow, parent);
        assertNotSame("a list row must be re-inflated for grid mode", listRow, tile);
        // The tile is one unit: cover slot + format label at its corner + title below.
        assertNotNull(tile.findViewById(R.id.book_grid_cover));
        assertEquals("Alpha",
                ((TextView) tile.findViewById(R.id.book_grid_title)).getText().toString());
        assertEquals("PDF",
                ((TextView) tile.findViewById(R.id.book_grid_format)).getText().toString());
        // A PDF carries no cover, so the letter badge shows.
        assertEquals("A",
                ((TextView) tile.findViewById(R.id.book_grid_initial)).getText().toString());

        adapter.setMode(BookAdapter.MODE_LIST);
        View row2 = adapter.getView(0, tile, parent);
        assertNotSame("a grid tile must be re-inflated for list mode", tile, row2);
        assertNotNull(row2.findViewById(R.id.book_title));
        assertEquals("Alpha",
                ((TextView) row2.findViewById(R.id.book_title)).getText().toString());
    }

    @Test
    public void gridModeShowsBadgeForFormatsWithoutCover() {
        BookAdapter adapter = new BookAdapter(app);
        adapter.setMode(BookAdapter.MODE_GRID);
        adapter.setBooks(Arrays.asList(book("/sdcard/a.pdf", "PDF", "Alpha", null)));

        View tile = adapter.getView(0, null, new FrameLayout(app));

        ImageView cover = tile.findViewById(R.id.book_grid_cover);
        TextView initial = tile.findViewById(R.id.book_grid_initial);
        assertEquals(View.GONE, cover.getVisibility());
        assertEquals(View.VISIBLE, initial.getVisibility());
        assertEquals("A", initial.getText().toString());
    }

    @Test
    public void setBooksWithNullEmptiesTheAdapter() throws Exception {
        File epub = new File(folder.getRoot(), "cover.epub");
        TestFixtures.writeEpub(epub, TestFixtures.OPF_WITH_COVER);

        BookAdapter adapter = new BookAdapter(app);
        adapter.setBooks(Arrays.asList(book(epub.getAbsolutePath(), "EPUB", "Covered", null)));
        assertEquals(1, adapter.getCount());

        adapter.setBooks(null);
        assertEquals(0, adapter.getCount());
    }
}
