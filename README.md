# Relay

A webhook delivery service. Register an endpoint, submit an event, and Relay
delivers it by HTTP POST — with retries, dead-lettering, and a signed body.

```
POST /events
     │
     ▼
  events ──fan out──▶ deliveries (pending)
                            │
                     SKIP LOCKED claim
                            ▼
                         worker ──POST──▶ your endpoint
                            │
             2xx ───────────┴─────────── non-2xx
              │                             │
          delivered                  retry 2^n s, 5×
                                            │
                                          dead
```

Postgres is both the store and the queue. Workers claim deliveries with
`SELECT ... FOR UPDATE SKIP LOCKED`, so the pool scales horizontally with no
broker and no coordination between replicas. If a worker dies mid-attempt, a
sweeper returns its rows to `pending` and another worker finishes the job.

Java 21 · Spring Boot 3 · PostgreSQL · Flyway · `JdbcClient` (no JPA)

**Live demo:** https://relay-2s95.onrender.com — register an endpoint, send it
an event, watch the delivery retry and dead-letter. It runs on a free instance
that sleeps when idle, so the first load takes about a minute to wake.

## Run

```bash
docker compose up --build
```

Starts Postgres, two worker replicas, and the API plus dashboard on
http://localhost:8080.

The dashboard registers endpoints, submits events, and shows every
delivery with its event type, status code and error. Click an event type
to read the payload that was sent.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/endpoints` | Register a URL; returns the signing secret once |
| `GET` | `/endpoints` | List endpoints |
| `GET` | `/endpoints/{id}/deliveries` | Delivery log with response codes and errors |
| `POST` | `/events` | Submit an event; fans out to matching endpoints |
| `GET` | `/events/{id}` | The event and its payload |
| `GET` | `/deliveries` | Status counts and the recent delivery log |
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

Three variables — `DB_URL`, `DB_USER`, `DB_PASSWORD` — and any reachable
Postgres. Nothing else.

**Free:** [render.yaml](render.yaml) declares one Render web service; pair it
with a Neon database. Neon gives you a single `postgresql://user:pass@host/db`
string — split it across the three variables, prefix the URL with `jdbc:`, and
keep `?sslmode=require`.

That one free instance runs the API, the dashboard and the worker together,
and sleeps after 15 minutes idle. **Deliveries only retry while it is awake**,
so one backing off overnight resumes on the next visit. That is a demo
compromise, not how this is meant to run.

**Properly:** [docker-compose.yml](docker-compose.yml) and [fly.toml](fly.toml)
run the API and the workers as separate processes, selected by
`--relay.worker.enabled`, with the worker pool scaled on its own. That is the
real shape — N workers claiming from one table, no coordination between them.
