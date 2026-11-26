package org.example.delivery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryApplicationTests {

    @Autowired
    private JdbcClient jdbc;

    @LocalServerPort
    private int port;

    @Test
    void migratesEmptyDatabaseAndServesHealth() throws Exception {
        int versions = jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1', '2') AND success = 1")
                .query(Integer.class).single();
        assertEquals(2, versions);

        int units = jdbc.sql("SELECT COUNT(*) FROM org_unit").query(Integer.class).single();
        assertEquals(0, units);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/system/health"))
                .timeout(Duration.ofSeconds(5))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"UP\""), response.body());
        }
    }

}
