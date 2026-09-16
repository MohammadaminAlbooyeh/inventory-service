package com.inventory.messaging;

import com.inventory.model.Reservation;
import com.inventory.service.ReservationService;
import com.platform.events.OrderCreatedEvent;
import com.platform.topics.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Reserves stock for an order and enqueues the outcome event in one transaction, so the
 * reservations and the outbox row commit together (or not at all).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReservationHandler {

    private final ReservationService reservationService;
    private final InventoryEventProducer eventProducer;

    @Transactional
    public void reserveForOrder(OrderCreatedEvent event) {
        if (!reservationService.listByOrder(event.getOrderId()).isEmpty()) {
            log.info("Order {} already has reservations, skipping duplicate {}",
                    event.getOrderId(), PlatformTopics.ORDER_CREATED);
            return;
        }

        List<Reservation> reservations = new ArrayList<>();
        List<String> failedProducts = new ArrayList<>();

        for (OrderCreatedEvent.Item item : event.getItems()) {
            reservationService.createReservation(event.getOrderId(), item.getProductId(), item.getQuantity())
                    .ifPresentOrElse(reservations::add, () -> failedProducts.add(item.getProductId()));
        }

        if (failedProducts.isEmpty()) {
            eventProducer.publishReserved(event.getOrderId(), reservations);
        } else {
            reservations.forEach(r -> reservationService.cancelReservation(r.getReservationCode()));
            eventProducer.publishReservationFailed(event.getOrderId(),
                    "Insufficient stock for products: " + String.join(", ", failedProducts));
        }
    }
}
