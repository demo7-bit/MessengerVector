package org.telegram.messenger;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StringHelper {
    private static final Pattern LINK_PATTERN = Pattern.compile("(?i)(?:(?:https?|tg)://|www\\.|(?:[\\p{L}\\p{N}-]+\\.)+[a-z]{2,})(?:\\S*)");
    private static final Pattern TELEGRAM_PRODUCT_PATTERN = Pattern.compile(
            "(?:Telegram\\s+(?:Premium|Stars|(?:for\\s+)?Business|FAQ|Features)|Телеграм\\s+(?:Премиум|Звёзды|для\\s+бизнеса))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Replacement[] REPLACEMENTS = {
            new Replacement("Telegram", "Vector"),
            new Replacement("Телеграм", "Вектор")
    };
    private static final Replacement[] RESTORATIONS = {
            new Replacement("Vector", "Telegram"),
            new Replacement("Вектор", "Телеграм")
    };

    private StringHelper() {}

    public static String replaceTelegram(String text) {
        return replaceTelegram(null, text);
    }

    public static String replaceTelegram(String key, String text) {
        if (text == null || !isSupportedSystemLanguage() || shouldKeepTelegramBrand(key, text)) return text;
        Matcher linkMatcher = LINK_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        int lastEnd = 0;
        while (linkMatcher.find()) {
            result.append(replacePlainText(text.substring(lastEnd, linkMatcher.start())));
            result.append(linkMatcher.group());
            lastEnd = linkMatcher.end();
        }
        result.append(replacePlainText(text.substring(lastEnd)));
        return result.toString();
    }

    public static String restoreTelegram(String text) {
        String result = text;
        if (result == null) {
            return null;
        }
        for (Replacement restoration : RESTORATIONS) {
            result = restoration.pattern.matcher(result).replaceAll(Matcher.quoteReplacement(restoration.value));
        }
        return result;
    }

    private static boolean shouldKeepTelegramBrand(String key, String text) {
        if (TELEGRAM_PRODUCT_PATTERN.matcher(text).find()) {
            return true;
        }
        if (key == null) {
            return false;
        }
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        return normalizedKey.contains("premium")
                || normalizedKey.contains("stars")
                || normalizedKey.contains("business")
                || normalizedKey.contains("faq")
                || normalizedKey.contains("gift")
                || "showadsinfo".equals(normalizedKey)
                || "botmonetizationinfo".equals(normalizedKey)
                || "telegramfeatures".equals(normalizedKey)
                || "telegramversion".equals(normalizedKey)
                || "vectorunofficialclientnotice".equals(normalizedKey);
    }

    private static boolean isSupportedSystemLanguage() {
        String language = Locale.getDefault().getLanguage();
        return "ru".equalsIgnoreCase(language) || "en".equalsIgnoreCase(language);
    }

    private static String replacePlainText(String text) {
        String result = text;
        for (Replacement replacement : REPLACEMENTS) {
            result = replacement.pattern.matcher(result).replaceAll(Matcher.quoteReplacement(replacement.value));
        }
        return result;
    }

    private static final class Replacement {
        private final Pattern pattern;
        private final String value;

        private Replacement(String source, String value) {
            this.pattern = Pattern.compile(Pattern.quote(source), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            this.value = value;
        }
    }
}
