package com.inventory.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer retries are bounded; once they are exhausted the record is forwarded to a
 * per-topic dead-letter topic ({@code <topic>.DLT}) instead of blocking the partition.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_INTERVAL_MS = 1_000L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> {
                    log.error("Routing record from {} offset {} to DLT after {} retries",
                            record.topic(), record.offset(), MAX_RETRIES, exception);
                    return new TopicPartition(record.topic() + ".DLT", -1);
                });
        return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    }
}
