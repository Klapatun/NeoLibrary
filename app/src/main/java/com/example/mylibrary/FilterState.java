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
 * <li>a non-empty format selection becomes {@code format IN (?,…)} — the
 *     placeholders and the args of {@link #queryArgs()} in the order of
 *     {@link Formats#ALL} (deterministic, not the order the boxes were
 *     ticked); a non-empty genre selection becomes
 *     {@code genre IS NOT NULL AND genre IN (?,…)} with the args in
 *     FIRST-ADDED order (there is no canonical genre list — the reference is
 *     dynamic, formed from the enriched books); both selected AND the two
 *     clauses — formats first, then genres;</li>
 * <li>{@link #recentView} wins over the format AND genre selection in the
 *     QUERY (mutually exclusive views → {@link BookProvider#RECENT_URI}, no
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
    /** Bundle key: the selected genre names (an empty array = no genre
     *  restriction). */
    public static final String KEY_GENRES = "filter_genres";
    /** Bundle key: whether the "Recently read" view is on. */
    public static final String KEY_RECENT = "filter_recent";

    /** The selected format ids (canonical {@code Formats.ALL} values); an empty
     *  set means "all formats" (no format restriction at all). */
    public final Set<String> selectedFormats = new LinkedHashSet<String>();

    /** The selected genre names. There is NO canonical genre list — the
     *  reference is dynamic (the distinct non-null genres of the enriched
     *  books, the dialog's "Genres" tab), so the set's iteration order is
     *  FIRST-ADDED (the order of the reference at selection time) and
     *  {@link #queryArgs()} follows it. An empty set means "no genre
     *  restriction". */
    public final Set<String> selectedGenres = new LinkedHashSet<String>();

    /** Whether the "Recently read" view is on. It wins over the format and
     *  genre selection in the query (the views are mutually exclusive), while
     *  the selection itself stays configured (the funnel's dot). */
    public boolean recentView;

    /** Whether a filter is configured — drives the dot on the funnel icon
     *  ({@code ic_filter_active}): the format OR the genre selection. Stays
     *  true while {@code recentView} is on — the dot reflects the CONFIGURED
     *  filter, not the current view. */
    public boolean isActive() {
        return !selectedFormats.isEmpty() || !selectedGenres.isEmpty();
    }

    /** A copy of this state — the draft the filter dialog works on (the cancel
     *  semantics: the real state changes only on OK). */
    public FilterState copy() {
        FilterState draft = new FilterState();
        draft.selectedFormats.addAll(this.selectedFormats);
        draft.selectedGenres.addAll(this.selectedGenres);
        draft.recentView = this.recentView;
        return draft;
    }

    /** The URI of the query for this state: the "Recently read" view wins over
     *  the format and genre selection (mutually exclusive views); otherwise
     *  the all-books view. */
    public Uri queryUri() {
        if (recentView) return BookProvider.RECENT_URI;
        return BookProvider.CONTENT_URI;
    }

    /** The SQL selection of the query for this state, or {@code null} for no
     *  selection (all books; the recent view carries no selection). A non-empty
     *  format selection becomes {@code format IN (?,…)} — one placeholder per
     *  selected format; a non-empty genre selection becomes {@code genre IS
     *  NOT NULL AND genre IN (?,…)} — one placeholder per selected genre;
     *  both — the two clauses AND-ed (formats first). */
    public String querySelection() {
        if (recentView) return null;
        boolean hasFormats = !selectedFormats.isEmpty();
        boolean hasGenres = !selectedGenres.isEmpty();
        if (!hasFormats && !hasGenres) return null;
        StringBuilder sel = new StringBuilder();
        if (hasFormats) {
            sel.append("format IN (");
            boolean first = true;
            for (String f : Formats.ALL) {
                if (selectedFormats.contains(f)) {
                    if (!first) sel.append(",");
                    first = false;
                    sel.append("?");
                }
            }
            sel.append(")");
        }
        if (hasGenres) {
            if (hasFormats) sel.append(" AND ");
            sel.append("genre IS NOT NULL AND genre IN (");
            boolean first = true;
            for (String g : selectedGenres) {
                if (!first) sel.append(",");
                first = false;
                sel.append("?");
            }
            sel.append(")");
        }
        return sel.toString();
    }

    /** The args for {@link #querySelection()}: the selected format ids in the
     *  order of {@link Formats#ALL} (deterministic, not the order the boxes
     *  were ticked), then the selected genre names in first-added order
     *  (there is no canonical genre list) — or {@code null} (no args: all
     *  books / the recent view). */
    public String[] queryArgs() {
        if (recentView) return null;
        boolean hasFormats = !selectedFormats.isEmpty();
        boolean hasGenres = !selectedGenres.isEmpty();
        if (!hasFormats && !hasGenres) return null;
        String[] args = new String[selectedFormats.size() + selectedGenres.size()];
        int i = 0;
        for (String f : Formats.ALL) {
            if (selectedFormats.contains(f)) args[i++] = f;
        }
        for (String g : selectedGenres) args[i++] = g;
        return args;
    }

    /** The format part of the selection as its own arg array (for the direct
     *  rebind path, which bypasses the provider and calls {@code
     *  BookDatabase.cursorAll(String[], String[])}): the selected ids in
     *  {@link Formats#ALL} order, or {@code null} for no format restriction. */
    public String[] formatFilterArgs() {
        if (selectedFormats.isEmpty()) return null;
        String[] args = new String[selectedFormats.size()];
        int i = 0;
        for (String f : Formats.ALL) {
            if (selectedFormats.contains(f)) args[i++] = f;
        }
        return args;
    }

    /** The genre part of the selection as its own arg array (see
     *  {@link #formatFilterArgs()}): the selected names in first-added order,
     *  or {@code null} for no genre restriction. */
    public String[] genreFilterArgs() {
        if (selectedGenres.isEmpty()) return null;
        return selectedGenres.toArray(new String[0]);
    }

    /** Saves the state into the instance state (the filter is session-only —
     *  see the class javadoc). */
    public void save(Bundle outState) {
        outState.putStringArray(KEY_FORMATS, selectedFormats.toArray(new String[0]));
        outState.putStringArray(KEY_GENRES, selectedGenres.toArray(new String[0]));
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
        String[] genres = inState.getStringArray(KEY_GENRES);
        if (genres != null) {
            for (String g : genres) state.selectedGenres.add(g);
        }
        state.recentView = inState.getBoolean(KEY_RECENT, false);
        return state;
    }
}
