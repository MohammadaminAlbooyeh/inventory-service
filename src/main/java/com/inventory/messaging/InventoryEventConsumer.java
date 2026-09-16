package com.inventory.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.service.ReservationService;
import com.platform.events.OrderCreatedEvent;
import com.platform.topics.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventConsumer {

    private final ReservationService reservationService;
    private final OrderReservationHandler orderReservationHandler;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = PlatformTopics.ORDER_CREATED, groupId = "inventory-service")
    public void onOrderCreated(String message) {
        OrderCreatedEvent event = parse(message);
        if (event == null) {
            return;
        }
        log.info("Received {} for order {} with {} items",
                PlatformTopics.ORDER_CREATED, event.getOrderId(), event.getItems().size());
        orderReservationHandler.reserveForOrder(event);
    }

    @KafkaListener(topics = PlatformTopics.INVENTORY_RESERVATION_CANCEL, groupId = "inventory-service")
    public void onReservationCancel(String message) {
        try {
            Map<String, String> payload = objectMapper.readValue(message,
                    new TypeReference<Map<String, String>>() {});
            String orderId = payload.get("orderId");
            if (orderId != null) {
                reservationService.cancelByOrder(orderId);
                log.info("Cancelled reservations for order {} (saga compensation)", orderId);
            }
        } catch (Exception e) {
            log.error("Failed to parse {} message", PlatformTopics.INVENTORY_RESERVATION_CANCEL, e);
        }
    }

    private OrderCreatedEvent parse(String message) {
        try {
            return objectMapper.readValue(message, OrderCreatedEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse {} message", PlatformTopics.ORDER_CREATED, e);
            return null;
        }
    }
}
