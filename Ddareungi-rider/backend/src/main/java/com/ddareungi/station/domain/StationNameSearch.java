package com.ddareungi.station.domain;

import java.util.Comparator;
import java.util.List;

public final class StationNameSearch {
    private StationNameSearch() {}

    public static List<Station> select(List<Station> stations, String query, Location center) {
        if (KoreanSearch.normalize(query).length() < 2) return List.of();
        record Match(Station station, int score) {}
        var matches = stations.stream().map(station -> new Match(station,
                KoreanSearch.bestScore(station.stationName(), station.address(), query)))
                .filter(match -> match.score() >= 0).toList();
        boolean strong = matches.stream().anyMatch(match -> match.score() <= 2);
        return matches.stream().filter(match -> !strong || match.score() <= 2)
                .sorted(Comparator.comparingInt(Match::score)
                        .thenComparingDouble(match -> center.distanceTo(match.station().location()))
                        .thenComparing(match -> match.station().stationId()))
                .limit(20).map(Match::station).toList();
    }
}
