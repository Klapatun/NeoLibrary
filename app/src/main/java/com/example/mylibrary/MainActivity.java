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
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mylibrary.db.BookDatabase;
import com.example.mylibrary.db.Catalog;
import com.example.mylibrary.meta.MetaEnricher;
import com.example.mylibrary.model.Book;
import com.example.mylibrary.scan.Formats;
import com.example.mylibrary.remote.LibraryClient;
import com.example.mylibrary.util.FileLocator;
import com.example.mylibrary.util.LocateResult;
import com.example.mylibrary.util.Openers;

import java.io.File;
import java.util.Arrays;
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
    /** Rows per page in tile (grid) mode. */
    private static final int GRID_PAGE_SIZE = 6;
    /** SharedPreferences key: the pagination on/off choice (persists like view_mode). */
    private static final String PREF_KEY_PAGINATION = "pagination_enabled";
    /** Instance state: 0-based catalog page. Not written to library_prefs. */
    private static final String STATE_CATALOG_PAGE = "catalog_page";
    /** Instance state: first visible row when pagination is off. */
    private static final String STATE_CATALOG_SCROLL_POSITION = "catalog_scroll_position";
    /** Instance state: top of that row when pagination is off. */
    private static final String STATE_CATALOG_SCROLL_TOP = "catalog_scroll_top";

    private BookDatabase db;
    private Catalog catalog;
    private BookAdapter adapter;
    private FrameLayout bookContainer;
    private ProgressBar progressBar;
    private TextView emptyView;
    private PagedListView list;
    private PagedGridView grid;
    private ImageButton btnMenu;
    private ImageButton btnFilter;
    /** Header line: library-server connection. Hidden when the build has no URL. */
    private TextView connectionStatus;
    private ImageButton toggleView;
    private LinearLayout enrichBar;
    private TextView enrichStatus;
    private int viewMode = BookAdapter.MODE_GRID; // tiles are the default view
    private LinearLayout paginationBar;
    private ImageButton btnPrevPage;
    private ImageButton btnNextPage;
    private TextView pageIndicator;
    /** Whether the books are shown in fixed pages (the kebab's "Add pagination")
     *  instead of one long scroll; persisted like the view-mode choice. */
    private boolean paginationEnabled;
    /** Pixel height of an unbound list row, measured once per activity.
     *  Zero until the first positive measure; not persisted. */
    private int listRowHeightPx;
    /** One-shot: after recreation, the first loader delivery restores the
     *  catalog place. Cold start leaves this false, so pause/stop/start/resume
     *  never move the page or the scroll. */
    private boolean restoreCatalogPlace;
    /** First visible row to restore when pagination is off. */
    private int restoreScrollPosition;
    /** Top of that row to restore when pagination is off. */
    private int restoreScrollTop;
    /** The view's touch slop; the swipe threshold is a multiple of it. */
    private int swipeSlop;
    /** The header kebab's popup last built by {@link #showHeaderMenu}; exposed for
     *  unit tests (same pattern as {@code BookAdapter.getLastPopupMenu}). */
    private PopupMenu lastHeaderMenu;

    /** A horizontal swipe across the catalog (list or grid) turns the page —
     *  left = next, right = previous — through the same {@link #showPage(int)}
     *  path as the Prev/Next buttons (clamp + scroll to the top + indicator).
     *  The gesture is detected by the PagedListView/PagedGridView (their
     *  dispatchTouchEvent overrides; see PageSwipeTracker for why an
     *  OnTouchListener would not work) and arrives here POSTED, i.e. after the
     *  touch sequence has fully unwound. The views also intercept a qualifying
     *  swipe from the row/tile under the finger (on API 19 a swipe that stays
     *  inside a row would otherwise release into a row click — see the views'
     *  javadoc), so a page swipe can never also open the "Open this book?"
     *  dialog. Active only while pagination is on; with one page, showPage()
     *  clamps to a no-op. */
    private final PageSwipeTracker.OnSwipe pageSwipe = new PageSwipeTracker.OnSwipe() {
        @Override public void onSwipe(int direction) {
            if (!paginationEnabled) return;
            showPage(adapter.getPage() + direction);
        }
    };

    /** The catalog's filter state (the single "state → query" conversion point,
     *  the plan's step 2): the format modal, the loader and the instance state
     *  all work through it. Replaced the old currentFilter string plus the
     *  spinner's label/value lists. */
    private FilterState filters;

    /** Whether the stage-2 enrichment worker is currently running: the "Genres"
     *  tab of the filter dialog is locked while it runs (the genre reference is
     *  still growing), and is unlocked on {@code onFinished()}/{@code
     *  onCancelled()}. */
    private boolean enriching;

    /** The open filter dialog's "Genres" tab, or null while the dialog is
     *  closed: startEnrichment() locks it, onEnrichmentStopped() unlocks it
     *  with a fresh reference (the finished/cancelled pass may have added
     *  genres). */
    private FilterTab openFilterGenresTab;

    /** Header connection line. Invoked on the main thread by LibraryClient. */
    private final LibraryClient.Listener connectionListener = new LibraryClient.Listener() {
        @Override public void onConnectionStatus(int status, int httpCode) {
            if (isFinishing() || isDestroyed()) return;
            showConnectionStatus(status, httpCode);
        }
    };

    /** Stage-2 progress strip; invoked on the main thread by MetaEnricher. */
    private final MetaEnricher.OnProgress enrichListener = new MetaEnricher.OnProgress() {
        @Override public void onProgress(int done, int total) {
            if (isFinishing()) return;
            enrichStatus.setText(getString(R.string.enriching_progress, done, total));
        }
        @Override public void onFinished() {
            onEnrichmentStopped();
        }
        @Override public void onCancelled() {
            // By contract onFinished() is NOT sent after a cancel — this is the
            // cancel-side counterpart (see MetaEnricher.cancel): the same
            // screen-side effect below.
            onEnrichmentStopped();
        }
    };

    /** The screen-side effect of a FINISHED or CANCELLED enrichment pass: the
     *  enriching flag clears and the strip goes away (it gives the list its full
     *  height back — the bookContainer layout listener re-measures the list page
     *  size once the new height is laid out); an open filter dialog's "Genres"
     *  tab is unlocked with a fresh reference. */
    private void onEnrichmentStopped() {
        // isDestroyed covers cancel() from onDestroy: performDestroy sets the
        // flag before onDestroy, and refreshing a dialog of a dead screen is
        // what races the enricher's connection (see BookDatabase.distinctGenres).
        if (isFinishing() || isDestroyed()) return;
        enriching = false;
        enrichBar.setVisibility(View.GONE);
        // An open filter dialog's "Genres" tab unlocks now — the reference is
        // rebuilt (the finished/cancelled pass may have added genres).
        if (openFilterGenresTab != null) {
            openFilterGenresTab.setLoading(false);
            openFilterGenresTab.refresh(db.distinctGenres().toArray(new String[0]));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // The app context, not the Activity's: the database is process-wide state,
        // and in-flight background tasks (scan, import, enrichment) may outlive the
        // screen — they hold this object, so it must not pin the Activity.
        db = new BookDatabase(getApplicationContext());
        catalog = new Catalog(getApplicationContext(), db);

        // The filter state is session-only (unlike view_mode/pagination, which
        // persist in SharedPreferences): restore it from the instance state
        // BEFORE the adapter and the loader come up, so the first load is
        // already filtered and the user never sees a flash of "all books"
        // data.
        filters = FilterState.restore(savedInstanceState);

        progressBar = (ProgressBar) findViewById(R.id.progress);
        emptyView = (TextView) findViewById(R.id.empty_view);
        enrichBar = (LinearLayout) findViewById(R.id.enrich_bar);
        enrichStatus = (TextView) findViewById(R.id.enrich_status);

        bookContainer = (FrameLayout) findViewById(R.id.book_container);
        list = (PagedListView) findViewById(R.id.book_list);
        grid = (PagedGridView) findViewById(R.id.book_grid);
        btnMenu = (ImageButton) findViewById(R.id.btn_menu);
        connectionStatus = (TextView) findViewById(R.id.connection_status);
        LibraryClient.bind(connectionListener);
        btnFilter = (ImageButton) findViewById(R.id.btn_filter);
        toggleView = (ImageButton) findViewById(R.id.toggle_view);
        paginationBar = (LinearLayout) findViewById(R.id.pagination_bar);
        btnPrevPage = (ImageButton) findViewById(R.id.page_prev);
        btnNextPage = (ImageButton) findViewById(R.id.page_next);
        pageIndicator = (TextView) findViewById(R.id.page_indicator);

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
        //
        // The adapter's OWN default mode is MODE_LIST, which does not match the
        // screen's default (grid): on a grid launch setViewMode below takes its
        // early-return path (the field already holds MODE_GRID) and would never
        // call adapter.setMode — the first layout pass would then bind LIST rows
        // (item_book, circle covers) inside the GridView. Force the adapter onto
        // the mode the screen is about to apply, before any row gets inflated.
        int initialMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_KEY_VIEW_MODE, BookAdapter.MODE_GRID);
        // The initial cursor is the CURRENT view's query (recent-aware, through
        // the same "state → query" conversion point as the rebinds below); the
        // loader replaces it on the first load.
        adapter = new BookAdapter(this, currentCatalogCursor(), this);
        adapter.setMode(initialMode);
        grid.setAdapter(adapter);
        // Any external data change (an enrichment batch, a remove, a filter, a
        // rescan rebind) can shrink the cursor below the current page or grow the
        // page count: refresh the strip so "page X / Y" and the button states
        // mirror the adapter.
        adapter.registerDataSetObserver(new DataSetObserver() {
            @Override public void onChanged() {
                updatePageIndicator();
            }
            @Override public void onInvalidated() {
                updatePageIndicator();
            }
        });

        // The system action bar is off on this screen (AppTheme.NoActionBar):
        // import and rescan live in the header kebab's popup menu now (the kebab
        // is the rightmost header button), not as separate header buttons.
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showHeaderMenu(v);
            }
        });

        // The funnel opens the filter dialog (the multi-choice format list).
        btnFilter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                FilterTab.show(MainActivity.this, filters.copy(),
                        db.distinctGenres().toArray(new String[0]), enriching);
            }
        });

        toggleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setViewMode(viewMode == BookAdapter.MODE_LIST ? BookAdapter.MODE_GRID : BookAdapter.MODE_LIST);
            }
        });

        btnPrevPage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showPage(adapter.getPage() - 1);
            }
        });
        btnNextPage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showPage(adapter.getPage() + 1);
            }
        });

        // The display mode (list/tiles) is persisted in the phone's memory
        // (SharedPreferences), so it survives the app being closed: apply the saved
        // choice (tiles by default) now. The field's initial value (MODE_GRID)
        // matches the layout's initial state, so a first launch — or a stored
        // default — is a no-op transition (the adapter was already forced onto
        // this mode above, so the early return skips no state that matters).
        setViewMode(initialMode);
        // The pagination choice is persisted like the view mode (the kebab's
        // "Add/Remove pagination"); apply it with the initial view mode already in
        // place, because the page size depends on it.
        paginationEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_KEY_PAGINATION, false);
        if (paginationEnabled) {
            int page = 0;
            if (savedInstanceState != null) {
                page = savedInstanceState.getInt(STATE_CATALOG_PAGE, 0);
            }
            adapter.setPagination(true, pageSizeForCurrentMode(), page);
        }
        if (savedInstanceState != null) {
            // Page top (pagination on) or the saved scroll (pagination off).
            // Applied once from the first loader delivery, not from here:
            // the cursor is not the loader's result yet, and view-state
            // restore would otherwise put the old window's offset on page 0.
            restoreCatalogPlace = true;
            if (!paginationEnabled) {
                restoreScrollPosition = savedInstanceState.getInt(
                        STATE_CATALOG_SCROLL_POSITION, 0);
                restoreScrollTop = savedInstanceState.getInt(STATE_CATALOG_SCROLL_TOP, 0);
            }
        }
        updatePageIndicator();
        // The list's page size ("as many rows as fit the screen") is a function of
        // the book area's laid-out height, and that height changes under the
        // user's fingers: the first layout pass after a cold start, the enrichment
        // strip appearing/disappearing, the pager strip itself appearing/dis-
        // appearing. Whenever the container re-lays out at a DIFFERENT height,
        // re-measure. The recompute is posted so it reads the settled height and
        // never notifies the adapter in the middle of a layout pass.
        bookContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (bottom - top == oldBottom - oldTop) return; // width-only: nothing to re-measure
                v.post(new Runnable() {
                    @Override public void run() {
                        recomputeListPageSize();
                    }
                });
            }
        });
        // The page-swipe gesture: the horizontal degree of freedom the vertical
        // list/grid never uses is a natural page-turn (see pageSwipe).
        swipeSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        list.enablePageSwipe(swipeSlop, pageSwipe);
        grid.enablePageSwipe(swipeSlop, pageSwipe);

        // The funnel icon reflects the restored state right away (the dot).
        updateFilterIcon();
        if (savedInstanceState == null) {
            // Cold start (first launch of this task): walk storage and (re)build the
            // catalog. This is also what picks up files added outside the app
            // between sessions. The catalog loader does NOT start here: its first
            // query would race the scan (both tasks share the AsyncTask pool) and,
            // on a loaded machine, its stale pre-scan (0-row) result can be
            // DELIVERED after the scan's rebind — blanking the fresh catalog and
            // leaving the list empty. The loader starts in ScanTask.onPostExecute,
            // after the rebind, so its first query always sees post-scan data.
            // Debug reaches the local library server. The host is BuildConfig,
            // not this screen. Release is not configured and does not connect.
            // The prefetch does not write the catalog. The header line shows
            // the outcome.
            if (LibraryClient.isConfigured()) {
                int known = LibraryClient.lastStatus();
                showConnectionStatus(known == LibraryClient.STATUS_IDLE
                        ? LibraryClient.STATUS_CONNECTING : known,
                        LibraryClient.lastHttpCode());
            }
            LibraryClient.prefetchIndex();
            startScan();
        } else {
            // Recreation (rotation / configuration change): the catalog already
            // has the data — a full rescan would be pure waste (and would reset
            // the enrichment worker from the top of the queue). The loader starts
            // straight away (its first query sees the existing rows); the worker
            // was cancelled in onDestroy; if any books still need stage 2, resume
            // it over the remaining queue (needMeta()). The framework Activity
            // (unlike AndroidX's FragmentActivity) has no loader shortcuts of its
            // own — go through the LoaderManager explicitly.
            getLoaderManager().initLoader(LOADER_BOOKS, null, this);
            startEnrichment();
            if (LibraryClient.isConfigured()
                    && LibraryClient.lastStatus() != LibraryClient.STATUS_IDLE) {
                showConnectionStatus(LibraryClient.lastStatus(), LibraryClient.lastHttpCode());
            }
        }
    }

    /** The header line. Stays hidden when this build has no server. */
    private void showConnectionStatus(int status, int httpCode) {
        if (!LibraryClient.isConfigured()) {
            connectionStatus.setVisibility(View.GONE);
            return;
        }
        connectionStatus.setVisibility(View.VISIBLE);
        if (status == LibraryClient.STATUS_CONNECTED) {
            connectionStatus.setText(R.string.connection_connected);
        } else if (status == LibraryClient.STATUS_FAILED) {
            connectionStatus.setText(R.string.connection_failed);
        } else if (status == LibraryClient.STATUS_HTTP) {
            connectionStatus.setText(getString(R.string.connection_http, httpCode));
        } else {
            connectionStatus.setText(R.string.connection_checking);
        }
    }

    /** Rotation / configuration change: remember the filter state and the
     *  catalog place. The view mode and the pagination toggle already survive
     *  via SharedPreferences; the filter, the page, and the scroll are
     *  session-only, so they go to the instance state, not there. A cold
     *  start stays page 0 at the top of the list. */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        filters.save(outState);
        outState.putInt(STATE_CATALOG_PAGE, adapter.getPage());
        if (!paginationEnabled) {
            AbsListView visible = visibleCatalog();
            outState.putInt(STATE_CATALOG_SCROLL_POSITION, visible.getFirstVisiblePosition());
            View first = visible.getChildAt(0);
            outState.putInt(STATE_CATALOG_SCROLL_TOP, first == null ? 0 : first.getTop());
        }
    }

    /** The list or the grid that currently holds the adapter. */
    private AbsListView visibleCatalog() {
        return viewMode == BookAdapter.MODE_GRID ? grid : list;
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

        // The page size depends on the mode (6 tiles vs. a screenful of rows):
        // re-measure it and keep the current page clamped to the new size.
        if (paginationEnabled) {
            adapter.setPagination(true, pageSizeForCurrentMode(), adapter.getPage());
            (isGrid ? grid : list).setSelection(0);
            updatePageIndicator();
        }
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
        // The pagination item offers the OPPOSITE action (like the view toggle
        // icon does) and carries the checkmark while pagination is on.
        MenuItem pagination = menu.getMenu().findItem(R.id.main_menu_pagination);
        if (pagination != null) {
            pagination.setTitle(paginationEnabled
                    ? R.string.menu_pagination_remove : R.string.menu_pagination_add);
            pagination.setChecked(paginationEnabled);
        }
        // The "Recently read" item's checkmark mirrors the current view (the same
        // pattern as the pagination item): the item is a checkable VIEW toggle,
        // not a format, so it lives in the kebab, not the filter dialog.
        MenuItem recent = menu.getMenu().findItem(R.id.main_menu_recent);
        if (recent != null) {
            recent.setChecked(filters.recentView);
        }
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.main_menu_import) {
                    launchImport();
                } else if (id == R.id.main_menu_rescan) {
                    startScan();
                } else if (id == R.id.main_menu_clear) {
                    confirmClear();
                } else if (id == R.id.main_menu_pagination) {
                    setPaginationEnabled(!paginationEnabled);
                } else if (id == R.id.main_menu_recent) {
                    // The views are mutually exclusive: toggling "Recently read"
                    // does NOT clear the format selection (the funnel's dot
                    // reflects the CONFIGURED filter); confirming the filter
                    // dialog (OK) resets the recent view and wins.
                    filters.recentView = !filters.recentView;
                    applyCurrentView();
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

    /** The kebab's "Clear library": asks for confirmation and, on confirm, wipes
     *  the whole catalog — all rows plus the cover cache (the on-disk files are
     *  left untouched, exactly like the per-book Remove). The stage-2 worker is
     *  cancelled first: its queue is about to be gone, and letting it keep working
     *  would only re-extract books that no longer exist in the catalog. The
     *  catalog row delete is committed on this (UI) thread, just now — so the
     *  same deterministic rebind as the other direct writes on this screen
     *  (see {@link #startScan}): re-query the catalog and rebind the adapter
     *  ourselves, then also announce through the normal channel (the
     *  "recently read" observer and any other listeners). */
    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_confirm_title)
                .setMessage(getString(R.string.clear_confirm_message) + "\n\n"
                        + getString(R.string.clear_file_note))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_yes, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        MetaEnricher.cancel();
                        catalog.clear();
                        adapter.changeCursor(currentCatalogCursor());
                        updateEmptyView();
                        Toast.makeText(MainActivity.this, R.string.library_cleared,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // -----------------------------------------------------------------
    // Pagination (the kebab's "Add/Remove pagination": long scroll <-> pages)
    // -----------------------------------------------------------------

    /** Flips the pagination mode, persists the choice (like the view mode) and
     *  re-applies it: page 0, the current mode's page size (6 tiles, or as many
     *  list rows as fit on the screen), the visible view scrolled back to its
     *  first row, the strip refreshed. */
    private void setPaginationEnabled(boolean enabled) {
        if (paginationEnabled == enabled) return;
        paginationEnabled = enabled;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_KEY_PAGINATION, enabled).commit();
        if (enabled) {
            adapter.setPagination(true, pageSizeForCurrentMode(), 0);
            (viewMode == BookAdapter.MODE_GRID ? grid : list).setSelection(0);
        } else {
            adapter.setPagination(false, 0, 0);
        }
        updatePageIndicator();
    }

    /** The page size for the current view mode: a fixed 6 in tiles, or as many
     *  list rows as fit on the screen (measured, not guessed). */
    private int pageSizeForCurrentMode() {
        if (viewMode == BookAdapter.MODE_GRID) return GRID_PAGE_SIZE;
        return computeListPageSize();
    }

    /** How many list rows fit in the books area: the list's own laid-out height
     *  (or the grid's — they share the same {@code bookContainer} FrameLayout —
     *  when the list is not laid out yet, e.g. right after a mode switch; the
     *  full screen height as a last resort before the first layout pass,
     *  corrected by the {@code bookContainer} layout listener). One row's height
     *  is measured from a real (unbound) row at the list's width — the row height
     *  is constant (the fixed 48dp cover slot plus the padding), so the unbound
     *  measure is exact. */
    private int computeListPageSize() {
        int available = list.getHeight();
        if (available <= 0) available = grid.getHeight();
        if (available <= 0) available = getResources().getDisplayMetrics().heightPixels;
        if (listRowHeightPx <= 0) {
            int width = list.getWidth() > 0
                    ? list.getWidth() : getResources().getDisplayMetrics().widthPixels;
            View row = LayoutInflater.from(this).inflate(R.layout.item_book, list, false);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int measured = row.getMeasuredHeight();
            if (measured > 0) {
                listRowHeightPx = measured;
            }
        }
        return Math.max(1, available / Math.max(1, listRowHeightPx));
    }

    /** Re-measures the list page size. Invoked by the {@code bookContainer}
     *  layout listener whenever the book area's height settles at a new value
     *  (the first layout, the enrichment strip appearing/disappearing, the pager
     *  strip appearing/disappearing, a rotation). */
    private void recomputeListPageSize() {
        if (!paginationEnabled || viewMode != BookAdapter.MODE_LIST) return;
        adapter.setPagination(true, computeListPageSize(), adapter.getPage());
        updatePageIndicator();
    }

    /** Shows page {@code desired} (clamped to the last existing page) and scrolls
     *  the visible view back to its first row, so a page turn never keeps the old
     *  page's scroll offset. */
    private void showPage(int desired) {
        int last = Math.max(0, adapter.getPageCount() - 1);
        int target = Math.max(0, Math.min(desired, last));
        if (target == adapter.getPage()) return;
        adapter.setPage(target);
        (viewMode == BookAdapter.MODE_GRID ? grid : list).setSelection(0);
        updatePageIndicator();
    }

    /** The strip's state — the single place that decides its visibility: shown
     *  iff pagination is on AND there is at least one page (an empty catalog
     *  hides it, the empty view takes over). "X / Y" and the button states
     *  mirror the adapter. */
    private void updatePageIndicator() {
        int pages = adapter.getPageCount();
        if (!paginationEnabled || pages == 0) {
            paginationBar.setVisibility(View.GONE);
            return;
        }
        paginationBar.setVisibility(View.VISIBLE);
        int page = adapter.getPage();
        pageIndicator.setText((page + 1) + " / " + pages);
        btnPrevPage.setEnabled(page > 0);
        btnNextPage.setEnabled(page < pages - 1);
    }

    // -----------------------------------------------------------------
    // Filter modal (the funnel icon in the header)
    // -----------------------------------------------------------------

    /** Re-applies the current filter state to the catalog view: restarts the
     *  loader (the new query comes from {@code filters} in
     *  {@link #onCreateLoader}) and refreshes the funnel icon's dot. */
    private void applyCurrentView() {
        getLoaderManager().restartLoader(LOADER_BOOKS, null, this);
        updateFilterIcon();
    }

    /** The funnel icon's state: the dot ({@code ic_filter_active}) while a
     *  filter is configured — it stays while the recent view is on screen,
     *  because it reflects the CONFIGURED filter, not the current view. */
    private void updateFilterIcon() {
        btnFilter.setImageResource(filters.isActive()
                ? R.drawable.ic_filter_active : R.drawable.ic_filter);
    }

    /** The current filter state (package-private: the unit tests read the
     *  live state through it, same pattern as {@link #getLastHeaderMenu()}). */
    FilterState getFilterState() {
        return filters;
    }

    /** OK of the filter dialog: both tabs become {@link #filters}
     *  with {@code recentView} false, then {@link #applyCurrentView()}. */
    void commitFilterDraft(FilterTab formatsTab, FilterTab genreTab) {
        FilterState next = new FilterState();
        for (int i = 0; i < formatsTab.all.length; i++) {
            if (formatsTab.checked.get(i)) {
                next.selectedFormats.add(formatsTab.all[i]);
            }
        }
        for (int i = 0; i < genreTab.all.length; i++) {
            if (genreTab.checked.get(i)) {
                next.selectedGenres.add(genreTab.all[i]);
            }
        }
        next.recentView = false;
        filters = next;
        applyCurrentView();
    }

    /** The open dialog's Genres tab, or null after the dialog is gone. */
    void setOpenFilterGenresTab(FilterTab tab) {
        openFilterGenresTab = tab;
    }

    // -----------------------------------------------------------------
    // CursorLoader callbacks (the "observer" half of the UI)
    // -----------------------------------------------------------------

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // The query is read from the filter state (the single "state → query"
        // conversion point, the plan's step 2): this screen does not know the
        // provider's URIs or the selection syntax — only FilterState does.
        Uri uri = filters.queryUri();
        String selection = filters.querySelection();
        String[] selArgs = filters.queryArgs();
        // Ordering lives in BookProvider / BookDatabase (title COLLATE NOCASE /
        // last_read DESC).
        return new CursorLoader(this, uri, null, selection, selArgs, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        adapter.changeCursor(cursor);
        updateEmptyView();
        restoreCatalogPlaceAfterLoad(cursor);
    }

    /** Applies the recreated catalog place once. Pagination on: the top of
     *  the restored page ({@code setSelection(0)}), so it wins over the old
     *  window's view-state. Pagination off: the saved row and its top.
     *  {@code AbsListView.setSelectionFromTop} is API 21, so on minSdk 19 the
     *  row is placed with {@code setSelection} and then {@code scrollListBy}
     *  until the first child's top matches. A later delivery (enrichment)
     *  does not repeat it. The posted runnable checks the flag first and
     *  never turns it back on, so two posts in a row scroll once. An empty
     *  non-null cursor is the loader's empty catalog: drop the flag and do
     *  not scroll. A null cursor leaves the flag, in case rows are still
     *  coming. */
    private void restoreCatalogPlaceAfterLoad(Cursor cursor) {
        if (!restoreCatalogPlace) return;
        final AbsListView visible = visibleCatalog();
        if (paginationEnabled) {
            visible.post(new Runnable() {
                @Override public void run() {
                    if (!restoreCatalogPlace) return;
                    restoreCatalogPlace = false;
                    visible.setSelection(0);
                }
            });
            return;
        }
        final int count = adapter.getCount();
        if (count > 0) {
            final int position = Math.min(restoreScrollPosition, count - 1);
            final int top = restoreScrollTop;
            visible.post(new Runnable() {
                @Override public void run() {
                    if (!restoreCatalogPlace) return;
                    restoreCatalogPlace = false;
                    visible.setSelection(position);
                    visible.post(new Runnable() {
                        @Override public void run() {
                            View child = visible.getChildAt(0);
                            if (child == null) return;
                            int delta = child.getTop() - top;
                            if (delta != 0) visible.scrollListBy(delta);
                        }
                    });
                }
            });
            return;
        }
        if (cursor != null) {
            restoreCatalogPlace = false;
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        adapter.changeCursor(null);
    }

    /** Sets the empty-view hint for the current view (the list itself is
     *  empty): the "Recently read" view has its own hint, the format-filter
     *  view the generic one (the texts are not tied to a single format). */
    private void updateEmptyView() {
        if (adapter.getCount() > 0) return;
        if (filters.recentView) {
            emptyView.setText("No books read yet.");
        } else {
            emptyView.setText(R.string.empty_filter);
        }
    }

    /** The catalog cursor the current view expects for {@code filters} — the
     *  same query {@link #onCreateLoader} would ask the provider for (the same
     *  "state → query" conversion point). Used as the initial cursor in
     *  {@link #onCreate} and for the direct rebind in {@link #startScan}
     *  after a rescan (and after a remove / a clear). The format/genre args go
     *  through {@link FilterState#formatFilterArgs()}/{@link
     *  FilterState#genreFilterArgs()} — the two-dimension overload of
     *  {@code BookDatabase.cursorAll} (the direct path bypasses the provider). */
    private Cursor currentCatalogCursor() {
        if (filters.recentView) {
            return db.cursorRecent(200);
        }
        return db.cursorAll(filters.formatFilterArgs(), filters.genreFilterArgs());
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

    /** The tap's "Open this book?": on confirm {@link #openBook} asks the locator.
     *  Ready bytes go to the reader (viewer intent, then markRead). Absent,
     *  downloading, and failed stay on this screen. Cancel does nothing. */
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

    /** Launches the book in Neo Reader when {@link FileLocator#locateWork} says
     *  the bytes are ready. Downloading, failed, and absent do not call
     *  {@link Openers} and do not mark the work read. Openers still owns MIME
     *  and the viewer; markRead stays here, and only after {@code onLaunched}. */
    private void openBook(final Book book) {
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
                        catalog.deleteByPath(book.path);
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
                    }
                })
                .show();
    }

    // -----------------------------------------------------------------
    // Stage 1: fast scan
    // -----------------------------------------------------------------

    private void startScan() {
        progressBar.setVisibility(View.VISIBLE);
        new ScanTask(catalog, db, this).execute();
    }

    /** Hides the scan progress bar before the catalog write. The caller has
     *  already checked that the screen is alive. */
    void onScanProgressDone() {
        progressBar.setVisibility(View.GONE);
    }

    /** Rebinds the list after a finished scan, then toasts and starts enrichment.
     *  The caller has already checked that the screen is alive. */
    void onScanPosted(List<Book> found) {
        if (found != null) {
            // The upserts above were committed on this (UI) thread, just now —
            // so re-query the catalog ourselves and rebind the adapter.
            // Deterministic: it does not rely on the CursorLoader's
            // ContentObserver being alive (on API 19 it can be lost after a
            // loader cancel/restart cycle — without this rebind the list
            // would stay empty after a rescan that finds new books). The
            // loader's next delivery simply replaces this cursor.
            adapter.changeCursor(currentCatalogCursor());
            updateEmptyView();
            // Only now start the catalog loader (it is deliberately NOT
            // started in onCreate on a cold start — see there): its first
            // query must see the post-scan data, never the empty pre-scan
            // table whose stale delivery would otherwise race this rebind.
            // On a RE-SCAN the loader usually already runs (and may have
            // a PRE-scan query in flight): plain initLoader is then a
            // no-op, and that in-flight query would be DELIVERED after
            // this fresh rebind — a stale cursor that blanks the view
            // (the observer that would trigger a fresh re-query is
            // usually already dead after the first restart cycle on
            // API 19 — same hazard as the rebind above). restartLoader
            // cancels the in-flight query and re-queries the post-scan
            // data instead; initLoader creates the loader on a cold
            // start, where nothing is in flight yet.
            Loader<Cursor> existingLoader =
                    getLoaderManager().getLoader(LOADER_BOOKS);
            if (existingLoader == null) {
                getLoaderManager().initLoader(LOADER_BOOKS, null, this);
            } else {
                getLoaderManager().restartLoader(LOADER_BOOKS, null, this);
            }
        }
        Toast.makeText(this,
                "Found " + (found == null ? 0 : found.size()) + " book(s)",
                Toast.LENGTH_SHORT).show();
        startEnrichment();
    }

    // -----------------------------------------------------------------
    // Stage 2: background metadata + covers
    // -----------------------------------------------------------------

    /** Starts the background enrichment worker if any book still needs it. List
     *  refresh is NOT the worker's job: stage-1 writes rebind the adapter directly
     *  (see {@link #startScan}) and stage-2 announces itself via {@code notifyChange}. */
    private void startEnrichment() {
        if (isFinishing()) return;
        if (!db.hasPendingMeta()) return;
        // The strip takes height away from the list — the bookContainer layout
        // listener re-measures the list page size once the new height is laid out.
        // NOTE the order: start() first CANCELS any previous pass, and that
        // cancel() fires the previous pass's onCancelled() (enrichListener), which
        // clears the flag and hides the strip. So the strip and the flag are set
        // AFTER start() returns — the new pass's state wins.
        MetaEnricher.start(this, db, enrichListener);
        enriching = true;
        enrichBar.setVisibility(View.VISIBLE);
        enrichStatus.setText(R.string.enriching);
        // An open filter dialog's "Genres" tab locks for the pass (its
        // reference is still growing). AFTER start(): start()'s internal
        // cancel of the previous pass fires that pass's onCancelled() ->
        // onEnrichmentStopped(), which would unlock the tab — the new pass's
        // lock must win.
        if (openFilterGenresTab != null) {
            openFilterGenresTab.setLoading(true);
        }
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

    private void importToLibrary(Uri uri, String displayName) {
        final File dest = new File(getExternalFilesDir("books"), displayName);
        final ProgressDialog pd = ProgressDialog.show(this, null, "Importing…", true, false);
        new ImportTask(getApplicationContext(), catalog, db, uri, displayName, dest, pd, this).execute();
    }

    /** Toast and catalog rebind after a finished import. The caller has already
     *  dismissed the progress dialog and checked that the screen is alive.
     *  {@code ok} stays {@link Boolean} so a null result unboxes as before. */
    void onImportPosted(Boolean ok, String displayName) {
        if (ok) {
            Toast.makeText(this, "Imported " + displayName, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Import failed", Toast.LENGTH_LONG).show();
        }
        // The notifyChange in doInBackground still goes out for the other
        // listeners (e.g. the "recently read" view); this rebind only
        // guarantees that the list in front of the user catches up.
    }

}
