package com.example.mylibrary;

import android.content.Context;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

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
}
