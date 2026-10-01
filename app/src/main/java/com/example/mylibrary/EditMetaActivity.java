package com.example.mylibrary;

import android.app.Activity;
import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.meta.MetaWriter;
import com.example.mylibrary.model.Book;

import java.io.File;

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

        db = new BookDatabase(this);
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

        final ProgressDialog pd = ProgressDialog.show(this, null, "Saving metadata…", true, false);
        new AsyncTask<Void, Void, Boolean>() {
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
                Book updated = db.getById(book.id);
                if (updated == null) updated = book;
                updated.title = title;
                updated.author = author;
                updated.publisher = publisher;
                updated.description = description;
                updated.exported = ok && inline;
                // Mark the row as user-edited so the background enricher never
                // clobbers these values with the file's original metadata.
                updated.userEdited = true;
                db.upsert(updated);
                return ok;
            }
            @Override protected void onPostExecute(Boolean ok) {
                pd.dismiss();
                if (inline && !ok) {
                    Toast.makeText(EditMetaActivity.this,
                            "Could not write into the file. Changes saved to the library only.",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(EditMetaActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                }
                setResult(RESULT_OK);
                finish();
            }
        }.execute();
    }
}
