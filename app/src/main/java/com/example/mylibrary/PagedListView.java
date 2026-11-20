package com.example.mylibrary;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ListView;

/**
 * The catalog's {@link ListView}. The three page-swipe overrides delegate to
 * {@link PageSwipeControl} (gesture bookkeeping is {@link PageSwipeTracker}).
 */
public class PagedListView extends ListView {

    private PageSwipeControl control;

    public PagedListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Wires the page-swipe (the view's slop is display-dependent, so it is passed
     *  in from the activity once measured). */
    void enablePageSwipe(int slop, PageSwipeTracker.OnSwipe listener) {
        control = new PageSwipeControl(this, slop, listener);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (control != null) {
            control.onDispatch(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (control != null && control.ownsGesture()) {
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (control != null && control.ownsGesture()) {
            return true;
        }
        return super.onTouchEvent(ev);
    }
}
