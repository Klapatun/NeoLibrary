package com.example.mylibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Unit tests for {@link PageSwipeTracker} — the gesture bookkeeping behind the
 * page swipe in {@code PagedListView}/{@code PagedGridView}. The end-to-end test
 * ({@code MainActivityTest.swipingTheCatalogTurnsPagesWhenPaginationIsOn}) drives
 * a real view with a real slop; here the tracker is fed synthetic events with a
 * round slop so each branch of the state machine has an exact expectation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class PageSwipeTrackerTest {

    /** Round slop: a swipe needs |dx| >= 30px and |dx| >= 2*|dy|. */
    private static final int SLOP = 10;

    private final PageSwipeTracker tracker = new PageSwipeTracker(SLOP);

    private static MotionEvent down(float x, float y) {
        return MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, x, y, 0);
    }

    private static MotionEvent move(float x, float y) {
        return MotionEvent.obtain(1000, 1050, MotionEvent.ACTION_MOVE, x, y, 0);
    }

    private static MotionEvent pointerDown(float x, float y) {
        return MotionEvent.obtain(1000, 1060, MotionEvent.ACTION_POINTER_DOWN, x, y, 0);
    }

    private static MotionEvent up(float x, float y) {
        return MotionEvent.obtain(1000, 1100, MotionEvent.ACTION_UP, x, y, 0);
    }

    private static MotionEvent cancel(float x, float y) {
        return MotionEvent.obtain(1000, 1100, MotionEvent.ACTION_CANCEL, x, y, 0);
    }

    private static void recycle(MotionEvent... events) {
        for (MotionEvent ev : events) ev.recycle();
    }

    @Test
    public void aWiggleBelowTheThresholdIsNoSwipe() {
        MotionEvent d = down(100, 100);
        MotionEvent m = move(110, 105);   // |dx| = 10 < 3x slop
        MotionEvent u = up(120, 110);     // |dx| = 20 < 3x slop
        tracker.onEvent(d);
        tracker.onEvent(m);
        assertFalse("a wiggle below the threshold must not arm the interception",
                tracker.swipeInFlight());
        assertEquals("a wiggle below the threshold must not turn a page",
                0, tracker.onEvent(u));
        recycle(d, m, u);
    }

    @Test
    public void aDiagonalDragFavoredByVerticalIsNoSwipe() {
        // |dx| reaches 3x slop but the vertical displacement dominates
        // (|dx| = 30 < 2 x 80 = 160): this is a diagonal SCROLL, not a page turn.
        MotionEvent d = down(100, 100);
        MotionEvent m = move(120, 160);
        MotionEvent u = up(130, 180);
        tracker.onEvent(d);
        tracker.onEvent(m);
        tracker.onEvent(u);
        assertFalse(tracker.swipeInFlight());
        recycle(d, m, u);
    }

    @Test
    public void aPureVerticalDragIsNoSwipe() {
        MotionEvent d = down(100, 100);
        MotionEvent m = move(104, 150);
        MotionEvent u = up(108, 220);
        tracker.onEvent(d);
        tracker.onEvent(m);
        tracker.onEvent(u);
        assertFalse(tracker.swipeInFlight());
        recycle(d, m, u);
    }

    @Test
    public void aLeftSwipeArmsInterceptionAndReportsNextPage() {
        MotionEvent d = down(100, 100);
        MotionEvent m = move(70, 103);   // |dx| = 30, |dy| = 3: crossed
        MotionEvent u = up(40, 106);
        tracker.onEvent(d);
        assertFalse("below the threshold the gesture is not a swipe yet",
                tracker.swipeInFlight());
        tracker.onEvent(m);
        assertTrue("once the threshold is crossed the view must intercept",
                tracker.swipeInFlight());
        assertEquals("finger moved left = next page", 1, tracker.onEvent(u));
        // The UP belongs to the view's own onTouchEvent — the flag stays up
        // (it is only cleared by the next DOWN or a CANCEL).
        assertTrue("the view must keep swallowing the UP it took over",
                tracker.swipeInFlight());
        recycle(d, m, u);
    }

    @Test
    public void aRightSwipeReportsThePreviousPage() {
        MotionEvent d = down(100, 100);
        MotionEvent m = move(130, 103);
        MotionEvent u = up(160, 106);
        tracker.onEvent(d);
        tracker.onEvent(m);
        assertEquals("finger moved right = previous page", -1, tracker.onEvent(u));
        recycle(d, m, u);
    }

    @Test
    public void aSecondFingerCancelsTheSwipe() {
        // Two fingers means a pinch/scroll, not a page turn: the gesture is
        // disarmed even though the first finger already crossed the threshold.
        MotionEvent d = down(100, 100);
        MotionEvent m = move(60, 102);    // crossed
        MotionEvent p = pointerDown(140, 120);
        MotionEvent u = up(30, 104);
        tracker.onEvent(d);
        tracker.onEvent(m);
        assertTrue(tracker.swipeInFlight());
        tracker.onEvent(p);
        assertFalse("a second finger must disarm the swipe", tracker.swipeInFlight());
        assertEquals("the two-finger gesture must not turn a page",
                0, tracker.onEvent(u));
        recycle(d, m, p, u);
    }

    @Test
    public void aCancelDisarmsAndTheNextGestureStartsClean() {
        MotionEvent d1 = down(100, 100);
        MotionEvent m1 = move(60, 102);   // crossed
        MotionEvent c1 = cancel(60, 102);
        tracker.onEvent(d1);
        tracker.onEvent(m1);
        assertTrue(tracker.swipeInFlight());
        tracker.onEvent(c1);
        assertFalse("CANCEL must disarm the interception", tracker.swipeInFlight());

        // The next gesture is independent: a qualifying swipe is still detected.
        MotionEvent d2 = down(100, 100);
        MotionEvent m2 = move(50, 101);
        MotionEvent u2 = up(40, 102);
        tracker.onEvent(d2);
        tracker.onEvent(m2);
        assertEquals(1, tracker.onEvent(u2));
        recycle(d1, m1, c1, d2, m2, u2);
    }

    @Test
    public void aNewDownAfterASwipeStartsClean() {
        MotionEvent d1 = down(100, 100);
        MotionEvent m1 = move(50, 101);
        MotionEvent u1 = up(40, 102);
        tracker.onEvent(d1);
        tracker.onEvent(m1);
        tracker.onEvent(u1);
        assertTrue("the flag survives the UP (the view owns the UP)", tracker.swipeInFlight());

        // ...and the next DOWN clears it, so a plain tap cannot turn a page.
        MotionEvent d2 = down(100, 100);
        tracker.onEvent(d2);
        assertFalse("a fresh DOWN must clear the previous gesture", tracker.swipeInFlight());
        MotionEvent u2 = up(102, 101);
        assertEquals("a plain tap after a swipe must not turn a page",
                0, tracker.onEvent(u2));
        recycle(d1, m1, u1, d2, u2);
    }

    @Test
    public void onceInFlightTheGestureStaysInterceptedEvenIfItDriftsBack() {
        // Hysteresis: after the threshold is crossed, a MOVE back inside the
        // threshold must NOT release the gesture to the row (the interception
        // would flicker and the row could eat the UP as a click).
        MotionEvent d = down(100, 100);
        MotionEvent m1 = move(50, 101);   // crossed
        MotionEvent m2 = move(90, 101);   // drifted back (|dx| = 10)
        MotionEvent u = up(95, 102);
        tracker.onEvent(d);
        tracker.onEvent(m1);
        assertTrue(tracker.swipeInFlight());
        tracker.onEvent(m2);
        assertTrue("the interception must not flicker once armed",
                tracker.swipeInFlight());
        // The release point itself no longer qualifies — no page turn...
        assertEquals("a drifted-back swipe must not turn a page", 0, tracker.onEvent(u));
        // ...but the view still owns the gesture.
        assertTrue(tracker.swipeInFlight());
        recycle(d, m1, m2, u);
    }
}
