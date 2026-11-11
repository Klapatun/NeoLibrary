package com.example.mylibrary;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.meta.MetaWriter;
import com.example.mylibrary.model.Book;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

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
    private LinearLayout genreContainer;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_meta);

        db = new BookDatabase(getApplicationContext());
        book = getIntent().getParcelableExtra(EXTRA_BOOK);
        if (book == null) {
            finish();
            return;
        }
        // The list cursor does not carry work_genres. The parcel is the row the
        // user opened (path and format stay); the genre list comes from the catalog.
        Book stored = db.getById(book.id);
        if (stored != null && stored.genres != null) {
            book.genres = stored.genres;
        }

        hint = (TextView) findViewById(R.id.edit_format_hint);
        etTitle = (EditText) findViewById(R.id.edit_title);
        etAuthor = (EditText) findViewById(R.id.edit_author);
        etPublisher = (EditText) findViewById(R.id.edit_publisher);
        etDescription = (EditText) findViewById(R.id.edit_description);
        genreContainer = (LinearLayout) findViewById(R.id.edit_genres);

        etTitle.setText(book.title == null ? "" : book.title);
        etAuthor.setText(book.author == null ? "" : book.author);
        etPublisher.setText(book.publisher == null ? "" : book.publisher);
        etDescription.setText(book.description == null ? "" : book.description);

        if (book.genres != null && !book.genres.isEmpty()) {
            for (int i = 0; i < book.genres.size(); i++) {
                addGenreField(book.genres.get(i));
            }
        } else {
            addGenreField("");
        }

        String format = book.format == null ? "" : book.format.toUpperCase();
        boolean inlineEditable = "EPUB".equals(format) || "FB2".equals(format);
        if (inlineEditable) {
            hint.setText("Format: " + format + "  —  Edits will be saved into the file itself.");
        } else {
            hint.setText("Format: " + format + "  —  Edits are stored in the app library only.");
        }

        ((Button) findViewById(R.id.btn_add_genre)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addGenreField(""); }
        });
        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        ((Button) findViewById(R.id.btn_cancel)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    private void addGenreField(String value) {
        EditText et = new EditText(this);
        et.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        et.setText(value == null ? "" : value);
        genreContainer.addView(et);
    }

    private ArrayList<String> readGenresFromForm() {
        ArrayList<String> raw = new ArrayList<String>();
        for (int i = 0; i < genreContainer.getChildCount(); i++) {
            View child = genreContainer.getChildAt(i);
            if (child instanceof EditText) {
                String t = ((EditText) child).getText().toString().trim();
                if (t.length() > 0) raw.add(t);
            }
        }
        return MetaData.normalizeGenres(raw);
    }

    private void save() {
        final String title = etTitle.getText().toString().trim();
        final String author = etAuthor.getText().toString().trim();
        final String publisher = etPublisher.getText().toString().trim();
        final String description = etDescription.getText().toString().trim();
        final ArrayList<String> genres = readGenresFromForm();

        final boolean inline = "EPUB".equals(book.format) || "FB2".equals(book.format);
        final File file = new File(book.path);
        final Book target = book;

        final ProgressDialog pd = ProgressDialog.show(this, null, "Saving metadata…", true, false);
        new SaveTask(this, target, file, inline,
                title, author, publisher, genres, description, pd).execute();
    }

    private static final class SaveTask extends AsyncTask<Void, Void, Boolean> {
        private final Context appContext;
        private final BookDatabase db;
        private final Book target;
        private final File file;
        private final boolean inline;
        private final String title;
        private final String author;
        private final String publisher;
        private final ArrayList<String> genres;
        private final String description;
        private final WeakReference<ProgressDialog> dialog;
        private final WeakReference<EditMetaActivity> self;

        SaveTask(EditMetaActivity host, Book target, File file, boolean inline,
                 String title, String author, String publisher, ArrayList<String> genres,
                 String description, ProgressDialog pd) {
            this.appContext = host.getApplicationContext();
            this.db = host.db;
            this.target = target;
            this.file = file;
            this.inline = inline;
            this.title = title;
            this.author = author;
            this.publisher = publisher;
            this.genres = genres;
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
                md.genres = genres;
                md.description = description;
                ok = MetaWriter.write(file, md);
            }
            Book updated = db.getById(target.id);
            if (updated == null) {
                return ok;
            }
            updated.title = title;
            updated.author = author;
            updated.publisher = publisher;
            updated.genres = genres;
            updated.description = description;
            updated.exported = ok && inline;
            db.upsert(updated);
            db.markUserEdited(updated.id);
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
