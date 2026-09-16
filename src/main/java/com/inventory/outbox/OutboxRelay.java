package com.inventory.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Drains the outbox to Kafka. Runs on a short fixed delay; failed sends stay PENDING and
 * are retried on the next tick, so a broker outage delays delivery but never loses it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    @Nullable
    private final MeterRegistry meterRegistry;

    @Value("${outbox.batch-size:100}")
    private int batchSize;

    @PostConstruct
    void registerMetrics() {
        if (meterRegistry != null) {
            meterRegistry.gauge("inventory.outbox.pending", this,
                    r -> r.repository.countByStatus(OutboxEvent.Status.PENDING));
        }
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.findByStatusOrderByIdAsc(
                OutboxEvent.Status.PENDING, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get();
                event.setStatus(OutboxEvent.Status.SENT);
                event.setSentAt(LocalDateTime.now());
            } catch (Exception e) {
                event.setAttempts(event.getAttempts() + 1);
                log.warn("Outbox send failed for event {} (topic {}, attempt {}) - will retry",
                        event.getId(), event.getTopic(), event.getAttempts(), e);
            }
        }
        repository.saveAll(batch);
    }
}
