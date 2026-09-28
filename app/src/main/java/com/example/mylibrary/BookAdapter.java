package com.example.mylibrary;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.mylibrary.meta.CoverExtractor;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.util.CoverLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds a list of {@link Book}s to rows in two view modes:
 * <ul>
 *   <li>{@link #MODE_LIST} — a compact single-line row ({@code item_book}).</li>
 *   <li>{@link #MODE_GRID} — a tile with a cover preview ({@code item_book_grid});
 *       the letter badge is shown when the format has no extractable cover.</li>
 * </ul>
 * When switching modes, views whose layout type no longer matches are re-inflated.
 */
public class BookAdapter extends BaseAdapter {

    public static final int MODE_LIST = 0;
    public static final int MODE_GRID = 1;

    private final LayoutInflater inflater;
    private final List<Book> books = new ArrayList<Book>();
    private int mode = MODE_LIST;

    public BookAdapter(Context context) {
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

    public void setBooks(List<Book> list) {
        books.clear();
        if (list != null) books.addAll(list);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return books.size(); }
    @Override public Book getItem(int position) { return books.get(position); }
    @Override public long getItemId(int position) { return books.get(position).id; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        // Re-inflate when the recycled view was built for the other mode.
        if (v == null || !Integer.valueOf(mode).equals(v.getTag(R.id.view_mode))) {
            v = inflater.inflate(mode == MODE_GRID ? R.layout.item_book_grid : R.layout.item_book,
                    parent, false);
            v.setTag(R.id.view_mode, mode);
        }
        Book b = getItem(position);
        if (mode == MODE_GRID) {
            bindGrid(v, b);
        } else {
            bindList(v, b);
        }
        return v;
    }

    // ------------------------------------------------------------------
    // list mode
    // ------------------------------------------------------------------

    private void bindList(View v, Book b) {
        TextView init = (TextView) v.findViewById(R.id.book_initial);
        TextView title = (TextView) v.findViewById(R.id.book_title);
        TextView sub = (TextView) v.findViewById(R.id.book_subtitle);
        TextView fmt = (TextView) v.findViewById(R.id.book_format);

        init.setText(b.initial());
        title.setText(titleOf(b));
        String subText = (b.author != null && b.author.length() > 0) ? b.author : b.path;
        sub.setText(subText);
        fmt.setText(b.displayFormat());
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
        if (canCover) {
            // May already be showing a cached cover from a previous bind.
            cover.setVisibility(View.VISIBLE);
            initial.setVisibility(View.INVISIBLE); // badge shown only until a cover loads
            CoverLoader.load(b, cover, initial);
        } else {
            cover.setVisibility(View.GONE);
            initial.setVisibility(View.VISIBLE);
        }
    }

    private static String titleOf(Book b) {
        return (b.title == null || b.title.length() == 0) ? "(untitled)" : b.title;
    }
}
