package com.ddareungi;

import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LocalContextTest {
    @Autowired StationSearchPort search;
    @Test void startsWithoutExternalInfrastructure() {
        assertThat(search.findNearby(new Location(37.4979, 127.0276), 1500)).hasSize(3);
    }
}
