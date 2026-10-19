package com.ddareungi.station.domain;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KoreanSearchTest {
    @Test void followsSharedSearchContract() throws Exception {
        try (var source = getClass().getResourceAsStream("/korean-search.tsv")) {
            for (String line : new String(source.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String[] parts = line.split("\t");
                assertThat(KoreanSearch.score(parts[0], parts[1])).as(line).isEqualTo(Integer.parseInt(parts[2]));
            }
        }
    }

    @Test void matchesAllQueryWordsAcrossNameAndAddress() throws Exception {
        try (var source = getClass().getResourceAsStream("/korean-search-fields.tsv")) {
            for (String line : new String(source.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String[] parts = line.split("\t");
                assertThat(KoreanSearch.bestScore(parts[0], parts[1], parts[2])).as(line).isEqualTo(Integer.parseInt(parts[3]));
            }
        }
        var center = new Location(37.5, 127);
        var station = new Station("test", "스타벅스 강남역점 앞", "서울 강남구", center, 10, null, 0, null);
        assertThat(StationNameSearch.select(List.of(station), "강남구 스타벅스", center)).containsExactly(station);
        assertThat(StationNameSearch.select(List.of(station), "부산 스타벅스", center)).isEmpty();
    }

    @Test void convertsKeyboardRunsWithoutReplacingEnglishOrReservedCharacters() {
        assertThat(KoreanSearch.koreanKeyboardQuery("rkdska역")).isEqualTo("강남역");
        assertThat(KoreanSearch.koreanKeyboardQuery("tmxkqjrtm 강남역")).isEqualTo("스타벅스 강남역");
        assertThat(KoreanSearch.koreanKeyboardQuery("A&B #+ Gangnam Starbucks")).isEqualTo("A&B #+ Gangnam Starbucks");
        for (String word : List.of("서울대입구역", "까치산역", "값이", "닭갈비", "광화문", "괜찮은", "쐐기", "의정부"))
            assertThat(KoreanSearch.koreanKeyboardQuery(KoreanSearch.keyboard(word))).as(word).isEqualTo(word);
    }

    @Test void roundTripsEveryHangulSyllableIncludingCompoundVowelsAndFinals() {
        for (char c = '가'; c <= '힣'; c++) {
            String syllable = String.valueOf(c);
            assertThat(KoreanSearch.koreanKeyboardQuery(KoreanSearch.keyboard(syllable))).as(syllable).isEqualTo(syllable);
        }
    }

    @Test void ranksLiteralBeforeKeyboardAndSuppressesFuzzyWhenStrongMatchesExist() {
        var center = new Location(37.5, 127);
        var stations = List.of(station("fuzzy", "강남얙", center), station("keyboard", "rkdskadur", center), station("literal", "강남역", center));
        assertThat(StationNameSearch.select(stations, "강남역", center)).extracting(Station::stationId)
                .containsExactly("literal", "keyboard");
        assertThat(StationNameSearch.select(stations.subList(0, 1), "강남역", center)).extracting(Station::stationId)
                .containsExactly("fuzzy");
    }

    private Station station(String id, String name, Location location) {
        return new Station(id, name, "서울", location, 10, null, 0, null);
    }
}
