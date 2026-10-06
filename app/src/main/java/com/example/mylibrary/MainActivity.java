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
import android.view.MenuInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
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
import com.example.mylibrary.util.Openers;

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
    /** SharedPreferences file for the UI settings that must survive an app restart. */
    private static final String PREFS_NAME = "library_prefs";
    /** The chosen display mode (a {@code BookAdapter.MODE_*} value). */
    private static final String PREF_KEY_VIEW_MODE = "view_mode";

    private BookDatabase db;
    private BookAdapter adapter;
    private Spinner filterSpinner;
    private ProgressBar progressBar;
    private TextView emptyView;
    private ListView list;
    private GridView grid;
    private ImageButton btnMenu;
    private ImageButton toggleView;
    private LinearLayout enrichBar;
    private TextView enrichStatus;
    private int viewMode = BookAdapter.MODE_GRID; // tiles are the default view
    /** The header kebab's popup last built by {@link #showHeaderMenu}; exposed for
     *  unit tests (same pattern as {@code BookAdapter.getLastPopupMenu}). */
    private PopupMenu lastHeaderMenu;

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
        btnMenu = (ImageButton) findViewById(R.id.btn_menu);
        toggleView = (ImageButton) findViewById(R.id.toggle_view);

        list.setEmptyView(emptyView);
        grid.setEmptyView(emptyView);
        // The adapter is cursor-driven from the start; the loader below replaces the
        // cursor on every load. Attached to exactly ONE view at a time — the visible
        // one (the tile grid initially: tiles are the default view). list and grid
        // are stacked in a FrameLayout and a CursorAdapter cannot be attached to two
        // views at once, so setViewMode moves it: detaches it from the view going
        // away and attaches it to the one coming forward (guarded by
        // getAdapter() == null so the move happens only on the first toggle each way).
        // "this" as BookMenuActions: the per-book kebab (Details / Edit metadata /
        // Remove) dispatches its picks here — the navigation and the delete
        // confirmation live on the screen, not in the row binding.
        adapter = new BookAdapter(this, db.cursorAll(null), this);
        grid.setAdapter(adapter);

        // The system action bar is off on this screen (AppTheme.NoActionBar):
        // import and rescan live in the header kebab's popup menu now (the kebab
        // is the rightmost header button), not as separate header buttons.
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showHeaderMenu(v);
            }
        });

        toggleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setViewMode(viewMode == BookAdapter.MODE_LIST ? BookAdapter.MODE_GRID : BookAdapter.MODE_LIST);
            }
        });

        // The display mode (list/tiles) is persisted in the phone's memory
        // (SharedPreferences), so it survives the app being closed: apply the saved
        // choice (tiles by default) now. The field's initial value (MODE_GRID)
        // matches the layout's initial state, so a first launch — or a stored
        // default — is a no-op transition.
        setViewMode(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_KEY_VIEW_MODE, BookAdapter.MODE_GRID));
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

        // Remember the choice in the phone's memory so the next launch opens in
        // the same mode (written only when the mode actually changed, so the
        // startup no-op does not touch the file).
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(PREF_KEY_VIEW_MODE, mode).commit();
    }

    // -----------------------------------------------------------------
    // Header kebab (the rightmost header button): import / rescan
    // -----------------------------------------------------------------

    /**
     * Shows the header's overflow menu ({@code main_menu}) anchored to the kebab
     * button. The framework {@code Menu} interface has no inflate() of its own —
     * go through {@link MenuInflater} (the plain-framework equivalent of the
     * AppCompat one-liner), the same way {@code BookAdapter} builds the per-book
     * menu. Package-private and returns the menu so unit tests can pick items
     * without driving the popup window.
     */
    PopupMenu showHeaderMenu(View anchor) {
        final PopupMenu menu = new PopupMenu(this, anchor);
        new MenuInflater(this).inflate(R.menu.main_menu, menu.getMenu());
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.main_menu_import) {
                    launchImport();
                } else if (id == R.id.main_menu_rescan) {
                    startScan();
                }
                menu.dismiss();
                return true;
            }
        });
        lastHeaderMenu = menu;
        menu.show();
        return menu;
    }

    /** The header popup last built by {@link #showHeaderMenu} (for unit tests). */
    PopupMenu getLastHeaderMenu() {
        return lastHeaderMenu;
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
        // White text layout: the spinner sits on the indigo header bar, where the
        // default dark-on-light selected item would be hard to read.
        ArrayAdapter<String> sa = new ArrayAdapter<String>(this,
                R.layout.spinner_item, filterLabels);
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

    @Override
    public void onBookTapped(Book book) {
        // A plain tap on a row/tile no longer goes to the detail screen — it asks
        // first (Open / Cancel). The detail page stays reachable through the kebab
        // (onDetails only).
        confirmOpen(book);
    }

    // -----------------------------------------------------------------
    // Opening a book: the tap confirmation (Open / Cancel)
    // -----------------------------------------------------------------

    /** The tap's "Open this book?": on confirm the book is launched in the reader
     *  with the same side effects the detail screen's Open button had (viewer
     *  intent, markRead, notify); on cancel nothing happens. */
    private void confirmOpen(final Book book) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_confirm_title)
                .setMessage(getString(R.string.open_confirm_message, BookAdapter.titleOf(book)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.open, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        openBook(book);
                    }
                })
                .show();
    }

    /** Launches the book in Neo Reader. The shared "file there? viewer there?" logic
     *  lives in {@link Openers#openFile(File, Activity, OpenOutcome)}; the catalog
     *  side effects (markRead, notify) stay here. */
    private void openBook(final Book book) {
        Openers.openFile(new File(book.path), this, new Openers.OpenOutcome() {
            @Override public void onLaunched() {
                db.markRead(book.id);
                // The list is cursor-driven: announce the new last_read so the
                // "recently read" view (and the list, if it is on screen) re-queries.
                BookProvider.notifyChangeAll(MainActivity.this);
            }
            @Override public void onMissingFile() {
                Toast.makeText(MainActivity.this, R.string.file_not_found,
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
                        // The delete was committed on this (UI) thread, just now —
                        // re-query the catalog ourselves and rebind the adapter.
                        // Deterministic: it does not rely on the CursorLoader's
                        // ContentObserver being alive (on API 19 it can be lost after
                        // a loader cancel/restart cycle — without this rebind the
                        // removed row could stay on screen until some unrelated
                        // loader event, same class of bug as the rescan fix in
                        // startScan and the import fix after it).
                        adapter.changeCursor(currentCatalogCursor());
                        updateEmptyView();
                        // Also announce through the normal channel (the "recently
                        // read" observer and any other listeners).
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
    // Import
    // -----------------------------------------------------------------

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
                if (ok) {
                    Toast.makeText(MainActivity.this, "Imported " + displayName, Toast.LENGTH_SHORT).show();
                    // The upsert above was committed on the background thread, just
                    // before this callback — so re-query the catalog ourselves and
                    // rebind the adapter. Deterministic: it does not rely on the
                    // CursorLoader's ContentObserver being alive (on API 19 it can be
                    // lost after a loader cancel/restart cycle — without this rebind
                    // the list could stay empty after an import, same class of bug as
                    // the rescan fix in startScan). The loader's next delivery simply
                    // replaces this cursor.
                    adapter.changeCursor(currentCatalogCursor());
                    updateEmptyView();
                } else {
                    Toast.makeText(MainActivity.this, "Import failed", Toast.LENGTH_LONG).show();
                }
                // The notifyChange in doInBackground still goes out for the other
                // listeners (e.g. the "recently read" view); this rebind only
                // guarantees that the list in front of the user catches up.
            }
        }.execute();
    }
}
