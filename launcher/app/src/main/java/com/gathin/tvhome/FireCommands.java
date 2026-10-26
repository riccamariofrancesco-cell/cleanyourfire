package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shell commands CleanupActivity sends over LocalAdb, and the parsing of what comes back. Plain Java, so the
 * JVM tests cover it without a TV.
 */
final class FireCommands {

    /** Restore record and backups, readable from a PC too (adb pull) and kept across a reinstall of this app. */
    static final String SDCARD_DIR = "/sdcard/Download";
    static final String RECORD_FILE = "cleanyourfire-ripristino.txt";

    /** Fire OS 7: FireHomeRedirect's alarm fallback needs alarms allowed to fire at once. */
    static final String MIN_FUTURITY = "min_futurity=0";

    enum Outcome { DISABLED, PROTECTED, FAILED }

    private FireCommands() {
    }

    static String disable(String pkg) {
        return "pm disable-user --user 0 " + checked(pkg);
    }

    static String enable(String pkg) {
        return "pm enable " + checked(pkg);
    }

    static String grant(String pkg, String permission) {
        return "pm grant " + checked(pkg) + " " + permission;
    }

    /** pm disable-user prints "Package x new state: disabled-user"; Fire OS refuses its core apps by name. */
    static Outcome disableOutcome(String output) {
        if (output.contains("new state: disabled-user")) return Outcome.DISABLED;
        if (output.toLowerCase(java.util.Locale.ROOT).contains("protected")) return Outcome.PROTECTED;
        return Outcome.FAILED;
    }

    /** The package names in `pm list packages` output, in order. */
    static Set<String> packages(String output) {
        Set<String> found = new LinkedHashSet<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) found.add(line.substring("package:".length()).trim());
        }
        return found;
    }

    /** enabled_accessibility_services with component added once; the other services stay. */
    static String withService(String current, String component) {
        List<String> services = services(current);
        if (!services.contains(component)) services.add(component);
        return String.join(":", services);
    }

    /** enabled_accessibility_services without component; the other services stay. */
    static String withoutService(String current, String component) {
        List<String> services = services(current);
        services.remove(component);
        return String.join(":", services);
    }

    private static List<String> services(String current) {
        List<String> services = new ArrayList<>();
        if (current == null) return services;
        for (String part : current.trim().split(":")) {
            part = part.trim();
            if (!part.isEmpty() && !"null".equals(part)) services.add(part);
        }
        return services;
    }

    /** alarm_manager_constants with min_futurity=0, keeping any other constant the firmware set. */
    static String withMinFuturity(String current) {
        List<String> parts = new ArrayList<>();
        if (current != null) {
            for (String part : current.trim().split(",")) {
                part = part.trim();
                if (!part.isEmpty() && !"null".equals(part) && !part.startsWith("min_futurity=")) parts.add(part);
            }
        }
        parts.add(MIN_FUTURITY);
        return String.join(",", parts);
    }

    /** `settings put` with the value quoted for the device shell. */
    static String settingsPut(String namespace, String key, String value) {
        if (value.isEmpty()) return "settings delete " + namespace + " " + key;
        return "settings put " + namespace + " " + key + " " + quote(value);
    }

    static String settingsGet(String namespace, String key) {
        return "settings get " + namespace + " " + key;
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** The restore record: one disabled package per line, the ones this app turned off. */
    static String record(Collection<String> packages) {
        StringBuilder text = new StringBuilder();
        for (String pkg : packages) text.append(checked(pkg)).append('\n');
        return text.toString();
    }

    static Set<String> parseRecord(String text) {
        Set<String> packages = new LinkedHashSet<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (DebloatList.PACKAGE.matcher(line).matches()) packages.add(line);
        }
        return packages;
    }

    /** Shell commands that write lines to path: a few quoted lines per command, so none outgrows an old adbd. */
    static List<String> writeFile(String path, Collection<String> lines) {
        List<String> commands = new ArrayList<>();
        commands.add(": > " + quote(path));
        StringBuilder chunk = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            chunk.append(' ').append(quote(line));
            if (++count == 20) {
                commands.add("printf '%s\\n'" + chunk + " >> " + quote(path));
                chunk.setLength(0);
                count = 0;
            }
        }
        if (count > 0) commands.add("printf '%s\\n'" + chunk + " >> " + quote(path));
        return commands;
    }

    private static String checked(String pkg) {
        if (!DebloatList.PACKAGE.matcher(pkg).matches()) throw new IllegalArgumentException("Not a package: " + pkg);
        return pkg;
    }
}
