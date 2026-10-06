package com.example.mylibrary;

import android.view.MotionEvent;

/**
 * Bookkeeping for the horizontal page-swipe gesture over the catalog's list/grid views
 * (see {@link PagedListView} / {@link PagedGridView}).
 *
 * <p>Why the views override {@code dispatchTouchEvent} instead of using an
 * {@link android.view.View.OnTouchListener}: an {@code OnTouchListener} set on a
 * list is only invoked by {@code View.dispatchTouchEvent}, and {@code ViewGroup}
 * overrides that method. It hands the event to the row/tile under the finger first,
 * and a row (clickable) consumes the whole gesture, so the list's own listener would
 * never run for a swipe started on a book — only for a touch on the list's bare
 * padding. Overriding {@code dispatchTouchEvent} in the view subclass runs before
 * any child dispatch, so every gesture is seen.</p>
 *
 * <p>The tracker is observe-only (it never consumes events itself) and needs no
 * state beyond the down point. A gesture counts as a swipe if, at release, the
 * horizontal displacement is at least 3x the touch slop AND at least 2x the vertical
 * one — so a vertical scroll or a diagonal drag never turns a page. The moment a
 * gesture crosses that threshold mid-drag it is marked "in flight" and the view
 * intercepts it (see {@link #swipeInFlight()}): on API 19 a row keeps its pre-pressed
 * state as long as the finger stays inside its bounds, so a horizontal swipe over a
 * row would otherwise release-into-a-click; the interception hands the row an
 * ACTION_CANCEL instead.</p>
 */
final class PageSwipeTracker {

    /** Reports a completed horizontal swipe. Direction: +1 = finger moved left
     *  (next page), -1 = finger moved right (previous page). */
    interface OnSwipe {
        void onSwipe(int direction);
    }

    private final int slop;
    private float downX = Float.NaN;
    private float downY;
    /** True from the moment the gesture crosses the swipe threshold until the next
     *  DOWN — the views use it to intercept the gesture and to swallow the events
     *  it has taken over (a flag cleared on the next DOWN, not on UP, so the view
     *  still owns the UP event itself). */
    private boolean swipeInFlight;

    PageSwipeTracker(int slop) {
        this.slop = slop;
    }

    /** Feed every event of the touch sequence (the views call it from
     *  {@code dispatchTouchEvent}, before the framework's own dispatch). Returns
     *  the swipe direction on a qualifying release, 0 otherwise. */
    int onEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swipeInFlight = false;
                return 0;
            case MotionEvent.ACTION_POINTER_DOWN:
                downX = Float.NaN; // a second finger: this is no longer a swipe
                swipeInFlight = false;
                return 0;
            case MotionEvent.ACTION_UP:
                int direction = 0;
                if (!Float.isNaN(downX)) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (swipeSize(dx, dy)) {
                        direction = dx < 0 ? 1 : -1;
                    }
                }
                downX = Float.NaN;
                // swipeInFlight is deliberately NOT cleared here: the view is still
                // delivering this UP through its own onTouchEvent and must keep
                // swallowing it. The next DOWN (or a CANCEL) ends the gesture.
                return direction;
            case MotionEvent.ACTION_CANCEL:
                downX = Float.NaN;
                swipeInFlight = false;
                return 0;
            default:
                if (!Float.isNaN(downX) && swipeSize(ev.getX() - downX, ev.getY() - downY)) {
                    swipeInFlight = true;
                }
                return 0;
        }
    }

    /** Whether the current gesture has crossed the swipe threshold and the view has
     *  to keep it (intercept it from the row, swallow its events). */
    boolean swipeInFlight() {
        return swipeInFlight;
    }

    private boolean swipeSize(float dx, float dy) {
        return Math.abs(dx) >= 3 * slop && Math.abs(dx) >= 2 * Math.abs(dy);
    }
}
