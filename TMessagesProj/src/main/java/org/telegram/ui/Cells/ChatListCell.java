package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;

public class ChatListCell extends LinearLayout {

    public static final int TYPE_TWO_LINES = SharedConfig.CHAT_LIST_MODE_TWO_LINES;
    public static final int TYPE_THREE_LINES = SharedConfig.CHAT_LIST_MODE_THREE_LINES;
    public static final int TYPE_SINGLE_LINE = SharedConfig.CHAT_LIST_MODE_SINGLE_LINE;

    private class ListView extends FrameLayout {

        private RadioButton button;
        private final int type;
        private RectF rect = new RectF();
        private TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        public ListView(Context context, int type) {
            super(context);
            setWillNotDraw(false);

            this.type = type;
            setContentDescription(getTitle());

            textPaint.setTextSize(AndroidUtilities.dp(13));

            button = new RadioButton(context) {
                @Override
                public void invalidate() {
                    super.invalidate();
                    ListView.this.invalidate();
                }
            };
            button.setSize(AndroidUtilities.dp(20));
            addView(button, LayoutHelper.createFrame(22, 22, Gravity.RIGHT | Gravity.TOP, 0, 26, 10, 0));
            button.setChecked(type == getSelectedType(), false);
        }

        private String getTitle() {
            if (type == TYPE_THREE_LINES) {
                return LocaleController.getString(R.string.ChatListExpanded);
            } else if (type == TYPE_SINGLE_LINE) {
                return LocaleController.getString(R.string.ChatListSingleLine);
            }
            return LocaleController.getString(R.string.ChatListDefault);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int color = Theme.getColor(Theme.key_switchTrack);
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);

            button.setColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_radioBackgroundChecked));

            rect.set(AndroidUtilities.dp(1), AndroidUtilities.dp(1), getMeasuredWidth() - AndroidUtilities.dp(1), AndroidUtilities.dp(73));
            Theme.chat_instantViewRectPaint.setColor(Color.argb((int) (43 * button.getProgress()), r, g, b));
            canvas.drawRoundRect(rect, AndroidUtilities.dp(6), AndroidUtilities.dp(6), Theme.chat_instantViewRectPaint);

            rect.set(0, 0, getMeasuredWidth(), AndroidUtilities.dp(74));
            Theme.dialogs_onlineCirclePaint.setColor(Color.argb((int) (31 * (1.0f - button.getProgress())), r, g, b));
            canvas.drawRoundRect(rect, AndroidUtilities.dp(6), AndroidUtilities.dp(6), Theme.dialogs_onlineCirclePaint);

            String text = getTitle();
            int width = (int) Math.ceil(textPaint.measureText(text));

            textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            canvas.drawText(text, (getMeasuredWidth() - width) / 2, AndroidUtilities.dp(96), textPaint);

            for (int a = 0; a < 2; a++) {
                int cy = AndroidUtilities.dp(a == 0 ? 21 : 53);
                Theme.dialogs_onlineCirclePaint.setColor(Color.argb(a == 0 ? 204 : 90, r, g, b));
                canvas.drawCircle(AndroidUtilities.dp(22), cy, AndroidUtilities.dp(11), Theme.dialogs_onlineCirclePaint);

                int linesCount = type == TYPE_THREE_LINES ? 3 : type == TYPE_SINGLE_LINE ? 1 : 2;
                for (int i = 0; i < linesCount; i++) {
                    Theme.dialogs_onlineCirclePaint.setColor(Color.argb(i == 0 ? 204 : 90, r, g, b));
                    if (type == TYPE_THREE_LINES) {
                        rect.set(AndroidUtilities.dp(41), cy - AndroidUtilities.dp(8.3f - i * 7), getMeasuredWidth() - AndroidUtilities.dp(i == 0 ? 72 : 48), cy - AndroidUtilities.dp(8.3f - 3 - i * 7));
                        canvas.drawRoundRect(rect, AndroidUtilities.dpf2(1.5f), AndroidUtilities.dpf2(1.5f), Theme.dialogs_onlineCirclePaint);
                    } else if (type == TYPE_SINGLE_LINE) {
                        rect.set(AndroidUtilities.dp(41), cy - AndroidUtilities.dp(7), getMeasuredWidth() - AndroidUtilities.dp(72), cy - AndroidUtilities.dp(3));
                        canvas.drawRoundRect(rect, AndroidUtilities.dp(2), AndroidUtilities.dp(2), Theme.dialogs_onlineCirclePaint);
                    } else {
                        rect.set(AndroidUtilities.dp(41), cy - AndroidUtilities.dp(7 - i * 10), getMeasuredWidth() - AndroidUtilities.dp(i == 0 ? 72 : 48), cy - AndroidUtilities.dp(7 - 4 - i * 10));
                        canvas.drawRoundRect(rect, AndroidUtilities.dp(2), AndroidUtilities.dp(2), Theme.dialogs_onlineCirclePaint);
                    }
                }
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(RadioButton.class.getName());
            info.setChecked(button.isChecked());
            info.setCheckable(true);
            info.setContentDescription(getTitle());
        }
    }

    private final ListView[] listView = new ListView[3];

    private static int getSelectedType() {
        return SharedConfig.chatListMode;
    }

    public ChatListCell(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(10), AndroidUtilities.dp(21), 0);

        LinearLayout firstRow = new LinearLayout(context);
        firstRow.setOrientation(HORIZONTAL);
        addView(firstRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 113));

        LinearLayout secondRow = new LinearLayout(context);
        secondRow.setOrientation(HORIZONTAL);
        addView(secondRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 113));

        for (int a = 0; a < listView.length; a++) {
            final int type = a;
            listView[a] = new ListView(context, type);
            LinearLayout row = a < 2 ? firstRow : secondRow;
            row.addView(listView[a], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0.5f, a == 1 ? 10 : 0, 0, 0, 0));
            listView[a].setOnClickListener(v -> {
                for (int b = 0; b < listView.length; b++) {
                    listView[b].button.setChecked(listView[b] == v, true);
                }
                didSelectChatType(type);
            });
        }
        secondRow.addView(new FrameLayout(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0.5f, 10, 0, 0, 0));
    }

    protected void didSelectChatType(int type) {

    }

    @Override
    public void invalidate() {
        super.invalidate();
        for (int a = 0; a < listView.length; a++) {
            if (listView[a] != null) {
                listView[a].invalidate();
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(236), MeasureSpec.EXACTLY));
    }
}
