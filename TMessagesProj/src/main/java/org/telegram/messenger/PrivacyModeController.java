package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class PrivacyModeController {
    private static final String INVISIBLE_MODE = "privacy_invisible_mode";
    private static final String INVISIBLE_SETTINGS = "privacy_invisible_settings";
    private static final String SAFE_MODE = "privacy_safe_mode";
    private static final String SAFE_MODE_EXCEPTIONS = "privacy_safe_mode_exceptions";

    private static final int[] INVISIBLE_TYPES = {
            ContactsController.PRIVACY_RULES_TYPE_PHONE,
            ContactsController.PRIVACY_RULES_TYPE_LASTSEEN,
            ContactsController.PRIVACY_RULES_TYPE_PHOTO,
            ContactsController.PRIVACY_RULES_TYPE_FORWARDS,
            ContactsController.PRIVACY_RULES_TYPE_CALLS,
            ContactsController.PRIVACY_RULES_TYPE_VOICE_MESSAGES,
            ContactsController.PRIVACY_RULES_TYPE_BIRTHDAY,
            ContactsController.PRIVACY_RULES_TYPE_GIFTS,
            ContactsController.PRIVACY_RULES_TYPE_BIO,
            ContactsController.PRIVACY_RULES_TYPE_MUSIC,
            ContactsController.PRIVACY_RULES_TYPE_INVITE
    };

    private static final int[] SAFE_MODE_TYPES = {
            ContactsController.PRIVACY_RULES_TYPE_PHONE,
            ContactsController.PRIVACY_RULES_TYPE_LASTSEEN,
            ContactsController.PRIVACY_RULES_TYPE_PHOTO,
            ContactsController.PRIVACY_RULES_TYPE_FORWARDS,
            ContactsController.PRIVACY_RULES_TYPE_CALLS,
            ContactsController.PRIVACY_RULES_TYPE_VOICE_MESSAGES,
            ContactsController.PRIVACY_RULES_TYPE_NO_PAID_MESSAGES,
            ContactsController.PRIVACY_RULES_TYPE_BIRTHDAY,
            ContactsController.PRIVACY_RULES_TYPE_GIFTS,
            ContactsController.PRIVACY_RULES_TYPE_BIO,
            ContactsController.PRIVACY_RULES_TYPE_MUSIC,
            ContactsController.PRIVACY_RULES_TYPE_INVITE
    };

    private PrivacyModeController() {
    }

    public static boolean isInvisibleModeEnabled(int account) {
        return MessagesController.getMainSettings(account).getBoolean(INVISIBLE_MODE, false);
    }

    public static void enableInvisibleMode(int account, Runnable onComplete) {
        MessagesController.getMainSettings(account).edit()
                .putString(INVISIBLE_SETTINGS, snapshotInvisibleSettings(account))
                .putBoolean(INVISIBLE_MODE, true)
                .apply();
        applyInvisibleMode(account, onComplete);
    }

    public static void disableInvisibleMode(int account, Runnable onComplete) {
        MessagesController.getMainSettings(account).edit()
                .putBoolean(INVISIBLE_MODE, false)
                .apply();
        restoreInvisibleSettings(account, () -> {
            MessagesController.getMainSettings(account).edit()
                    .remove(INVISIBLE_SETTINGS)
                    .apply();
            if (onComplete != null) onComplete.run();
        });
    }

    public static boolean isSafeModeEnabled(int account) {
        return MessagesController.getMainSettings(account).getBoolean(SAFE_MODE, false);
    }

    public static void enableSafeMode(int account, Runnable onComplete) {
        MessagesController.getMainSettings(account).edit()
                .putString(SAFE_MODE_EXCEPTIONS, snapshotExceptions(account))
                .putBoolean(SAFE_MODE, true)
                .apply();
        clearAllExceptions(account, onComplete);
    }

    public static void disableSafeMode(int account, Runnable onComplete) {
        MessagesController.getMainSettings(account).edit()
                .putBoolean(SAFE_MODE, false)
                .apply();
        restoreExceptions(account, () -> {
            MessagesController.getMainSettings(account).edit()
                    .remove(SAFE_MODE_EXCEPTIONS)
                    .apply();
            if (onComplete != null) onComplete.run();
        });
    }

    public static boolean areRulesLoaded(int account) {
        ContactsController controller = ContactsController.getInstance(account);
        for (int type = 0; type < ContactsController.PRIVACY_RULES_TYPE_COUNT; type++) {
            if (type != ContactsController.PRIVACY_RULES_TYPE_MESSAGES && controller.getLoadingPrivacyInfo(type)) {
                return false;
            }
        }
        return controller.getGlobalPrivacySettings() != null;
    }

    public static void applyInvisibleMode(int account, Runnable onComplete) {
        AtomicInteger pending = new AtomicInteger(INVISIBLE_TYPES.length + 1);
        Runnable requestDone = () -> {
            if (pending.decrementAndGet() == 0 && onComplete != null) {
                onComplete.run();
            }
        };
        boolean premium = UserConfig.getInstance(account).isPremium();
        for (int type : INVISIBLE_TYPES) {
            boolean allowAll = type == ContactsController.PRIVACY_RULES_TYPE_VOICE_MESSAGES && !premium;
            boolean allowBots = type == ContactsController.PRIVACY_RULES_TYPE_GIFTS;
            sendRules(account, type, buildInvisibleRules(account, type, allowAll, allowBots), requestDone);
        }
        setMessagesToContactsAndPremium(account, requestDone);
    }

    public static void clearAllExceptions(int account, Runnable onComplete) {
        ContactsController controller = ContactsController.getInstance(account);
        ArrayList<Integer> types = new ArrayList<>();
        for (int type : SAFE_MODE_TYPES) {
            if (hasExceptions(controller.getPrivacyRules(type))) {
                types.add(type);
            }
        }
        if (types.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        AtomicInteger pending = new AtomicInteger(types.size());
        Runnable requestDone = () -> {
            if (pending.decrementAndGet() == 0 && onComplete != null) {
                onComplete.run();
            }
        };
        for (int type : types) {
            sendRules(account, type, copyRules(account, controller.getPrivacyRules(type), false), requestDone);
        }
    }

    public static void enforceSafeMode(ArrayList<Long> plus, ArrayList<Long> minus, int account) {
        if (isSafeModeEnabled(account)) {
            if (plus != null) plus.clear();
            if (minus != null) minus.clear();
        }
    }

    private static boolean hasExceptions(ArrayList<TLRPC.PrivacyRule> rules) {
        if (rules == null) return false;
        for (TLRPC.PrivacyRule rule : rules) {
            if (rule instanceof TLRPC.TL_privacyValueAllowUsers ||
                    rule instanceof TLRPC.TL_privacyValueDisallowUsers ||
                    rule instanceof TLRPC.TL_privacyValueAllowChatParticipants ||
                    rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                return true;
            }
        }
        return false;
    }

    private static String snapshotInvisibleSettings(int account) {
        JSONObject snapshot = new JSONObject();
        JSONArray rules = new JSONArray();
        ContactsController controller = ContactsController.getInstance(account);
        try {
            for (int type : invisibleSnapshotTypes()) {
                ArrayList<TLRPC.PrivacyRule> currentRules = controller.getPrivacyRules(type);
                if (currentRules == null) continue;
                for (TLRPC.PrivacyRule rule : currentRules) {
                    JSONObject item = privacyRuleToJson(type, rule);
                    if (item != null) rules.put(item);
                }
            }
            snapshot.put("rules", rules);
            TLRPC.GlobalPrivacySettings global = controller.getGlobalPrivacySettings();
            if (global != null) {
                snapshot.put("messages_premium", global.new_noncontact_peers_require_premium);
                snapshot.put("messages_paid", (global.flags & 32) != 0);
                snapshot.put("messages_stars", global.noncontact_peers_paid_stars);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return snapshot.toString();
    }

    private static int[] invisibleSnapshotTypes() {
        int[] types = new int[INVISIBLE_TYPES.length + 1];
        System.arraycopy(INVISIBLE_TYPES, 0, types, 0, INVISIBLE_TYPES.length);
        types[types.length - 1] = ContactsController.PRIVACY_RULES_TYPE_NO_PAID_MESSAGES;
        return types;
    }

    private static JSONObject privacyRuleToJson(int type, TLRPC.PrivacyRule rule) throws Exception {
        String kind;
        ArrayList<Long> ids = null;
        if (rule instanceof TLRPC.TL_privacyValueAllowUsers) { kind = "allow_users"; ids = ((TLRPC.TL_privacyValueAllowUsers) rule).users; }
        else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) { kind = "disallow_users"; ids = ((TLRPC.TL_privacyValueDisallowUsers) rule).users; }
        else if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) { kind = "allow_chats"; ids = ((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats; }
        else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) { kind = "disallow_chats"; ids = ((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats; }
        else if (rule instanceof TLRPC.TL_privacyValueAllowAll) kind = "allow_all";
        else if (rule instanceof TLRPC.TL_privacyValueDisallowAll) kind = "disallow_all";
        else if (rule instanceof TLRPC.TL_privacyValueAllowContacts) kind = "allow_contacts";
        else if (rule instanceof TLRPC.TL_privacyValueDisallowContacts) kind = "disallow_contacts";
        else if (rule instanceof TLRPC.TL_privacyValueAllowCloseFriends) kind = "allow_close_friends";
        else if (rule instanceof TLRPC.TL_privacyValueAllowPremium) kind = "allow_premium";
        else if (rule instanceof TLRPC.TL_privacyValueAllowBots) kind = "allow_bots";
        else if (rule instanceof TLRPC.TL_privacyValueDisallowBots) kind = "disallow_bots";
        else return null;
        JSONObject item = new JSONObject().put("type", type).put("kind", kind);
        if (ids != null) {
            JSONArray jsonIds = new JSONArray();
            for (long id : ids) jsonIds.put(id);
            item.put("ids", jsonIds);
        }
        return item;
    }

    private static void restoreInvisibleSettings(int account, Runnable onComplete) {
        String value = MessagesController.getMainSettings(account).getString(INVISIBLE_SETTINGS, null);
        if (value == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        Map<Integer, ArrayList<TLRPC.InputPrivacyRule>> saved = new HashMap<>();
        JSONObject snapshot;
        try {
            snapshot = new JSONObject(value);
            JSONArray rules = snapshot.getJSONArray("rules");
            boolean keepExceptionsInactive = isSafeModeEnabled(account);
            for (int i = 0; i < rules.length(); i++) {
                JSONObject item = rules.getJSONObject(i);
                String kind = item.getString("kind");
                if (keepExceptionsInactive && isExceptionKind(kind)) continue;
                TLRPC.InputPrivacyRule rule = privacyRuleFromJson(account, item);
                if (rule != null) saved.computeIfAbsent(item.getInt("type"), key -> new ArrayList<>()).add(rule);
            }
        } catch (Exception e) {
            FileLog.e(e);
            if (onComplete != null) onComplete.run();
            return;
        }
        AtomicInteger pending = new AtomicInteger(saved.size() + 1);
        Runnable requestDone = () -> {
            if (pending.decrementAndGet() == 0 && onComplete != null) onComplete.run();
        };
        for (Map.Entry<Integer, ArrayList<TLRPC.InputPrivacyRule>> entry : saved.entrySet()) {
            sendRules(account, entry.getKey(), entry.getValue(), requestDone);
        }
        restoreMessagesSettings(account, snapshot, requestDone);
    }

    private static boolean isExceptionKind(String kind) {
        return "allow_users".equals(kind) || "disallow_users".equals(kind) || "allow_chats".equals(kind) || "disallow_chats".equals(kind);
    }

    private static TLRPC.InputPrivacyRule privacyRuleFromJson(int account, JSONObject item) throws Exception {
        String kind = item.getString("kind");
        TLRPC.InputPrivacyRule rule;
        if ("allow_users".equals(kind)) rule = new TLRPC.TL_inputPrivacyValueAllowUsers();
        else if ("disallow_users".equals(kind)) rule = new TLRPC.TL_inputPrivacyValueDisallowUsers();
        else if ("allow_chats".equals(kind)) rule = new TLRPC.TL_inputPrivacyValueAllowChatParticipants();
        else if ("disallow_chats".equals(kind)) rule = new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();
        else if ("allow_all".equals(kind)) return new TLRPC.TL_inputPrivacyValueAllowAll();
        else if ("disallow_all".equals(kind)) return new TLRPC.TL_inputPrivacyValueDisallowAll();
        else if ("allow_contacts".equals(kind)) return new TLRPC.TL_inputPrivacyValueAllowContacts();
        else if ("disallow_contacts".equals(kind)) return new TLRPC.TL_inputPrivacyValueDisallowContacts();
        else if ("allow_close_friends".equals(kind)) return new TLRPC.TL_inputPrivacyValueAllowCloseFriends();
        else if ("allow_premium".equals(kind)) return new TLRPC.TL_inputPrivacyValueAllowPremium();
        else if ("allow_bots".equals(kind)) return new TLRPC.TL_inputPrivacyValueAllowBots();
        else if ("disallow_bots".equals(kind)) return new TLRPC.TL_inputPrivacyValueDisallowBots();
        else return null;
        JSONArray ids = item.optJSONArray("ids");
        if (ids == null) return rule;
        for (int i = 0; i < ids.length(); i++) {
            long id = ids.getLong(i);
            if (rule instanceof TLRPC.TL_inputPrivacyValueAllowUsers) {
                TLRPC.InputUser user = MessagesController.getInstance(account).getInputUser(id);
                if (user != null) ((TLRPC.TL_inputPrivacyValueAllowUsers) rule).users.add(user);
            } else if (rule instanceof TLRPC.TL_inputPrivacyValueDisallowUsers) {
                TLRPC.InputUser user = MessagesController.getInstance(account).getInputUser(id);
                if (user != null) ((TLRPC.TL_inputPrivacyValueDisallowUsers) rule).users.add(user);
            } else if (rule instanceof TLRPC.TL_inputPrivacyValueAllowChatParticipants) {
                ((TLRPC.TL_inputPrivacyValueAllowChatParticipants) rule).chats.add(id);
            } else {
                ((TLRPC.TL_inputPrivacyValueDisallowChatParticipants) rule).chats.add(id);
            }
        }
        return rule;
    }

    private static void restoreMessagesSettings(int account, JSONObject snapshot, Runnable onComplete) {
        TLRPC.GlobalPrivacySettings current = ContactsController.getInstance(account).getGlobalPrivacySettings();
        TL_account.setGlobalPrivacySettings request = new TL_account.setGlobalPrivacySettings();
        request.settings = new TLRPC.TL_globalPrivacySettings();
        if (current != null) {
            request.settings.flags = current.flags;
            request.settings.archive_and_mute_new_noncontact_peers = current.archive_and_mute_new_noncontact_peers;
            request.settings.keep_archived_unmuted = current.keep_archived_unmuted;
            request.settings.keep_archived_folders = current.keep_archived_folders;
            request.settings.hide_read_marks = current.hide_read_marks;
            request.settings.display_gifts_button = current.display_gifts_button;
            request.settings.disallowed_stargifts = current.disallowed_stargifts;
        }
        boolean paid = snapshot.optBoolean("messages_paid", false);
        request.settings.flags = paid ? request.settings.flags | 32 : request.settings.flags & ~32;
        request.settings.new_noncontact_peers_require_premium = snapshot.optBoolean("messages_premium", false);
        request.settings.noncontact_peers_paid_stars = snapshot.optLong("messages_stars", 0);
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (current != null) {
                current.flags = request.settings.flags;
                current.new_noncontact_peers_require_premium = request.settings.new_noncontact_peers_require_premium;
                current.noncontact_peers_paid_stars = request.settings.noncontact_peers_paid_stars;
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.privacyRulesUpdated);
            onComplete.run();
        }), ConnectionsManager.RequestFlagFailOnServerErrorsExceptFloodWait);
    }

    private static String snapshotExceptions(int account) {
        JSONArray snapshot = new JSONArray();
        ContactsController controller = ContactsController.getInstance(account);
        try {
            for (int type : SAFE_MODE_TYPES) {
                ArrayList<TLRPC.PrivacyRule> rules = controller.getPrivacyRules(type);
                if (rules == null) continue;
                for (TLRPC.PrivacyRule rule : rules) {
                    String kind = null;
                    ArrayList<Long> ids = null;
                    if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                        kind = "allow_users";
                        ids = ((TLRPC.TL_privacyValueAllowUsers) rule).users;
                    } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                        kind = "disallow_users";
                        ids = ((TLRPC.TL_privacyValueDisallowUsers) rule).users;
                    } else if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                        kind = "allow_chats";
                        ids = ((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats;
                    } else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                        kind = "disallow_chats";
                        ids = ((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats;
                    }
                    if (kind == null || ids == null || ids.isEmpty()) continue;
                    JSONArray jsonIds = new JSONArray();
                    for (long id : ids) jsonIds.put(id);
                    snapshot.put(new JSONObject().put("type", type).put("kind", kind).put("ids", jsonIds));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return snapshot.toString();
    }

    private static void restoreExceptions(int account, Runnable onComplete) {
        String value = MessagesController.getMainSettings(account).getString(SAFE_MODE_EXCEPTIONS, null);
        if (value == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        Map<Integer, ArrayList<TLRPC.InputPrivacyRule>> saved = new HashMap<>();
        try {
            JSONArray snapshot = new JSONArray(value);
            for (int i = 0; i < snapshot.length(); i++) {
                JSONObject item = snapshot.getJSONObject(i);
                int type = item.getInt("type");
                String kind = item.getString("kind");
                JSONArray ids = item.getJSONArray("ids");
                TLRPC.InputPrivacyRule rule;
                if ("allow_users".equals(kind)) rule = new TLRPC.TL_inputPrivacyValueAllowUsers();
                else if ("disallow_users".equals(kind)) rule = new TLRPC.TL_inputPrivacyValueDisallowUsers();
                else if ("allow_chats".equals(kind)) rule = new TLRPC.TL_inputPrivacyValueAllowChatParticipants();
                else if ("disallow_chats".equals(kind)) rule = new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();
                else continue;
                for (int j = 0; j < ids.length(); j++) {
                    long id = ids.getLong(j);
                    if (rule instanceof TLRPC.TL_inputPrivacyValueAllowUsers) {
                        TLRPC.InputUser user = MessagesController.getInstance(account).getInputUser(id);
                        if (user != null) ((TLRPC.TL_inputPrivacyValueAllowUsers) rule).users.add(user);
                    } else if (rule instanceof TLRPC.TL_inputPrivacyValueDisallowUsers) {
                        TLRPC.InputUser user = MessagesController.getInstance(account).getInputUser(id);
                        if (user != null) ((TLRPC.TL_inputPrivacyValueDisallowUsers) rule).users.add(user);
                    } else if (rule instanceof TLRPC.TL_inputPrivacyValueAllowChatParticipants) {
                        ((TLRPC.TL_inputPrivacyValueAllowChatParticipants) rule).chats.add(id);
                    } else {
                        ((TLRPC.TL_inputPrivacyValueDisallowChatParticipants) rule).chats.add(id);
                    }
                }
                saved.computeIfAbsent(type, key -> new ArrayList<>()).add(rule);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (saved.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        AtomicInteger pending = new AtomicInteger(saved.size());
        Runnable requestDone = () -> {
            if (pending.decrementAndGet() == 0 && onComplete != null) onComplete.run();
        };
        ContactsController controller = ContactsController.getInstance(account);
        for (Map.Entry<Integer, ArrayList<TLRPC.InputPrivacyRule>> entry : saved.entrySet()) {
            ArrayList<TLRPC.InputPrivacyRule> rules = new ArrayList<>(entry.getValue());
            rules.addAll(copyRules(account, controller.getPrivacyRules(entry.getKey()), false));
            sendRules(account, entry.getKey(), rules, requestDone);
        }
    }

    private static ArrayList<TLRPC.InputPrivacyRule> buildInvisibleRules(int account, int type, boolean allowAll, boolean allowBots) {
        ArrayList<TLRPC.InputPrivacyRule> result = copyRules(account, ContactsController.getInstance(account).getPrivacyRules(type), true);
        result.add(allowAll ? new TLRPC.TL_inputPrivacyValueAllowAll() : new TLRPC.TL_inputPrivacyValueDisallowAll());
        if (allowBots) {
            result.add(new TLRPC.TL_inputPrivacyValueAllowBots());
        }
        return result;
    }

    private static ArrayList<TLRPC.InputPrivacyRule> copyRules(int account, ArrayList<TLRPC.PrivacyRule> rules, boolean exceptionsOnly) {
        ArrayList<TLRPC.InputPrivacyRule> result = new ArrayList<>();
        if (rules == null) return result;
        for (TLRPC.PrivacyRule rule : rules) {
            if (rule instanceof TLRPC.TL_privacyValueAllowUsers || rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                if (!exceptionsOnly) continue;
                ArrayList<Long> ids = rule instanceof TLRPC.TL_privacyValueAllowUsers
                        ? ((TLRPC.TL_privacyValueAllowUsers) rule).users
                        : ((TLRPC.TL_privacyValueDisallowUsers) rule).users;
                TLRPC.InputPrivacyRule inputRule = rule instanceof TLRPC.TL_privacyValueAllowUsers
                        ? new TLRPC.TL_inputPrivacyValueAllowUsers()
                        : new TLRPC.TL_inputPrivacyValueDisallowUsers();
                ArrayList<TLRPC.InputUser> users = inputRule instanceof TLRPC.TL_inputPrivacyValueAllowUsers
                        ? ((TLRPC.TL_inputPrivacyValueAllowUsers) inputRule).users
                        : ((TLRPC.TL_inputPrivacyValueDisallowUsers) inputRule).users;
                for (long id : ids) {
                    TLRPC.User user = MessagesController.getInstance(account).getUser(id);
                    if (user != null) users.add(MessagesController.getInstance(account).getInputUser(user));
                }
                if (!users.isEmpty()) result.add(inputRule);
            } else if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                if (!exceptionsOnly) continue;
                TLRPC.TL_inputPrivacyValueAllowChatParticipants input = new TLRPC.TL_inputPrivacyValueAllowChatParticipants();
                input.chats.addAll(((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats);
                if (!input.chats.isEmpty()) result.add(input);
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                if (!exceptionsOnly) continue;
                TLRPC.TL_inputPrivacyValueDisallowChatParticipants input = new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();
                input.chats.addAll(((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats);
                if (!input.chats.isEmpty()) result.add(input);
            } else if (!exceptionsOnly) {
                if (rule instanceof TLRPC.TL_privacyValueAllowAll) result.add(new TLRPC.TL_inputPrivacyValueAllowAll());
                else if (rule instanceof TLRPC.TL_privacyValueDisallowAll) result.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
                else if (rule instanceof TLRPC.TL_privacyValueAllowContacts) result.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
                else if (rule instanceof TLRPC.TL_privacyValueDisallowContacts) result.add(new TLRPC.TL_inputPrivacyValueDisallowContacts());
                else if (rule instanceof TLRPC.TL_privacyValueAllowCloseFriends) result.add(new TLRPC.TL_inputPrivacyValueAllowCloseFriends());
                else if (rule instanceof TLRPC.TL_privacyValueAllowPremium) result.add(new TLRPC.TL_inputPrivacyValueAllowPremium());
                else if (rule instanceof TLRPC.TL_privacyValueAllowBots) result.add(new TLRPC.TL_inputPrivacyValueAllowBots());
                else if (rule instanceof TLRPC.TL_privacyValueDisallowBots) result.add(new TLRPC.TL_inputPrivacyValueDisallowBots());
            }
        }
        return result;
    }

    private static void sendRules(int account, int type, ArrayList<TLRPC.InputPrivacyRule> rules, Runnable onComplete) {
        TL_account.setPrivacy request = new TL_account.setPrivacy();
        request.key = getKey(type);
        if (request.key == null) {
            onComplete.run();
            return;
        }
        request.rules.addAll(rules);
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TL_account.privacyRules) {
                TL_account.privacyRules result = (TL_account.privacyRules) response;
                MessagesController.getInstance(account).putUsers(result.users, false);
                MessagesController.getInstance(account).putChats(result.chats, false);
                ContactsController.getInstance(account).setPrivacyRules(result.rules, type);
            }
            onComplete.run();
        }), ConnectionsManager.RequestFlagFailOnServerErrorsExceptFloodWait);
    }

    private static TLRPC.InputPrivacyKey getKey(int type) {
        switch (type) {
            case ContactsController.PRIVACY_RULES_TYPE_LASTSEEN: return new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
            case ContactsController.PRIVACY_RULES_TYPE_INVITE: return new TLRPC.TL_inputPrivacyKeyChatInvite();
            case ContactsController.PRIVACY_RULES_TYPE_CALLS: return new TLRPC.TL_inputPrivacyKeyPhoneCall();
            case ContactsController.PRIVACY_RULES_TYPE_P2P: return new TLRPC.TL_inputPrivacyKeyPhoneP2P();
            case ContactsController.PRIVACY_RULES_TYPE_PHOTO: return new TLRPC.TL_inputPrivacyKeyProfilePhoto();
            case ContactsController.PRIVACY_RULES_TYPE_FORWARDS: return new TLRPC.TL_inputPrivacyKeyForwards();
            case ContactsController.PRIVACY_RULES_TYPE_PHONE: return new TLRPC.TL_inputPrivacyKeyPhoneNumber();
            case ContactsController.PRIVACY_RULES_TYPE_ADDED_BY_PHONE: return new TLRPC.TL_inputPrivacyKeyAddedByPhone();
            case ContactsController.PRIVACY_RULES_TYPE_VOICE_MESSAGES: return new TLRPC.TL_inputPrivacyKeyVoiceMessages();
            case ContactsController.PRIVACY_RULES_TYPE_BIO: return new TLRPC.TL_inputPrivacyKeyAbout();
            case ContactsController.PRIVACY_RULES_TYPE_BIRTHDAY: return new TLRPC.TL_inputPrivacyKeyBirthday();
            case ContactsController.PRIVACY_RULES_TYPE_GIFTS: return new TLRPC.TL_inputPrivacyKeyStarGiftsAutoSave();
            case ContactsController.PRIVACY_RULES_TYPE_NO_PAID_MESSAGES: return new TLRPC.TL_inputPrivacyKeyNoPaidMessages();
            case ContactsController.PRIVACY_RULES_TYPE_MUSIC: return new TLRPC.TL_inputPrivacyKeySavedMusic();
            default: return null;
        }
    }

    private static void setMessagesToContactsAndPremium(int account, Runnable onComplete) {
        TLRPC.GlobalPrivacySettings current = ContactsController.getInstance(account).getGlobalPrivacySettings();
        TL_account.setGlobalPrivacySettings request = new TL_account.setGlobalPrivacySettings();
        request.settings = new TLRPC.TL_globalPrivacySettings();
        if (current != null) {
            request.settings.flags = current.flags & ~32;
            request.settings.archive_and_mute_new_noncontact_peers = current.archive_and_mute_new_noncontact_peers;
            request.settings.keep_archived_unmuted = current.keep_archived_unmuted;
            request.settings.keep_archived_folders = current.keep_archived_folders;
            request.settings.hide_read_marks = current.hide_read_marks;
            request.settings.display_gifts_button = current.display_gifts_button;
            request.settings.disallowed_stargifts = current.disallowed_stargifts;
        }
        request.settings.new_noncontact_peers_require_premium = true;
        request.settings.noncontact_peers_paid_stars = 0;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (current != null) {
                current.flags = request.settings.flags;
                current.new_noncontact_peers_require_premium = true;
                current.noncontact_peers_paid_stars = 0;
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.privacyRulesUpdated);
            onComplete.run();
        }), ConnectionsManager.RequestFlagFailOnServerErrorsExceptFloodWait);
    }
}
