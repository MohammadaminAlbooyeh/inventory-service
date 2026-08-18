package com.inventory.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.model.Reservation;
import com.platform.topics.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishReserved(String orderId, List<Reservation> reservations) {
        Map<String, Object> payload = Map.of(
                "orderId", orderId,
                "reservations", reservations.stream()
                        .map(r -> Map.of(
                                "reservationId", r.getReservationCode(),
                                "productId", r.getProductId(),
                                "quantity", r.getQuantity()))
                        .toList()
        );
        send(PlatformTopics.INVENTORY_RESERVED, orderId, payload);
    }

    public void publishReservationFailed(String orderId, String reason) {
        Map<String, Object> payload = Map.of(
                "orderId", orderId,
                "reason", reason
        );
        send(PlatformTopics.INVENTORY_RESERVATION_FAILED, orderId, payload);
    }

    private void send(String topic, String key, Map<String, Object> payload) {
        try {
            kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(payload));
            log.info("Published {} for key {}", topic, key);
        } catch (Exception e) {
            log.error("Failed to publish {} for key {}", topic, key, e);
        }
    }
}