package org.telegram.ui;

import android.view.MotionEvent;

/** Telegram's native passcode UI backed by the independent chat access-code store. */
public class ChatPasscodeActivity extends PasscodeActivity {
    private boolean modalUnlock;

    public ChatPasscodeActivity(@PasscodeActivityType int type) {
        super(type, true);
    }

    public ChatPasscodeActivity setUnlockCallback(String chatTitle, Runnable success, Runnable cancel) {
        modalUnlock = true;
        setChatPasscodeCallback(chatTitle, success, cancel);
        return this;
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return !modalUnlock && super.isSwipeBackEnabled(event);
    }

    @Override
    public boolean canBeginSlide() {
        return !modalUnlock && super.canBeginSlide();
    }
}
