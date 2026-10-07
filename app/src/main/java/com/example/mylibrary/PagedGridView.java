package com.example.mylibrary;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.GridView;

/**
 * The catalog's {@link GridView} with the horizontal page-swipe — see
 * {@link PagedListView} for the full rationale (gesture detection in
 * {@code dispatchTouchEvent} because an {@code OnTouchListener} on a grid is never
 * invoked for a touch on a tile; interception in {@code onInterceptTouchEvent}
 * because on API 19 a horizontal swipe over a tile would otherwise release into a
 * tile click; consumption in {@code onTouchEvent} for the events the gesture
 * takes over). When pagination is on, swiping the grid left turns to the next
 * page and right to the previous one.
 */
public class PagedGridView extends GridView {

    private PageSwipeTracker tracker;
    private PageSwipeTracker.OnSwipe swipe;

    public PagedGridView(Context context, AttributeSet attrs) {
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
            // The gesture has become a page swipe: take it away from the tile
            // (the framework will send the tile ACTION_CANCEL — its press is
            // cleared, so it cannot turn into a click).
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (tracker != null && swipe != null && tracker.swipeInFlight()) {
            // We own the gesture: consume it so the grid itself does not scroll,
            // select, or click on a horizontal page swipe.
            return true;
        }
        return super.onTouchEvent(ev);
    }
}
