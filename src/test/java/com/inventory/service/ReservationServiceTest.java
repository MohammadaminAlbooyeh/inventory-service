package com.inventory.service;

import com.inventory.lock.ReservationLockService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final long RESERVATION_TTL_MINUTES = 10;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private StockService stockService;

    @Mock
    private ReservationLockService lockService;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    void createReservationSucceedsWhenStockAvailable() {
        when(lockService.tryLock("product:p1")).thenReturn(true);
        when(stockService.reserveStock("p1", 2)).thenReturn(true);
        Reservation reservation = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(2)
                .status(ReservationStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(RESERVATION_TTL_MINUTES))
                .build();
        when(reservationRepository.save(any(Reservation.class))).thenReturn(reservation);

        Optional<Reservation> result = reservationService.createReservation("ord-1", "p1", 2);

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("p1");
        assertThat(result.get().getStatus()).isEqualTo(ReservationStatus.PENDING);
        verify(stockService).reserveStock("p1", 2);
        verify(lockService).unlock("product:p1");
    }

    @Test
    void createReservationFailsWhenLockNotAcquired() {
        when(lockService.tryLock("product:p1")).thenReturn(false);

        Optional<Reservation> result = reservationService.createReservation("ord-1", "p1", 2);

        assertThat(result).isEmpty();
        verify(stockService, never()).reserveStock(anyString(), anyInt());
    }

    @Test
    void createReservationFailsWhenInsufficientStock() {
        when(lockService.tryLock("product:p1")).thenReturn(true);
        when(stockService.reserveStock("p1", 999)).thenReturn(false);

        Optional<Reservation> result = reservationService.createReservation("ord-1", "p1", 999);

        assertThat(result).isEmpty();
        verify(stockService).reserveStock("p1", 999);
        verify(lockService).unlock("product:p1");
    }

    @Test
    void confirmReservationTransitionsToConfirmed() {
        Reservation reservation = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(2)
                .status(ReservationStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(RESERVATION_TTL_MINUTES))
                .build();
        when(reservationRepository.findByReservationCode("code-1")).thenReturn(Optional.of(reservation));

        Reservation result = reservationService.confirmReservation("code-1");

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void confirmReservationThrowsWhenNotFound() {
        when(reservationRepository.findByReservationCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.confirmReservation("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reservation not found");
    }

    @Test
    void confirmReservationThrowsWhenNotPending() {
        Reservation reservation = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(2)
                .status(ReservationStatus.CONFIRMED)
                .expiresAt(LocalDateTime.now().plusMinutes(RESERVATION_TTL_MINUTES))
                .build();
        when(reservationRepository.findByReservationCode("code-1")).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.confirmReservation("code-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pending");
    }

    @Test
    void cancelReservationReleasesStockAndCancels() {
        Reservation reservation = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(3)
                .status(ReservationStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(RESERVATION_TTL_MINUTES))
                .build();
        when(reservationRepository.findByReservationCode("code-1")).thenReturn(Optional.of(reservation));

        reservationService.cancelReservation("code-1");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(stockService).releaseStock("p1", 3);
    }

    @Test
    void cancelReservationSkipsAlreadyCancelled() {
        Reservation reservation = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(3)
                .status(ReservationStatus.CANCELLED)
                .expiresAt(LocalDateTime.now().plusMinutes(RESERVATION_TTL_MINUTES))
                .build();
        when(reservationRepository.findByReservationCode("code-1")).thenReturn(Optional.of(reservation));

        reservationService.cancelReservation("code-1");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(stockService, never()).releaseStock(anyString(), anyInt());
    }

    @Test
    void cancelByOrderCancelsAllPendingReservations() {
        Reservation r1 = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(2)
                .status(ReservationStatus.PENDING).build();
        Reservation r2 = Reservation.builder()
                .id(2L).reservationCode("code-2").orderId("ord-1").productId("p2").quantity(3)
                .status(ReservationStatus.CONFIRMED).build();
        when(reservationRepository.findByOrderId("ord-1")).thenReturn(List.of(r1, r2));

        reservationService.cancelByOrder("ord-1");

        assertThat(r1.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r2.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(stockService).releaseStock("p1", 2);
        verify(stockService).releaseStock("p2", 3);
    }

    @Test
    void listByOrderReturnsReservationsForOrder() {
        Reservation reservation = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(2)
                .status(ReservationStatus.PENDING).build();
        when(reservationRepository.findByOrderId("ord-1")).thenReturn(List.of(reservation));

        List<Reservation> results = reservationService.listByOrder("ord-1");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getOrderId()).isEqualTo("ord-1");
    }

    @Test
    void expireStaleReservationsExpiresPendingPastExpiry() {
        Reservation stale = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(2)
                .status(ReservationStatus.PENDING)
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(java.time.LocalDateTime.class)))
                .thenReturn(List.of(stale));

        reservationService.expireStaleReservations();

        assertThat(stale.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(stockService).releaseStock("p1", 2);
    }
}
