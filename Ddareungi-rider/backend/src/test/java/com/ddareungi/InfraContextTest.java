package com.ddareungi;

import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.station.infrastructure.JdbcStationSearchAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
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

    @Autowired Environment environment;
    // Readiness depends on MySQL only; Redis outages degrade the API but must not drop the instance from rotation.
    @Test void readinessGroupIncludesDatabaseButNotRedis() {
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
    }
}
