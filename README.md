# LLM Gateway

A single HTTP service between client applications and LLM providers. Applications call the gateway
with a gateway-issued API key; the gateway calls the provider, returns the answer, and records
tokens, cost, and latency for every request.

When several applications call providers directly, nobody controls spending, limits, or failure
handling. The gateway centralizes that.

**Status:** phase 1 (MVP) complete, plus retries and a circuit breaker brought forward from phase 3.

## Contents

- [Quick start](#quick-start)
- [Web console](#web-console)
- [Using the API](#using-the-api)
- [Configuration](#configuration)
- [Architecture decisions](#architecture-decisions)
- [Project layout](#project-layout)
- [Testing](#testing)
- [Roadmap](#roadmap)

## Quick start

Requirements: Docker, or Java 21 and Maven for running outside Docker.

### With Docker Compose

```bash
cp .env.example .env    # then set POSTGRES_PASSWORD and ADMIN_TOKEN
docker compose up --build
```

The gateway listens on `http://localhost:8080` and PostgreSQL on `localhost:5432`.
Generate a strong admin token with `openssl rand -base64 32`.

### From an IDE or Maven

The app reads the same `.env` file when it is started from the project root, so no extra setup
is needed beyond a running PostgreSQL:

```bash
docker compose up -d postgres
mvn spring-boot:run
```

To use another database (for example [Neon](https://neon.tech)), set `DATABASE_URL`,
`DATABASE_USERNAME`, and `DATABASE_PASSWORD` in `.env`. The URL must be a JDBC URL:
`jdbc:postgresql://<host>/<database>?sslmode=require`. With Neon, use the direct endpoint rather
than the `-pooler` one, because Flyway's migration lock does not work through PgBouncer in
transaction mode.

## Web console

The gateway serves a small console at `http://localhost:8080/`, written in plain HTML, CSS, and
JavaScript with no build step (`src/main/resources/static`).

- **Playground:** chat with any model through `/v1/chat/completions` using a gateway key, and see
  tokens and latency for each answer.
- **Clients:** sign in with the admin token, create clients, issue keys (shown once, with copy and
  "use in Playground"), and revoke keys.
- **Usage:** totals and cost per model for a client, with date filters.

The admin token and the gateway key are kept in `sessionStorage`, so they are forgotten when the
tab closes. Light and dark themes follow the system setting until you pick one with the round button
in the bottom right corner.

## Using the API

### 1. Create a client and issue a key (admin)

```bash
curl -X POST http://localhost:8080/admin/clients \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "my-app"}'
# {"id":"<client-id>","name":"my-app","status":"ACTIVE","created_at":"..."}

curl -X POST http://localhost:8080/admin/clients/<client-id>/keys \
  -H "X-Admin-Token: $ADMIN_TOKEN"
# {"id":"<key-id>","client_id":"...","key":"gw_...","key_prefix":"gw_xxxxxxxxx","created_at":"..."}
```

The full key appears only in this response. Store it; it cannot be recovered, only replaced.

### 2. Call the chat completions endpoint

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer gw_..." -H "Content-Type: application/json" \
  -d '{"model": "fake-fast", "messages": [{"role": "user", "content": "Hello"}], "max_tokens": 100}'
```

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1792003511,
  "model": "fake-fast",
  "choices": [
    {"index": 0, "message": {"role": "assistant", "content": "[fake-fast] You said: Hello"}, "finish_reason": "stop"}
  ],
  "usage": {"prompt_tokens": 2, "completion_tokens": 7, "total_tokens": 9}
}
```

Because the endpoint follows the OpenAI format, the official OpenAI SDKs work by changing only the
base URL:

```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="gw_...")
reply = client.chat.completions.create(model="fake-fast", messages=[{"role": "user", "content": "Hello"}])
```

Supported request fields: `model`, `messages` (each with `role` and `content`), `max_tokens`,
`temperature`. Other fields are ignored. `stream: true` is rejected with 400.

### 3. Read usage (admin)

```bash
curl "http://localhost:8080/admin/usage?clientId=<client-id>&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z" \
  -H "X-Admin-Token: $ADMIN_TOKEN"
```

```json
{
  "client_id": "...",
  "from": "2026-10-01T00:00:00Z",
  "to": "2026-11-01T00:00:00Z",
  "models": [
    {"model": "fake-fast", "requests": 1, "input_tokens": 2, "output_tokens": 7, "cost_usd": 0.00000300}
  ]
}
```

`from` is inclusive and `to` exclusive; both are optional. Failed calls count as requests with
zero tokens and zero cost.

### 4. Revoke a key (admin)

```bash
curl -X DELETE http://localhost:8080/admin/keys/<key-id> -H "X-Admin-Token: $ADMIN_TOKEN"
```

### Endpoints

| Route | Auth | Purpose | Success |
|---|---|---|---|
| `POST /v1/chat/completions` | `Authorization: Bearer <key>` | Chat completion | 200 |
| `GET /admin/clients` | `X-Admin-Token` | List clients with their keys (prefix and status only) | 200 |
| `POST /admin/clients` | `X-Admin-Token` | Create a client | 201 |
| `POST /admin/clients/{id}/keys` | `X-Admin-Token` | Issue a key | 201 |
| `DELETE /admin/keys/{id}` | `X-Admin-Token` | Revoke a key | 204 |
| `GET /admin/usage?clientId=&from=&to=` | `X-Admin-Token` | Usage totals per model | 200 |

### Errors

Every error uses the same body:

```json
{"error": {"message": "Unknown model 'gpt-9'", "type": "invalid_request_error", "code": "unknown_model"}}
```

| Status | When |
|---|---|
| 400 | Invalid body or parameters, unknown model, `stream: true` |
| 401 | Missing, unknown, or revoked key; suspended client; bad admin token |
| 404 | Unknown client or key (admin endpoints) |
| 409 | Client name already taken |
| 502 | The provider returned an error |
| 503 | The provider's circuit breaker is open |
| 504 | The provider timed out |

A failed provider call still produces a usage record, with status `PROVIDER_ERROR`, `TIMEOUT`,
or `CIRCUIT_OPEN`.

## Configuration

All settings come from environment variables (or `.env`). Defaults are in
`src/main/resources/application.yml`.

| Variable | Default | Purpose |
|---|---|---|
| `ADMIN_TOKEN` | *(required)* | Token for the admin endpoints |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/gateway` | JDBC URL |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `gateway` / *(empty)* | Database credentials |
| `POSTGRES_PASSWORD` | *(required by Compose)* | Password for the Compose PostgreSQL |
| `FAKE_FAST_LATENCY_MS` / `FAKE_SLOW_LATENCY_MS` | `50` / `1000` | Fake provider latency |
| `FAKE_ERROR_RATE` | `0.0` | Fake provider error probability, 0.0 to 1.0 |
| `OPENAI_API_KEY` | *(empty: disabled)* | Enables the real provider |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Any OpenAI-compatible API |
| `OPENAI_MODELS` | *(empty)* | Comma-separated models served by the real provider |
| `OPENAI_PROVIDER_NAME` | `openai` | Provider name stored in usage records |
| `OPENAI_CONNECT_TIMEOUT_MS` / `OPENAI_READ_TIMEOUT_MS` | `2000` / `30000` | Real provider timeouts |
| `RETRY_MAX_ATTEMPTS` | `3` | Attempts per request, including the first; 1 disables retries |
| `RETRY_INITIAL_WAIT_MS` | `200` | First backoff wait; doubles after each retry |
| `RETRY_ON_TIMEOUT` | `false` | Whether timeouts are retried |
| `CB_FAILURE_RATE_THRESHOLD` | `50` | Failure percentage that opens the circuit |
| `CB_SLIDING_WINDOW_SIZE` | `20` | Calls the failure rate is computed over |
| `CB_MINIMUM_NUMBER_OF_CALLS` | `10` | Calls needed before the circuit can open |
| `CB_WAIT_IN_OPEN_MS` | `30000` | Time the circuit stays open before test calls |

### Providers

**Fake provider** (`fake-fast`, `fake-slow`): always on. It answers deterministically from the last
user message and estimates tokens as characters divided by 4, rounded up. Tests and load tests use
it, so they cost nothing and never touch the network.

**Real provider**: a client for the OpenAI chat completions API. It also works with compatible
services by changing `OPENAI_BASE_URL`, for example Groq (`https://api.groq.com/openai/v1`),
OpenRouter (`https://openrouter.ai/api/v1`), DeepSeek (`https://api.deepseek.com/v1`), or a local
Ollama (`http://localhost:11434/v1`). Token counts always come from the provider's response.

Routing picks the first provider that serves the requested model; the fake provider is checked
first.

### Prices

Prices live in `application.yml`, in USD per million tokens, per model. **Every model a provider
serves needs a price, or the gateway refuses to start.** When you add models to `OPENAI_MODELS`,
add their current prices from the provider's pricing page:

```yaml
gateway:
  pricing:
    models:
      gpt-4o-mini:
        input-per-million: 0.15
        output-per-million: 0.60
      "[openai/gpt-4.1]":          # brackets for names with dots or slashes
        input-per-million: 2.00
        output-per-million: 8.00
```

Cost is `input_tokens * input_price / 1,000,000 + output_tokens * output_price / 1,000,000`,
computed with `BigDecimal` and rounded half-up to 8 decimal places.

### Retries and circuit breaker

Each provider has its own retry and its own circuit breaker
([Resilience4j](https://resilience4j.readme.io/)). The retry wraps the circuit breaker, so every
attempt counts towards the failure rate and an open circuit stops the remaining attempts at once.

- Retryable errors: HTTP 408, 409, 429, 5xx, and network failures. Other 4xx responses would fail
  the same way again, so they are neither retried nor counted by the circuit breaker; this keeps
  one client's bad requests from cutting off the provider for everyone.
- Timeouts are not retried by default, because each attempt can take the full read timeout.
- While the circuit is open, requests get 503 at once without reaching the provider.

## Architecture decisions

### D1. The public endpoint follows the OpenAI chat completions format

Any existing OpenAI SDK works against the gateway by changing only the base URL, so clients need
no custom code. The cost is that the public contract is tied to a format we do not control; phase 1
supports a subset of it.

### D2. API keys are stored as SHA-256 hashes, never in plain text

The key is shown once, at creation; only its hash and a 12-character prefix are stored. Keys are
long random values (32 bytes from `SecureRandom`), so a slow hash such as bcrypt adds no
protection, and the key is looked up on every request, so the lookup must be fast and indexable.
A lost key cannot be recovered, only replaced.

### D3. Providers sit behind a single `LlmProvider` interface

```java
public interface LlmProvider {
    String name();
    Set<String> models();
    default boolean supports(String model) { return models().contains(model); }
    ChatResult complete(ChatRequest request);
}
```

`ChatRequest` and `ChatResult` are internal types, independent of the public API and of any
provider's format. This lets providers be swapped, retried, and load tested without touching the
request flow, and the fake provider makes tests free, fast, and deterministic. `models()` was added
to the original interface so that missing prices are caught at startup.

### D4. Cost is computed and stored at request time, using `BigDecimal`

Provider prices change, so computing cost later from a current price table would rewrite history.
`double` is not acceptable for money. A wrong price in configuration produces wrong stored costs
that need a manual correction.

### D5. Spring MVC with virtual threads, not WebFlux

The workload is almost entirely waiting on network I/O, which virtual threads handle well, and
blocking code is simpler to write, test, and debug than reactive code. Streaming (phase 4) will
need care, and anything that pins a carrier thread must be avoided.

### D6. Prompt and response content is never persisted

Only metadata is stored: tokens, cost, latency, and status. Prompts may contain personal or
confidential data. The trade-off is that requests cannot be replayed or debugged from stored data.

### O1. If saving the usage record fails, the client still gets its response

**Decision:** availability first. If the provider answered but the usage record cannot be saved,
the client receives the response and the failure is logged at `ERROR` with every field of the lost
record (key, provider, model, tokens, cost, latency, status), so it can be reconciled from the logs.

**Why:** a database problem should not take the whole gateway down, and the provider has already
been paid for the call, so failing the request would waste that cost as well. The price is that
some usage can go unbilled until it is reconciled.

### O2. The first real provider is an OpenAI-compatible client

One client covers OpenAI and the many services that copy its API, the public format already
matches it (D1), and its base URL, key, and model list fit the environment-variable configuration.

### O3. Repository and service name

Still open. The code currently uses the package `com.llmgateway` and the service name
`llm-gateway`.

## Project layout

Packages are organized by feature:

| Package | Contents |
|---|---|
| `auth` | Key generation and hashing, admin token and API key interceptors |
| `client` | Clients and keys, admin endpoints |
| `completion` | Public endpoint, request and response DTOs, routing |
| `provider` | `LlmProvider`, internal types, fake and OpenAI-compatible providers, resilience |
| `usage` | Usage records, cost calculation, usage report |
| `config` | Error format, exception handling, web configuration |

Database migrations are in `src/main/resources/db/migration` (Flyway).

## Testing

```bash
mvn verify
```

Integration tests start PostgreSQL with Testcontainers, so Docker must be running. No test calls a
real provider: the real provider client is tested against WireMock (success, HTTP errors,
timeouts, malformed responses). CI runs the same command on every push
(`.github/workflows/ci.yml`).

## Roadmap

- **Phase 2:** rate limiting and spending quotas with Redis; response caching.
- **Phase 3:** a second real provider and fallback between providers. Retries and the circuit
  breaker are already in place.
- **Phase 4:** streaming; metrics with Micrometer and Prometheus; a Grafana dashboard; load tests
  against the fake provider with published results.
