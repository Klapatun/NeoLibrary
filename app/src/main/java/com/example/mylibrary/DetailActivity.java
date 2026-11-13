package com.example.mylibrary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.Catalog;
import com.example.mylibrary.meta.CoverExtractor;
import com.example.mylibrary.meta.MetaEnricher;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.util.CoverLoader;
import com.example.mylibrary.util.FileLocator;
import com.example.mylibrary.util.LocateResult;
import com.example.mylibrary.util.Openers;

import java.lang.ref.WeakReference;

/**
 * Shows a single book's details and offers to open it in Neo Reader.
 *
 * <p>The Edit metadata button on this screen opens {@link EditMetaActivity} with
 * the current book ({@code EXTRA_BOOK}). The same action stays in the per-book
 * kebab menu of the main screen ({@code BookAdapter} popup). Removing the book
 * from the library stays in that kebab.</p>
 */
public class DetailActivity extends Activity {

    public static final String EXTRA_BOOK_ID = "extra_book_id";

    private BookDatabase db;
    private Catalog catalog;
    private Book book;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        // The app context, not the Activity's: the database is process-wide state,
        // and the in-flight enrichment task holds it — it must not pin the Activity.
        db = new BookDatabase(getApplicationContext());
        catalog = new Catalog(getApplicationContext(), db);
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
            new EnrichTask(this, book).execute();
        }

        ((Button) findViewById(R.id.btn_edit_meta)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(DetailActivity.this, EditMetaActivity.class);
                i.putExtra(EditMetaActivity.EXTRA_BOOK, book);
                startActivity(i);
            }
        });

        ((Button) findViewById(R.id.btn_open)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBook(); }
        });
    }

    /** Re-read the catalog row after the editor (or any other pause). A user edit
     *  of EPUB/FB2 can change {@code files.path}. No second {@link EnrichTask}:
     *  the fast path starts only from {@link #onCreate}. Cover and the letter
     *  badge are left as {@code onCreate} set them — {@link #showBook} does not
     *  write them. */
    @Override
    protected void onResume() {
        super.onResume();
        if (book == null) return;
        Book fresh = db.getById(book.id);
        if (fresh == null) {
            finish();
            return;
        }
        book = fresh;
        showBook(book);
    }

    private void openBook() {
        // The locator decides whether the bytes are ready. Openers still owns
        // MIME and the viewer. markRead stays here, and only after onLaunched.
        // Absent, downloading, and failed do not start a viewer and do not
        // start a download.
        LocateResult located = FileLocator.locateWork(db, book.id);
        if (located.status == LocateResult.DOWNLOADING) {
            Toast.makeText(this, R.string.file_downloading, Toast.LENGTH_LONG).show();
            return;
        }
        if (located.status == LocateResult.FAILED) {
            Toast.makeText(this, R.string.file_download_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (located.status != LocateResult.READY || located.file == null) {
            Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_LONG).show();
            return;
        }
        Openers.openFile(located.file, this, new Openers.OpenOutcome() {
            @Override public void onLaunched() {
                catalog.markRead(book.id);
            }
            @Override public void onMissingFile() {
                Toast.makeText(DetailActivity.this, R.string.file_not_found,
                        Toast.LENGTH_LONG).show();
            }
            @Override public void onNoViewer() {
                showNoViewer();
            }
        });
    }

    private void showNoViewer() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.no_viewer_title)
                .setMessage(R.string.no_viewer_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /** Refreshes the on-screen title, author and the "other" block from a book.
     *  Genre is always shown: the parsed value, or an em dash when metadata
     *  parsing found none. */
    private void showBook(Book b) {
        ((TextView) findViewById(R.id.detail_title)).setText(nz(b.title));
        ((TextView) findViewById(R.id.detail_author)).setText(nz(b.author));

        StringBuilder other = new StringBuilder();
        if (b.format != null) other.append("Format: ").append(b.format).append("\n");
        other.append("Genre: ").append(formatGenres(b.genres)).append("\n");
        if (b.publisher != null && b.publisher.length() > 0)
            other.append("Publisher: ").append(b.publisher).append("\n");
        other.append("Size: ").append(humanSize(b.sizeBytes)).append("\n");
        if (b.description != null && b.description.length() > 0)
            other.append("\n").append(b.description);
        ((TextView) findViewById(R.id.detail_other)).setText(other.toString());
    }

    /** The fast-path enrichment task. A STATIC nested class on purpose: an anonymous
     *  inner class would carry a synthetic strong reference to the Activity (javac
     *  emits it for every anonymous class inside an instance method, used or not),
     *  and the parse can run up to the enricher's per-file budget (2 minutes).
     *  Holds the app context for the work and the Activity weakly for the update. */
    private static final class EnrichTask extends AsyncTask<Void, Void, Book> {
        private final Context appContext;
        private final BookDatabase db;
        private final Book target;
        private final WeakReference<DetailActivity> self;

        EnrichTask(DetailActivity host, Book target) {
            this.appContext = host.getApplicationContext();
            this.db = host.db; // safe: it holds the app context
            this.target = target;
            this.self = new WeakReference<DetailActivity>(host);
        }

        @Override protected Book doInBackground(Void... v) {
            MetaEnricher.enrichOne(appContext, db, target);
            return db.getById(target.id);
        }

        @Override protected void onPostExecute(Book fresh) {
            DetailActivity a = self.get();
            if (a == null || a.isFinishing() || fresh == null) return;
            a.book = fresh;
            a.showBook(fresh);
        }
    }

    private static String nz(String s) {
        return (s == null || s.length() == 0) ? "—" : s;
    }

    private static String formatGenres(java.util.ArrayList<String> genres) {
        if (genres == null || genres.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < genres.size(); i++) {
            if (i > 0) sb.append(", ");
            String g = genres.get(i);
            if (g != null) sb.append(g);
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
