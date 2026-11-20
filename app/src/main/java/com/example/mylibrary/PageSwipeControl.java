package com.example.mylibrary;

import android.view.MotionEvent;
import android.view.View;

/**
 * Shared page-swipe glue for the catalog {@link PagedListView} and
 * {@link PagedGridView} (see {@link PageSwipeTracker}): when pagination is on,
 * swiping left turns to the next page and right to the previous one.
 *
 * <p>The swipe is detected in {@code dispatchTouchEvent}, which runs before the
 * framework dispatches the gesture to the row or tile under the finger — an
 * {@code OnTouchListener} on a plain list or grid would never be invoked for
 * that, because the row/tile consumes the gesture. Detection is observe-only
 * (the event is always passed on, so the normal row/tile press/click handling
 * is untouched), and the page turn is <em>posted</em> through the host view's
 * {@link View#post}, so it runs after the touch sequence has fully unwound.</p>
 *
 * <p>One more override is needed for correctness on API 19:
 * {@code onInterceptTouchEvent}. There, the framework only takes a touch away
 * from a row or tile when the <em>vertical</em> movement exceeds the slop; and
 * a row/tile inside a list or grid only drops its pre-pressed state when the
 * finger leaves its <em>bounds</em>. So a horizontal swipe that stays inside a
 * row or tile would release into a click (the "Open this book?" dialog) on a
 * real device too. The moment the gesture crosses the swipe threshold, the view
 * intercepts it — the framework then hands the row/tile ACTION_CANCEL (no
 * click) — and {@code onTouchEvent} swallows the remaining events, including
 * the UP, so the list or grid itself does not scroll or select on a page swipe.
 * A tap or a short wiggle below the threshold is never intercepted: normal
 * row/tile clicks work exactly as before.</p>
 */
final class PageSwipeControl {

    private final View host;
    private final PageSwipeTracker tracker;
    private final PageSwipeTracker.OnSwipe swipe;

    PageSwipeControl(View host, int slop, PageSwipeTracker.OnSwipe listener) {
        this.host = host;
        this.tracker = new PageSwipeTracker(slop);
        this.swipe = listener;
    }

    void onDispatch(MotionEvent ev) {
        final int direction = tracker.onEvent(ev);
        if (direction != 0) {
            host.post(new Runnable() {
                @Override public void run() {
                    swipe.onSwipe(direction);
                }
            });
        }
    }

    boolean ownsGesture() {
        return tracker.swipeInFlight();
    }
}
