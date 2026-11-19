package com.ddareungi.station.domain;

import java.util.Comparator;
import java.util.List;

public final class StationNameSearch {
    private StationNameSearch() {}

    /** A station with its name and address prepared once for {@link KoreanSearch} (catalog load time). */
    public record Indexed(Station station, KoreanSearch.Prepared name, KoreanSearch.Prepared address) {
        public static Indexed of(Station station) {
            return new Indexed(station, KoreanSearch.prepare(station.stationName()), KoreanSearch.prepare(station.address()));
        }
    }

    public static List<Station> select(List<Station> stations, String query, Location center) {
        return selectIndexed(stations.stream().map(Indexed::of).toList(), query, center);
    }

    /** Same ranking as {@link #select(List, String, Location)} over stations whose search keys were precomputed. */
    public static List<Station> selectIndexed(List<Indexed> stations, String query, Location center) {
        if (KoreanSearch.normalize(query).length() < 2) return List.of();
        KoreanSearch.Query prepared = KoreanSearch.prepareQuery(query);
        record Match(Station station, int score) {}
        var matches = stations.stream().map(indexed -> new Match(indexed.station(),
                KoreanSearch.bestScore(indexed.name(), indexed.address(), prepared)))
                .filter(match -> match.score() >= 0).toList();
        boolean strong = matches.stream().anyMatch(match -> match.score() <= 2);
        return matches.stream().filter(match -> !strong || match.score() <= 2)
                .sorted(Comparator.comparingInt(Match::score)
                        .thenComparingDouble(match -> center.distanceTo(match.station().location()))
                        .thenComparing(match -> match.station().stationId()))
                .limit(20).map(Match::station).toList();
    }
}
