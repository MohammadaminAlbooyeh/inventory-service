package com.inventory.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.model.Reservation;
import com.inventory.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventConsumer {

    public static final String ORDER_CREATED_TOPIC = "order.created";

    private final ReservationService reservationService;
    private final InventoryEventProducer eventProducer;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = ORDER_CREATED_TOPIC, groupId = "inventory-service")
    public void onOrderCreated(String message) {
        OrderCreatedEvent event = parse(message);
        if (event == null) {
            return;
        }
        log.info("Received {} for order {} with {} items",
                ORDER_CREATED_TOPIC, event.getOrderId(), event.getItems().size());

        List<Reservation> reservations = new ArrayList<>();
        List<String> failedProducts = new ArrayList<>();

        for (OrderCreatedEvent.OrderItem item : event.getItems()) {
            Optional<Reservation> reservation = reservationService
                    .createReservation(event.getOrderId(), item.getProductId(), item.getQuantity());
            if (reservation.isPresent()) {
                reservations.add(reservation.get());
            } else {
                failedProducts.add(item.getProductId());
            }
        }

        if (failedProducts.isEmpty()) {
            eventProducer.publishReserved(event.getOrderId(), reservations);
        } else {
            String reason = "Insufficient stock for products: "
                    + failedProducts.stream().collect(Collectors.joining(", "));
            eventProducer.publishReservationFailed(event.getOrderId(), reason);
            reservations.forEach(r -> reservationService.cancelReservation(r.getReservationCode()));
        }
    }

    private OrderCreatedEvent parse(String message) {
        try {
            return objectMapper.readValue(message, OrderCreatedEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse {} message", ORDER_CREATED_TOPIC, e);
            return null;
        }
    }
}