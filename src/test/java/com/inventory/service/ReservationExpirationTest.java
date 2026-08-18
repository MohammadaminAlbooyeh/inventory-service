package com.inventory.service;

import com.inventory.model.Reservation;
import com.inventory.model.enums.ReservationStatus;
import com.inventory.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private StockService stockService;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    void expireStaleReservationsExpiresPendingReservationsPastExpiry() {
        Reservation stale = Reservation.builder()
                .id(1L).reservationCode("code-stale").orderId("ord-1").productId("p1").quantity(2)
                .status(ReservationStatus.PENDING)
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(java.time.LocalDateTime.class)))
                .thenReturn(List.of(stale));

        reservationService.expireStaleReservations();

        assertThat(stale.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(stockService).releaseStock("p1", 2);
    }

    @Test
    void expireStaleReservationsDoesNothingWhenNoStaleReservations() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(java.time.LocalDateTime.class)))
                .thenReturn(List.of());

        reservationService.expireStaleReservations();

        verify(stockService, never()).releaseStock(anyString(), any(Integer.class));
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void expireStaleReservationsDoesNotAffectConfirmedReservations() {
        Reservation confirmed = Reservation.builder()
                .id(1L).reservationCode("code-confirmed").orderId("ord-1").productId("p1").quantity(2)
                .status(ReservationStatus.CONFIRMED)
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(java.time.LocalDateTime.class)))
                .thenReturn(List.of());

        reservationService.expireStaleReservations();

        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(stockService, never()).releaseStock(anyString(), any(Integer.class));
    }
}
