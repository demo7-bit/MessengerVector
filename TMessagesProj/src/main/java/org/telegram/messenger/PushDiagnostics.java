/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A small, privacy-safe log surface for diagnosing the complete push path with adb.
 * Tokens and message payloads are never written to logcat.
 */
public final class PushDiagnostics {

    public static final String TAG = "VectorPush";
    private static final String VECTOR_FIREBASE_PROJECT = "vector-messenger-9c882";

    private static boolean firebaseConfigurationLogged;

    private PushDiagnostics() {
    }

    public static void log(String stage, String details) {
        if (!isEnabled()) {
            return;
        }
        Log.i(TAG, format(stage, details));
    }

    public static void error(String stage, String details, Throwable error) {
        if (!isEnabled()) {
            return;
        }
        String message = format(stage, details);
        if (error == null) {
            Log.e(TAG, message);
        } else {
            Log.e(TAG, message, error);
        }
    }

    public static synchronized void logFirebaseConfiguration(Context context) {
        if (firebaseConfigurationLogged || context == null) {
            return;
        }
        firebaseConfigurationLogged = true;
        String projectId = getStringResource(context, "project_id");
        String senderId = getStringResource(context, "gcm_defaultSenderId");
        String appId = getStringResource(context, "google_app_id");
        boolean vectorConfiguration = VECTOR_FIREBASE_PROJECT.equals(projectId);
        boolean completeConfiguration = !TextUtils.isEmpty(projectId)
                && !TextUtils.isEmpty(senderId)
                && !TextUtils.isEmpty(appId);
        log("firebase_config",
                "telegramApiId=" + BuildVars.APP_ID
                        + " package=" + context.getPackageName()
                        + " project=" + safe(projectId)
                        + " sender=" + safe(senderId)
                        + " appId=" + safe(appId)
                        + " vectorProject=" + vectorConfiguration);
        if (!completeConfiguration) {
            error("firebase_config_invalid",
                    "Required generated Firebase resources are missing for package=" + context.getPackageName(),
                    null);
        } else if (!vectorConfiguration) {
            error("firebase_config_unexpected",
                    "Unexpected Firebase project=" + projectId + "; expected=" + VECTOR_FIREBASE_PROJECT,
                    null);
        } else {
            log("firebase_config_valid", "Vector Firebase configuration is active.");
        }
    }

    public static String tokenSummary(String token) {
        if (TextUtils.isEmpty(token)) {
            return "token=empty";
        }
        return "tokenLength=" + token.length() + " tokenSha256=" + sha256Prefix(token);
    }

    public static String notificationState(Context context) {
        if (context == null) {
            return "notificationsEnabled=unknown runtimePermission=unknown";
        }
        boolean enabled = NotificationManagerCompat.from(context).areNotificationsEnabled();
        String permission;
        if (Build.VERSION.SDK_INT >= 33) {
            permission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
        } else {
            permission = "not_required";
        }
        return "notificationsEnabled=" + enabled + " runtimePermission=" + permission;
    }

    private static boolean isEnabled() {
        return BuildVars.DEBUG_VERSION || BuildVars.LOGS_ENABLED;
    }

    private static String format(String stage, String details) {
        return stage + (TextUtils.isEmpty(details) ? "" : " | " + details);
    }

    private static String safe(String value) {
        return TextUtils.isEmpty(value) ? "missing" : value;
    }

    private static String getStringResource(Context context, String name) {
        try {
            Resources resources = context.getResources();
            int id = resources.getIdentifier(name, "string", context.getPackageName());
            return id == 0 ? null : resources.getString(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                result.append(Character.forDigit((digest[i] >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(digest[i] & 0x0f, 16));
            }
            return result.toString();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }
}
