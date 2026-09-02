package dev.hauch.restartly.util;

import java.util.Objects;

/**
 * Minimal semantic version used to compare the running version against the
 * version published on GitHub releases.
 */
public final class Version implements Comparable<Version> {

    private final int major;
    private final int minor;
    private final int patch;

    public Version(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static Version parse(String raw) {
        Objects.requireNonNull(raw, "version");
        String cleaned = raw.trim();
        int dash = cleaned.indexOf('-');
        if (dash >= 0) {
            cleaned = cleaned.substring(0, dash);
        }
        String[] parts = cleaned.split("\\.");
        if (parts.length < 1 || parts.length > 3) {
            throw new IllegalArgumentException("Invalid version: '" + raw + "'");
        }
        try {
            int maj = Integer.parseInt(parts[0]);
            int min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int pat = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new Version(maj, min, pat);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid version: '" + raw + "'", e);
        }
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Version other) {
        int likeMajor = Integer.compare(major, other.major);
        if (likeMajor != 0) {
            return likeMajor;
        }
        int likeMinor = Integer.compare(minor, other.minor);
        if (likeMinor != 0) {
            return likeMinor;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Version version)) {
            return false;
        }
        return major == version.major && minor == version.minor && patch == version.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }
}