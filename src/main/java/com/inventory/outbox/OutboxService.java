package com.inventory.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends events to the outbox. Call from inside the business transaction so the event and
 * the state change commit atomically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, Object payload) {
        try {
            OutboxEvent event = OutboxEvent.builder()
                    .topic(topic)
                    .eventKey(key)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxEvent.Status.PENDING)
                    .attempts(0)
                    .build();
            repository.save(event);
        } catch (Exception e) {
            // A serialization failure must abort the business transaction - the event
            // would otherwise be silently dropped.
            throw new IllegalStateException("Failed to serialize outbox payload for topic " + topic, e);
        }
    }
}
