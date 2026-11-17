package com.ddareungi.clientconfig;

import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release version {@code major.minor.patch} (spec 6). Components are compared as numbers, so {@code 1.10.0} is
 * newer than {@code 1.9.0}. Each component is a non-negative integer without leading zeros and at most 9 digits;
 * anything else (pre-release suffixes, two or four components, signs, spaces) is rejected.
 */
public record AppVersion(int major, int minor, int patch) implements Comparable<AppVersion> {
    private static final Pattern FORMAT =
            Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    private static final Comparator<AppVersion> ORDER = Comparator.comparingInt(AppVersion::major)
            .thenComparingInt(AppVersion::minor).thenComparingInt(AppVersion::patch);

    public AppVersion {
        if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("negative version component");
    }

    /** The parsed version, or empty when the text is null or not {@code major.minor.patch}. */
    public static Optional<AppVersion> tryParse(@Nullable String text) {
        if (text == null) return Optional.empty();
        Matcher matcher = FORMAT.matcher(text);
        if (!matcher.matches()) return Optional.empty();
        return Optional.of(new AppVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))));
    }

    public boolean isBefore(AppVersion other) { return compareTo(other) < 0; }

    @Override
    public int compareTo(AppVersion other) { return ORDER.compare(this, other); }

    @Override
    public String toString() { return major + "." + minor + "." + patch; }
}
