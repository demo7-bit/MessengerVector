package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Local, device-only protection for individual dialogs. */
public final class ChatPasscodeController {
    private static final long DEFAULT_AUTO_LOCK_MS = 30_000L;
    private static final long EXPIRY_MS = 30L * 24 * 60 * 60 * 1000;
    private static final String PREF_HASH = "chat_passcode_hash";
    private static final String PREF_SALT = "chat_passcode_salt";
    private static final String PREF_DIALOGS = "chat_passcode_dialogs";
    private static final String PREF_LAST_USED = "chat_passcode_last_used";
    private static final String PREF_BIOMETRIC = "chat_passcode_biometric";
    private static final String PREF_AUTO_LOCK = "chat_passcode_auto_lock";
    private static final ConcurrentHashMap<Long, Long> unlockedUntil = new ConcurrentHashMap<>();

    private ChatPasscodeController() {}

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean hasPasscode() {
        expireIfNeeded();
        return !prefs().getString(PREF_HASH, "").isEmpty();
    }

    public static void setPasscode(String passcode) {
        if (passcode == null || !passcode.matches("\\d{4}")) {
            throw new IllegalArgumentException("Chat access code must contain exactly four digits");
        }
        byte[] salt = new byte[24];
        new SecureRandom().nextBytes(salt);
        prefs().edit()
                .putString(PREF_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(PREF_HASH, hash(passcode, salt))
                .putLong(PREF_LAST_USED, System.currentTimeMillis())
                .apply();
        unlockedUntil.clear();
    }

    public static boolean checkPasscode(String passcode) {
        String encodedSalt = prefs().getString(PREF_SALT, "");
        String expected = prefs().getString(PREF_HASH, "");
        if (encodedSalt.isEmpty() || expected.isEmpty()) return false;
        boolean valid = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                hash(passcode, Base64.decode(encodedSalt, Base64.NO_WRAP)).getBytes(StandardCharsets.US_ASCII));
        if (valid) prefs().edit().putLong(PREF_LAST_USED, System.currentTimeMillis()).apply();
        return valid;
    }

    public static void clearPasscode() {
        prefs().edit().remove(PREF_HASH).remove(PREF_SALT).remove(PREF_DIALOGS)
                .remove(PREF_LAST_USED).remove(PREF_AUTO_LOCK).putBoolean(PREF_BIOMETRIC, false).apply();
        unlockedUntil.clear();
    }

    public static boolean isProtected(long dialogId) {
        expireIfNeeded();
        return prefs().getStringSet(PREF_DIALOGS, java.util.Collections.emptySet()).contains(Long.toString(dialogId));
    }

    public static void setProtected(long dialogId, boolean value) {
        Set<String> ids = new HashSet<>(prefs().getStringSet(PREF_DIALOGS, java.util.Collections.emptySet()));
        if (value) ids.add(Long.toString(dialogId)); else ids.remove(Long.toString(dialogId));
        prefs().edit().putStringSet(PREF_DIALOGS, ids).apply();
        unlockedUntil.remove(dialogId);
    }

    public static void unlock(long dialogId) {
        // Long.MAX_VALUE represents the currently open authenticated session.
        // onPause converts it to the 30-second return grace period.
        unlockedUntil.put(dialogId, Long.MAX_VALUE);
        prefs().edit().putLong(PREF_LAST_USED, System.currentTimeMillis()).apply();
    }

    public static void recordSuccessfulUse() {
        prefs().edit().putLong(PREF_LAST_USED, System.currentTimeMillis()).apply();
    }

    public static boolean isUnlocked(long dialogId) {
        Long until = unlockedUntil.get(dialogId);
        boolean unlocked = until != null && until > SystemClock.elapsedRealtime();
        if (!unlocked && until != null) unlockedUntil.remove(dialogId, until);
        return unlocked;
    }

    public static void resumeSession(long dialogId) {
        if (isUnlocked(dialogId)) unlockedUntil.put(dialogId, Long.MAX_VALUE);
    }

    public static void extendGrace(long dialogId) {
        Long until = unlockedUntil.get(dialogId);
        // Start the selected grace interval once, when an actively unlocked
        // chat is left. Repeated lifecycle callbacks must not move its end.
        if (isProtected(dialogId) && until != null && until == Long.MAX_VALUE) {
            unlockedUntil.put(dialogId, SystemClock.elapsedRealtime() + getAutoLockMs());
        }
    }

    public static boolean useBiometric() { return prefs().getBoolean(PREF_BIOMETRIC, false); }
    public static void setUseBiometric(boolean value) { prefs().edit().putBoolean(PREF_BIOMETRIC, value).apply(); }
    public static long getAutoLockMs() { return prefs().getLong(PREF_AUTO_LOCK, DEFAULT_AUTO_LOCK_MS); }
    public static void setAutoLockMs(long value) {
        if (value != 30_000L && value != 60_000L && value != 300_000L) value = DEFAULT_AUTO_LOCK_MS;
        prefs().edit().putLong(PREF_AUTO_LOCK, value).apply();
    }

    public static boolean expireIfNeeded() {
        long last = prefs().getLong(PREF_LAST_USED, 0);
        if (last > 0 && System.currentTimeMillis() - last >= EXPIRY_MS) {
            prefs().edit().remove(PREF_HASH).remove(PREF_SALT).remove(PREF_DIALOGS)
                    .remove(PREF_LAST_USED).remove(PREF_AUTO_LOCK).putBoolean(PREF_BIOMETRIC, false).apply();
            unlockedUntil.clear();
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext,
                    LocaleController.getString(R.string.ChatPasscodeExpired), Toast.LENGTH_LONG).show());
            return true;
        }
        return false;
    }

    private static String hash(String passcode, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(passcode.getBytes(StandardCharsets.UTF_8));
            return Utilities.bytesToHex(digest.digest());
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }
}
