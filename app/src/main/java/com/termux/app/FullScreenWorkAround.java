package com.termux.app;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

/**
 * Work around for fullscreen mode to keep ExtraKeysView visible when the soft keyboard is shown.
 * Derived from the Android fullscreen/soft-keyboard layout workaround used by Termux-X11.
 */
public class FullScreenWorkAround {
    private View mChildOfContent;
    private int mUsableHeightPrevious;
    private ViewGroup.LayoutParams mViewGroupLayoutParams;
    private int mNavBarHeight;

    public static void apply(TermuxActivity activity) {
        new FullScreenWorkAround(activity);
    }

    private FullScreenWorkAround(TermuxActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        mChildOfContent = content.getChildAt(0);
        mViewGroupLayoutParams = mChildOfContent.getLayoutParams();
        mNavBarHeight = activity.getNavBarHeight();
        mChildOfContent.getViewTreeObserver().addOnGlobalLayoutListener(this::possiblyResizeChildOfContent);
    }

    private void possiblyResizeChildOfContent() {
        int usableHeightNow = computeUsableHeight();
        if (usableHeightNow != mUsableHeightPrevious) {
            int usableHeightSansKeyboard = mChildOfContent.getRootView().getHeight();
            int heightDifference = usableHeightSansKeyboard - usableHeightNow;
            if (heightDifference > (usableHeightSansKeyboard / 4)) {
                mViewGroupLayoutParams.height = (usableHeightSansKeyboard - heightDifference) + getNavBarHeight();
            } else {
                mViewGroupLayoutParams.height = usableHeightSansKeyboard;
            }
            mChildOfContent.requestLayout();
            mUsableHeightPrevious = usableHeightNow;
        }
    }

    private int getNavBarHeight() {
        return mNavBarHeight;
    }

    private int computeUsableHeight() {
        Rect r = new Rect();
        mChildOfContent.getWindowVisibleDisplayFrame(r);
        return r.bottom - r.top;
    }
}
