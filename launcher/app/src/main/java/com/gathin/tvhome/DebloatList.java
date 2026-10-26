package com.gathin.tvhome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Fire OS cleanup list (assets/debloat-fire-os7.txt), shared with tools/cleanyourfire.sh and .ps1.
 * "## [id] Title (on|off)" opens a category; every other non-comment line is one package name, "#" starts a comment.
 */
final class DebloatList {

    static final String ASSET = "debloat-fire-os7.txt";

    private static final Pattern HEADER = Pattern.compile("^##\\s*\\[([a-z0-9_-]+)\\]\\s*(.*?)\\s*\\((on|off)\\)\\s*$");
    /** What pm accepts as a package name; anything else is refused, so a line can never become a shell command. */
    static final Pattern PACKAGE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

    static final class Entry {
        final String pkg;
        final String note;

        Entry(String pkg, String note) {
            this.pkg = pkg;
            this.note = note;
        }
    }

    static final class Category {
        final String id;
        final String title;
        final boolean defaultOn;
        final List<Entry> entries = new ArrayList<>();

        Category(String id, String title, boolean defaultOn) {
            this.id = id;
            this.title = title;
            this.defaultOn = defaultOn;
        }
    }

    private DebloatList() {
    }

    static List<Category> parse(Reader source) throws IOException {
        List<Category> categories = new ArrayList<>();
        Category current = null;
        BufferedReader in = new BufferedReader(source);
        String line;
        int number = 0;
        while ((line = in.readLine()) != null) {
            number++;
            String trimmed = line.trim();
            Matcher header = HEADER.matcher(trimmed);
            if (header.matches()) {
                current = new Category(header.group(1), header.group(2), "on".equals(header.group(3)));
                categories.add(current);
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int hash = trimmed.indexOf('#');
            String pkg = (hash < 0 ? trimmed : trimmed.substring(0, hash)).trim();
            String note = hash < 0 ? "" : trimmed.substring(hash + 1).trim();
            if (!PACKAGE.matcher(pkg).matches()) {
                throw new IOException("Line " + number + ": not a package name: " + pkg);
            }
            if (current == null) throw new IOException("Line " + number + ": package before the first category");
            current.entries.add(new Entry(pkg, note));
        }
        return Collections.unmodifiableList(categories);
    }
}
