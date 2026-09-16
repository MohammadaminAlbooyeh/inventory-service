package com.inventory.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventRepository repository;

    @org.mockito.Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OutboxService outboxService;

    @Test
    void appendPersistsPendingEventWithSerializedPayload() {
        outboxService.append("inventory.reserved", "ord-1", Map.of("orderId", "ord-1"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getTopic()).isEqualTo("inventory.reserved");
        assertThat(saved.getEventKey()).isEqualTo("ord-1");
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getPayload()).contains("\"orderId\":\"ord-1\"");
    }

    @Test
    void appendAbortsTransactionWhenPayloadCannotBeSerialized() {
        // An empty bean: Jackson fails on it with FAIL_ON_EMPTY_BEANS (the default).
        Object unserializable = new Object() {
        };

        assertThatThrownBy(() -> outboxService.append("t", "k", unserializable))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
