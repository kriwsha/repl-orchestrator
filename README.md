# Logical Replication Orchestrator

[![Minimum required Java version](https://img.shields.io/badge/Java-21%2B-blue?logo=openjdk)](https://openjdk.org/projects/jdk/21/)

A Spring Boot 3 / Java 21 service that provisions PostgreSQL logical replication links and
supervises them, healing the schema drift that otherwise stalls apply and lets WAL pile up
on the source.

Because the service holds connections to **both** sides, schema comparison is direct — no
`dblink` and no publisher-side extension required. That makes it usable against managed
sources you do not control.

## What it does

1. **Pluggable credential storage** — `CredentialStore` SPI; Vault KV v2 included, plus an
   in-memory store for development. Credentials can also be posted to the API.
2. **Takes a full replication spec** — source and subscriber credential ids, publication,
   subscription, slot, and the tables to replicate.
3. **Provisions the link** — publication on the source, then subscription on the subscriber.
4. **Supervises and heals** — on drift, compares source vs subscriber structure and applies
   the safe additive subset, then re-enables the subscription.

## Three design decisions that differ from the obvious approach

### 1. The log message is not the trigger

Matching `...is missing column...` looks like the natural signal. It is the most fragile
piece available:

- the wording differs across major versions (`is missing replicated column` in current PG);
- it is **translated** whenever `lc_messages` is not English;
- `pg_read_file` needs the `pg_read_server_files` role and `logging_collector = on`, and is
  simply unavailable on RDS, Cloud SQL, and most managed platforms.

So the control flow is inverted. **Detectors decide whether to look closer; the schema diff
decides what to do.** A detector firing never causes DDL — only a concrete, resolvable
structural difference does. `ServerLogDetector` remains available as a corroborating plugin
and is disabled by default.

Likewise, *"the slot went inactive"* is a poor trigger: the slot flaps as the apply worker
exits and restarts. Subscriptions are therefore created with `disable_on_error = true`, which
turns a retry storm into a clean latch (`pg_subscription.subenabled = false`) that is stable
to observe.

### 2. Only additive drift is auto-applied

| situation | action | why |
|---|---|---|
| column on source, missing on subscriber, allow-listed built-in type | `ADD COLUMN` | the change that actually breaks apply, and it is safely reversible-free |
| same, custom / enum / domain type | proposed only | the type may not exist on the subscriber, or may exist with different semantics |
| type mismatch | proposed only | in-place `ALTER TYPE` can rewrite the table, fail on data, or silently change meaning |
| column dropped on source | nothing | the source stops sending it; an extra subscriber column is not an error |
| table missing on subscriber | proposed only | needs `ALTER SUBSCRIPTION ... REFRESH PUBLICATION` |

`autoHeal` defaults to **false** — the service reports a plan until you opt in per link.

**Correctness caveat.** Healing restores *replication*, not necessarily *data*. If the source
added the column `WITH DEFAULT`, its historical rows were backfilled locally, and with a fast
default that backfill never travelled through WAL. The subscriber's pre-existing rows stay
NULL. Streaming resumes; backfilling history is a separate job.

### 3. Secrets

- **The API is unauthenticated as written.** Anything that can create a replication link can
  read from the source database. Put Spring Security (mTLS, OIDC) in front of it before it
  is reachable by anything.
- **`CREATE SUBSCRIPTION` writes the password into `pg_subscription.subconninfo`** on the
  subscriber, where superusers can read it. Vault fixes storage in *this* service; it does
  not change that. Use a dedicated `REPLICATION`-only role on the source.
- **Rotation needs a follow-up.** Changing the password in Vault does not update an existing
  subscription — it will keep failing with the old one. Call
  `POST /api/v1/replications/{name}/refresh-connection` after rotating.
- Credentials are re-resolved from the store on every operation (connections are deliberately
  unpooled), so rotation takes effect on the next task rather than being pinned by a warm pool.
- No endpoint returns secret material; `GET` on a credential reports existence only. Make sure
  request-body logging is off.

## Identifier safety

Object names cannot be bind parameters in DDL, so every identifier passes through
`util.Ident`: a conservative allow-list check (letters, digits, underscore, max 63 chars)
followed by proper quoting. Table references must be `schema.table`.

## Plugin model

Both extension points are `ServiceLoader`-discovered, so a third-party JAR on the classpath
adds a backend with no change here:

```
META-INF/services/dev.replorch.spi.CredentialStore
META-INF/services/dev.replorch.spi.ApplyErrorDetector
```

Spring beans take precedence over `ServiceLoader` entries on scheme collision, since those
are the ones that can be configured. Natural additions: AWS Secrets Manager, CyberArk Conjur,
Kubernetes Secrets; a CloudWatch/Loki detector for managed platforms where log files are
exposed through an API rather than `pg_read_file`.

## Build and run

Requires JDK 21 and network access to Maven Central.

```bash
mvn package
VAULT_TOKEN=... java -jar target/repl-orchestrator-0.1.0.jar
```

> Not compiled in the environment this was authored in — Maven Central was unreachable and
> only a JRE was present. Treat the first `mvn package` as the real compile check.

## API

```bash
# 1. store credentials (dev store; use vault: in production)
curl -X POST localhost:8080/api/v1/credentials -H 'Content-Type: application/json' -d '{
  "id": "memory:src-1", "host": "pg-source", "port": 5432, "database": "app",
  "username": "repl", "password": "...", "sslmode": "require" }'

curl -X POST localhost:8080/api/v1/credentials -H 'Content-Type: application/json' -d '{
  "id": "memory:dst-1", "host": "pg-sub", "port": 5432, "database": "app",
  "username": "postgres", "password": "...", "sslmode": "require" }'

# 2. create the replication link
curl -X POST localhost:8080/api/v1/replications -H 'Content-Type: application/json' -d '{
  "name": "orders-to-dwh",
  "sourceCredentialId": "memory:src-1",
  "subscriberCredentialId": "memory:dst-1",
  "publicationName": "pub_orders",
  "subscriptionName": "sub_orders",
  "slotName": "slot_orders",
  "tables": ["public.orders", "public.order_items"],
  "copyData": true,
  "autoHeal": false }'

# 3. health, drift plan, heal
curl localhost:8080/api/v1/replications/orders-to-dwh/health
curl localhost:8080/api/v1/replications/orders-to-dwh/plan
curl -X POST localhost:8080/api/v1/replications/orders-to-dwh/heal

# after rotating the source password in the store
curl -X POST localhost:8080/api/v1/replications/orders-to-dwh/refresh-connection

curl -X DELETE localhost:8080/api/v1/replications/orders-to-dwh
```

`GET /api/v1/credentials/stores` lists the discovered schemes.

## Preflight checks at provisioning time

Before creating anything, the provisioner verifies each table exists on the source and that
it is not `REPLICA IDENTITY DEFAULT` without a primary key — a configuration that replicates
`INSERT` fine and then fails on the first `UPDATE`/`DELETE`, which is a miserable failure to
debug months later.

## Limitations (v0.1)

- The replication registry is in-memory: supervision does not survive a restart. Back
  `ReplicationRegistry` with the orchestrator's own database for production.
- Native pub/sub only; table mapping assumes matching `schema.table` on both sides.
- Column-list publications (PG15+) are not modelled — the diff is whole-table.
- New-table drift is reported, not healed (`REFRESH PUBLICATION` is out of scope).
- No leader election: run one instance, or add locking before running several against the
  same links.

## Relationship to the SQL-level tooling

Prevention beats reactive DDL. Where you control the source, a publisher-side guard that
refuses unsafe changes (and an Expand–Contract-aware migration pipeline) keeps drift from
ever shipping. This service is for sources you do not control, and as defence in depth.
