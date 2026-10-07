package com.example.mylibrary;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ListView;

/**
 * The catalog's {@link ListView} with the horizontal page-swipe (see
 * {@link PageSwipeTracker}): when pagination is on, swiping the list left turns
 * to the next page and right to the previous one.
 *
 * <p>The swipe is detected in {@link #dispatchTouchEvent}, which runs before the
 * framework dispatches the gesture to the row under the finger — an
 * {@code OnTouchListener} on a plain list would never be invoked for that,
 * because the row consumes the gesture. Detection is observe-only (the event is
 * always passed on, so the normal row press/click handling is untouched), and the
 * page turn is <em>posted</em>, so it runs after the touch sequence has fully
 * unwound.</p>
 *
 * <p>One more override is needed for correctness on API 19: {@link #onInterceptTouchEvent}.
 * There, the framework only takes a touch away from a row when the <em>vertical</em>
 * movement exceeds the slop; and a row inside a list only drops its pre-pressed
 * state when the finger leaves its <em>bounds</em>. So a horizontal swipe that
 * stays inside a row would release into a row click (the "Open this book?" dialog)
 * on a real device too. The moment the gesture crosses the swipe threshold, this
 * view intercepts it — the framework then hands the row ACTION_CANCEL (no click)
 * — and {@link #onTouchEvent} swallows the remaining events so the list itself does
 * not scroll or select on a page swipe. A tap or a short wiggle below the
 * threshold is never intercepted: normal row clicks work exactly as before.</p>
 */
public class PagedListView extends ListView {

    private PageSwipeTracker tracker;
    private PageSwipeTracker.OnSwipe swipe;

    public PagedListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Wires the page-swipe (the view's slop is display-dependent, so it is passed
     *  in from the activity once measured). */
    void enablePageSwipe(int slop, PageSwipeTracker.OnSwipe listener) {
        tracker = new PageSwipeTracker(slop);
        swipe = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (tracker != null && swipe != null) {
            final int direction = tracker.onEvent(ev);
            if (direction != 0) {
                post(new Runnable() {
                    @Override public void run() {
                        swipe.onSwipe(direction);
                    }
                });
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (tracker != null && swipe != null && tracker.swipeInFlight()) {
            // The gesture has become a page swipe: take it away from the row
            // (the framework will send the row ACTION_CANCEL — its press is
            // cleared, so it cannot turn into a click).
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (tracker != null && swipe != null && tracker.swipeInFlight()) {
            // We own the gesture: consume it so the list itself does not scroll,
            // select, or click on a horizontal page swipe.
            return true;
        }
        return super.onTouchEvent(ev);
    }
}
