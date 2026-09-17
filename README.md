# Relay

A webhook delivery service. Register an endpoint, submit an event, and Relay
delivers it by HTTP POST — with retries, dead-lettering, and a signed body.

Postgres is both the store and the queue. Workers claim deliveries with
`SELECT ... FOR UPDATE SKIP LOCKED`, so the pool scales horizontally with no
broker and no coordination between replicas. If a worker dies mid-attempt, a
sweeper returns its rows to `pending` and another worker finishes the job.

Java 21 · Spring Boot 3 · PostgreSQL · Flyway · `JdbcClient` (no JPA)

## Run

```bash
docker compose up --build
```

Starts Postgres, two worker replicas, and the API plus dashboard on
http://localhost:8080.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/endpoints` | Register a URL; returns the signing secret once |
| `GET` | `/endpoints` | List endpoints |
| `GET` | `/endpoints/{id}/deliveries` | Delivery log with response codes and errors |
| `POST` | `/events` | Submit an event; fans out to matching endpoints |
| `GET` | `/deliveries/{id}` | Status, attempts, next retry time |
| `POST` | `/deliveries/{id}/retry` | Resurrect a dead delivery |

```bash
curl -X POST localhost:8080/endpoints -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/hooks","eventTypes":["invoice.paid"]}'

curl -X POST localhost:8080/events -H 'Content-Type: application/json' \
  -d '{"type":"invoice.paid","payload":{"amount":4200}}'
```

An empty `eventTypes` means "send me everything".

## Delivery rules

- Any 2xx is `delivered`. Anything else records the status code and retries.
- Backoff is `2^attempts` seconds — 2, 4, 8, 16 — capped at 5 attempts.
- The 5th failure marks the delivery `dead`: no more attempts, still visible
  in the log, resurrectable via the retry endpoint.
- 5s request timeout. Redirects are not followed.

## Headers sent to receivers

| Header | Purpose |
|---|---|
| `Relay-Event-Id` | Stable across retries — deduplicate on this |
| `Relay-Event-Type` | The event type |
| `Relay-Delivery-Id` | This delivery row |
| `Relay-Attempt` | Attempt number, from 1 |
| `Relay-Signature` | HMAC-SHA256 of the raw body, hex encoded |

Verify `Relay-Signature` by computing HMAC-SHA256 over the raw request body
with your endpoint secret, then comparing in constant time.

## Tests

```bash
./gradlew build
```

Testcontainers starts a real Postgres and runs the real migrations; WireMock
plays a flaky customer endpoint. Needs a running Docker daemon. On Docker 29+,
add `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`.

## Deploy

One image runs either role, selected by `--relay.worker.enabled`. See
[fly.toml](fly.toml) for the `api` and `worker` process definitions. Set
`DB_URL`, `DB_USER`, and `DB_PASSWORD` in the environment.
