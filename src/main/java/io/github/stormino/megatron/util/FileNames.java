package io.github.stormino.megatron.util;

import java.util.Locale;
import java.util.Set;

/**
 * Turns names coming from Mega (attacker-controlled) into names that are safe on every
 * filesystem, Windows included: no path separators or traversal, no reserved characters,
 * no device names, bounded length with the extension preserved.
 */
public final class FileNames {

    public static final int MAX_FILE_NAME_LENGTH = 200;
    public static final int MAX_FOLDER_NAME_LENGTH = 120;

    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FileNames() {
    }

    public static String sanitizeFileName(String name, String fallback) {
        String cleaned = clean(name);
        if (cleaned.isEmpty()) {
            return fallback;
        }
        if (cleaned.length() > MAX_FILE_NAME_LENGTH) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 && cleaned.length() - dot <= 16 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, MAX_FILE_NAME_LENGTH - ext.length()).strip() + ext;
        }
        return guardReserved(cleaned);
    }

    public static String sanitizeFolderName(String name, String fallback) {
        String cleaned = clean(name);
        if (cleaned.length() > MAX_FOLDER_NAME_LENGTH) {
            cleaned = trimTrailing(cleaned.substring(0, MAX_FOLDER_NAME_LENGTH));
        }
        return cleaned.isEmpty() ? fallback : guardReserved(cleaned);
    }

    private static String clean(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = trimTrailing(sb.toString().strip());
        if (out.equals(".") || out.equals("..") || out.chars().allMatch(ch -> ch == '.')) {
            return "";
        }
        return out;
    }

    private static String trimTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static String guardReserved(String name) {
        int dot = name.indexOf('.');
        String stem = (dot >= 0 ? name.substring(0, dot) : name).toUpperCase(Locale.ROOT);
        return RESERVED.contains(stem) ? "_" + name : name;
    }
}
