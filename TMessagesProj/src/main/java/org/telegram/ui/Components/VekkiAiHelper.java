package org.telegram.ui.Components;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Shared, deliberately small request builder for user-initiated Vekki actions. */
public final class VekkiAiHelper {

    public static final String BOT_USERNAME = "VekkiAI_Bot";
    public static final int MAX_ATTACHED_PHOTOS = 10;
    private static final String COMMAND_SHORT = "!vk_short";
    private static final String COMMAND_EXPLAIN = "!vk_explain";

    // Used only until Telegram's server-provided account limit is available. Once loaded, use the
    // exact transport limit (currently 4096 standard / 8192 Premium) so a one-shot AI request is
    // never needlessly shortened and never split by ChatActivityEnterView.
    public static final int FALLBACK_AI_REQUEST_LENGTH = 4096;

    public enum RequestType {
        SHORT,
        SUMMARY,
        EXPLAIN
    }

    public static final class PreparedRequest {
        @NonNull
        public final String text;
        @NonNull
        public final List<MessageObject> attachedPhotoMessages;

        private PreparedRequest(@NonNull String text, @NonNull List<MessageObject> attachedPhotoMessages) {
            this.text = text;
            this.attachedPhotoMessages = Collections.unmodifiableList(new ArrayList<>(attachedPhotoMessages));
        }

        public boolean hasAttachedPhotos() {
            return !attachedPhotoMessages.isEmpty();
        }
    }

    private VekkiAiHelper() {
    }

    public static boolean isVekkiBot(@Nullable TLRPC.User user) {
        return user != null && user.bot && UserObject.hasPublicUsername(user, BOT_USERNAME);
    }

    public static int getSafeRequestLimit(int account) {
        int configuredLimit = MessagesController.getInstance(account).getMaxMessageLength();
        if (configuredLimit <= 0) {
            configuredLimit = FALLBACK_AI_REQUEST_LENGTH;
        }
        return Math.max(1, configuredLimit);
    }

    public static int getSafePhotoCaptionLimit(int account) {
        int configuredLimit = MessagesController.getInstance(account).getCaptionMaxLengthLimit();
        if (configuredLimit <= 0) {
            configuredLimit = 1024;
        }
        return Math.max(1, configuredLimit);
    }

    public static boolean hasAnalyzableContent(@Nullable MessageObject message) {
        return !TextUtils.isEmpty(toSafeContent(message));
    }

    /**
     * Builds one bounded request. Messages are sorted chronologically; if the full set does not
     * fit, the newest contiguous portion wins and no individual message is split unless even the
     * newest message alone exceeds the available payload.
     */
    @Nullable
    public static String buildRequest(int account, @Nullable List<MessageObject> source, @NonNull RequestType type) {
        PreparedRequest request = prepareRequest(account, source, type);
        return request == null ? null : request.text;
    }

    /**
     * Takes an immutable snapshot of the request text and keeps existing Telegram server media
     * references for up to ten selected photos. The caller sends one photo normally or multiple
     * photos as one media group with a single request caption, without downloading or copying the
     * files.
     */
    @Nullable
    public static PreparedRequest prepareRequest(int account, @Nullable List<MessageObject> source,
                                                 @NonNull RequestType type) {
        if (source == null || source.isEmpty()) {
            return null;
        }

        ArrayList<MessageObject> sorted = new ArrayList<>(source.size());
        for (MessageObject message : source) {
            if (hasAnalyzableContent(message)) {
                sorted.add(message);
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        Collections.sort(sorted, MESSAGE_COMPARATOR);

        ArrayList<MessageObject> transferablePhotos = new ArrayList<>();
        for (MessageObject message : sorted) {
            if (hasTransferablePhoto(message)) {
                transferablePhotos.add(message);
            }
        }

        ArrayList<MessageObject> attachedPhotoMessages = new ArrayList<>();
        if (type == RequestType.SHORT || type == RequestType.EXPLAIN) {
            if (transferablePhotos.size() > MAX_ATTACHED_PHOTOS) {
                return null;
            }
            attachedPhotoMessages.addAll(transferablePhotos);
        } else if (transferablePhotos.size() == 1) {
            // Preserve the existing unread-summary behavior; albums are only user-selected
            // Short/Explain requests.
            attachedPhotoMessages.add(transferablePhotos.get(0));
        }

        ArrayList<String> contents = new ArrayList<>(sorted.size());
        for (MessageObject message : sorted) {
            String content = toSafeContent(message);
            if (!TextUtils.isEmpty(content)) {
                contents.add(content);
            }
        }
        if (contents.isEmpty()) {
            return null;
        }

        final int limit = attachedPhotoMessages.isEmpty()
                ? getSafeRequestLimit(account) : getSafePhotoCaptionLimit(account);
        if (type == RequestType.SHORT || type == RequestType.EXPLAIN) {
            String command = type == RequestType.SHORT ? COMMAND_SHORT : COMMAND_EXPLAIN;
            String header = command + "\n";
            FitResult fit = fitNewest(contents, limit - header.length());
            if (fit.text.length() == 0) {
                return null;
            }
            return new PreparedRequest(header + fit.text, attachedPhotoMessages);
        }

        final int instructionRes;
        instructionRes = R.string.VekkiAIPromptSummary;
        String instruction = LocaleController.getString(instructionRes);
        if (!attachedPhotoMessages.isEmpty()) {
            instruction += "\n" + LocaleController.getString(R.string.VekkiAIPromptImageAttached);
        }
        final String dataGuard = LocaleController.getString(R.string.VekkiAIPromptDataGuard);
        final String dataStart = LocaleController.getString(R.string.VekkiAIPromptDataStart);
        final String dataEnd = LocaleController.getString(R.string.VekkiAIPromptDataEnd);
        final String limitNote = LocaleController.getString(R.string.VekkiAIPromptLimitNote);

        String header = instruction + "\n\n" + dataGuard + "\n\n" + dataStart + "\n";
        String footer = "\n" + dataEnd;
        FitResult fit = fitNewest(contents, limit - header.length() - footer.length());
        if (fit.limited) {
            header = instruction + "\n\n" + dataGuard + "\n\n" + limitNote + "\n\n" + dataStart + "\n";
            fit = fitNewest(contents, limit - header.length() - footer.length());
        }
        if (fit.text.length() == 0) {
            return null;
        }

        String result = header + fit.text + footer;
        if (result.length() > limit) {
            // Defensive only: fitNewest already budgets in UTF-16 units.
            result = safePrefix(result, limit);
        }
        return new PreparedRequest(result, attachedPhotoMessages);
    }

    public static boolean hasTooManyTransferablePhotos(@Nullable List<MessageObject> source) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        int count = 0;
        for (MessageObject message : source) {
            if (hasTransferablePhoto(message) && ++count > MAX_ATTACHED_PHOTOS) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTransferablePhoto(@Nullable MessageObject message) {
        if (message == null || message.messageOwner == null || !message.isPhoto()) {
            return false;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        return media != null && media.photo instanceof TLRPC.TL_photo;
    }

    @NonNull
    public static String toSafeContent(@Nullable MessageObject message) {
        if (message == null || message.messageOwner == null || message.isSponsored()) {
            return "";
        }

        String attachment = null;
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (message.isPhoto()) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaPhoto);
        } else if (message.isRoundVideo()) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaRoundVideo);
        } else if (message.isGif()) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaGif);
        } else if (message.isVideo()) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaVideo);
        } else if (message.isVoice()) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaVoice);
        } else if (message.isAnyKindOfSticker()) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaSticker);
        } else if (message.isMusic()) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaMusic);
        } else if (media instanceof TLRPC.TL_messageMediaContact) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaContact);
        } else if (message.isLocation()) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaLocation);
        } else if (media instanceof TLRPC.TL_messageMediaPoll) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaPoll);
            TLRPC.TL_messageMediaPoll poll = (TLRPC.TL_messageMediaPoll) media;
            if (poll.poll != null && poll.poll.question != null && !TextUtils.isEmpty(poll.poll.question.text)) {
                attachment += "\n" + poll.poll.question.text.trim();
            }
        } else if (media instanceof TLRPC.TL_messageMediaToDo) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaTodo);
        } else if (message.isStoryMedia()) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaStory);
        } else if (message.getDocument() != null) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaFile);
        } else if (media != null && !(media instanceof TLRPC.TL_messageMediaEmpty)
                && !(media instanceof TLRPC.TL_messageMediaWebPage)) {
            attachment = LocaleController.getString(R.string.VekkiAIMediaAttachment);
        }

        String text = message.messageOwner.message == null ? "" : message.messageOwner.message.trim();
        if (TextUtils.isEmpty(attachment)) {
            return text;
        }
        return TextUtils.isEmpty(text) ? attachment : attachment + "\n" + text;
    }

    private static final Comparator<MessageObject> MESSAGE_COMPARATOR = (left, right) -> {
        int byDate = Integer.compare(left.messageOwner.date, right.messageOwner.date);
        if (byDate != 0) {
            return byDate;
        }
        return Integer.compare(left.getId(), right.getId());
    };

    private static FitResult fitNewest(List<String> contents, int budget) {
        if (budget <= 0) {
            return new FitResult("", true);
        }
        ArrayList<String> selected = new ArrayList<>();
        int used = 0;
        boolean limited = false;
        for (int i = contents.size() - 1; i >= 0; i--) {
            String content = contents.get(i);
            int required = content.length() + (selected.isEmpty() ? 0 : 2);
            if (required <= budget - used) {
                selected.add(content);
                used += required;
            } else {
                limited = true;
                if (selected.isEmpty()) {
                    int contentBudget = Math.max(0, budget - 1);
                    String shortened = safePrefix(content, contentBudget);
                    if (!shortened.isEmpty()) {
                        selected.add(shortened + "…");
                    }
                }
                break;
            }
        }
        if (selected.size() < contents.size()) {
            limited = true;
        }
        Collections.reverse(selected);
        return new FitResult(TextUtils.join("\n\n", selected), limited);
    }

    private static String safePrefix(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static final class FitResult {
        final String text;
        final boolean limited;

        FitResult(String text, boolean limited) {
            this.text = text;
            this.limited = limited;
        }
    }
}
