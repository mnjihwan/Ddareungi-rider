package com.ddareungi.clientconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionTest {
    private static AppVersion v(String text) { return AppVersion.tryParse(text).orElseThrow(); }

    @Test void componentsAreComparedAsNumbers() {
        assertThat(v("1.10.0")).isGreaterThan(v("1.9.0"));
        assertThat(v("1.9.10")).isGreaterThan(v("1.9.9"));
        assertThat(v("10.0.0")).isGreaterThan(v("9.99.99"));
        assertThat(v("2.0.0")).isGreaterThan(v("1.999.999"));
        assertThat(v("1.4.0").isBefore(v("1.4.1"))).isTrue();
        assertThat(v("1.4.0").isBefore(v("1.4.0"))).isFalse();
        assertThat(v("1.4.0")).isEqualByComparingTo(v("1.4.0"));
    }

    @Test void parsesBoundaries() {
        assertThat(v("0.0.0")).isEqualTo(new AppVersion(0, 0, 0));
        assertThat(v("999999999.999999999.999999999").major()).isEqualTo(999_999_999);
        assertThat(v("1.10.0").toString()).isEqualTo("1.10.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1", "1.4", "1.4.0.1", "1.4.0-beta", "v1.4.0", "01.4.0", "1.04.0", "1.4.00",
            "-1.4.0", "1.4.0 ", " 1.4.0", "1..0", "a.b.c", "1000000000.0.0", "1.4.0\n", "１.４.０"})
    void rejectsAnythingButMajorMinorPatch(String text) {
        assertThat(AppVersion.tryParse(text)).isEmpty();
    }

    @Test void nullIsNotAVersion() {
        assertThat(AppVersion.tryParse(null)).isEmpty();
    }
}
