package org.telegram.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import org.telegram.ui.Components.OverlayActionBarLayoutDialog;

/** Hosts the very same Telegram passcode fragment used when removing chat protection. */
public final class ChatPasscodeDialog {
    private ChatPasscodeDialog() {}

    public static Dialog create(Context context, Runnable success, Runnable cancel) {
        OverlayActionBarLayoutDialog dialog = new OverlayActionBarLayoutDialog(context, null);
        ChatPasscodeActivity passcode = new ChatPasscodeActivity(
                PasscodeActivity.TYPE_ENTER_CODE_TO_MANAGE_SETTINGS).setUnlockCallback(
                null, success, cancel);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setDismissOnOutsideTouchEnabled(false);
        final boolean[] added = {false};
        dialog.setOnShowListener(d -> {
            makeFullscreen(dialog);
            if (!added[0]) {
                added[0] = true;
                dialog.addFragment(passcode, true);
            }
        });
        return dialog;
    }

    private static void makeFullscreen(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        window.setAttributes(params);
        window.getDecorView().setSystemUiVisibility(
                window.getDecorView().getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
}
