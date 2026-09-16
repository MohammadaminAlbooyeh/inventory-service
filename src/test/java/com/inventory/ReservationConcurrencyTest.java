package com.inventory;

import com.inventory.model.Reservation;
import com.inventory.service.ReservationService;
import com.inventory.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hammers the reservation path with concurrent requests for a single product and asserts
 * there is no oversell: exactly {@code stock} reservations succeed and reserved == stock.
 */
@SpringBootTest
@ActiveProfiles("dev")
@EmbeddedKafka(partitions = 1)
@TestPropertySource(properties = {
        "lock.max-attempts=200",
        "lock.base-backoff-ms=5",
        "lock.max-backoff-ms=40"
})
class ReservationConcurrencyTest {

    private static final int STOCK = 10;
    private static final int THREADS = 24;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockService stockService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String productId;

    @BeforeEach
    void setUp() {
        try {
            redisTemplate.hasKey("__ping__:" + UUID.randomUUID());
        } catch (RuntimeException e) {
            assumeTrue(false, "Redis not available: " + e.getMessage());
        }
        Long warehouseId = stockService.createWarehouse("CC", "CC").getId();
        productId = "cc-" + UUID.randomUUID();
        stockService.upsertStock(productId, warehouseId, STOCK);
    }

    @Test
    void doesNotOversellUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();

        Future<?>[] futures = new Future<?>[THREADS];
        for (int i = 0; i < THREADS; i++) {
            final String orderId = "ord-" + i;
            futures[i] = pool.submit(() -> {
                start.await();
                Optional<Reservation> r = reservationService.createReservation(orderId, productId, 1);
                if (r.isPresent()) {
                    success.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(success.get()).isEqualTo(STOCK);
        assertThat(stockService.getByProductId(productId).getReservedQuantity()).isEqualTo(STOCK);
        assertThat(stockService.getByProductId(productId).getAvailableQuantity()).isZero();

        List<Reservation> confirmed = reservationService.listByOrder("ord-0");
        assertThat(confirmed.size()).isLessThanOrEqualTo(1);
    }
}
