package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import com.google.android.exoplayer2.util.Log;

import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.DrawerLayoutContainer;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackButtonMenu;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.List;

public class ChatActivityContainer extends FrameLayout {

    public final ChatActivity chatActivity;
    private final INavigationLayout parentLayout;
    private View fragmentView;

    public ChatActivityContainer(
        Context context,
        INavigationLayout parentLayout,
        Bundle args
    ) {
        super(context);
        this.parentLayout = parentLayout;

        chatActivity = new ChatActivity(args) {
            @Override
            public void setNavigationBarColor(int color) {}

            @Override
            protected void onSearchLoadingUpdate(boolean loading) {
                ChatActivityContainer.this.onSearchLoadingUpdate(loading);
            }

            @Override
            protected boolean allowPresentFragment() {
                return ChatActivityContainer.this.allowChatActivityNavigation();
            }

            @Override
            public void finishFragment() {
                if (!ChatActivityContainer.this.onChatActivityFinishRequested()) {
                    super.finishFragment();
                }
            }

            @Override
            public boolean finishFragment(boolean animated) {
                if (ChatActivityContainer.this.onChatActivityFinishRequested()) {
                    return true;
                }
                return super.finishFragment(animated);
            }
        };
        chatActivity.isInsideContainer = true;
    }

    private int topPadding;
    public void setTopPadding(int topPadding) {
        this.topPadding = topPadding;
    }

    protected void onSearchLoadingUpdate(boolean loading) {

    }

    protected boolean allowChatActivityNavigation() {
        return true;
    }

    protected boolean onChatActivityFinishRequested() {
        return false;
    }

    private boolean fragmentCreated;
    private boolean destroyed;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        initChatActivity();
    }

    protected void initChatActivity() {
        if (fragmentCreated || destroyed) {
            return;
        }
        if (!chatActivity.onFragmentCreate()) {
            return;
        }
        fragmentCreated = true;

        fragmentView = chatActivity.fragmentView;
        chatActivity.setParentLayout(parentLayout);
        if (fragmentView == null) {
            fragmentView = chatActivity.createView(getContext());
        } else {
            ViewGroup parent = (ViewGroup) fragmentView.getParent();
            if (parent != null) {
                chatActivity.onRemoveFromParent();
                parent.removeView(fragmentView);
            }
        }
        if (chatActivity.getChatListView() != null && topPadding != 0) {
            chatActivity.getChatListView().setPadding(0, topPadding, 0, 0);
        }
        chatActivity.openedInstantly();
        addView(fragmentView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (isActive) {
            chatActivity.onResume();
        }
    }

    private boolean isActive = true;
    public void onPause() {
        onPause(false);
    }

    public void onPause(boolean preserveInputFocus) {
        if (!isActive) {
            return;
        }
        isActive = false;
        if (fragmentView != null) {
            chatActivity.setPreserveInputFocusOnPauseOnce(preserveInputFocus);
            chatActivity.onPause();
        }
    }

    public void onResume() {
        if (destroyed || isActive) {
            return;
        }
        isActive = true;
        if (fragmentView != null) {
            chatActivity.onResume();
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        if (fragmentCreated) {
            if (isActive && fragmentView != null) {
                chatActivity.onPause();
            }
            isActive = false;
            chatActivity.onFragmentDestroy();
            chatActivity.setParentLayout(null);
        }
        fragmentCreated = false;
        fragmentView = null;
        removeAllViews();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }
}
