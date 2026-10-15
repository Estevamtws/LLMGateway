# LLM Gateway

**Language / Idioma:** [English](#llm-gateway) | [Português](#portugues)


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

### In the browser (GitHub Codespaces)

The repository includes a dev container (`.devcontainer/`) with Java 21, Maven, and Docker, so the
whole system runs in a Codespace with nothing installed locally. Create a `.env` there as above,
then run `docker compose up --build` and open the forwarded port 8080.

### Hosting (Render, Railway, Fly.io)

Deploy the `Dockerfile` and set `ADMIN_TOKEN`, `DATABASE_URL`, `DATABASE_USERNAME`, and
`DATABASE_PASSWORD` (for example from Neon). The app listens on `PORT` when the platform sets it.

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

---

<a id="portugues"></a>

# LLM Gateway — Português

[English](#llm-gateway) | **Português**

Um único serviço HTTP entre aplicações clientes e provedores de LLM. As aplicações chamam o gateway
com uma chave de API emitida por ele; o gateway chama o provedor, devolve a resposta e registra
tokens, custo e latência de cada requisição.

Quando várias aplicações chamam os provedores diretamente, não há controle centralizado de gastos,
limites ou tratamento de falhas. O gateway centraliza esses aspectos.

**Status:** fase 1 (MVP) concluída, além de retries e circuit breaker antecipados da fase 3.

## Conteúdo

- [Início rápido](#início-rápido)
- [Console web](#console-web-pt)
- [Uso da API](#uso-da-api)
- [Configuração](#configuração-pt)
- [Decisões de arquitetura](#decisões-de-arquitetura)
- [Estrutura do projeto](#estrutura-do-projeto)
- [Testes](#testes-pt)
- [Roadmap](#roadmap-pt)

## Início rápido

Requisitos: Docker ou Java 21 e Maven para executar fora do Docker.

### Com Docker Compose

```bash
cp .env.example .env    # depois defina POSTGRES_PASSWORD e ADMIN_TOKEN
docker compose up --build
```

O gateway fica disponível em `http://localhost:8080` e o PostgreSQL em `localhost:5432`.
Gere um token administrativo forte com `openssl rand -base64 32`.

### Pela IDE ou pelo Maven

A aplicação lê o mesmo arquivo `.env` quando iniciada na raiz do projeto; basta ter o
PostgreSQL em execução:

```bash
docker compose up -d postgres
mvn spring-boot:run
```

Para utilizar outro banco de dados (por exemplo, [Neon](https://neon.tech)), configure
`DATABASE_URL`, `DATABASE_USERNAME` e `DATABASE_PASSWORD` no `.env`. A URL deve estar no formato JDBC:
`jdbc:postgresql://<host>/<database>?sslmode=require`. No Neon, utilize o endpoint direto,
e não o endpoint `-pooler`, porque o bloqueio de migrações do Flyway não funciona por meio do
PgBouncer no modo de transação.

### Pelo navegador (GitHub Codespaces)

O repositório inclui um contêiner de desenvolvimento (`.devcontainer/`) com Java 21, Maven e Docker.
Assim, todo o sistema pode ser executado em um Codespace sem instalações locais. Crie o arquivo
`.env` como descrito acima, execute `docker compose up --build` e abra a porta 8080 encaminhada.

### Hospedagem (Render, Railway, Fly.io)

Implante o `Dockerfile` e defina `ADMIN_TOKEN`, `DATABASE_URL`, `DATABASE_USERNAME` e
`DATABASE_PASSWORD` (por exemplo, usando o Neon). A aplicação escuta a porta definida em `PORT`
quando a plataforma fornece essa variável.

<a id="console-web-pt"></a>

## Console web

O gateway disponibiliza um console em `http://localhost:8080/`, desenvolvido com HTML, CSS e
JavaScript puros, sem etapa de build (`src/main/resources/static`).

- **Playground:** converse com qualquer modelo por `/v1/chat/completions` usando uma chave do gateway e visualize os tokens e a latência de cada resposta.
- **Clientes:** autentique-se com o token administrativo, crie clientes, emita chaves (exibidas uma única vez, com opções para copiar e "usar no Playground") e revogue chaves.
- **Uso:** consulte totais e custos por modelo para um cliente, com filtros de data.

O token administrativo e a chave do gateway ficam armazenados em `sessionStorage`, sendo descartados
quando a aba é fechada. Os temas claro e escuro acompanham a configuração do sistema até que você
escolha um tema pelo botão circular no canto inferior direito.

## Uso da API

### 1. Criar um cliente e emitir uma chave (administrador)

```bash
curl -X POST http://localhost:8080/admin/clients \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "my-app"}'
# {"id":"<client-id>","name":"my-app","status":"ACTIVE","created_at":"..."}

curl -X POST http://localhost:8080/admin/clients/<client-id>/keys \
  -H "X-Admin-Token: $ADMIN_TOKEN"
# {"id":"<key-id>","client_id":"...","key":"gw_...","key_prefix":"gw_xxxxxxxxx","created_at":"..."}
```

A chave completa aparece apenas nessa resposta. Armazene-a; ela não poderá ser recuperada, apenas substituída.

### 2. Chamar o endpoint de chat completions

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

Como o endpoint segue o formato da OpenAI, os SDKs oficiais da OpenAI funcionam alterando apenas a URL base:

```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8080/v1", api_key="gw_...")
reply = client.chat.completions.create(model="fake-fast", messages=[{"role": "user", "content": "Hello"}])
```

Campos de requisição aceitos: `model`, `messages` (cada mensagem com `role` e `content`),
`max_tokens` e `temperature`. Outros campos são ignorados. `stream: true` retorna erro 400.

### 3. Consultar uso (administrador)

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

`from` é inclusivo e `to` é exclusivo; ambos são opcionais. Chamadas com falha contam como
requisições com zero tokens e custo zero.

### 4. Revogar uma chave (administrador)

```bash
curl -X DELETE http://localhost:8080/admin/keys/<key-id> -H "X-Admin-Token: $ADMIN_TOKEN"
```

### Endpoints

| Rota | Autenticação | Finalidade | Sucesso |
|---|---|---|---|
| `POST /v1/chat/completions` | `Authorization: Bearer <key>` | Completar conversa | 200 |
| `GET /admin/clients` | `X-Admin-Token` | Listar clientes e suas chaves (somente prefixo e status) | 200 |
| `POST /admin/clients` | `X-Admin-Token` | Criar cliente | 201 |
| `POST /admin/clients/{id}/keys` | `X-Admin-Token` | Emitir chave | 201 |
| `DELETE /admin/keys/{id}` | `X-Admin-Token` | Revogar chave | 204 |
| `GET /admin/usage?clientId=&from=&to=` | `X-Admin-Token` | Totais de uso por modelo | 200 |

### Erros

Todos os erros seguem o mesmo formato:

```json
{"error": {"message": "Unknown model 'gpt-9'", "type": "invalid_request_error", "code": "unknown_model"}}
```

| Status | Quando ocorre |
|---|---|
| 400 | Corpo ou parâmetros inválidos, modelo desconhecido, `stream: true` |
| 401 | Chave ausente, desconhecida ou revogada; cliente suspenso; token administrativo inválido |
| 404 | Cliente ou chave desconhecido (endpoints administrativos) |
| 409 | Nome de cliente já utilizado |
| 502 | O provedor retornou um erro |
| 503 | O circuit breaker do provedor está aberto |
| 504 | O provedor excedeu o tempo limite |

Uma chamada com falha no provedor ainda gera um registro de uso com status `PROVIDER_ERROR`,
`TIMEOUT` ou `CIRCUIT_OPEN`.

<a id="configuração-pt"></a>

## Configuração

Todas as configurações vêm de variáveis de ambiente (ou do `.env`). Os valores padrão estão em
`src/main/resources/application.yml`.

| Variável | Padrão | Finalidade |
|---|---|---|
| `ADMIN_TOKEN` | *(obrigatório)* | Token para endpoints administrativos |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/gateway` | URL JDBC |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `gateway` / *(vazio)* | Credenciais do banco |
| `POSTGRES_PASSWORD` | *(obrigatório no Compose)* | Senha do PostgreSQL no Compose |
| `FAKE_FAST_LATENCY_MS` / `FAKE_SLOW_LATENCY_MS` | `50` / `1000` | Latência dos provedores simulados |
| `FAKE_ERROR_RATE` | `0.0` | Probabilidade de erro do provedor simulado, de 0.0 a 1.0 |
| `OPENAI_API_KEY` | *(vazio: desabilitado)* | Habilita o provedor real |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Qualquer API compatível com a OpenAI |
| `OPENAI_MODELS` | *(vazio)* | Modelos atendidos pelo provedor real, separados por vírgulas |
| `OPENAI_PROVIDER_NAME` | `openai` | Nome do provedor armazenado nos registros de uso |
| `OPENAI_CONNECT_TIMEOUT_MS` / `OPENAI_READ_TIMEOUT_MS` | `2000` / `30000` | Tempos limite do provedor real |
| `RETRY_MAX_ATTEMPTS` | `3` | Tentativas por requisição, incluindo a primeira; 1 desabilita retries |
| `RETRY_INITIAL_WAIT_MS` | `200` | Espera inicial entre tentativas; dobra a cada retry |
| `RETRY_ON_TIMEOUT` | `false` | Define se timeouts são repetidos |
| `CB_FAILURE_RATE_THRESHOLD` | `50` | Percentual de falhas que abre o circuito |
| `CB_SLIDING_WINDOW_SIZE` | `20` | Número de chamadas usado para calcular a taxa de falhas |
| `CB_MINIMUM_NUMBER_OF_CALLS` | `10` | Chamadas necessárias para que o circuito possa abrir |
| `CB_WAIT_IN_OPEN_MS` | `30000` | Tempo em que o circuito permanece aberto antes das chamadas de teste |

### Provedores

**Provedor simulado** (`fake-fast`, `fake-slow`): sempre habilitado. Responde de forma determinística
com base na última mensagem do usuário e estima tokens dividindo a quantidade de caracteres por 4,
arredondando para cima. Testes e testes de carga o utilizam, sem custos nem acesso à rede.

**Provedor real:** cliente para a API de chat completions da OpenAI. Também funciona com serviços
compatíveis, alterando `OPENAI_BASE_URL`, como Groq (`https://api.groq.com/openai/v1`),
OpenRouter (`https://openrouter.ai/api/v1`), DeepSeek (`https://api.deepseek.com/v1`)
ou um Ollama local (`http://localhost:11434/v1`). As quantidades de tokens sempre vêm da resposta do provedor.

O roteamento seleciona o primeiro provedor que atende ao modelo solicitado; o provedor simulado
é verificado primeiro.

### Preços

Os preços ficam em `application.yml`, em dólares americanos por milhão de tokens, para cada modelo.
**Todo modelo atendido por um provedor precisa ter preço configurado, ou o gateway se recusa a iniciar.**
Ao adicionar modelos em `OPENAI_MODELS`, inclua os preços atuais informados na página de preços do provedor:

```yaml
gateway:
  pricing:
    models:
      gpt-4o-mini:
        input-per-million: 0.15
        output-per-million: 0.60
      "[openai/gpt-4.1]":          # colchetes para nomes com pontos ou barras
        input-per-million: 2.00
        output-per-million: 8.00
```

O custo é `input_tokens * input_price / 1,000,000 + output_tokens * output_price / 1,000,000`,
calculado com `BigDecimal` e arredondado para 8 casas decimais, com a regra half-up.

### Retries e circuit breaker

Cada provedor tem seu próprio mecanismo de retry e seu próprio circuit breaker
([Resilience4j](https://resilience4j.readme.io/)). O retry envolve o circuit breaker; assim,
cada tentativa conta para a taxa de falhas, e um circuito aberto interrompe imediatamente
as tentativas restantes.

- Erros passíveis de retry: HTTP 408, 409, 429, 5xx e falhas de rede. Outras respostas 4xx falhariam novamente; portanto, não são repetidas nem contabilizadas pelo circuit breaker. Isso impede que requisições inválidas de um cliente bloqueiem o provedor para todos.
- Timeouts não são repetidos por padrão, pois cada tentativa pode consumir todo o tempo limite de leitura.
- Enquanto o circuito estiver aberto, as requisições recebem imediatamente 503, sem acessar o provedor.

## Decisões de arquitetura

### D1. O endpoint público segue o formato de chat completions da OpenAI

Qualquer SDK existente da OpenAI funciona com o gateway alterando apenas a URL base; assim, os
clientes não precisam de código específico. A desvantagem é que o contrato público fica atrelado
a um formato que não controlamos; a fase 1 oferece suporte apenas a um subconjunto dele.

### D2. As chaves de API são armazenadas como hashes SHA-256, nunca em texto puro

A chave é exibida uma única vez, durante a criação; apenas seu hash e um prefixo de 12 caracteres
são armazenados. As chaves são valores aleatórios longos (32 bytes gerados por `SecureRandom`),
portanto um hash lento como bcrypt não acrescenta proteção, e a chave é consultada em toda
requisição, o que exige uma consulta rápida e indexável. Uma chave perdida não pode ser recuperada,
apenas substituída.

### D3. Os provedores ficam atrás de uma única interface `LlmProvider`

```java
public interface LlmProvider {
    String name();
    Set<String> models();
    default boolean supports(String model) { return models().contains(model); }
    ChatResult complete(ChatRequest request);
}
```

`ChatRequest` e `ChatResult` são tipos internos, independentes da API pública e do formato de cada
provedor. Isso permite trocar provedores, aplicar retries e executar testes de carga sem modificar
o fluxo das requisições; o provedor simulado torna os testes gratuitos, rápidos e determinísticos.
O método `models()` foi adicionado à interface original para identificar preços ausentes na inicialização.

### D4. O custo é calculado e armazenado no momento da requisição, usando `BigDecimal`

Os preços dos provedores mudam; calcular custos antigos a partir de uma tabela atual reescreveria
o histórico. `double` não é adequado para valores monetários. Um preço incorreto na configuração
produz custos armazenados incorretamente, que precisam de correção manual.

### D5. Spring MVC com virtual threads, em vez de WebFlux

A carga consiste quase inteiramente em aguardar I/O de rede, o que virtual threads tratam bem.
Código bloqueante é mais simples de escrever, testar e depurar do que código reativo. Streaming
(fase 4) exigirá cuidado, e é necessário evitar operações que fixem uma virtual thread à sua
carrier thread.

### D6. O conteúdo de prompts e respostas nunca é persistido

Somente metadados são armazenados: tokens, custo, latência e status. Prompts podem conter dados
pessoais ou confidenciais. A consequência é que não é possível reproduzir requisições nem depurá-las
a partir dos dados armazenados.

### O1. Se falhar o armazenamento do registro de uso, o cliente ainda recebe a resposta

**Decisão:** priorizar a disponibilidade. Se o provedor respondeu, mas o registro de uso não pôde
ser salvo, o cliente recebe a resposta e a falha é registrada em nível `ERROR`, com todos os campos
do registro perdido (chave, provedor, modelo, tokens, custo, latência, status), permitindo a
reconciliação pelos logs.

**Motivo:** um problema no banco de dados não deveria indisponibilizar todo o gateway. O custo da
chamada ao provedor já foi incorrido, de modo que falhar a requisição também desperdiçaria esse custo.
A consequência é que parte do uso poderá ficar sem cobrança até a reconciliação.

### O2. O primeiro provedor real é um cliente compatível com a OpenAI

Um único cliente cobre a OpenAI e os vários serviços que reproduzem sua API. O formato público já
é compatível (D1), e sua URL base, chave e lista de modelos se encaixam na configuração por
variáveis de ambiente.

### O3. Nome do repositório e do serviço

Ainda não definido. Atualmente, o código usa o pacote `com.llmgateway` e o nome de serviço
`llm-gateway`.

## Estrutura do projeto

Os pacotes são organizados por funcionalidade:

| Pacote | Conteúdo |
|---|---|
| `auth` | Geração e hash de chaves; interceptadores de token administrativo e de chave de API |
| `client` | Clientes e chaves; endpoints administrativos |
| `completion` | Endpoint público, DTOs de requisição e resposta, roteamento |
| `provider` | `LlmProvider`, tipos internos, provedores simulados e compatíveis com OpenAI, resiliência |
| `usage` | Registros de uso, cálculo de custo e relatório de uso |
| `config` | Formato dos erros, tratamento de exceções e configuração web |

As migrações do banco de dados ficam em `src/main/resources/db/migration` (Flyway).

<a id="testes-pt"></a>

## Testes

```bash
mvn verify
```

Os testes de integração iniciam o PostgreSQL com Testcontainers; por isso, o Docker precisa estar
em execução. Nenhum teste chama um provedor real: o cliente do provedor real é testado com WireMock
(sucesso, erros HTTP, timeouts e respostas malformadas). A integração contínua executa o mesmo
comando em cada push (`.github/workflows/ci.yml`).

<a id="roadmap-pt"></a>

## Roadmap

- **Fase 2:** rate limiting e quotas de gastos com Redis; cache de respostas.
- **Fase 3:** um segundo provedor real e fallback entre provedores. Retries e circuit breaker já estão implementados.
- **Fase 4:** streaming; métricas com Micrometer e Prometheus; dashboard do Grafana; testes de carga contra o provedor simulado, com publicação dos resultados.
