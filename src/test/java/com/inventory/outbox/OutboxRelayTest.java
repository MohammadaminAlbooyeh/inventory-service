package com.inventory.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, kafkaTemplate, null);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
    }

    private OutboxEvent pending(long id) {
        return OutboxEvent.builder()
                .id(id).topic("inventory.reserved").eventKey("ord-" + id).payload("{}")
                .status(OutboxEvent.Status.PENDING).attempts(0)
                .build();
    }

    @Test
    void marksEventSentAfterSuccessfulPublish() {
        OutboxEvent event = pending(1);
        when(repository.findByStatusOrderByIdAsc(eq(OutboxEvent.Status.PENDING), any()))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.SENT);
        assertThat(event.getSentAt()).isNotNull();
        verify(repository).saveAll(List.of(event));
    }

    @Test
    void keepsEventPendingAndCountsAttemptWhenPublishFails() {
        OutboxEvent event = pending(1);
        when(repository.findByStatusOrderByIdAsc(eq(OutboxEvent.Status.PENDING), any()))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        verify(repository).saveAll(List.of(event));
    }

    @Test
    void doesNothingWhenOutboxIsEmpty() {
        when(repository.findByStatusOrderByIdAsc(eq(OutboxEvent.Status.PENDING), any()))
                .thenReturn(List.of());

        relay.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(repository, never()).saveAll(any());
    }
}
