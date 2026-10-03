package com.example.mylibrary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.LoaderManager;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.meta.MetaEnricher;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.scan.Formats;
import com.example.mylibrary.scan.LibraryScanner;
import com.example.mylibrary.util.CoverCache;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Home screen: loads the library in three stages and lets the user browse the catalog
 * (filtered by format or the "Recently read" view), open a book in Neo Reader, edit
 * metadata, or import additional files.
 *
 * <p><b>Stage 1 — fast scan:</b> walk storage, build a file-name-only skeleton for
 * every supported book, upsert it, show the list. The user can already see and
 * interact with everything from this point on.</p>
 * <p><b>Stage 2 — background enrichment:</b> {@link MetaEnricher} extracts in-file
 * metadata and covers book by book off the UI thread; each batch notifies the
 * {@link CursorLoader} below, which refreshes only the changed rows.</p>
 * <p><b>Stage 3 — interaction:</b> the list/grid/loader wiring; the cursor adapter is
 * driven by {@code content://...books} so any catalog change (scan, enrichment,
 * import) shows up without manual reloads or blocking the interface.</p>
 */
public class MainActivity extends Activity
        implements LoaderManager.LoaderCallbacks<Cursor>, BookAdapter.BookMenuActions {

    private static final int REQ_IMPORT = 100;
    private static final int LOADER_BOOKS = 1;

    private BookDatabase db;
    private BookAdapter adapter;
    private Spinner filterSpinner;
    private ProgressBar progressBar;
    private TextView emptyView;
    private ListView list;
    private GridView grid;
    private ImageButton toggleView;
    private LinearLayout enrichBar;
    private TextView enrichStatus;
    private int viewMode = BookAdapter.MODE_LIST;

    private List<String> filterLabels;
    private List<String> filterValues; // format ids, or "" for All, or "__recent__"
    private String currentFilter = "";

    /** Stage-2 progress strip; invoked on the main thread by MetaEnricher. */
    private final MetaEnricher.OnProgress enrichListener = new MetaEnricher.OnProgress() {
        @Override public void onProgress(int done, int total) {
            if (isFinishing()) return;
            enrichStatus.setText(getString(R.string.enriching_progress, done, total));
        }
        @Override public void onFinished() {
            if (isFinishing()) return;
            enrichBar.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new BookDatabase(this);

        progressBar = (ProgressBar) findViewById(R.id.progress);
        emptyView = (TextView) findViewById(R.id.empty_view);
        enrichBar = (LinearLayout) findViewById(R.id.enrich_bar);
        enrichStatus = (TextView) findViewById(R.id.enrich_status);

        list = (ListView) findViewById(R.id.book_list);
        grid = (GridView) findViewById(R.id.book_grid);
        toggleView = (ImageButton) findViewById(R.id.toggle_view);

        list.setEmptyView(emptyView);
        grid.setEmptyView(emptyView);
        // The adapter is cursor-driven from the start; the loader below replaces the
        // cursor on every load. Attached to exactly ONE view at a time — the visible
        // one (list initially). list and grid are stacked in a FrameLayout and a
        // CursorAdapter cannot be attached to two views at once, so setViewMode moves
        // it: detaches it from the view going away and attaches it to the one coming
        // forward (guarded by getAdapter() == null so the move happens only on the
        // first toggle each way).
        // "this" as BookMenuActions: the per-book kebab (Details / Edit metadata /
        // Remove) dispatches its picks here — the navigation and the delete
        // confirmation live on the screen, not in the row binding.
        adapter = new BookAdapter(this, db.cursorAll(null), this);
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Book b = adapter.getItem(pos);
                if (b != null) openDetails(b.id);
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Book b = adapter.getItem(pos);
                if (b != null) openDetails(b.id);
            }
        });
        toggleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setViewMode(viewMode == BookAdapter.MODE_LIST ? BookAdapter.MODE_GRID : BookAdapter.MODE_LIST);
            }
        });

        setViewMode(BookAdapter.MODE_LIST);
        setupFilterSpinner();
        // The framework Activity (unlike AndroidX's FragmentActivity) has no loader
        // shortcuts of its own — go through the LoaderManager explicitly.
        getLoaderManager().initLoader(LOADER_BOOKS, null, this);
        if (savedInstanceState == null) {
            // Cold start (first launch of this task): walk storage and (re)build the
            // catalog. This is also what picks up files added outside the app
            // between sessions.
            startScan();
        } else {
            // Recreation (rotation / configuration change): the catalog and the
            // loaders already have the data — a full rescan would be pure waste
            // (and would reset the enrichment worker from the top of the queue).
            // The worker was cancelled in onDestroy; if any books still need stage
            // 2, resume it over the remaining queue (needMeta()).
            startEnrichment();
        }
    }

    @Override
    protected void onDestroy() {
        // Never let the enrichment worker outlive the screen it is refreshing.
        MetaEnricher.cancel();
        super.onDestroy();
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
            if (this.grid.getAdapter() == null) {
                this.list.setAdapter(null);
                this.grid.setAdapter(adapter);
            }

            this.grid.setVisibility(View.VISIBLE);
            this.list.setVisibility(View.GONE);
        } else {
            if (this.list.getAdapter() == null) {
                this.grid.setAdapter(null);
                this.list.setAdapter(adapter);
            }

            this.list.setVisibility(View.VISIBLE);
            this.grid.setVisibility(View.GONE);
        }
    }

    // -----------------------------------------------------------------
    // Filter spinner -> loader arguments
    // -----------------------------------------------------------------

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
                applyFilter(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void applyFilter(int pos) {
        if (pos < 0 || pos >= filterValues.size()) return;
        currentFilter = filterValues.get(pos);
        getLoaderManager().restartLoader(LOADER_BOOKS, null, this);
    }

    // -----------------------------------------------------------------
    // CursorLoader callbacks (the "observer" half of the UI)
    // -----------------------------------------------------------------

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // The filter arguments are read from the activity's current state (the
        // spinner is the single source of truth), so the args Bundle is not needed.
        Uri uri;
        String selection = null;
        String[] selArgs = null;
        if ("__recent__".equals(currentFilter)) {
            uri = BookProvider.RECENT_URI;
        } else {
            uri = BookProvider.CONTENT_URI;
            if (currentFilter.length() > 0) {
                selection = "format=?";
                selArgs = new String[]{currentFilter};
            }
        }
        // Ordering lives in BookProvider (title COLLATE NOCASE / last_read DESC).
        return new CursorLoader(this, uri, null, selection, selArgs, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        adapter.changeCursor(cursor);
        updateEmptyView();
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        adapter.changeCursor(null);
    }

    /** Sets the empty-view hint for the current filter (the list itself is empty). */
    private void updateEmptyView() {
        if (adapter.getCount() > 0) return;
        if ("__recent__".equals(currentFilter)) {
            emptyView.setText("No books read yet.");
        } else {
            emptyView.setText(R.string.empty_filter);
        }
    }

    /** The catalog cursor the current view expects for {@link #currentFilter} — the
     *  same query {@link #onCreateLoader} would ask the provider for. Used for the
     *  direct rebind in {@link #startScan} after a rescan. */
    private Cursor currentCatalogCursor() {
        if ("__recent__".equals(currentFilter)) {
            return db.cursorRecent(200);
        }
        return db.cursorAll(currentFilter);
    }

    private void openDetails(long id) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(DetailActivity.EXTRA_BOOK_ID, id);
        startActivity(i);
    }

    // -----------------------------------------------------------------
    // Per-book kebab picks (BookAdapter.BookMenuActions)
    // -----------------------------------------------------------------

    @Override
    public void onDetails(Book book) {
        openDetails(book.id);
    }

    @Override
    public void onEditMetadata(Book book) {
        // The list is cursor-driven: the editor announces its own save through
        // notifyChangeAll, so a plain startActivity (no result round-trip) suffices.
        Intent i = new Intent(this, EditMetaActivity.class);
        i.putExtra(EditMetaActivity.EXTRA_BOOK, book);
        startActivity(i);
    }

    @Override
    public void onRemove(Book book) {
        confirmRemove(book);
    }

    /** The kebab's "Remove": same confirmation and same effect as the detail screen
     *  had (catalog row gone, file on disk untouched, both catalog views notified). */
    private void confirmRemove(final Book book) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(getString(R.string.delete_confirm) + "\n\n"
                        + getString(R.string.remove_file_note))
                .setNegativeButton(R.string.delete_no, null)
                .setPositiveButton(R.string.delete_yes, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        db.deleteByPath(book.path);
                        // Both catalog views may still be showing the row — tell
                        // their cursors it is gone (no other code path re-queries).
                        BookProvider.notifyChangeAll(MainActivity.this);
                    }
                })
                .show();
    }

    // -----------------------------------------------------------------
    // Stage 1: fast scan
    // -----------------------------------------------------------------

    @SuppressLint("StaticFieldLeak")
    private void startScan() {
        progressBar.setVisibility(View.VISIBLE);
        new AsyncTask<Void, Void, List<Book>>() {
            @Override protected List<Book> doInBackground(Void... v) {
                // Fast by design: file walk only, no in-file metadata extraction
                // (that is stage 2's job).
                return LibraryScanner.scan(MainActivity.this, null);
            }
            @Override protected void onPostExecute(List<Book> found) {
                progressBar.setVisibility(View.GONE);
                // Do NOT clear the whole table here: upsertBasic() already keeps
                // metadata, enrichment state and last_read timestamps in sync.
                if (found != null) {
                    for (Book b : found) db.upsertBasic(b);
                    // The upserts above were committed on this (UI) thread, just now —
                    // so re-query the catalog ourselves and rebind the adapter.
                    // Deterministic: it does not rely on the CursorLoader's
                    // ContentObserver being alive (on API 19 it can be lost after a
                    // loader cancel/restart cycle — without this rebind the list
                    // would stay empty after a rescan that finds new books). The
                    // loader's next delivery simply replaces this cursor.
                    adapter.changeCursor(currentCatalogCursor());
                    updateEmptyView();
                    // Also announce through the normal channel (the "recently read"
                    // observer and any other listeners).
                    getContentResolver().notifyChange(BookProvider.CONTENT_URI, null);
                }
                Toast.makeText(MainActivity.this,
                        "Found " + (found == null ? 0 : found.size()) + " book(s)",
                        Toast.LENGTH_SHORT).show();
                startEnrichment();
            }
        }.execute();
    }

    // -----------------------------------------------------------------
    // Stage 2: background metadata + covers
    // -----------------------------------------------------------------

    /** Starts the background enrichment worker if any book still needs it. List
     *  refresh is NOT the worker's job: stage-1 writes rebind the adapter directly
     *  (see {@link #startScan}) and stage-2 announces itself via {@code notifyChange}. */
    private void startEnrichment() {
        if (isFinishing()) return;
        if (db.needMeta().isEmpty()) return;
        enrichBar.setVisibility(View.VISIBLE);
        enrichStatus.setText(R.string.enriching);
        MetaEnricher.start(this, db, enrichListener);
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
                    if (b != null) {
                        long existing = db.getIdForPath(dest.getAbsolutePath());
                        db.upsertBasic(b);
                        if (existing >= 0) {
                            // The import overwrote an existing file: the row still carries
                            // the OLD in-file metadata and the cache the OLD cover (a new
                            // file without a cover would keep the stale one forever).
                            // Drop both — the re-extraction below refreshes them, and the
                            // pending flag is the safety net for a later bulk pass.
                            db.markMetaPending(existing);
                            CoverCache.delete(MainActivity.this, dest.getAbsolutePath());
                        }
                        // Stage 2 for this single book — already off the UI thread.
                        MetaEnricher.enrichOne(MainActivity.this, db, b);
                        getContentResolver().notifyChange(BookProvider.CONTENT_URI, null);
                    }
                    return b != null;
                } catch (Exception e) {
                    return false;
                }
            }
            @Override protected void onPostExecute(Boolean ok) {
                pd.dismiss();
                if (ok) Toast.makeText(MainActivity.this, "Imported " + displayName, Toast.LENGTH_SHORT).show();
                else Toast.makeText(MainActivity.this, "Import failed", Toast.LENGTH_LONG).show();
                // No manual reload: the notifyChange above made the CursorLoader
                // re-query and the list refreshed itself.
            }
        }.execute();
    }
}
