package com.inventory;

import com.inventory.model.enums.ReservationStatus;
import com.inventory.service.ReservationService;
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
        "inventory.reservation_failed",
        "inventory.reservation_cancel"
})
@ActiveProfiles("dev")
class InventoryReservationCancelContractTest {

    private static final long TIMEOUT_MS = 15_000;

    @Autowired
    private StockService stockService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private KafkaTemplate<String, String> producer;

    @BeforeEach
    void setUp() {
        Long warehouse = stockService.createWarehouse("SagaWarehouse", "SagaCity").getId();
        stockService.upsertStock("p1", warehouse, 10);
        stockService.upsertStock("p2", warehouse, 10);
    }

    @Test
    void reservationCancelConsumerCancelsAllReservationsForOrder() throws Exception {
        reservationService.createReservation("ord-saga", "p1", 3);
        reservationService.createReservation("ord-saga", "p2", 2);

        List<?> reservationsBefore = reservationService.listByOrder("ord-saga");
        assertThat(reservationsBefore).hasSize(2);
        assertThat(((com.inventory.model.Reservation) reservationsBefore.get(0)).getStatus())
                .isEqualTo(ReservationStatus.PENDING);

        String payload = "{\"orderId\":\"ord-saga\"}";
        send("inventory.reservation_cancel", "ord-saga", payload);

        waitForCancellation("ord-saga", 2);

        List<com.inventory.model.Reservation> reservationsAfter = reservationService.listByOrder("ord-saga");
        assertThat(reservationsAfter).hasSize(2);
        assertThat(reservationsAfter.get(0).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservationsAfter.get(1).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    private void waitForCancellation(String orderId, int expectedCancelled) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<com.inventory.model.Reservation> reservations = reservationService.listByOrder(orderId);
            long cancelledCount = reservations.stream()
                    .filter(r -> r.getStatus() == ReservationStatus.CANCELLED)
                    .count();
            if (cancelledCount == expectedCancelled) {
                return;
            }
            Thread.sleep(200);
        }
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
}
