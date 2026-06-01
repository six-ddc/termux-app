package com.termux.app.terminal.io;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;

import androidx.annotation.Nullable;

public class TermuxPlusExtraKeysScrollView extends HorizontalScrollView {

    private int mSnapColumnWidth;
    private int mDownX;
    private int mDownY;
    private int mLastSettleScrollX = -1;
    private final int mTouchSlop;

    private final Runnable mSettleThenSnapRunnable = new Runnable() {
        @Override
        public void run() {
            int scrollX = getScrollX();
            if (Math.abs(scrollX - mLastSettleScrollX) <= 1) {
                snapToNearestColumn();
                return;
            }

            mLastSettleScrollX = scrollX;
            postDelayed(this, 32);
        }
    };

    public TermuxPlusExtraKeysScrollView(Context context) {
        this(context, null);
    }

    public TermuxPlusExtraKeysScrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFillViewport(false);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
    }

    public void setSnapColumnWidth(int snapColumnWidth) {
        mSnapColumnWidth = Math.max(1, snapColumnWidth);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = (int) event.getX();
                mDownY = (int) event.getY();
                stopSettleSnap();
                break;
            case MotionEvent.ACTION_MOVE:
                int dx = Math.abs((int) event.getX() - mDownX);
                int dy = Math.abs((int) event.getY() - mDownY);
                if (getParent() != null && dx > mTouchSlop && dx > dy)
                    getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null)
                    getParent().requestDisallowInterceptTouchEvent(false);
                scheduleSettleSnap();
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null)
                getParent().requestDisallowInterceptTouchEvent(false);
            scheduleSettleSnap();
        }
        return handled;
    }

    @Override
    public void fling(int velocityX) {
        super.fling(velocityX);
        scheduleSettleSnap();
    }

    public void snapToNearestColumn() {
        if (mSnapColumnWidth <= 0 || getChildCount() == 0)
            return;

        int maxScrollX = Math.max(0, getChildAt(0).getWidth() - getWidth());
        int targetScrollX = Math.round(getScrollX() / (float) mSnapColumnWidth) * mSnapColumnWidth;
        targetScrollX = Math.max(0, Math.min(maxScrollX, targetScrollX));
        smoothScrollTo(targetScrollX, 0);
    }

    private void scheduleSettleSnap() {
        stopSettleSnap();
        mLastSettleScrollX = -1;
        postDelayed(mSettleThenSnapRunnable, 32);
    }

    private void stopSettleSnap() {
        removeCallbacks(mSettleThenSnapRunnable);
    }
}
