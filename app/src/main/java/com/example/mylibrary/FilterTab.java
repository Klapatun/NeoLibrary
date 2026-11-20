package com.example.mylibrary;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TextView;

import com.example.mylibrary.scan.Formats;

import java.util.ArrayList;

/** One tab of the filter dialog: the search field, the select-all/clear
 *  row and the multi-choice list of ONE vocabulary (the format ids or the
 *  genre names). The checked state lives in {@link #checked} indexed over
 *  the ORIGINAL vocabulary, so search filtering (a visible subset) never
 *  loses it. The genres tab is this same class with a DYNAMIC vocabulary:
 *  its reference can be rebuilt in place via {@link #refresh} while the
 *  dialog is open (the unlock after an enrichment pass adds the newly
 *  enriched genres).
 *
 *  <p>While {@link #loading} is on (the genres tab only, while the stage-2
 *  worker runs) the whole tab is locked: the checkboxes are untouchable,
 *  the search / select-all / clear are disabled, and the hint explains
 *  why. A separate file on purpose: the class has no reference to an outer
 *  instance, and the dialog pages must not hold one.</p> */
final class FilterTab {

    /** The tab's page view (a child of the tabcontent frame). */
    final LinearLayout page;
    final EditText search;
    final TextView loadingHint;
    final CheckBox selectAll;
    final TextView clear;
    final ListView list;
    final ArrayAdapter<String> adapter;

    /** The vocabulary (Formats.ALL or the catalog's distinct genres). */
    String[] all;
    /** The checked state indexed over the ORIGINAL {@link #all} positions. */
    SparseBooleanArray checked;
    /** The visible (search-filtered) subset of {@link #all}. */
    final ArrayList<String> visible = new ArrayList<String>();
    /** visible[i] -> its index in {@link #all} (grown on demand). */
    int[] visibleToOriginal = new int[0];
    /** True while the stage-2 worker runs (the genres tab): the tab is
     *  locked (see the class javadoc). */
    boolean loading;
    /** The framework tab label. Null until {@link #bindAppliedMarker}
     *  (the constructor rebuilds before the TabWidget exists). The draft
     *  dot is this view's right compound drawable. */
    TextView appliedMarker;

    FilterTab(Context context, String[] all, int searchHintRes, int pageId) {
        this.all = all;
        this.checked = new SparseBooleanArray();

        page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setId(pageId);
        page.setPadding(16, 8, 16, 16);

        search = new EditText(context);
        search.setHint(searchHintRes);
        search.setSingleLine(true);
        page.addView(search);

        // The lock indicator: shown while the reference is being rebuilt
        // (the genres tab only — the formats tab never sets loading).
        loadingHint = new TextView(context);
        loadingHint.setText(R.string.filter_genres_loading);
        loadingHint.setTextColor(0xFF757575);
        loadingHint.setVisibility(View.GONE);
        page.addView(loadingHint);

        final LinearLayout selectAllRow = new LinearLayout(context);
        selectAllRow.setOrientation(LinearLayout.HORIZONTAL);
        selectAllRow.setPadding(0, 16, 0, 8);
        selectAllRow.setGravity(Gravity.CENTER_VERTICAL);

        selectAll = new CheckBox(context);
        selectAll.setText(R.string.filter_select_all);
        // Checkbox takes remaining space, pushes clear button to the right.
        selectAll.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        selectAllRow.addView(selectAll);

        // The clear button: visible only when at least one item is
        // selected (a small cross, aligned right).
        clear = new TextView(context);
        clear.setText("\u2715");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        clear.setTextColor(0xFF757575);
        clear.setPadding(12, 4, 12, 4);
        clear.setVisibility(View.GONE);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.setMargins(8, 0, 16, 0);
        clear.setLayoutParams(clearParams);
        clear.setClickable(true);
        clear.setFocusable(true);
        selectAllRow.addView(clear);

        page.addView(selectAllRow);

        list = new ListView(context);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        page.addView(list);

        // The adapter holds the VISIBLE list: the search rebuilds it in
        // place (notifyDataSetChanged), while the checked state stays
        // indexed over the full vocabulary.
        adapter = new ArrayAdapter<String>(context,
                android.R.layout.simple_list_item_multiple_choice, visible);
        list.setAdapter(adapter);

        // Search filtering (ignored while the reference is being rebuilt).
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (loading) return;
                rebuildVisible();
            }
        });

        // Select All / Deselect All: check (and re-show the full list) or
        // uncheck the WHOLE vocabulary — the visible rows are just a
        // subset of it.
        selectAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (loading) return;
                boolean isChecked = selectAll.isChecked();
                for (int i = 0; i < all.length; i++) {
                    checked.put(i, isChecked);
                }
                if (isChecked) {
                    // Select All re-shows the full list (search reset).
                    search.setText("");
                }
                reapplyRowStates();
                updateSelectAllState();
            }
        });

        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (loading) return;
                for (int i = 0; i < all.length; i++) {
                    checked.put(i, false);
                }
                reapplyRowStates();
                updateSelectAllState();
            }
        });

        // List item click toggles the checked state for that entry.
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (loading) return;
                int originalIndex = visibleToOriginal[position];
                boolean newChecked = !checked.get(originalIndex);
                checked.put(originalIndex, newChecked);
                list.setItemChecked(position, newChecked);
                updateSelectAllState();
            }
        });

        rebuildVisible();
        applyLoading();
    }

    /** Rebuilds the visible subset from the current search text and
     *  re-applies the checked state and the select-all/clear row. */
    void rebuildVisible() {
        String query = search.getText().toString().toLowerCase();
        visible.clear();
        int idx = 0;
        for (int i = 0; i < all.length; i++) {
            if (query.isEmpty() || all[i].toLowerCase().contains(query)) {
                if (idx >= visibleToOriginal.length) {
                    visibleToOriginal = new int[all.length];
                }
                visible.add(all[i]);
                visibleToOriginal[idx] = i;
                idx++;
            }
        }
        adapter.notifyDataSetChanged();
        reapplyRowStates();
        updateSelectAllState();
    }

    /** Re-applies the checked state to the visible rows (the rows are the
     *  searchable subset; the state itself lives over the full
     *  vocabulary). */
    void reapplyRowStates() {
        for (int i = 0; i < visible.size(); i++) {
            list.setItemChecked(i, checked.get(visibleToOriginal[i]));
        }
    }

    /** Refreshes the select-all label and the clear button's visibility to
     *  match the current checked state. The only place the tab-label dot
     *  turns on or off: on while this tab's draft has any check, off
     *  otherwise. A null marker (before {@link #bindAppliedMarker}) leaves
     *  the label untouched. {@code setLoading} does not call this and does
     *  not hide the dot. */
    void updateSelectAllState() {
        boolean allChecked = all.length > 0;
        boolean anyChecked = false;
        for (int i = 0; i < all.length; i++) {
            if (checked.get(i)) {
                anyChecked = true;
            } else {
                allChecked = false;
            }
        }
        selectAll.setChecked(allChecked && anyChecked);
        selectAll.setText(allChecked && anyChecked
                ? R.string.filter_deselect_all : R.string.filter_select_all);
        clear.setVisibility(anyChecked ? View.VISIBLE : View.GONE);
        if (appliedMarker == null) {
            return;
        }
        if (anyChecked) {
            appliedMarker.setCompoundDrawablesWithIntrinsicBounds(
                    0, 0, R.drawable.filter_tab_applied, 0);
            int pad = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 6f,
                    appliedMarker.getResources().getDisplayMetrics());
            appliedMarker.setCompoundDrawablePadding(pad);
        } else {
            appliedMarker.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        }
    }

    /** Remembers the framework tab label and paints the draft dot at once. */
    void bindAppliedMarker(TextView title) {
        appliedMarker = title;
        updateSelectAllState();
    }

    /** Rebuilds the vocabulary in place (the dynamic genre reference: a
     *  finished or cancelled enrichment pass may have added genres). The
     *  checked state is preserved BY NAME across the rebuild — a selected
     *  genre stays selected even as the reference grows. */
    void refresh(String[] newAll) {
        final String[] old = this.all;
        final SparseBooleanArray oldChecked = this.checked;
        this.all = newAll;
        SparseBooleanArray next = new SparseBooleanArray();
        for (int j = 0; j < old.length; j++) {
            if (!oldChecked.get(j)) continue;
            for (int i = 0; i < newAll.length; i++) {
                if (newAll[i].equals(old[j])) {
                    next.put(i, true);
                    break;
                }
            }
        }
        this.checked = next;
        // The search no longer matches the rebuilt vocabulary: reset it
        // and re-show the full list.
        search.setText("");
        rebuildVisible();
    }

    /** Locks or unlocks the whole tab (the genres tab while the stage-2
     *  worker runs): the checkboxes are untouchable, the search /
     *  select-all / clear are disabled, and the hint explains why. */
    void setLoading(boolean isLoading) {
        loading = isLoading;
        applyLoading();
    }

    private void applyLoading() {
        search.setEnabled(!loading);
        selectAll.setEnabled(!loading);
        clear.setEnabled(!loading);
        list.setEnabled(!loading);
        // The already-inflated rows' checkboxes too (a disabled list
        // delivers no row touches regardless; newly inflated rows come up
        // enabled and stay untouchable while the list itself is locked).
        for (int i = 0; i < list.getChildCount(); i++) {
            list.getChildAt(i).setEnabled(!loading);
        }
        loadingHint.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** The funnel icon's filter dialog: a TabHost with the two filter
     *  dimensions — "Formats" (the format ids, {@link Formats#ALL}, the single
     *  source of truth) and "Genres" (the dynamic genre reference: the
     *  catalog's distinct non-null genres). Each tab is a {@link FilterTab}
     *  (search field, select-all/clear row, multi-choice list), and the dialog
     *  works on a DRAFT copy of the filter state: the real state (and the dot
     *  on the funnel) changes only on OK, and OK also resets the recent view —
     *  the views are mutually exclusive, the filter view wins. Cancel throws
     *  the draft away (nothing changes).
     *
     *  <p>While the stage-2 enrichment worker runs, the Genres tab is LOCKED
     *  (its reference is still growing): the checkboxes are untouchable, the
     *  search / select-all / clear are disabled and the hint says why. It
     *  unlocks — with a fresh reference — on {@code onFinished()}/{@code
     *  onCancelled()} (see the host's open genres tab).</p> */
    static void show(MainActivity host, FilterState draft, String[] genreNames, boolean genresLocked) {
        // The two tab pages (children of the tabcontent frame the TabHost
        // swaps). The formats tab works on the static Formats.ALL vocabulary;
        // the checked state is filled from the draft (cancel semantics: the
        // real state changes only on OK).
        final FilterTab formatsTab = new FilterTab(host, Formats.ALL,
                R.string.filter_search_hint, R.id.formats_tab);
        for (int i = 0; i < Formats.ALL.length; i++) {
            if (draft.selectedFormats.contains(Formats.ALL[i])) {
                formatsTab.checked.put(i, true);
            }
        }
        // The constructor's rebuild ran BEFORE this fill: push the restored
        // state into the list now (the checkmarks of the materialized rows,
        // the select-all/clear row) — without this a re-opened dialog would
        // show every box unticked even though the draft carries the selection.
        formatsTab.rebuildVisible();

        // The genre reference is DYNAMIC: the catalog's distinct non-null
        // genres, read here on open and again whenever an enrichment pass
        // stops (the finished/cancelled pass may have added genres).
        final FilterTab genreTab = new FilterTab(host, genreNames,
                R.string.filter_search_hint_genres, R.id.genres_tab);
        for (int i = 0; i < genreNames.length; i++) {
            if (draft.selectedGenres.contains(genreNames[i])) {
                genreTab.checked.put(i, true);
            }
        }
        // Same re-push as the formats tab (see there): the draft's genre
        // selection must be visible on the rows the moment the tab opens.
        genreTab.rebuildVisible();
        // Locked while the worker runs; the enrichment callbacks keep it in
        // sync while the dialog is open (see openFilterGenresTab).
        genreTab.setLoading(genresLocked);
        host.setOpenFilterGenresTab(genreTab);

        // The TabHost (framework, API 19 — no ViewPager): the TabWidget on
        // top, the tabcontent frame (holding the two pages) below it. setup()
        // must run after the frame is in place; addTab() then swaps the pages
        // by id (the first addTab selects the Formats tab).
        final TabWidget tabs = new TabWidget(host);
        tabs.setId(android.R.id.tabs);
        final FrameLayout tabContent = new FrameLayout(host);
        tabContent.setId(android.R.id.tabcontent);
        tabContent.addView(formatsTab.page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        tabContent.addView(genreTab.page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        final LinearLayout tabLayout = new LinearLayout(host);
        tabLayout.setOrientation(LinearLayout.VERTICAL);
        tabLayout.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tabLayout.addView(tabContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // (context, null) on purpose, NOT the single-arg constructor: the label
        // indicator is inflated from an internal layout id that the attrs
        // constructor resolves from the theme (tab_indicator_holo); the single-arg
        // one leaves it 0, and the first addTab() would inflate resource #0x0
        // and crash the dialog (Resources$NotFoundException).
        final TabHost tabHost = new TabHost(host, null);
        tabHost.addView(tabLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        tabHost.setup();
        tabHost.addTab(tabHost.newTabSpec("formats")
                .setIndicator(host.getString(R.string.filter_tab_formats))
                .setContent(R.id.formats_tab));
        tabHost.addTab(tabHost.newTabSpec("genres")
                .setIndicator(host.getString(R.string.filter_tab_genres))
                .setContent(R.id.genres_tab));
        // Not getTabWidget(): its shadow looks up android.R.id.tabs on the
        // Activity, and this strip lives in the dialog. On a device addTab
        // already installed the holo title; the fallback label only fills a
        // strip that addTab left empty.
        formatsTab.bindAppliedMarker(ensureFilterTabLabel(
                host, tabs, 0, R.string.filter_tab_formats));
        genreTab.bindAppliedMarker(ensureFilterTabLabel(
                host, tabs, 1, R.string.filter_tab_genres));
        // A re-open lands on the Genres tab when the draft carries a genre
        // selection (the plan's step 8); otherwise on Formats (already
        // selected by the first addTab).
        tabHost.setCurrentTab(draft.selectedGenres.isEmpty() ? 0 : 1);
        // addTab() hides a page only when the current tab changes. The Genres
        // page is added while Formats is already current, so it stays VISIBLE
        // and its "Select all" row is drawn on top of the Formats one.
        if (tabHost.getCurrentTab() == 0) {
            genreTab.page.setVisibility(View.GONE);
        } else {
            formatsTab.page.setVisibility(View.GONE);
        }

        new AlertDialog.Builder(host)
                .setTitle(R.string.filter_dialog_title)
                .setView(tabHost)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.filter_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        host.commitFilterDraft(formatsTab, genreTab);
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        // The dialog is gone: no open tab to lock/unlock
                        // anymore.
                        host.setOpenFilterGenresTab(null);
                    }
                })
                .show();
    }

    /** Tab label at {@code index}. Reuses the holo title when {@code addTab}
     *  already put one there; otherwise installs a plain label (a child with
     *  no title at that index is replaced). */
    private static TextView ensureFilterTabLabel(Context context, TabWidget tabs, int index, int labelRes) {
        if (index < tabs.getChildCount()) {
            TextView existing = (TextView) tabs.getChildAt(index)
                    .findViewById(android.R.id.title);
            if (existing != null) {
                return existing;
            }
            tabs.removeViewAt(index);
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setId(android.R.id.title);
        title.setText(labelRes);
        row.addView(title);
        tabs.addView(row, index);
        return title;
    }
}
