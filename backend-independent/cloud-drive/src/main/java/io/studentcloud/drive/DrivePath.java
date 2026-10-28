package io.studentcloud.drive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A platform-independent path inside one drive; it never contains a host path. */
public record DrivePath(String value) {
    private static final int MAX_PATH_LENGTH = 4096;

    public DrivePath {
        value = normalize(value);
    }

    public static DrivePath of(String value) { return new DrivePath(value); }

    public DrivePath child(String name) {
        requireName(name);
        return of(isRoot() ? "/" + name : value + "/" + name);
    }

    public DrivePath parent() {
        if (isRoot()) return this;
        int separator = value.lastIndexOf('/');
        return of(separator == 0 ? "/" : value.substring(0, separator));
    }

    public String name() { return isRoot() ? "/" : value.substring(value.lastIndexOf('/') + 1); }
    public boolean isRoot() { return value.equals("/"); }
    public String relative() { return isRoot() ? "" : value.substring(1); }

    private static String normalize(String input) {
        if (input == null || input.isBlank() || input.length() > MAX_PATH_LENGTH)
            throw new IllegalArgumentException("Invalid drive path");
        if (input.indexOf('\\') >= 0 || input.indexOf(':') >= 0)
            throw new IllegalArgumentException("Drive paths use forward slashes and cannot contain a host drive");
        List<String> parts = new ArrayList<>();
        for (String part : input.split("/", -1)) {
            if (part.isEmpty()) continue;
            requireName(part);
            parts.add(part);
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.endsWith(".") || name.endsWith(" ") || name.chars().anyMatch(Character::isISOControl)
                || name.toLowerCase(Locale.ROOT).startsWith(".studentcloud-"))
            throw new IllegalArgumentException("Invalid drive path segment");
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
            throw new IllegalArgumentException("Reserved platform filename");
    }

    @Override public String toString() { return value; }
}
