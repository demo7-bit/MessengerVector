package org.telegram.ui.Components;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.CodeHighlighting;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.iv.MathSpan;

/** Native, bounded math formatting used only for verified incoming Vekki bot messages. */
public final class VekkiMathFormatter {

    private static final String INLINE_OPEN = "\\(";
    private static final String INLINE_CLOSE = "\\)";
    private static final String DISPLAY_OPEN = "\\[";
    private static final String DISPLAY_CLOSE = "\\]";
    private static final int MAX_FORMULAS = 12;
    private static final int MAX_SOURCE_LENGTH = 2048;

    private VekkiMathFormatter() {
    }

    @NonNull
    public static CharSequence apply(@Nullable MessageObject message, @Nullable CharSequence text,
                                     @NonNull TextPaint paint, int maxWidthPx) {
        if (TextUtils.isEmpty(text) || maxWidthPx <= 0 || !isVerifiedVekkiMessage(message)) {
            return text == null ? "" : text;
        }
        if (text instanceof Spanned
                && ((Spanned) text).getSpans(0, text.length(), MathSpan.class).length > 0) {
            return text;
        }

        SpannableStringBuilder result = new SpannableStringBuilder(text);
        boolean changed = false;
        int cursor = 0;
        int formulaCount = 0;
        while (cursor < result.length() && formulaCount < MAX_FORMULAS) {
            Delimiter delimiter = findNextOpening(result, cursor);
            if (delimiter == null) {
                break;
            }
            int sourceStart = delimiter.start + delimiter.open.length();
            int close = findClosing(result, sourceStart, delimiter.close, delimiter.display);
            if (close < 0) {
                cursor = sourceStart;
                continue;
            }
            int spanEnd = close + delimiter.close.length();
            String source = result.subSequence(sourceStart, close).toString();
            if (isValidSource(source, delimiter.display)
                    && !overlapsProtectedSpan(result, delimiter.start, spanEnd)) {
                MathSpan span = MathSpan.createForMessage(
                        source, paint.getColor(), paint.getTextSize(), maxWidthPx);
                if (span != null) {
                    result.setSpan(span, delimiter.start, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    changed = true;
                    formulaCount++;
                }
            }
            cursor = spanEnd;
        }
        return changed ? result : text;
    }

    private static boolean isVerifiedVekkiMessage(@Nullable MessageObject message) {
        if (message == null || message.messageOwner == null || message.isOut()
                || !(message.messageOwner.from_id instanceof TLRPC.TL_peerUser)) {
            return false;
        }
        TLRPC.User sender = MessagesController.getInstance(message.currentAccount)
                .getUser(message.messageOwner.from_id.user_id);
        return VekkiAiHelper.isVekkiBot(sender);
    }

    @Nullable
    private static Delimiter findNextOpening(@NonNull CharSequence text, int from) {
        for (int i = Math.max(0, from); i + 1 < text.length(); i++) {
            if (text.charAt(i) != '\\' || isEscaped(text, i)) {
                continue;
            }
            char next = text.charAt(i + 1);
            if (next == '(') {
                return new Delimiter(i, INLINE_OPEN, INLINE_CLOSE, false);
            } else if (next == '[') {
                return new Delimiter(i, DISPLAY_OPEN, DISPLAY_CLOSE, true);
            }
        }
        return null;
    }

    private static int findClosing(@NonNull CharSequence text, int from,
                                   @NonNull String close, boolean display) {
        for (int i = from; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if (!display && (c == '\n' || c == '\r')) {
                return -1;
            }
            if (c == '\\' && !isEscaped(text, i) && text.charAt(i + 1) == close.charAt(1)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isEscaped(@NonNull CharSequence text, int slashIndex) {
        int precedingSlashes = 0;
        for (int i = slashIndex - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            precedingSlashes++;
        }
        return (precedingSlashes & 1) != 0;
    }

    private static boolean isValidSource(@NonNull String source, boolean display) {
        if (source.isEmpty() || source.length() > MAX_SOURCE_LENGTH
                || !source.equals(source.trim()) || !display && (source.indexOf('\n') >= 0 || source.indexOf('\r') >= 0)) {
            return false;
        }
        int braceDepth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                return false;
            }
            if (c == '{' && !isEscaped(source, i)) {
                braceDepth++;
            } else if (c == '}' && !isEscaped(source, i) && --braceDepth < 0) {
                return false;
            }
        }
        return braceDepth == 0;
    }

    private static boolean overlapsProtectedSpan(@NonNull Spanned text, int start, int end) {
        return text.getSpans(start, end, URLSpanMono.class).length > 0
                || text.getSpans(start, end, CodeHighlighting.Span.class).length > 0;
    }

    private static final class Delimiter {
        final int start;
        final String open;
        final String close;
        final boolean display;

        Delimiter(int start, String open, String close, boolean display) {
            this.start = start;
            this.open = open;
            this.close = close;
            this.display = display;
        }
    }
}
