/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class GcmPushListenerService extends FirebaseMessagingService {

    @Override
    public void onCreate() {
        super.onCreate();
        PushDiagnostics.logFirebaseConfiguration(this);
        PushDiagnostics.log("firebase_service_create", "process=" + android.os.Process.myPid());
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String from = message.getFrom();
        Map<String, String> data = message.getData();
        long time = message.getSentTime();

        PushDiagnostics.log("firebase_message_received",
                "messageId=" + message.getMessageId()
                        + " from=" + from
                        + " sentTime=" + time
                        + " dataKeys=" + data.keySet()
                        + " hasEncryptedPayload=" + data.containsKey("p"));

        PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_FIREBASE, data.get("p"), time);
    }

    @Override
    public void onDeletedMessages() {
        PushDiagnostics.log("firebase_messages_deleted", "requesting MTProto resync");
        PushListenerController.processDeletedMessages();
    }

    @Override
    public void onNewToken(@NonNull String token) {
        AndroidUtilities.runOnUIThread(() -> {
            PushDiagnostics.log("firebase_token_refreshed", PushDiagnostics.tokenSummary(token));
            ApplicationLoader.postInitApplication();
            PushListenerController.GooglePushListenerServiceProvider.INSTANCE.onNewToken(token);
        });
    }

    @Override
    public void onDestroy() {
        PushDiagnostics.log("firebase_service_destroy", "process=" + android.os.Process.myPid());
        super.onDestroy();
    }
}
