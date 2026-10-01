package com.example.mylibrary;

import android.content.Context;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
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
 * Extends {@link CursorAdapter} so the {@code CursorLoader} in the main screen can
 * drive it: the loader re-queries whenever the catalog changes (scan, background
 * enrichment, import) and the adapter re-binds the rows. When switching modes, views
 * whose layout type no longer matches are re-inflated.
 */
public class BookAdapter extends CursorAdapter {

    public static final int MODE_LIST = 0;
    public static final int MODE_GRID = 1;

    private final LayoutInflater inflater;
    private int mode = MODE_LIST;

    public BookAdapter(Context context, Cursor c) {
        super(context, c);
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

    /** The book at the given position (for row-click handling), or null if the
     *  cursor moved on. */
    public Book getItem(int position) {
        Cursor c = getCursor();
        if (c == null || !c.moveToPosition(position)) return null;
        return BookDatabase.fromCursor(c);
    }

    @Override
    public long getItemId(int position) {
        Cursor c = getCursor();
        if (c == null || !c.moveToPosition(position)) return -1;
        int idx = c.getColumnIndexOrThrow("_id");
        return c.getLong(idx);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // A view recycled from the other mode has a different layout: drop it so
        // newView() builds a fresh one for the current mode, then let the framework
        // do its normal newView()/bindView() dance.
        if (convertView != null && !Integer.valueOf(mode).equals(convertView.getTag(R.id.view_mode))) {
            convertView = null;
        }
        return super.getView(position, convertView, parent);
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

    private static String titleOf(Book b) {
        return (b.title == null || b.title.length() == 0) ? "(untitled)" : b.title;
    }
}
