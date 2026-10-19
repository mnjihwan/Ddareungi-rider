package com.ddareungi;

import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.station.infrastructure.JdbcStationSearchAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("infra")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "seoul-bike.api.key=local-context-test", "seoul-bike.api.collection-enabled=false"
})
class InfraContextTest {
    @Autowired StationSearchPort search;
    @Test void infraProfileHasAnActualSearchAdapter() {
        assertThat(search).isInstanceOf(JdbcStationSearchAdapter.class);
    }
}
