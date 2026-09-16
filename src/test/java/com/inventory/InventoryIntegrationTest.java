package com.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack test against real Postgres (with Flyway), Redis and Kafka in containers.
 * Skipped automatically where Docker is unavailable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class InventoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired
    private TestRestTemplate rest;

    @Test
    void reservesStockEndToEnd() {
        ResponseEntity<JsonNode> warehouse = rest.postForEntity(
                "/api/inventory/warehouses", Map.of("name", "Main", "location", "Tehran"), JsonNode.class);
        assertThat(warehouse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long warehouseId = warehouse.getBody().get("id").asLong();

        ResponseEntity<JsonNode> stock = rest.postForEntity(
                "/api/inventory/items",
                Map.of("productId", "p1", "warehouseId", warehouseId, "quantity", 10),
                JsonNode.class);
        assertThat(stock.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> reservation = rest.postForEntity(
                "/api/inventory/reservations",
                Map.of("orderId", "ord-1", "productId", "p1", "quantity", 3),
                JsonNode.class);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reservation.getBody().get("reservationCode").asText()).isNotBlank();

        JsonNode item = rest.getForObject("/api/inventory/items/p1", JsonNode.class);
        assertThat(item.get("reservedQuantity").asInt()).isEqualTo(3);
        assertThat(item.get("availableQuantity").asInt()).isEqualTo(7);

        JsonNode page = rest.getForObject("/api/inventory/items?page=0&size=10", JsonNode.class);
        assertThat(page.get("content")).hasSize(1);
    }
}
