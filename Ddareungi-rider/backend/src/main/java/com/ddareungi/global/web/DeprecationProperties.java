package com.ddareungi.global.web;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Deprecated API paths (spec 32, RFC 9745/8594), default none. Each entry of {@code api.deprecations[]}:
 * <ul>
 *   <li>{@code pattern}: Spring path pattern of the request path, e.g. {@code /api/v1/routes/plan}</li>
 *   <li>{@code deprecation}: RFC 3339 instant, sent as {@code Deprecation: @<unix seconds>}</li>
 *   <li>{@code sunset}: RFC 3339 instant, sent as {@code Sunset: <IMF-fixdate>} (RFC 1123 form, GMT); must not be
 *   before {@code deprecation}</li>
 *   <li>{@code link}: optional absolute http(s) URL, sent as {@code Link: <url>; rel="deprecation"}</li>
 * </ul>
 * Instants are truncated to whole seconds. Invalid entries fail the startup.
 */
@ConfigurationProperties("api")
public record DeprecationProperties(@DefaultValue List<Entry> deprecations) {
    /** HTTP-date (IMF-fixdate): two-digit day, English names, GMT. */
    static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    public DeprecationProperties {
        deprecations = deprecations == null ? List.of() : List.copyOf(deprecations);
        for (int i = 0; i < deprecations.size(); i++) deprecations.get(i).validate("api.deprecations[" + i + "]");
    }

    public record Entry(@Nullable String pattern, @Nullable String deprecation, @Nullable String sunset,
                        @Nullable String link) {
        public Entry {
            pattern = blankToNull(pattern);
            deprecation = blankToNull(deprecation);
            sunset = blankToNull(sunset);
            link = blankToNull(link);
        }

        void validate(String name) {
            if (pattern == null || !pattern.startsWith("/")) {
                throw new IllegalArgumentException(name + ".pattern must be a path pattern starting with '/'");
            }
            try {
                PathPatternParser.defaultInstance.parse(pattern);
            } catch (PatternParseException exception) {
                throw new IllegalArgumentException(name + ".pattern is not a valid path pattern: " + pattern, exception);
            }
            Instant deprecationAt = instant(name + ".deprecation", deprecation);
            Instant sunsetAt = instant(name + ".sunset", sunset);
            if (sunsetAt.isBefore(deprecationAt)) {
                throw new IllegalArgumentException(name + ".sunset (" + sunset + ") must not be before deprecation ("
                        + deprecation + ")");
            }
            if (link != null) {
                URI uri;
                try {
                    uri = new URI(link);
                } catch (URISyntaxException exception) {
                    throw new IllegalArgumentException(name + ".link is not a valid URL: " + link, exception);
                }
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                        || link.indexOf('>') >= 0) {
                    throw new IllegalArgumentException(name + ".link must be an absolute http(s) URL: " + link);
                }
            }
        }

        /** {@code @<unix seconds>} (RFC 9745 Structured Field Date). */
        public String deprecationHeader() { return "@" + instant("deprecation", deprecation).getEpochSecond(); }

        /** HTTP-date of the sunset (RFC 8594). */
        public String sunsetHeader() { return HTTP_DATE.format(instant("sunset", sunset)); }

        /** {@code <link>; rel="deprecation"}, or null without a link. */
        public @Nullable String linkHeader() { return link == null ? null : "<" + link + ">; rel=\"deprecation\""; }

        private static Instant instant(String name, @Nullable String value) {
            if (value == null) throw new IllegalArgumentException(name + " is required (RFC 3339, e.g. 2027-06-30T23:59:59Z)");
            try {
                return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.SECONDS);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(name + " must be an RFC 3339 date-time with offset: " + value,
                        exception);
            }
        }

        private static @Nullable String blankToNull(@Nullable String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}
