package com.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.model.StockItem;
import com.inventory.service.StockService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {
        "order.created",
        "inventory.reserved",
        "inventory.reservation_failed"
})
@ActiveProfiles("dev")
class InventoryOrderCreatedContractTest {

    private static final long TIMEOUT_MS = 15_000;

    @Autowired
    private StockService stockService;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private KafkaTemplate<String, String> producer;

    @BeforeEach
    void setUp() {
        Long warehouse = stockService.createWarehouse("Main", "Tehran").getId();
        stockService.upsertStock("p1", warehouse, 10);
        stockService.upsertStock("p2", warehouse, 5);
    }

    @Test
    void orderCreatedPublishesReservedWhenStockAvailable() throws Exception {
        send("order.created", "ord-1", orderCreatedPayload("ord-1", 2));

        JsonNode reserved = consumePayload("inventory.reserved");
        assertThat(reserved.get("orderId").asText()).isEqualTo("ord-1");
        assertThat(reserved.get("reservations")).hasSize(1);
        assertThat(reserved.get("reservations").get(0).get("productId").asText()).isEqualTo("p1");
        assertThat(reserved.get("reservations").get(0).get("reservationId").asText()).isNotBlank();

        StockItem item = stockService.getByProductId("p1");
        assertThat(item.getReservedQuantity()).isEqualTo(2);
    }

    @Test
    void orderCreatedPublishesReservationFailedWhenNoStock() throws Exception {
        send("order.created", "ord-2", orderCreatedPayload("ord-2", 999));

        JsonNode failed = consumePayload("inventory.reservation_failed");
        assertThat(failed.get("orderId").asText()).isEqualTo("ord-2");
        assertThat(failed.get("reason").asText()).contains("p1");
    }

    private void send(String topic, String key, String payload) throws Exception {
        if (producer == null) {
            Map<String, Object> props =
                    KafkaTestUtils.producerProps(embeddedKafkaBroker.getBrokersAsString());
            props.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
            producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
        producer.send(topic, key, payload).get(5, TimeUnit.SECONDS);
    }

    private JsonNode consumePayload(String topic) {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps("test-" + topic, "true", embeddedKafkaBroker);
        props.put("key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.of(topic));
            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, topic, java.time.Duration.ofMillis(TIMEOUT_MS));
            assertThat(record).isNotNull();
            try {
                return objectMapper.readTree(record.value());
            } catch (Exception e) {
                throw new AssertionError("Invalid JSON on topic " + topic + ": " + record.value(), e);
            }
        }
    }

    private String orderCreatedPayload(String orderId, int quantity) {
        return "{\"orderId\":\"%s\",\"userId\":\"u1\",\"items\":[{\"productId\":\"p1\",\"name\":\"Laptop\",\"unitPrice\":1200,\"quantity\":%d}],\"totalAmount\":%d}"
                .formatted(orderId, quantity, 1200L * quantity);
    }
}