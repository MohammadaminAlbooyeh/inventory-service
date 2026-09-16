package com.inventory.messaging;

import com.inventory.model.Reservation;
import com.inventory.model.enums.ReservationStatus;
import com.inventory.service.ReservationService;
import com.platform.events.OrderCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderReservationHandlerTest {

    @Mock
    private ReservationService reservationService;

    @Mock
    private InventoryEventProducer eventProducer;

    @InjectMocks
    private OrderReservationHandler handler;

    private OrderCreatedEvent event(OrderCreatedEvent.Item... items) {
        return OrderCreatedEvent.builder()
                .orderId("ord-1").userId("u-1")
                .items(List.of(items))
                .totalAmount(BigDecimal.TEN)
                .build();
    }

    private OrderCreatedEvent.Item item(String productId, int qty) {
        return OrderCreatedEvent.Item.builder()
                .productId(productId).name(productId).unitPrice(BigDecimal.ONE).quantity(qty)
                .build();
    }

    private Reservation reservation(String code, String productId) {
        return Reservation.builder()
                .id(1L).reservationCode(code).orderId("ord-1").productId(productId).quantity(1)
                .status(ReservationStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }

    @Test
    void publishesReservedWhenEveryItemIsReserved() {
        when(reservationService.listByOrder("ord-1")).thenReturn(List.of());
        Reservation r1 = reservation("code-1", "p1");
        Reservation r2 = reservation("code-2", "p2");
        when(reservationService.createReservation("ord-1", "p1", 1)).thenReturn(Optional.of(r1));
        when(reservationService.createReservation("ord-1", "p2", 1)).thenReturn(Optional.of(r2));

        handler.reserveForOrder(event(item("p1", 1), item("p2", 1)));

        verify(eventProducer).publishReserved("ord-1", List.of(r1, r2));
        verify(eventProducer, never()).publishReservationFailed(anyString(), anyString());
    }

    @Test
    void rollsBackAndPublishesFailedWhenAnItemCannotBeReserved() {
        when(reservationService.listByOrder("ord-1")).thenReturn(List.of());
        Reservation r1 = reservation("code-1", "p1");
        when(reservationService.createReservation("ord-1", "p1", 1)).thenReturn(Optional.of(r1));
        when(reservationService.createReservation("ord-1", "p2", 1)).thenReturn(Optional.empty());

        handler.reserveForOrder(event(item("p1", 1), item("p2", 1)));

        verify(reservationService).cancelReservation("code-1");
        verify(eventProducer).publishReservationFailed(eq("ord-1"), anyString());
        verify(eventProducer, never()).publishReserved(anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void skipsOrdersThatAlreadyHaveReservations() {
        when(reservationService.listByOrder("ord-1")).thenReturn(List.of(reservation("code-1", "p1")));

        handler.reserveForOrder(event(item("p1", 1)));

        verify(reservationService, never()).createReservation(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(eventProducer, never()).publishReserved(anyString(), org.mockito.ArgumentMatchers.anyList());
        verify(eventProducer, never()).publishReservationFailed(anyString(), anyString());
    }
}
