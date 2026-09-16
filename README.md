# inventory-service

Stock and reservation service for the platform. Owns warehouses, stock levels, and
order reservations, and participates in the order saga over Kafka.

## Responsibilities

- Track stock per product/warehouse (`quantity`, `reservedQuantity`, derived `availableQuantity`).
- Reserve stock for orders with a Redis-backed per-product lock to avoid oversell.
- Confirm / cancel / expire reservations (stale `PENDING` reservations expire after 10 minutes).
- Consume `order.created` → reserve stock → publish `inventory.reserved` or `inventory.reservation_failed`.
- Consume `inventory.reservation_cancel` → release stock (saga compensation).

## Tech

Spring Boot 3.3 · Java 17 · PostgreSQL + Flyway · Redis · Spring Kafka · springdoc OpenAPI

## Running locally

```bash
# Everything (Postgres, Redis, Kafka, service):
docker compose up --build

# Or just the infra, then run the app from your IDE / CLI with the dev profile (H2, no Flyway):
docker compose up postgres redis kafka
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Service listens on `:8084`.

- Swagger UI: http://localhost:8084/swagger-ui.html
- Health: http://localhost:8084/actuator/health

## Build

```bash
mvn verify
```

`com.platform:java-common-lib:0.1.0-SNAPSHOT` (shared events + topic names) must be in the
local Maven repo. Build it first from the platform monorepo:

```bash
mvn -f ../shared/java-common-lib/pom.xml install
```

## Configuration

| Env var | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/inventory_db` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `platform` / `platform` | DB credentials |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis for reservation locks |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka bootstrap |
| `API_KEY` | *(empty)* | If set, every `/api/**` call must send `X-API-Key: <value>`. Empty = open. |
| `RATE_LIMIT_ENABLED` | `true` | Redis-backed fixed-window limiter on `/api/**` |
| `RATE_LIMIT_REQUESTS` / `RATE_LIMIT_WINDOW_MS` | `120` / `10000` | Limiter budget and window |
| `OUTBOX_POLL_INTERVAL_MS` / `OUTBOX_BATCH_SIZE` | `1000` / `100` | Outbox relay poll delay and batch size |

## Security & operability

- **Auth**: static `X-API-Key` shared secret, enforced only when `API_KEY` is set (service
  is expected to sit behind the gateway). `/actuator/health`, `/actuator/info`,
  `/actuator/prometheus` and Swagger stay public.
- **Rate limiting**: Redis-backed per-client-IP fixed-window limiter returns `429` past the
  budget; the budget is shared across instances. Fails open if Redis is unreachable.
- **Reliable events**: outbound Kafka events are written to a transactional `outbox_events`
  table in the business transaction and drained by a relay, so a broker outage delays
  delivery but never loses an event. Dead-lettered records land on `<topic>.DLT` and are
  logged + counted (`inventory.kafka.dlt`).
- **Metrics**: `inventory.reservations{outcome=created|insufficient_stock|lock_contention|expired}`
  counters, exposed at `/actuator/prometheus`.
- **List endpoints** are paged: `GET /api/inventory/items?page=0&size=20` returns a Spring
  `Page` (`content`, `totalElements`, …). Max page size 100.

## HTTP API

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/inventory/items?page=&size=` | List stock items (paged) |
| GET | `/api/inventory/items/{productId}` | Get one stock item |
| POST | `/api/inventory/items` | Upsert stock (`productId`, `warehouseId`, `quantity`) |
| POST | `/api/inventory/items/{productId}/restock` | Add quantity |
| GET/POST | `/api/inventory/warehouses` | List / create warehouses |
| POST | `/api/inventory/reservations` | Reserve stock (`orderId`, `productId`, `quantity`) |
| POST | `/api/inventory/reservations/{code}/confirm` | Confirm a reservation |
| POST | `/api/inventory/reservations/{code}/cancel` | Cancel and release |
| GET | `/api/inventory/orders/{orderId}/reservations` | Reservations for an order |

## Kafka

| Direction | Topic | Payload |
| --- | --- | --- |
| in | `order.created` | `OrderCreatedEvent` (shared lib) |
| in | `inventory.reservation_cancel` | `{ "orderId": "..." }` |
| out | `inventory.reserved` | `{ orderId, reservations[] }` |
| out | `inventory.reservation_failed` | `{ orderId, reason }` |

Consuming `order.created` is idempotent per `orderId`. Consumer retries are bounded
(3 attempts, 1s back-off); after that the record is routed to `<topic>.DLT`.
