CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(128),
    topic VARCHAR(200) NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    sent_at TIMESTAMP
);

CREATE INDEX idx_outbox_status ON outbox_events(status, id);
