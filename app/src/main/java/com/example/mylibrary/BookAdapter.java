package com.example.mylibrary;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.example.mylibrary.model.Book;

import java.util.ArrayList;
import java.util.List;

/** Binds a list of {@link Book}s to {@code item_book} rows. */
public class BookAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Book> books = new ArrayList<Book>();

    public BookAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    public void setBooks(List<Book> list) {
        books.clear();
        if (list != null) books.addAll(list);
    }

    @Override public int getCount() { return books.size(); }
    @Override public Book getItem(int position) { return books.get(position); }
    @Override public long getItemId(int position) { return books.get(position).id; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.item_book, parent, false);
        }
        Book b = getItem(position);

        TextView init = (TextView) v.findViewById(R.id.book_initial);
        TextView title = (TextView) v.findViewById(R.id.book_title);
        TextView sub = (TextView) v.findViewById(R.id.book_subtitle);
        TextView fmt = (TextView) v.findViewById(R.id.book_format);

        init.setText(b.initial());
        title.setText(b.title == null || b.title.length() == 0 ? "(untitled)" : b.title);
        String subText = "";
        if (b.author != null && b.author.length() > 0) subText = b.author;
        else subText = b.path;
        sub.setText(subText);
        fmt.setText(b.displayFormat());
        return v;
    }
}
