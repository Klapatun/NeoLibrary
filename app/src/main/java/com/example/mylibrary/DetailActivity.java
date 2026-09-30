package com.example.mylibrary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.meta.CoverExtractor;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.util.CoverLoader;
import com.example.mylibrary.util.Openers;

import java.io.File;

/**
 * Shows a single book's details and offers: open in Neo Reader, edit metadata, and
 * remove from the library.
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

        ((TextView) findViewById(R.id.detail_title)).setText(nz(book.title));
        ((TextView) findViewById(R.id.detail_author)).setText(nz(book.author));
        ((TextView) findViewById(R.id.detail_file)).setText(book.path);

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

        StringBuilder other = new StringBuilder();
        if (book.format != null) other.append("Format: ").append(book.format).append("\n");
        if (book.publisher != null && book.publisher.length() > 0)
            other.append("Publisher: ").append(book.publisher).append("\n");
        other.append("Size: ").append(humanSize(book.sizeBytes)).append("\n");
        if (book.description != null && book.description.length() > 0)
            other.append("\n").append(book.description);
        ((TextView) findViewById(R.id.detail_other)).setText(other.toString());

        ((Button) findViewById(R.id.btn_open)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBook(); }
        });
        ((Button) findViewById(R.id.btn_edit)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editMeta(); }
        });
        ((Button) findViewById(R.id.btn_delete)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(); }
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

    private void editMeta() {
        Intent i = new Intent(this, EditMetaActivity.class);
        i.putExtra(EditMetaActivity.EXTRA_BOOK, book);
        startActivityForResult(i, 1);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Remove from library")
                .setMessage("Remove this book from the library?\n\nThe file itself will not be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        db.deleteByPath(book.path);
                        setResult(RESULT_OK);
                        finish();
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1 && resultCode == RESULT_OK) {
            book = db.getById(book.id);
            if (book != null) {
                ((TextView) findViewById(R.id.detail_title)).setText(nz(book.title));
                ((TextView) findViewById(R.id.detail_author)).setText(nz(book.author));
            }
            setResult(RESULT_OK);
        }
        super.onActivityResult(requestCode, resultCode, data);
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
