package com.example.mylibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.example.mylibrary.model.Book;
import org.robolectric.fakes.BaseCursor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Tests for {@link BookAdapter} (now cursor-driven): binding in both view modes and
 * — the part that broke in the past — re-inflating recycled views whose
 * {@code view_mode} tag no longer matches after a list/grid toggle. Rows are fed
 * through a minimal in-memory cursor over the books-table columns.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class BookAdapterTest {

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

    /** A minimal in-memory cursor exposing the books-table columns BookAdapter reads. */
    private static final class BooksCursor extends BaseCursor {
        private static final String[] COLUMNS = {
                "_id", "path", "format", "title", "author", "publisher", "description",
                "series", "size_bytes", "exported", "meta_done", "user_edited"
        };
        private final Book[] rows;
        private int pos = -1;

        BooksCursor(Book... rows) {
            this.rows = rows;
        }

        @Override public int getCount() { return rows.length; }
        @Override public int getPosition() { return pos; }
        @Override public boolean moveToPosition(int position) {
            if (position < -1 || position >= rows.length) return false;
            pos = position;
            return true;
        }
        @Override public boolean moveToFirst() { return moveToPosition(0); }
        @Override public boolean moveToNext() { return moveToPosition(pos + 1); }
        @Override public boolean moveToPrevious() { return moveToPosition(pos - 1); }
        @Override public boolean isBeforeFirst() { return pos < 0; }
        @Override public boolean isAfterLast() { return pos >= rows.length; }
        @Override public boolean isFirst() { return pos == 0; }
        @Override public boolean isLast() { return pos == rows.length - 1; }
        @Override public int getColumnIndex(String columnName) {
            if (columnName == null) return -1;
            for (int i = 0; i < COLUMNS.length; i++) {
                if (COLUMNS[i].equals(columnName)) return i;
            }
            return -1;
        }

        /** Robolectric's BaseCursor leaves this unimplemented; CursorAdapter's
         *  constructor calls it, so provide a real one. */
        @Override public int getColumnIndexOrThrow(String columnName) {
            int i = getColumnIndex(columnName);
            if (i < 0) throw new IllegalArgumentException("Unknown column " + columnName);
            return i;
        }
        // BaseCursor throws UnsupportedOperationException for observer registration
        // (CursorAdapter's init/swapCursor call it); a fake cursor needs no observers.
        @Override public void registerContentObserver(android.database.ContentObserver o) {}
        @Override public void unregisterContentObserver(android.database.ContentObserver o) {}
        @Override public String getString(int column) {
            Book b = rows[pos];
            switch (column) {
                case 0: return String.valueOf(b.id);
                case 1: return b.path;
                case 2: return b.format;
                case 3: return b.title;
                case 4: return b.author;
                case 5: return b.publisher;
                case 6: return b.description;
                case 7: return b.series;
                case 8: return String.valueOf(b.sizeBytes);
                case 9: return b.exported ? "1" : "0";
                case 10: return b.metaDone ? "1" : "0";
                case 11: return b.userEdited ? "1" : "0";
                default: return null;
            }
        }
        @Override public int getInt(int column) {
            String s = getString(column);
            return s == null ? 0 : Integer.parseInt(s);
        }
        @Override public long getLong(int column) {
            String s = getString(column);
            return s == null ? 0L : Long.parseLong(s);
        }
        @Override public void registerDataSetObserver(android.database.DataSetObserver o) {}
        @Override public void unregisterDataSetObserver(android.database.DataSetObserver o) {}
        @Override public void close() {}
    }

    private static BookAdapter adapterWith(Context app, Book... books) {
        return adapterWith(app, null, books);
    }

    private static BookAdapter adapterWith(Context app, BookAdapter.BookMenuActions actions,
            Book... books) {
        // The adapter is built with a null cursor first (like the activity does
        // before its first loader load) and then driven by changeCursor() — the same
        // path the CursorLoader uses. This also keeps the test off CursorAdapter's
        // constructor auto-requery, which would demand a real cursor window.
        BookAdapter adapter = new BookAdapter(app, null, actions);
        adapter.changeCursor(new BooksCursor(books));
        return adapter;
    }

    /** A {@link BookAdapter.BookMenuActions} that records which pick arrived. */
    private static final class RecordingActions implements BookAdapter.BookMenuActions {
        Book details;
        Book edited;
        Book removed;
        @Override public void onDetails(Book book) { details = book; }
        @Override public void onEditMetadata(Book book) { edited = book; }
        @Override public void onRemove(Book book) { removed = book; }
    }

    @Test
    public void listModeBindsTitleFormatAndLetterBadge() {
        BookAdapter adapter = adapterWith(app, book("/sdcard/a.pdf", "PDF", "Alpha", "The Author"));

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
        BookAdapter adapter = adapterWith(app,
                book("/sdcard/b.pdf", "PDF", "Beta", null),
                book("/sdcard/c.epub", "EPUB", null, "Ghost"));

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
        BookAdapter adapter = adapterWith(app, book("/sdcard/a.pdf", "PDF", "Alpha", null));
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
        BookAdapter adapter = adapterWith(app, book("/sdcard/a.pdf", "PDF", "Alpha", null));
        adapter.setMode(BookAdapter.MODE_GRID);

        View tile = adapter.getView(0, null, new FrameLayout(app));

        ImageView cover = tile.findViewById(R.id.book_grid_cover);
        TextView initial = tile.findViewById(R.id.book_grid_initial);
        assertEquals(View.GONE, cover.getVisibility());
        assertEquals(View.VISIBLE, initial.getVisibility());
        assertEquals("A", initial.getText().toString());
    }

    @Test
    public void changeCursorWithNullEmptiesTheAdapter() {
        BookAdapter adapter = adapterWith(app, book("/sdcard/cover.epub", "EPUB", "Covered", null));
        assertEquals(1, adapter.getCount());

        adapter.changeCursor(null);
        assertEquals(0, adapter.getCount());
    }

    @Test
    public void getItemMapsCursorPositionToBook() {
        Book a = book("/sdcard/a.pdf", "PDF", "Alpha", null);
        Book b = book("/sdcard/b.epub", "EPUB", "Beta", "Ghost");
        BookAdapter adapter = adapterWith(app, a, b);

        assertEquals(1, adapter.getItemId(0));
        Book got = adapter.getItem(1);
        assertNotNull(got);
        assertEquals("/sdcard/b.epub", got.path);
        assertEquals("Beta", got.title);
    }

    // ------------------------------------------------------------------
    // per-book kebab (Details / Edit metadata / Remove)
    // ------------------------------------------------------------------

    @Test
    public void kebabIsPresentAndClickableInListRowsAndGridTiles() {
        BookAdapter adapter = adapterWith(app, new RecordingActions(),
                book("/sdcard/a.pdf", "PDF", "Alpha", null));
        FrameLayout parent = new FrameLayout(app);

        View listRow = adapter.getView(0, null, parent);
        View kebabInList = listRow.findViewById(R.id.book_more);
        assertNotNull("the list row must carry the kebab button", kebabInList);
        assertTrue("the kebab must be clickable", kebabInList.isClickable());

        adapter.setMode(BookAdapter.MODE_GRID);
        View tile = adapter.getView(0, listRow, parent);
        View kebabInTile = tile.findViewById(R.id.book_more);
        assertNotNull("the grid tile must carry the kebab button", kebabInTile);
        assertTrue("the kebab must be clickable", kebabInTile.isClickable());
    }

    @Test
    public void kebabClickOpensThePerBookMenu() {
        BookAdapter adapter = adapterWith(app, new RecordingActions(),
                book("/sdcard/a.pdf", "PDF", "Alpha", null));
        View row = adapter.getView(0, null, new FrameLayout(app));
        View kebab = row.findViewById(R.id.book_more);

        assertNull("no menu shown yet", adapter.getLastPopupMenu());
        kebab.performClick();

        assertNotNull("the kebab click must open the menu", adapter.getLastPopupMenu());
    }

    @Test
    public void kebabMenuOffersDetailsEditAndRemoveAndDispatchesToActions() {
        Book b = book("/sdcard/a.pdf", "PDF", "Alpha", null);
        RecordingActions rec = new RecordingActions();
        BookAdapter adapter = adapterWith(app, rec, b);
        View row = adapter.getView(0, null, new FrameLayout(app));
        View kebab = row.findViewById(R.id.book_more);

        PopupMenu menu = adapter.showBookMenu(kebab, b);

        assertEquals("Details",
                menu.getMenu().findItem(R.id.book_menu_details).getTitle().toString());
        assertEquals("Edit metadata",
                menu.getMenu().findItem(R.id.book_menu_edit).getTitle().toString());
        assertEquals("Remove",
                menu.getMenu().findItem(R.id.book_menu_remove).getTitle().toString());

        // The framework MenuItem has no public click method — drive the pick through
        // Menu.performIdentifierAction, the same path a popup item tap goes through.
        menu.getMenu().performIdentifierAction(R.id.book_menu_details, 0);
        menu.getMenu().performIdentifierAction(R.id.book_menu_edit, 0);
        menu.getMenu().performIdentifierAction(R.id.book_menu_remove, 0);

        assertEquals(b, rec.details);
        assertEquals(b, rec.edited);
        assertEquals(b, rec.removed);
    }

    @Test
    public void kebabMenuPicksAreDroppedSilentlyWithoutAnActionsListener() {
        // The 2-arg constructor (no actions wired) must not crash on a menu pick —
        // the pick is simply dropped, not NPE'd.
        Book b = book("/sdcard/a.pdf", "PDF", "Alpha", null);
        BookAdapter adapter = new BookAdapter(app, null);
        adapter.changeCursor(new BooksCursor(b));
        View row = adapter.getView(0, null, new FrameLayout(app));
        View kebab = row.findViewById(R.id.book_more);

        adapter.showBookMenu(kebab, b)
                .getMenu().performIdentifierAction(R.id.book_menu_remove, 0);
    }
}
