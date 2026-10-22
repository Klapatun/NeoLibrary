package com.example.mylibrary;

import android.net.Uri;
import android.os.Bundle;

import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.scan.Formats;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The catalog's single filter state and the single "state → query" conversion
 * point (the modal filter's plan, step 2): the filter dialog, the catalog
 * loader and the instance state all work through this one plain class, and a
 * future filter type (e.g. "with/without cover") extends it with its own FIELD
 * here and its own clause in {@link #querySelection()}/{@link #queryArgs()} —
 * data, not an abstraction (no interfaces, no registry, no sub-package; the
 * provider's selection grammar grows additively alongside).
 *
 * <p>The pinned contract (see {@code FilterStateTest}):</p>
 * <ul>
 * <li>an empty {@link #selectedFormats} means "all formats": {@link
 *     BookProvider#CONTENT_URI} with no selection;</li>
 * <li>a non-empty selection becomes {@code format IN (?,…)} — the
 *     placeholders and the args of {@link #queryArgs()} in the order of
 *     {@link Formats#ALL} (deterministic, not the order the boxes were
 *     ticked);</li>
 * <li>{@link #recentView} wins over the format selection in the QUERY
 *     (mutually exclusive views → {@link BookProvider#RECENT_URI}, no
 *     selection) while the selection itself stays configured (the dot on the
 *     funnel icon, {@link #isActive()});</li>
 * <li>{@link #copy()} is an independent draft (the dialog's cancel semantics:
 *     the real state changes only on OK);</li>
 * <li>{@link #save(Bundle)}/{@link #restore(Bundle)} is the instance-state
 *     round-trip (the filter is session-only: it survives a rotation, not the
 *     app being closed — it is deliberately NOT a preference).</li>
 * </ul>
 */
public class FilterState {

    /** Bundle key: the selected format ids (an empty array = all formats). */
    public static final String KEY_FORMATS = "filter_formats";
    /** Bundle key: whether the "Recently read" view is on. */
    public static final String KEY_RECENT = "filter_recent";

    /** The selected format ids (canonical {@code Formats.ALL} values); an empty
     *  set means "all formats" (no format restriction at all). */
    public final Set<String> selectedFormats = new LinkedHashSet<String>();

    /** Whether the "Recently read" view is on. It wins over the format
     *  selection in the query (the views are mutually exclusive), while the
     *  selection itself stays configured (the funnel's dot). */
    public boolean recentView;

    /** Whether a filter is configured — drives the dot on the funnel icon
     *  ({@code ic_filter_active}): today the format selection only (in the
     *  future: OR over all filter types). Stays true while {@code recentView}
     *  is on — the dot reflects the CONFIGURED filter, not the current view. */
    public boolean isActive() {
        return !selectedFormats.isEmpty();
    }

    /** A copy of this state — the draft the filter dialog works on (the cancel
     *  semantics: the real state changes only on OK). */
    public FilterState copy() {
        FilterState draft = new FilterState();
        draft.selectedFormats.addAll(this.selectedFormats);
        draft.recentView = this.recentView;
        return draft;
    }

    /** The URI of the query for this state: the "Recently read" view wins over
     *  the format selection (mutually exclusive views); otherwise the
     *  all-books view. */
    public Uri queryUri() {
        if (recentView) return BookProvider.RECENT_URI;
        return BookProvider.CONTENT_URI;
    }

    /** The SQL selection of the query for this state, or {@code null} for no
     *  selection (all books; the recent view carries no selection). A non-empty
     *  format selection becomes {@code format IN (?,…)} — one placeholder per
     *  selected format. */
    public String querySelection() {
        if (recentView || selectedFormats.isEmpty()) return null;
        StringBuilder in = new StringBuilder("format IN (");
        boolean first = true;
        for (String f : Formats.ALL) {
            if (selectedFormats.contains(f)) {
                if (!first) in.append(",");
                first = false;
                in.append("?");
            }
        }
        in.append(")");
        return in.toString();
    }

    /** The args for {@link #querySelection()} — the selected ids in the order
     *  of {@link Formats#ALL} (deterministic, not the order the boxes were
     *  ticked) — or {@code null} (no args: all books / the recent view). */
    public String[] queryArgs() {
        if (recentView || selectedFormats.isEmpty()) return null;
        String[] args = new String[selectedFormats.size()];
        int i = 0;
        for (String f : Formats.ALL) {
            if (selectedFormats.contains(f)) args[i++] = f;
        }
        return args;
    }

    /** Saves the state into the instance state (the filter is session-only —
     *  see the class javadoc). */
    public void save(Bundle outState) {
        outState.putStringArray(KEY_FORMATS, selectedFormats.toArray(new String[0]));
        outState.putBoolean(KEY_RECENT, recentView);
    }

    /** Restores the state from the instance state; {@code null} (a cold start)
     *  gives the empty state. */
    public static FilterState restore(Bundle inState) {
        FilterState state = new FilterState();
        if (inState == null) return state;
        String[] formats = inState.getStringArray(KEY_FORMATS);
        if (formats != null) {
            for (String f : formats) state.selectedFormats.add(f);
        }
        state.recentView = inState.getBoolean(KEY_RECENT, false);
        return state;
    }
}
