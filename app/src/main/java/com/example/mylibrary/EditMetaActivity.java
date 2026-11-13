package com.example.mylibrary;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.Catalog;
import com.example.mylibrary.meta.MetaData;
import com.example.mylibrary.meta.MetaWriter;
import com.example.mylibrary.model.Book;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Lets the user edit the metadata of a book.
 *
 * <p>For EPUB and FB2, a create-and-delete probe decides where the edit lands.
 * If the file's directory accepts the probe, the edits are written into that
 * file. If it does not, the book is copied into the app's books directory, the
 * copy is written, and the existing catalog row points at the copy. For every
 * other format the edits are persisted in our SQLite catalog only.</p>
 */
public class EditMetaActivity extends Activity {

    public static final String EXTRA_BOOK = "extra_book";

    /** Non-null skips the real directory probe. Production code never assigns this. */
    static Boolean directoryWritableForTest;

    static boolean directoryAcceptsNewFile(File dir) {
        if (directoryWritableForTest != null) return directoryWritableForTest.booleanValue();
        if (dir == null || !dir.isDirectory()) return false;
        File probe = new File(dir, ".neowrite-" + System.currentTimeMillis());
        OutputStream out = null;
        boolean ok = false;
        try {
            out = new BufferedOutputStream(new FileOutputStream(probe));
            out.write(0);
            out.close();
            out = null;
            ok = probe.delete();
        } catch (Exception e) {
            ok = false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            if (probe.exists() && !probe.delete()) ok = false;
        }
        return ok;
    }

    private BookDatabase db;
    private Catalog catalog;
    private Book book;

    private EditText etTitle, etAuthor, etPublisher, etDescription;
    private LinearLayout genreContainer;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_meta);

        db = new BookDatabase(getApplicationContext());
        catalog = new Catalog(getApplicationContext(), db);
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
            @Override public void onClick(View v) {
                // API 19 performClick still invokes the listener on a disabled view.
                if (!v.isEnabled()) return;
                addGenreField("");
            }
        });
        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        ((Button) findViewById(R.id.btn_cancel)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    private void addGenreField(String value) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final EditText et = new EditText(this);
        et.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                refreshGenreControls();
            }
        });

        TextView clear = new TextView(this);
        clear.setText("\u2715");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        clear.setTextColor(0xFF757575);
        clear.setPadding(12, 4, 12, 4);
        clear.setContentDescription(getString(R.string.clear_genre));
        clear.setClickable(true);
        clear.setFocusable(true);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClearGenre(row); }
        });

        row.addView(et);
        row.addView(clear);
        genreContainer.addView(row);
        et.setText(value == null ? "" : value);
        refreshGenreControls();
    }

    /** One row: clear the field. Several rows: drop this row. */
    private void onClearGenre(LinearLayout row) {
        if (genreContainer.getChildCount() <= 1) {
            ((EditText) row.getChildAt(0)).setText("");
        } else {
            genreContainer.removeView(row);
            refreshGenreControls();
        }
    }

    /** Cross is gone only on the single empty row. Add stays off while any row is blank. */
    private void refreshGenreControls() {
        int count = genreContainer.getChildCount();
        boolean anyEmpty = false;
        for (int i = 0; i < count; i++) {
            LinearLayout row = (LinearLayout) genreContainer.getChildAt(i);
            EditText field = (EditText) row.getChildAt(0);
            View clear = row.getChildAt(1);
            boolean empty = field.getText().toString().trim().length() == 0;
            if (empty) anyEmpty = true;
            if (count == 1 && empty) {
                clear.setVisibility(View.GONE);
            } else {
                clear.setVisibility(View.VISIBLE);
            }
        }
        ((Button) findViewById(R.id.btn_add_genre)).setEnabled(!anyEmpty);
    }

    private ArrayList<String> readGenresFromForm() {
        ArrayList<String> raw = new ArrayList<String>();
        for (int i = 0; i < genreContainer.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) genreContainer.getChildAt(i);
            EditText field = (EditText) row.getChildAt(0);
            String t = field.getText().toString().trim();
            if (t.length() > 0) raw.add(t);
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
        private final Catalog catalog;
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
            this.catalog = host.catalog;
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
            if (inline && file.exists()
                    && directoryAcceptsNewFile(file.getParentFile())) {
                boolean ok = MetaWriter.write(file, formMeta());
                Book updated = db.getById(target.id);
                if (updated == null) return ok;
                applyForm(updated);
                updated.exported = ok && inline;
                catalog.saveUserEdit(updated);
                return ok;
            }
            if (!(inline && file.exists())) {
                return saveCatalog(true);
            }
            Book updated = db.getById(target.id);
            if (updated == null) return false;

            File copy = copyIntoAppBooks(updated.id);
            boolean wrote = false;
            if (copy != null) {
                try {
                    wrote = MetaWriter.write(copy, formMeta());
                } catch (Exception e) {
                    wrote = false;
                }
            }
            if (!wrote) {
                deleteCopyArtifacts(copy);
                applyForm(updated);
                updated.exported = false;
                catalog.saveUserEdit(updated);
                return false;
            }
            applyForm(updated);
            updated.exported = true;
            updated.sizeBytes = copy.length();
            if (catalog.saveUserEditAtNewPath(updated, copy.getAbsolutePath())) {
                return true;
            }
            deleteCopyArtifacts(copy);
            if (db.getIdForPath(updated.path) == target.id) {
                catalog.saveUserEdit(updated);
            }
            return false;
        }

        private boolean saveCatalog(boolean ok) {
            Book updated = db.getById(target.id);
            if (updated == null) return ok;
            applyForm(updated);
            updated.exported = ok && inline;
            catalog.saveUserEdit(updated);
            return ok;
        }

        private MetaData formMeta() {
            MetaData md = new MetaData();
            md.title = writerField(title, target.title);
            md.author = writerField(author, target.author);
            md.publisher = writerField(publisher, target.publisher);
            md.description = writerField(description, target.description);
            md.genres = genres;
            return md;
        }

        /** Null when the trimmed form string matches the book field the form showed. */
        private static String writerField(String formValue, String bookValue) {
            String form = formValue == null ? "" : formValue;
            String book = bookValue == null ? "" : bookValue.trim();
            if (form.equals(book)) return null;
            return formValue;
        }

        private void applyForm(Book updated) {
            updated.title = title;
            updated.author = author;
            updated.publisher = publisher;
            updated.genres = genres;
            updated.description = description;
        }

        /** Copy into app storage, or null when the copy must not be attempted. */
        private File copyIntoAppBooks(long workId) {
            File dir = appContext.getExternalFilesDir("books");
            if (dir == null) return null;
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            if (!dir.isDirectory()) return null;

            File dest = new File(dir, file.getName());
            if (nameTaken(dest, workId)) {
                dest = new File(dir, suffixedName(file.getName(), workId));
                if (nameTaken(dest, workId)) return null;
            }
            if (dest.getAbsolutePath().equals(file.getAbsolutePath())) return null;
            return copyAtomically(file, dest);
        }

        private boolean nameTaken(File candidate, long workId) {
            if (candidate.exists()) return true;
            long other = db.getIdForPath(candidate.getAbsolutePath());
            return other != -1 && other != workId;
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

    private static String suffixedName(String name, long workId) {
        int dot = name.lastIndexOf('.');
        String stem;
        String ext;
        if (dot >= 0) {
            stem = name.substring(0, dot);
            ext = name.substring(dot);
        } else {
            stem = name;
            ext = "";
        }
        return stem + "-" + workId + ext;
    }

    private static File copyAtomically(File source, File finalFile) {
        File parent = finalFile.getParentFile();
        if (parent == null) return null;
        File copying = new File(parent, finalFile.getName() + ".copying");
        InputStream in = null;
        OutputStream out = null;
        boolean renamed = false;
        try {
            in = new BufferedInputStream(new FileInputStream(source));
            out = new BufferedOutputStream(new FileOutputStream(copying));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            in = null;
            out.close();
            out = null;
            renamed = copying.renameTo(finalFile);
            if (!renamed) {
                streamCopy(copying, finalFile);
                copying.delete();
            }
            if (!finalFile.isFile()) return null;
            return finalFile;
        } catch (Exception e) {
            if (!renamed && finalFile.exists()) finalFile.delete();
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            if (copying.isFile()) copying.delete();
        }
    }

    private static void streamCopy(File from, File to) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(from));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(to));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private static void deleteCopyArtifacts(File copy) {
        if (copy == null) return;
        File parent = copy.getParentFile();
        String name = copy.getName();
        copy.delete();
        if (parent == null) return;
        new File(parent, name + ".tmp").delete();
        new File(parent, name + ".bak").delete();
        new File(parent, name + ".copying").delete();
    }
}
