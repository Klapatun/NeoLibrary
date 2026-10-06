package com.example.mylibrary;

import android.content.Context;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.meta.CoverExtractor;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.util.CoverLoader;

/**
 * Binds the books-table cursor to rows in two view modes:
 * <ul>
 *   <li>{@link #MODE_LIST} — a compact single-line row ({@code item_book}); the
 *       leading 48dp slot shows a small cover where the format can carry one, with
 *       the letter badge as fallback.</li>
 *   <li>{@link #MODE_GRID} — a tile with a cover preview ({@code item_book_grid});
 *       the letter badge is shown when the format has no extractable cover.</li>
 * </ul>
 * Both rows carry the same per-book kebab button ({@code book_more}): in the list it
 * sits at the row's right end after the format label, in the tile at the top-right
 * corner of the cover. Tapping it opens the {@code book_menu} popup (Details / Edit
 * metadata / Remove); the adapter only shows the menu — the picks are dispatched to
 * the {@link BookMenuActions} supplied by the screen (currently {@code MainActivity}),
 * which owns the navigation and the delete confirmation.
 *
 * <p>Extends {@link CursorAdapter} so the {@code CursorLoader} in the main screen can
 * drive it: the loader re-queries whenever the catalog changes (scan, background
 * enrichment, import) and the adapter re-binds the rows. When switching modes, views
 * whose layout type no longer matches are re-inflated.
 *
 * <p><b>Pagination:</b> {@link #setPagination} windows the cursor client-side — the
 * adapter still holds the WHOLE cursor (so every catalog change refreshes in place
 * and the screen's direct-rebind paths work unchanged) but serves only the current
 * page: {@link #getCount()} reports the page's rows and every visible position is
 * mapped onto the cursor row offset by the page start ({@link #rowForPosition}). A
 * cursor that shrinks below the current page (a remove, a filter, a clear) walks the
 * page back in {@link #changeCursor}. With pagination off (the class default) every
 * method behaves exactly as without this feature.
 */
public class BookAdapter extends CursorAdapter {

    public static final int MODE_LIST = 0;
    public static final int MODE_GRID = 1;

    /**
     * Receives the per-book kebab-menu picks. Kept separate from the adapter so the
     * row/tile bindings stay free of navigation and deletion logic (and so the menu
     * can be exercised in tests with a recording stub).
     */
    public interface BookMenuActions {
        /** "Details": show the book's detail page. */
        void onDetails(Book book);

        /** "Edit metadata": open the metadata editor for the book. */
        void onEditMetadata(Book book);

        /** "Remove": remove the book from the library (the file itself stays). */
        void onRemove(Book book);

        /**
         * A plain tap on the row/tile (not the kebab). The screen decides what a tap
         * does — currently the "Open this book?" confirmation on the main screen; the
         * detail page is reachable through the kebab only.
         */
        void onBookTapped(Book book);
    }

    private final LayoutInflater inflater;
    private final BookMenuActions menuActions;
    private int mode = MODE_LIST;
    /** The popup last built by {@link #showBookMenu}; exposed for unit tests. */
    private PopupMenu lastPopupMenu;
    /** Rows per page; 0 = pagination off (the whole cursor is scrollable). */
    private int pageSize = 0;
    /** The page currently shown (0-based); always within 0..getPageCount()-1. */
    private int page = 0;

    public BookAdapter(Context context, Cursor c) {
        this(context, c, (BookMenuActions) null);
    }

    public BookAdapter(Context context, Cursor c, BookMenuActions menuActions) {
        super(context, c, 0);
        this.menuActions = menuActions;
        inflater = LayoutInflater.from(context);
    }

    public void setMode(int mode) {
        if (this.mode != mode) {
            this.mode = mode;
            notifyDataSetChanged();
        }
    }

    public int getMode() {
        return mode;
    }

    // ------------------------------------------------------------------
    // pagination (a client-side window over the whole cursor)
    // ------------------------------------------------------------------

    /** The cursor row behind an adapter position: with pagination on, the
     *  position is offset by the current page's start; off, it maps 1:1. */
    private int rowForPosition(int position) {
        if (pageSize <= 0) return position;
        return page * pageSize + position;
    }

    /** How many pages the current cursor would hold at {@code size} rows per page
     *  (an empty cursor holds 0). */
    private int pageCountFor(int size) {
        if (size <= 0) return 0;
        Cursor c = getCursor();
        int total = (c == null) ? 0 : c.getCount();
        return (total + size - 1) / size;
    }

    /** Total pages at the CURRENT page size (0 when the cursor is empty, 1 when
     *  pagination is off but rows exist). */
    public int getPageCount() {
        if (pageSize <= 0) {
            Cursor c = getCursor();
            return (c == null || c.getCount() == 0) ? 0 : 1;
        }
        return pageCountFor(pageSize);
    }

    public boolean isPaginationEnabled() {
        return pageSize > 0;
    }

    /** The page currently shown (0-based); always within 0..getPageCount()-1. */
    public int getPage() {
        return page;
    }

    /** Shows page {@code p} (clamped to the last existing page). No-op while
     *  pagination is off; a real change notifies the attached views. */
    public void setPage(int p) {
        if (pageSize > 0) setPagination(true, pageSize, p);
    }

    /** Turns pagination on/off over the current cursor. On: the adapter serves
     *  {@code pageSize} rows per page, starting at {@code page} (clamped to the
     *  last existing page — a cursor that shrank under the page walks it back).
     *  Off: the whole cursor is served. A real change notifies the attached views. */
    public void setPagination(boolean enabled, int pageSize, int page) {
        int size = enabled ? Math.max(1, pageSize) : 0;
        int p = 0;
        if (size > 0) {
            int last = pageCountFor(size) - 1;
            p = (last <= 0) ? 0 : Math.max(0, Math.min(page, last));
        }
        if (size != this.pageSize || p != this.page) {
            this.pageSize = size;
            this.page = p;
            notifyDataSetChanged();
        }
    }

    @Override
    public void changeCursor(Cursor c) {
        // The new cursor may hold fewer rows than the current page spans (a remove,
        // a filter, a clear): walk the page back to the last existing one BEFORE the
        // swap — super.changeCursor fires the dataset callbacks (on API 19 a
        // non-null swap goes through swapCursor's notifyDataSetChanged; a null
        // swap fires notifyDataSetInvalidated — the screen's pager bar listens to
        // both) with mCursor already the new cursor, so that refresh must see the
        // clamped page, not the stale one.
        if (pageSize > 0 && c != null) {
            int last = (c.getCount() + pageSize - 1) / pageSize - 1;
            page = (last <= 0) ? 0 : Math.min(page, last);
        }
        super.changeCursor(c);
    }

    /** Serves only the current page of the cursor (the whole cursor when
     *  pagination is off). */
    @Override
    public int getCount() {
        Cursor c = getCursor();
        if (c == null) return 0;
        int total = c.getCount();
        if (pageSize <= 0) return total;
        int start = page * pageSize;
        return (start >= total) ? 0 : Math.min(pageSize, total - start);
    }

    /** The book at the given (visible) position (for row-click handling), or null
     *  if the cursor moved on. */
    public Book getItem(int position) {
        Cursor c = getCursor();
        if (c == null || !c.moveToPosition(rowForPosition(position))) return null;
        return BookDatabase.fromCursor(c);
    }

    @Override
    public long getItemId(int position) {
        Cursor c = getCursor();
        if (c == null || !c.moveToPosition(rowForPosition(position))) return -1;
        int idx = c.getColumnIndexOrThrow("_id");
        return c.getLong(idx);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Cursor cursor = getCursor();
        if (cursor == null) return null;
        // Position the SHARED cursor on the row to bind BEFORE the newView()/
        // bindView() dance: with pagination on, an adapter position maps to a
        // cursor row offset by the page start, and the base class would position
        // the cursor on the raw (windowed) position instead — binding the wrong
        // book.
        int row = rowForPosition(position);
        if (row >= cursor.getCount()) return null;
        cursor.moveToPosition(row);
        // A view recycled from the other mode has a different layout: drop it so
        // newView() builds a fresh one for the current mode.
        if (convertView != null && !Integer.valueOf(mode).equals(convertView.getTag(R.id.view_mode))) {
            convertView = null;
        }
        View v = (convertView == null)
                ? newView(parent.getContext(), cursor, parent)
                : convertView;
        bindView(v, parent.getContext(), cursor);
        return v;
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        View v = inflater.inflate(mode == MODE_GRID ? R.layout.item_book_grid : R.layout.item_book,
                parent, false);
        v.setTag(R.id.view_mode, mode);
        return v;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        // The framework positions the shared cursor on the row to bind before calling
        // here (and never does so for an empty data set). Guard only against null.
        if (cursor == null) return;
        Book b = BookDatabase.fromCursor(cursor);
        if (mode == MODE_GRID) {
            bindGrid(view, b);
        } else {
            bindList(view, b);
        }
        bindKebab(view, b);
        bindRowClick(view, b);
    }

    /**
     * Row-level click. Attached to the itemView instead of relying on
     * ListView.onItemClick because the row contains a clickable ImageButton
     * (book_more): ListView refuses to fire onItemClick for any row that has
     * a clickable descendant, so the row click would be dead otherwise.
     *
     * <p>The kebab has its own OnClickListener, so a tap on it is consumed
     * there and never reaches this listener — the two clicks stay independent.</p>
     */
    private void bindRowClick(View view, final Book b) {
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View row) {
                if (menuActions != null) {
                    menuActions.onBookTapped(b);
                }
            }
        });
        // Optional: keep long-press as a no-op so the framework doesn't try to
        // open a context menu (harmless, but keeps behaviour predictable).
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View row) {
                return false;
            }
        });
    }

    // ------------------------------------------------------------------
    // per-book kebab (present in both row layouts)
    // ------------------------------------------------------------------

    /** Wires the kebab button ({@code book_more}) to the per-book menu. The button
     *  is a plain {@code View} on purpose: its only job here is the click wiring. */
    private void bindKebab(View v, Book b) {
        View kebab = v.findViewById(R.id.book_more);
        kebab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View anchor) {
                showBookMenu(anchor, b);
            }
        });
    }

    /**
     * Shows the per-book overflow menu (Details / Edit metadata / Remove) anchored to
     * the kebab button — exactly the same popup for the list row and the grid tile.
     * Package-private and returns the menu so unit tests can pick items without
     * driving the popup window.
     */
    PopupMenu showBookMenu(View anchor, Book b) {
        final PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        // The framework Menu interface has no inflate() of its own — go through
        // MenuInflater (the plain-framework equivalent of the AppCompat one-liner).
        new MenuInflater(anchor.getContext()).inflate(R.menu.book_menu, menu.getMenu());
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                if (menuActions != null) {
                    int id = item.getItemId();
                    if (id == R.id.book_menu_details) {
                        menuActions.onDetails(b);
                    } else if (id == R.id.book_menu_edit) {
                        menuActions.onEditMetadata(b);
                    } else if (id == R.id.book_menu_remove) {
                        menuActions.onRemove(b);
                    }
                }
                menu.dismiss();
                return true;
            }
        });
        lastPopupMenu = menu;
        menu.show();
        return menu;
    }

    /** The popup last built by {@link #showBookMenu} (for unit tests). */
    PopupMenu getLastPopupMenu() {
        return lastPopupMenu;
    }

    // ------------------------------------------------------------------
    // list mode
    // ------------------------------------------------------------------

    private void bindList(View v, Book b) {
        TextView init = (TextView) v.findViewById(R.id.book_initial);
        TextView title = (TextView) v.findViewById(R.id.book_title);
        TextView sub = (TextView) v.findViewById(R.id.book_subtitle);
        TextView fmt = (TextView) v.findViewById(R.id.book_format);
        ImageView cover = (ImageView) v.findViewById(R.id.book_cover);

        init.setText(b.initial());
        title.setText(titleOf(b));
        String subText = (b.author != null && b.author.length() > 0) ? b.author : b.path;
        sub.setText(subText);
        fmt.setText(b.displayFormat());

        // Same mechanism as the grid: for formats that can carry a cover (EPUB/FB2) the
        // letter badge is replaced by the 48dp cover in its own slot; the badge shows
        // again automatically if no cover could be extracted.
        if (CoverExtractor.canHaveCover(b.format)) {
            cover.setTag(R.id.cover_tag, null);
            cover.setVisibility(View.VISIBLE);
            init.setVisibility(View.INVISIBLE);
            CoverLoader.load(b, cover, init, CoverLoader.Shape.CIRCLE); // round: match the circular badge
        } else {
            cover.setImageBitmap(null);
            cover.setVisibility(View.GONE);
            init.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------
    // grid mode
    // ------------------------------------------------------------------

    private void bindGrid(View v, Book b) {
        ImageView cover = (ImageView) v.findViewById(R.id.book_grid_cover);
        TextView initial = (TextView) v.findViewById(R.id.book_grid_initial);
        TextView title = (TextView) v.findViewById(R.id.book_grid_title);
        TextView fmt = (TextView) v.findViewById(R.id.book_grid_format);

        title.setText(titleOf(b));
        fmt.setText(b.displayFormat());

        boolean canCover = CoverExtractor.canHaveCover(b.format);
        initial.setText(b.initial());
        // Invalidate any cover tag first so a stale async load from a previous format
        // can never paint over this tile (its onPostExecute checks the tag == matches
        // the fresh one only). This is what prevented covers leaking across formats.
        cover.setTag(R.id.cover_tag, null);
        if (canCover) {
            // May already be showing a cached cover from a previous bind.
            cover.setVisibility(View.VISIBLE);
            initial.setVisibility(View.INVISIBLE); // badge shown only until a cover loads
            CoverLoader.load(b, cover, initial, CoverLoader.Shape.ROUNDED_RECT);
        } else {
            cover.setImageBitmap(null);
            cover.setVisibility(View.GONE);
            initial.setVisibility(View.VISIBLE);
        }
    }

    /** The row/dialog display title; "(untitled)" when the book has none yet. */
    public static String titleOf(Book b) {
        return (b.title == null || b.title.length() == 0) ? "(untitled)" : b.title;
    }
}
