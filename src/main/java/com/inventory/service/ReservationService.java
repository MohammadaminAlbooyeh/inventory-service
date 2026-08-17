package com.inventory.service;

import com.inventory.lock.ReservationLockService;
import com.inventory.model.Reservation;
import com.inventory.model.enums.ReservationStatus;
import com.inventory.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final long RESERVATION_TTL_MINUTES = 10;

    private final ReservationRepository reservationRepository;
    private final StockService stockService;
    private final ReservationLockService lockService;

    @Transactional
    public Optional<Reservation> createReservation(String orderId, String productId, int quantity) {
        String lockKey = "product:" + productId;
        if (!lockService.tryLock(lockKey)) {
            log.warn("Could not acquire lock for product {} (order {})", productId, orderId);
            return Optional.empty();
        }
        try {
            if (!stockService.reserveStock(productId, quantity)) {
                log.warn("Not enough stock for product {} (order {})", productId, orderId);
                return Optional.empty();
            }
            Reservation reservation = Reservation.builder()
                    .reservationCode(UUID.randomUUID().toString())
                    .orderId(orderId)
                    .productId(productId)
                    .quantity(quantity)
                    .status(ReservationStatus.PENDING)
                    .expiresAt(LocalDateTime.now().plusMinutes(RESERVATION_TTL_MINUTES))
                    .build();
            return Optional.of(reservationRepository.save(reservation));
        } finally {
            lockService.unlock(lockKey);
        }
    }

    @Transactional
    public Reservation confirmReservation(String reservationCode) {
        Reservation reservation = reservationRepository.findByReservationCode(reservationCode)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + reservationCode));
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new IllegalStateException("Reservation is not pending: " + reservationCode);
        }
        reservation.setStatus(ReservationStatus.CONFIRMED);
        return reservation;
    }

    @Transactional
    public void cancelReservation(String reservationCode) {
        Reservation reservation = reservationRepository.findByReservationCode(reservationCode)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + reservationCode));
        if (reservation.getStatus() == ReservationStatus.PENDING
                || reservation.getStatus() == ReservationStatus.CONFIRMED) {
            stockService.releaseStock(reservation.getProductId(), reservation.getQuantity());
            reservation.setStatus(ReservationStatus.CANCELLED);
        }
    }

    @Transactional(readOnly = true)
    public List<Reservation> listByOrder(String orderId) {
        return reservationRepository.findByOrderId(orderId);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStaleReservations() {
        List<Reservation> stale = reservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, LocalDateTime.now());
        for (Reservation reservation : stale) {
            stockService.releaseStock(reservation.getProductId(), reservation.getQuantity());
            reservation.setStatus(ReservationStatus.EXPIRED);
            log.info("Expired reservation {}", reservation.getReservationCode());
        }
    }
}