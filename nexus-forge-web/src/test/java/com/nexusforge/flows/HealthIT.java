package com.nexusforge.flows;

import com.nexusforge.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.springframework.http.HttpStatus.OK;

@Tag("integration")
class HealthIT extends IntegrationTestBase {

    @Test
    void actuator_health_is_public_and_up() {
        var response = rest().getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(OK);
        assertThat(response.getBody().get("status").asString()).isEqualTo("UP");
    }
}
