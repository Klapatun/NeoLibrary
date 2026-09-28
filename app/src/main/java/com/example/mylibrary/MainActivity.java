package com.example.mylibrary;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.scan.Formats;
import com.example.mylibrary.scan.LibraryScanner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Home screen: scans storage for supported books, lets the user browse the catalog
 * (filtered by format or the "Recently read" view), open a book in Neo Reader, edit
 * metadata, or import additional files.
 */
public class MainActivity extends Activity {

    private static final int REQ_IMPORT = 100;

    private BookDatabase db;
    private BookAdapter adapter;
    private Spinner filterSpinner;
    private ProgressBar progressBar;
    private TextView emptyView;
    private ListView list;
    private GridView grid;
    private ImageButton toggleView;
    private int viewMode = BookAdapter.MODE_LIST;

    private List<String> filterLabels;
    private List<String> filterValues; // format ids, or "" for All, or "__recent__"

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new BookDatabase(this);

        progressBar = (ProgressBar) findViewById(R.id.progress);
        emptyView = (TextView) findViewById(R.id.empty_view);

        list = (ListView) findViewById(R.id.book_list);
        grid = (GridView) findViewById(R.id.book_grid);
        toggleView = (ImageButton) findViewById(R.id.toggle_view);

        list.setEmptyView(emptyView);
        grid.setEmptyView(emptyView);
        adapter = new BookAdapter(this);
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Book b = adapter.getItem(pos);
                openDetails(b.id);
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Book b = adapter.getItem(pos);
                openDetails(b.id);
            }
        });
        toggleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setViewMode(viewMode == BookAdapter.MODE_LIST ? BookAdapter.MODE_GRID : BookAdapter.MODE_LIST);
            }
        });

        setViewMode(BookAdapter.MODE_LIST);
        setupFilterSpinner();
        startScan();
    }

    private void setViewMode(int mode) {
        if (viewMode == mode) return;
        viewMode = mode;
        boolean isGrid = mode == BookAdapter.MODE_GRID;

        this.adapter.setMode(mode);

        this.grid.setVisibility(isGrid ? View.VISIBLE : View.GONE);
        this.list.setVisibility(isGrid ? View.GONE : View.VISIBLE);
        // Icon hints at the OTHER mode: in list show the tiles icon, in tiles show the list icon.
        toggleView.setImageResource(isGrid ? R.drawable.ic_list : R.drawable.ic_grid);
        toggleView.setContentDescription(getString(isGrid ? R.string.view_list : R.string.view_grid));

        // Re-attach to whichever is now visible.
        if (isGrid) {
            if (this.grid.getAdapter() == null)
                this.grid.setAdapter(adapter);

            this.grid.setVisibility(View.VISIBLE);
            this.list.setVisibility(View.GONE);
        } else {
            if (this.list.getAdapter() == null)
                this.list.setAdapter(adapter);

            this.list.setVisibility(View.VISIBLE);
            this.grid.setVisibility(View.GONE);
        }
    }

    private void setupFilterSpinner() {
        filterLabels = new ArrayList<String>();
        filterValues = new ArrayList<String>();
        filterLabels.add("All formats");
        filterValues.add("");
        filterLabels.add("Recently read");
        filterValues.add("__recent__");
        for (String f : Formats.ALL) {
            filterLabels.add("Format: " + f);
            filterValues.add(f);
        }
        ArrayAdapter<String> sa = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, filterLabels);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner = (Spinner) findViewById(R.id.filter_spinner);
        filterSpinner.setAdapter(sa);
        filterSpinner.setSelection(0);
        filterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                reload(filterValues.get(pos));
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void reload(String filterValue) {
        List<Book> books;
        if ("__recent__".equals(filterValue)) {
            books = db.recent(200);
        } else {
            books = db.all(filterValue.length() == 0 ? null : filterValue.toUpperCase());
        }
        adapter.setBooks(books);
        if (books.isEmpty()) {
            if ("__recent__".equals(filterValue)) {
                emptyView.setText("No books read yet.");
            } else {
                emptyView.setText(R.string.empty_filter);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void openDetails(long id) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(DetailActivity.EXTRA_BOOK_ID, id);
        startActivity(i);
    }

    // -----------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------

    private void startScan() {
        progressBar.setVisibility(View.VISIBLE);
        new AsyncTask<Void, Void, List<Book>>() {
            @Override protected List<Book> doInBackground(Void... v) {
                return LibraryScanner.scan(MainActivity.this, null);
            }
            @Override protected void onPostExecute(List<Book> found) {
                progressBar.setVisibility(View.GONE);
                // Do NOT clear the whole table here: upsert() already keeps metadata and
                // last_read timestamps in sync, and clearing would wipe "Recently read".
                // (Removing entries whose files vanished could be added as a later step.)
                if (found != null) {
                    for (Book b : found) db.upsert(b);
                }
                Toast.makeText(MainActivity.this,
                        "Found " + (found == null ? 0 : found.size()) + " book(s)",
                        Toast.LENGTH_SHORT).show();
                int sel = filterSpinner.getSelectedItemPosition();
                reload(sel >= 0 ? filterValues.get(sel) : "");
            }
        }.execute();
    }

    // -----------------------------------------------------------------
    // Menu / import
    // -----------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_rescan) {
            startScan();
            return true;
        } else if (id == R.id.menu_import) {
            launchImport();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void launchImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(i, "Select a book file"), REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "Document picker unavailable", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            String displayName = queryDisplayName(uri);
            if (displayName == null || !Formats.isSupported(displayName)) {
                Toast.makeText(this, "That file type is not supported", Toast.LENGTH_LONG).show();
                return;
            }
            // On KitKat we can copy the picked file into our own storage so Neo Reader
            // (which expects a readable file path) can open it.
            importToLibrary(uri, displayName);
        }
    }

    private String queryDisplayName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex("_display_name");
                    if (idx >= 0 && c.moveToFirst()) return c.getString(idx);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void importToLibrary(final Uri uri, final String displayName) {
        final File dest = new File(getExternalFilesDir("books"), displayName);
        final ProgressDialog pd = ProgressDialog.show(this, null, "Importing…", true, false);
        new AsyncTask<Void, Void, Boolean>() {
            @Override protected Boolean doInBackground(Void... v) {
                try {
                    if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    java.io.OutputStream out = new java.io.FileOutputStream(dest);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                    Book b = LibraryScanner.scanSingle(dest);
                    if (b != null) db.upsert(b);
                    return b != null;
                } catch (Exception e) {
                    return false;
                }
            }
            @Override protected void onPostExecute(Boolean ok) {
                pd.dismiss();
                if (ok) Toast.makeText(MainActivity.this, "Imported " + displayName, Toast.LENGTH_SHORT).show();
                else Toast.makeText(MainActivity.this, "Import failed", Toast.LENGTH_LONG).show();
                int sel = filterSpinner.getSelectedItemPosition();
                reload(sel >= 0 ? filterValues.get(sel) : "");
            }
        }.execute();
    }
}
