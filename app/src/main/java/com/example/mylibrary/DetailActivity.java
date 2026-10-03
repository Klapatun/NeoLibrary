package com.example.mylibrary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.meta.CoverExtractor;
import com.example.mylibrary.meta.MetaEnricher;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.util.CoverLoader;
import com.example.mylibrary.util.Openers;

import java.io.File;

/**
 * Shows a single book's details and offers to open it in Neo Reader.
 *
 * <p>Editing the metadata and removing the book from the library were moved to the
 * per-book kebab menu of the main screen ({@code BookAdapter} popup) — one entry
 * point from the list or the tile, without the extra hop through this screen.</p>
 */
public class DetailActivity extends Activity {

    public static final String EXTRA_BOOK_ID = "extra_book_id";

    private BookDatabase db;
    private Book book;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        db = new BookDatabase(this);
        long id = getIntent().getLongExtra(EXTRA_BOOK_ID, -1);
        book = db.getById(id);
        if (book == null) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.detail_file)).setText(book.path);
        showBook(book);

        // Full-format cover (no circular crop) with the letter badge as fallback; same
        // mechanism as the list rows, square variant.
        TextView initial = (TextView) findViewById(R.id.detail_initial);
        ImageView cover = (ImageView) findViewById(R.id.detail_cover);
        initial.setText(book.initial());
        if (CoverExtractor.canHaveCover(book.format)) {
            cover.setVisibility(View.VISIBLE);
            initial.setVisibility(View.INVISIBLE);
            CoverLoader.load(book, cover, initial, CoverLoader.Shape.SQUARE);
        } else {
            cover.setVisibility(View.GONE);
            initial.setVisibility(View.VISIBLE);
        }

        if (!book.metaDone) {
            // Fast path: the background stage (running in MainActivity) may not have
            // reached this book yet — extract it now on a background thread so the
            // user sees real values instead of the file-name placeholder. The bulk
            // worker and this call may parse the same file in parallel: both are
            // read-only, and the DB update is idempotent.
            final Book target = book;
            new AsyncTask<Void, Void, Book>() {
                @Override protected Book doInBackground(Void... v) {
                    MetaEnricher.enrichOne(DetailActivity.this, db, target);
                    return db.getById(target.id);
                }
                @Override protected void onPostExecute(Book fresh) {
                    if (isFinishing() || fresh == null) return;
                    book = fresh;
                    showBook(fresh);
                }
            }.execute();
        }

        ((Button) findViewById(R.id.btn_open)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBook(); }
        });
    }

    private void openBook() {
        File f = new File(book.path);
        if (!f.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = Openers.openFile(f);
            startActivity(i);
            db.markRead(book.id);
            // The list is cursor-driven: announce the new last_read so the
            // "recently read" view (and the list, if it is on screen) re-queries.
            BookProvider.notifyChangeAll(this);
        } catch (Exception e) {
            showNoViewer();
        }
    }

    private void showNoViewer() {
        new AlertDialog.Builder(this)
                .setTitle("No reader found")
                .setMessage("Neo Reader 3.0 does not appear to be installed, or it cannot open this file type.")
                .setPositiveButton("OK", null)
                .show();
    }

    /** Refreshes the on-screen title, author and the "other" block from a book. */
    private void showBook(Book b) {
        ((TextView) findViewById(R.id.detail_title)).setText(nz(b.title));
        ((TextView) findViewById(R.id.detail_author)).setText(nz(b.author));

        StringBuilder other = new StringBuilder();
        if (b.format != null) other.append("Format: ").append(b.format).append("\n");
        if (b.publisher != null && b.publisher.length() > 0)
            other.append("Publisher: ").append(b.publisher).append("\n");
        other.append("Size: ").append(humanSize(b.sizeBytes)).append("\n");
        if (b.description != null && b.description.length() > 0)
            other.append("\n").append(b.description);
        ((TextView) findViewById(R.id.detail_other)).setText(other.toString());
    }

    private static String nz(String s) {
        return (s == null || s.length() == 0) ? "—" : s;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
