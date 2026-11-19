package com.ddareungi.station.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3: the precompiled-pattern / prepared implementation of {@link KoreanSearch} returns exactly what the pre-R3
 * implementation ({@link KoreanSearchReference}) returned, for the shared search contract cases and for random
 * mixed Hangul/jamo/Latin/digit/space strings. {@link StationNameSearch#selectIndexed} equals
 * {@link StationNameSearch#select}.
 */
class KoreanSearchEquivalenceTest {
    private static final String ALPHABET = "강남역삼선릉신논현출구번대로구서울스타벅스봉은사로값닭광화문괜찮쐐의정부ㄱㄴㅇㅅㄷㅂㅐㅒ rkdskadurtmxkqjrtmAB12-#.  ";

    private static String random(Random random, int maxLength) {
        int length = random.nextInt(maxLength + 1);
        var builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (random.nextInt(10) == 0) builder.append((char) ('가' + random.nextInt('힣' - '가' + 1)));
            else builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    @Test
    void sharedContractCasesMatchTheReference() throws Exception {
        for (String line : lines("/korean-search.tsv")) {
            String[] parts = line.split("\t");
            assertThat(KoreanSearch.score(parts[0], parts[1])).as(line).isEqualTo(KoreanSearchReference.score(parts[0], parts[1]));
            assertThat(KoreanSearch.score(KoreanSearch.prepare(parts[0]), KoreanSearch.prepareQuery(parts[1])))
                    .as(line).isEqualTo(KoreanSearchReference.score(parts[0], parts[1]));
        }
        for (String line : lines("/korean-search-fields.tsv")) {
            String[] parts = line.split("\t");
            int expected = KoreanSearchReference.bestScore(parts[0], parts[1], parts[2]);
            assertThat(KoreanSearch.bestScore(parts[0], parts[1], parts[2])).as(line).isEqualTo(expected);
            assertThat(KoreanSearch.bestScore(KoreanSearch.prepare(parts[0]), KoreanSearch.prepare(parts[1]),
                    KoreanSearch.prepareQuery(parts[2]))).as(line).isEqualTo(expected);
        }
    }

    @Test
    void randomStringsMatchTheReference() {
        Random random = new Random(1116);
        for (int i = 0; i < 20_000; i++) {
            String value = random(random, 14), address = random(random, 14), query = random(random, 7);
            if (i % 3 == 0 && value.length() > 3) query = value.substring(1, Math.min(value.length(), 5)); // more partial hits
            if (i % 5 == 0) query = KoreanSearch.keyboard(query);
            assertThat(KoreanSearch.normalize(value)).isEqualTo(KoreanSearchReference.normalize(value));
            assertThat(KoreanSearch.keyboard(value)).isEqualTo(KoreanSearchReference.keyboard(value));
            assertThat(KoreanSearch.score(value, query)).as("%s / %s", value, query).isEqualTo(KoreanSearchReference.score(value, query));
            assertThat(KoreanSearch.bestScore(value, address, query)).as("%s / %s / %s", value, address, query)
                    .isEqualTo(KoreanSearchReference.bestScore(value, address, query));
            assertThat(KoreanSearch.koreanKeyboardQuery(query)).isEqualTo(KoreanSearchReference.koreanKeyboardQuery(query));
        }
        assertThat(KoreanSearch.score(null, "강남")).isEqualTo(KoreanSearchReference.score(null, "강남"));
        assertThat(KoreanSearch.score("강남", null)).isEqualTo(KoreanSearchReference.score("강남", null));
    }

    @Test
    void indexedSelectionEqualsPlainSelection() {
        Random random = new Random(5);
        var center = new Location(37.5, 127);
        List<Station> stations = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            stations.add(new Station("ST-" + i, random(random, 12), random(random, 12),
                    new Location(37.4 + random.nextDouble() * 0.2, 126.9 + random.nextDouble() * 0.2), 10, null, 0, null));
        }
        var indexed = stations.stream().map(StationNameSearch.Indexed::of).toList();
        for (String query : List.of("강남역", "rkdska", "ㄱㄴㅇ", "강남얙", "스타 벅스", "1", "AB", "서울 강남", "역삼", "  ")) {
            List<Station> expected = referenceSelect(stations, query, center);
            assertThat(StationNameSearch.selectIndexed(indexed, query, center)).as(query).isEqualTo(expected);
            assertThat(StationNameSearch.select(stations, query, center)).as(query).isEqualTo(expected);
        }
    }

    /** Pre-R3 StationNameSearch.select over the reference scorer. */
    private static List<Station> referenceSelect(List<Station> stations, String query, Location center) {
        if (KoreanSearchReference.normalize(query).length() < 2) return List.of();
        record Match(Station station, int score) {}
        var matches = stations.stream().map(station -> new Match(station,
                KoreanSearchReference.bestScore(station.stationName(), station.address(), query)))
                .filter(match -> match.score() >= 0).toList();
        boolean strong = matches.stream().anyMatch(match -> match.score() <= 2);
        return matches.stream().filter(match -> !strong || match.score() <= 2)
                .sorted(java.util.Comparator.comparingInt(Match::score)
                        .thenComparingDouble(match -> center.distanceTo(match.station().location()))
                        .thenComparing(match -> match.station().stationId()))
                .limit(20).map(Match::station).toList();
    }

    private List<String> lines(String resource) throws Exception {
        try (var source = getClass().getResourceAsStream(resource)) {
            return new String(source.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
