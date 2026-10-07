package com.example.mylibrary;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.meta.MetaWriter;
import com.example.mylibrary.model.Book;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * Lets the user edit the metadata of a book.
 *
 * <p>For EPUB and FB2 the edits are written back into the file itself (so they are
 * visible to Neo Reader and any other app). For every other format the edits are
 * persisted in our SQLite catalog only, since their metadata structures are not
 * safely rewritable here.</p>
 */
public class EditMetaActivity extends Activity {

    public static final String EXTRA_BOOK = "extra_book";

    private BookDatabase db;
    private Book book;

    private EditText etTitle, etAuthor, etPublisher, etDescription;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_meta);

        // The app context, not the Activity's: the database is process-wide state,
        // and the in-flight save task holds it — it must not pin the Activity.
        db = new BookDatabase(getApplicationContext());
        book = getIntent().getParcelableExtra(EXTRA_BOOK);
        if (book == null) {
            finish();
            return;
        }

        hint = (TextView) findViewById(R.id.edit_format_hint);
        etTitle = (EditText) findViewById(R.id.edit_title);
        etAuthor = (EditText) findViewById(R.id.edit_author);
        etPublisher = (EditText) findViewById(R.id.edit_publisher);
        etDescription = (EditText) findViewById(R.id.edit_description);

        etTitle.setText(book.title == null ? "" : book.title);
        etAuthor.setText(book.author == null ? "" : book.author);
        etPublisher.setText(book.publisher == null ? "" : book.publisher);
        etDescription.setText(book.description == null ? "" : book.description);

        String format = book.format == null ? "" : book.format.toUpperCase();
        boolean inlineEditable = "EPUB".equals(format) || "FB2".equals(format);
        if (inlineEditable) {
            hint.setText("Format: " + format + "  —  Edits will be saved into the file itself.");
        } else {
            hint.setText("Format: " + format + "  —  Edits are stored in the app library only.");
        }

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        ((Button) findViewById(R.id.btn_cancel)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    private void save() {
        final String title = etTitle.getText().toString().trim();
        final String author = etAuthor.getText().toString().trim();
        final String publisher = etPublisher.getText().toString().trim();
        final String description = etDescription.getText().toString().trim();

        final boolean inline = "EPUB".equals(book.format) || "FB2".equals(book.format);
        final File file = new File(book.path);
        final Book target = book;

        final ProgressDialog pd = ProgressDialog.show(this, null, "Saving metadata…", true, false);
        new SaveTask(this, target, file, inline,
                title, author, publisher, description, pd).execute();
    }

    /** The metadata save task. A STATIC nested class on purpose: an anonymous inner
     *  class would carry a synthetic strong reference to the Activity (javac emits
     *  it for every anonymous class inside an instance method, used or not), and
     *  the file write can outlive the screen. Holds the app context for the work
     *  and the Activity and the progress dialog weakly for the UI follow-up. */
    private static final class SaveTask extends AsyncTask<Void, Void, Boolean> {
        private final Context appContext;
        private final BookDatabase db;
        private final Book target;
        private final File file;
        private final boolean inline;
        private final String title;
        private final String author;
        private final String publisher;
        private final String description;
        private final WeakReference<ProgressDialog> dialog;
        private final WeakReference<EditMetaActivity> self;

        SaveTask(EditMetaActivity host, Book target, File file, boolean inline,
                 String title, String author, String publisher, String description,
                 ProgressDialog pd) {
            this.appContext = host.getApplicationContext();
            this.db = host.db; // safe: it holds the app context
            this.target = target;
            this.file = file;
            this.inline = inline;
            this.title = title;
            this.author = author;
            this.publisher = publisher;
            this.description = description;
            this.dialog = new WeakReference<ProgressDialog>(pd);
            this.self = new WeakReference<EditMetaActivity>(host);
        }

        @Override protected Boolean doInBackground(Void... v) {
            boolean ok = true;
            if (inline && file.exists()) {
                MetaData md = new MetaData();
                md.title = title;
                md.author = author;
                md.publisher = publisher;
                md.description = description;
                ok = MetaWriter.write(file, md);
            }
            // Always persist to the catalog database.
            Book updated = db.getById(target.id);
            if (updated == null) {
                // The row vanished meanwhile (e.g. removed from another screen) —
                // do NOT resurrect it with a stale in-memory copy.
                return ok;
            }
            updated.title = title;
            updated.author = author;
            updated.publisher = publisher;
            updated.description = description;
            updated.exported = ok && inline;
            // Persist the fields, then mark the row user-edited so the background
            // enricher never clobbers these values with the file's original
            // metadata. (upsert() itself never touches user_edited/meta_done.)
            db.upsert(updated);
            db.markUserEdited(updated.id);
            // The list is cursor-driven: announce the new metadata so the catalog
            // views (list + recently read) re-query without a reload.
            BookProvider.notifyChangeAll(appContext);
            return ok;
        }

        @Override protected void onPostExecute(Boolean ok) {
            ProgressDialog d = dialog.get();
            if (d != null) d.dismiss();
            EditMetaActivity a = self.get();
            if (a == null || a.isFinishing()) return;
            if (inline && !ok) {
                Toast.makeText(a,
                        "Could not write into the file. Changes saved to the library only.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(a, "Saved", Toast.LENGTH_SHORT).show();
            }
            a.setResult(RESULT_OK);
            a.finish();
        }
    }
}
