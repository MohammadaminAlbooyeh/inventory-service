package com.inventory.messaging;

import com.inventory.model.Reservation;
import com.inventory.outbox.OutboxService;
import com.platform.topics.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Writes outbound events to the transactional outbox. Must be called from within the
 * business transaction; a relay performs the actual Kafka publish.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventProducer {

    private final OutboxService outboxService;

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
        outboxService.append(PlatformTopics.INVENTORY_RESERVED, orderId, payload);
        log.info("Queued {} for order {}", PlatformTopics.INVENTORY_RESERVED, orderId);
    }

    public void publishReservationFailed(String orderId, String reason) {
        Map<String, Object> payload = Map.of("orderId", orderId, "reason", reason);
        outboxService.append(PlatformTopics.INVENTORY_RESERVATION_FAILED, orderId, payload);
        log.info("Queued {} for order {}", PlatformTopics.INVENTORY_RESERVATION_FAILED, orderId);
    }
}
