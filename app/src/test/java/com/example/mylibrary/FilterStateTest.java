package com.example.mylibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import com.example.mylibrary.db.BookProvider;
import com.example.mylibrary.scan.Formats;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Robolectric tests for {@link FilterState} — the catalog's single filter
 * state and the single "state → query" conversion point (the modal filter's
 * plan, step 2): the dialog, the loader and the instance state all work
 * through it, and a future filter type extends exactly these expectations
 * with its own clause.
 *
 * <p>The pinned contract:</p>
 * <ul>
 * <li>an empty selection means "all books": {@link BookProvider#CONTENT_URI}
 *     with no selection;</li>
 * <li>a non-empty selection becomes {@code format IN (…)} with the
 *     placeholders and the args in the order of {@link Formats#ALL}
 *     (deterministic — not the order the boxes were ticked);</li>
 * <li>{@code recentView} wins over the format selection (mutually exclusive
 *     views — the query goes to {@link BookProvider#RECENT_URI} and carries
 *     no selection), while the selection itself stays configured (the dot on
 *     the funnel icon);</li>
 * <li>{@code copy()} is an independent draft (the cancel semantics: the real
 *     state changes only on OK);</li>
 * <li>{@code save}/{@code restore} is a Bundle round-trip, including an empty
 *     selection and a {@code null} Bundle (a cold start).</li>
 * </ul>
 *
 * <p>Robolectric (like {@code BookProviderTest}): {@code Uri} / {@code Bundle}
 * are Android types whose android.jar stubs throw in plain JUnit. Run at
 * {@code sdk = 19}.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class FilterStateTest {

    @Test
    public void anEmptyStateQueriesAllBooksAndIsNotActive() {
        FilterState state = new FilterState();

        assertFalse("an empty selection means 'all formats'", state.isActive());
        assertEquals(BookProvider.CONTENT_URI, state.queryUri());
        assertNull("no selection for the all-books view", state.querySelection());
        assertNull(state.queryArgs());
    }

    @Test
    public void aSingleFormatBecomesAnInClauseWithOnePlaceholder() {
        FilterState state = new FilterState();
        state.selectedFormats.add("EPUB");

        assertTrue(state.isActive());
        assertEquals(BookProvider.CONTENT_URI, state.queryUri());
        assertEquals("format IN (?)", state.querySelection());
        assertEquals(1, state.queryArgs().length);
        assertEquals("EPUB", state.queryArgs()[0]);
    }

    @Test
    public void twoFormatsBecomeAnInClauseWithArgsInTheOrderOfFormatsAll() {
        FilterState state = new FilterState();
        // Added in a different order than Formats.ALL (TXT first, PDF second —
        // the order the user would have ticked them): the args must come out in
        // the order of Formats.ALL (PDF before TXT), not the addition order.
        state.selectedFormats.add("TXT");
        state.selectedFormats.add("PDF");

        assertEquals(BookProvider.CONTENT_URI, state.queryUri());
        assertEquals("format IN (?,?)", state.querySelection());
        assertEquals(2, state.queryArgs().length);
        assertEquals("the args must be in the order of Formats.ALL",
                "PDF", state.queryArgs()[0]);
        assertEquals("TXT", state.queryArgs()[1]);
    }

    @Test
    public void theRecentViewWinsOverAFormatSelection() {
        FilterState state = new FilterState();
        state.selectedFormats.add("TXT");
        state.selectedFormats.add("PDF");
        state.recentView = true;

        // Recent wins (the views are mutually exclusive): the query goes to the
        // recent URI and carries no format selection at all.
        assertEquals(BookProvider.RECENT_URI, state.queryUri());
        assertNull("the recent view must not carry the format selection",
                state.querySelection());
        assertNull(state.queryArgs());
        // The selection is still configured (the funnel's dot stays) — recent
        // only hides it from the query.
        assertTrue(state.isActive());
    }

    @Test
    public void copyIsAnIndependentDraft() {
        FilterState original = new FilterState();
        original.selectedFormats.add("EPUB");

        // The dialog works on a copy of the current state...
        FilterState draft = original.copy();
        assertTrue(draft.selectedFormats.contains("EPUB"));
        assertFalse(draft.recentView);

        // ...and the user's changes in the draft must not reach the original
        // (the cancel semantics: the real state changes only on OK).
        draft.selectedFormats.add("TXT");
        draft.recentView = true;

        assertEquals(1, original.selectedFormats.size());
        assertTrue(original.selectedFormats.contains("EPUB"));
        assertFalse("the original must not gain the draft's addition",
                original.selectedFormats.contains("TXT"));
        assertFalse("the original must not gain the draft's recent view",
                original.recentView);
        // The draft itself carries both the copied state and the changes.
        assertTrue(draft.selectedFormats.contains("EPUB"));
        assertTrue(draft.selectedFormats.contains("TXT"));
        assertTrue(draft.recentView);
    }

    @Test
    public void saveAndRestoreRoundTripTheState() {
        FilterState state = new FilterState();
        state.selectedFormats.add("TXT");
        state.selectedFormats.add("EPUB");
        state.recentView = true;

        Bundle bundle = new Bundle();
        state.save(bundle);

        FilterState restored = FilterState.restore(bundle);
        assertEquals(state.selectedFormats, restored.selectedFormats);
        assertTrue(restored.recentView);
        // The restored state must convert to the same query as the original.
        assertEquals(state.queryUri(), restored.queryUri());
        assertEquals(state.querySelection(), restored.querySelection());
        assertEquals(state.queryArgs().length, restored.queryArgs().length);
        for (int i = 0; i < state.queryArgs().length; i++) {
            assertEquals(state.queryArgs()[i], restored.queryArgs()[i]);
        }
    }

    @Test
    public void saveAndRestoreSurviveAnEmptySelection() {
        FilterState state = new FilterState();

        Bundle bundle = new Bundle();
        state.save(bundle);

        FilterState restored = FilterState.restore(bundle);
        assertEquals(BookProvider.CONTENT_URI, restored.queryUri());
        assertNull("an empty selection must stay empty", restored.querySelection());
        assertNull(restored.queryArgs());
        assertFalse(restored.isActive());
        assertFalse(restored.recentView);
    }

    @Test
    public void restoreWithNoSavedStateGivesTheEmptyState() {
        // A cold start: onCreate calls FilterState.restore(savedInstanceState)
        // with savedInstanceState == null — the result must be the empty state.
        FilterState restored = FilterState.restore(null);
        assertNotNull(restored);
        assertEquals(BookProvider.CONTENT_URI, restored.queryUri());
        assertNull(restored.querySelection());
        assertFalse(restored.isActive());
        assertFalse(restored.recentView);
    }
}
