# PoS — backend

Spring MVC + Hibernate + MySQL API for the point-of-sale app, served by Jetty.
This repo is the API only — it pairs with
**[pos-frontend](https://github.com/mitul-garg/pos-frontend)** for the actual
point-of-sale UI, or can be driven directly via `curl`/Swagger. Spec:
[`requirements.md`](./requirements.md).

> **Status: C8 complete** — the app boots on Jetty, serves JSON, persists to MySQL
> through Hibernate, authenticates with JWTs, **scopes every query to the caller's
> tenant**, serves the catalogue (products, variants, QR codes minted from each
> store's own sequence), takes orders through to payment (hold/resume/cancel,
> server-recomputed pricing, an atomic stock decrement at checkout), takes returns
> (refund against the original sale's own snapshot, stock restored, a
> returnable-quantity check that locks the original order rather than racing it),
> and manages users and tenants — a tenant-scoped admin can't mint a `SUPER_ADMIN`
> or lock out the last active admin, and `/api/tenants/**` is the one cross-tenant
> surface, `SUPER_ADMIN`-only with the Hibernate filter disabled. All nine tables
> exist and `schema.sql` is committed. **A local MySQL is required to run
> `mvn test`**, and **a JWT signing key is required to run the app at all** (see
> Credentials below). Every C-step's endpoints were verified manually first (see
> [`prompts/README.md`](./prompts/README.md) for the per-step write-ups), then
> covered by an automated suite. `pos-frontend` now points at this API over HTTP
> (see Pairing it with the frontend, below) rather than its own mock store.

## Getting Started

**Prerequisites:** JDK 17+, Maven, a MySQL 8.0.16+ server running locally.

```sh
git clone https://github.com/mitul-garg/pos-backend.git
cd pos-backend
```

Two secrets don't ship with a working default, on purpose (see
[Credentials](#credentials) below for why) — add them to a gitignored local
file before the app will boot:

```properties
# src/main/resources/application-local.properties  (create this file)
pos.db.password=your-mysql-password
pos.jwt.secret=<32+ random bytes — e.g. output of `openssl rand -base64 48`>
```

```sh
mvn clean install
mvn jetty:run          # http://localhost:8080
```

This creates the `pos_dev` database on first connect, builds the schema from
the entities, and seeds demo tenants/users/catalogue (see
[Seed data](#seed-data-dev-only) below). Confirm it's up:

```sh
curl http://localhost:8080/api/health
```

or browse [`/swagger-ui/`](http://localhost:8080/swagger-ui/) for the whole
API. `POST /api/auth/login` with `{"tenantCode":"mg-road","username":"admin","password":"admin123"}`
gets you a token to try the rest with.

```sh
mvn test                # needs pos_test — see Database setup below
```

### Pairing it with the frontend

This repo has no UI of its own. Clone
**[pos-frontend](https://github.com/mitul-garg/pos-frontend)** alongside it
and run `npm install && npm run dev` — it talks to `http://localhost:8080/api`
by default, and this backend's CORS chain (`SecurityConfig`) already allows
its dev server's origin (`http://localhost:5173`) out of the box. See
pos-frontend's own README for its setup, including a mock-data-only mode that
needs no backend running at all.

Before changing anything here, read [`prompts/README.md`](./prompts/README.md) —
it indexes the conventions and the database documentation.

## Stack

Spring MVC (**not** Spring Boot) · Spring Security · Hibernate · MySQL 8 · Jetty ·
Maven · Log4j2 · JUnit.

No Boot means no auto-configuration — wiring is explicit Java config. That's
deliberate: seeing what Boot normally hides is part of the point.

## Running it

Endpoints, once the app is up (see [Getting Started](#getting-started) above):

| URL | Auth | What |
|---|---|---|
| [`/api/health`](http://localhost:8080/api/health) | open | Liveness — `{"status":"UP",...}` |
| [`/swagger-ui/`](http://localhost:8080/swagger-ui/) | open | API documentation |
| [`/api/openapi.json`](http://localhost:8080/api/openapi.json) | open | The OpenAPI 3 document |
| `POST /api/auth/login` | open | `{tenantCode, username, password}` → `{token, user}` |
| `GET /api/auth/me` | **token** | The current session, re-read from the database each call |
| `POST /api/auth/logout` | **token** | 204. A no-op — see [`prompts/c3-auth.md`](./prompts/c3-auth.md) |

Everything not listed as open requires `Authorization: Bearer <token>`, because the
chain is **default-deny** — a new endpoint is protected the moment it exists.

### API documentation

Generated at runtime from Spring's handler mappings by `com.pos.util.OpenApiGenerator`,
so it cannot drift from the controllers — annotate a handler with `@Operation` for
prose and it appears. **This is deliberately not springdoc**, which requires
`spring-boot-autoconfigure`, nor springfox, which is unmaintained and compiles against
`javax.servlet` so it cannot load on Spring 6. See
[`prompts/c1-skeleton.md`](./prompts/c1-skeleton.md) for the reasoning and how to
extend the generator.

### Database setup

**There isn't any.** The JDBC URLs carry `createDatabaseIfNotExist=true`, so the
driver creates `pos_dev` and `pos_test` on first connect, and Hibernate creates the
tables from the entities. A fresh clone needs a running MySQL 8.0.16+ and nothing
else.

Two databases, not one: **tests run against `pos_test` with
`hbm2ddl.auto=create-drop`, which drops every table on each run.** Pointed at
`pos_dev` that would wipe the seed data — and any in-progress manual test — on
every `mvn test`. `PersistenceConfigIT` asserts the test connection's catalog is
`pos_test` precisely so a mis-set URL fails instead of destroying data.

`createDatabaseIfNotExist` is deliberately **local-only**. A deployed environment
overrides the whole URL via `POS_DB_URL` and leaves it off, so a typo'd database
name fails loudly instead of silently creating an empty schema to boot against.

#### Credentials

You need a MySQL user that can create databases and tables. Point the app at it:

| Setting | Property | Environment variable |
|---|---|---|
| URL | `pos.db.url` | `POS_DB_URL` |
| Username | `pos.db.username` | `POS_DB_USERNAME` |
| Password | `pos.db.password` | `POS_DB_PASSWORD` |
| Pool size | `pos.db.pool.maxSize` | `POS_DB_POOL_MAX_SIZE` |

**Passwords are never committed.** `src/main/resources/application.properties` is
tracked and defaults the password to empty; put the real one in
`src/main/resources/application-local.properties`, which is gitignored and loaded
last so it wins.

**You also need a JWT signing key (C3).** `pos.jwt.secret` ships as `CHANGE_ME`
and `JwtTokenService` **refuses to start on it** — a signing key that works out of
the box is one nobody replaces, and anyone with the source could then mint a token
for any tenant. It must be at least 32 bytes (HS256 rejects anything shorter).

So a fresh clone needs one file:

```properties
# src/main/resources/application-local.properties  (gitignored)
pos.db.password=your-password-here
pos.jwt.secret=at-least-32-bytes-of-random-text-goes-here
```

Generate a key with `openssl rand -base64 48`.

| Setting | Property | Environment variable |
|---|---|---|
| JWT signing key | `pos.jwt.secret` | `POS_JWT_SECRET` |
| Token lifetime (minutes) | `pos.jwt.ttlMinutes` | `POS_JWT_TTL_MINUTES` |

Deployed environments set the environment variables instead and ship no local file.

Tokens last **12 hours** and there is no refresh: long enough that a full retail
shift is one login, so a cashier is never logged out mid-transaction. There is also
no revocation — `POST /api/auth/logout` is an acknowledgement, and the token it was
called with stays valid until it expires.

## The database

Full documentation is in [`prompts/database/`](./prompts/database/) — keep it
updated in the same change as the entity, because the schema is generated from
annotations and these docs are the only human-readable record of it.

| Doc | Covers |
|---|---|
| [Conventions](./prompts/database/README.md) | Naming, types, money, the `tenant_id` rule, how the schema is produced |
| [ER diagram](./prompts/database/er-diagram.md) | The whole model on one screen (Mermaid) |
| [Schema](./prompts/database/schema.md) | Table-by-table columns and types |
| [Constraints & indexes](./prompts/database/constraints-and-indexes.md) | Every key and index, and why each exists |

### How the schema is created

Generated by Hibernate from entity annotations. **There is no migration tool** —
the deploy strategy is drop-and-recreate rather than migrate, so there's no data to
preserve across a schema change.

| Environment | `hbm2ddl.auto` |
|---|---|
| Tests | `create-drop` |
| Local dev | `update` |
| Deployed | `create` on first boot, then `validate` |

`schema.sql` is generated and committed so DDL changes show up in diffs.
**Regenerate and commit it whenever an entity changes:**

```sh
mvn test -Dpos.schema.write=true
```

`SchemaSqlTest` fails the build if it drifts, so this isn't optional — a committed
file nobody regenerates describes a schema that stopped existing weeks ago. It
generates offline from a pinned MySQL 8.0.16 dialect, so the file is a property of
the entities rather than of whichever server you happen to be running.

Two costs, both handled:

- `update` never drops or alters an existing constraint, so a changed schema can
  diverge silently. Drop-and-recreate avoids that path entirely.
- `validate` checks tables and columns but **not indexes or unique keys** — so
  `SchemaConstraintsIT` queries `information_schema` for the ones isolation
  depends on.

## Multi-tenancy in one paragraph

> **All of it built**, including `/api/tenants/**`, the platform surface added in
> C8. See [`prompts/c4-tenancy.md`](./prompts/c4-tenancy.md) for the mechanism and
> [`prompts/c8-users-tenants.md`](./prompts/c8-users-tenants.md) for the platform
> surface specifically.

Every tenant-owned row carries `tenant_id`. A Spring Security filter reads the
`tenantId` claim from the JWT into a request-scoped `TenantContext`, and a
Hibernate `@Filter` scopes **every** query from it — so a forgotten `WHERE` clause
can't leak. `tenantId` is never a request parameter, and neither is the acting user
(`cashierId` / `processedBy` come from the token subject). A cross-tenant id
resolves as **404**, never 403, so ids in one tenant reveal nothing about another.
`/api/tenants/**` is the only cross-tenant surface: `SUPER_ADMIN`-only, filter
disabled, quarantined in its own package.

## Seed data (dev only)

`com.pos.service.DevSeeder` loads the frontend's demo tenants, users, catalogue and
opening sales at startup, so the same logins work end to end and the B6 isolation
checklist can be re-run against real persistence. **Tenants, users, 23 products, 40
variants, and 3 completed orders** (2 for `mg-road`, 1 for `airport`) — no seeded
returns. Nothing in the build plan called for any, and adding them would
mean duplicating `ReturnService`'s own math and sequence-minting the way
`seedOrders` already avoids duplicating `OrderService`'s — the manual and automated
return coverage exercises the real endpoints against the seeded orders instead.

The seeded orders are created **through `OrderService` and `PaymentService`**, so
their numbers come from each store's real sequence and their stock decrement is
the genuine atomic one, not a literal that would drift out of step. See
[`prompts/c6-orders.md`](./prompts/c6-orders.md) for how the seeder fakes an actor
(a `SecurityContext`, not just a `TenantContext`) for a startup task that has no
request to read one from.

The variants are created **through `VariantService`**, so their QR codes come from
each store's real sequence rather than from literals that would drift out of step the
first time someone adds a variant through the API. Both stores' runs start at
`POS-QR-{tenantId}-000001`, and `BISLERI-1L` exists in both — legal, because
uniqueness is per tenant, and a fixture the isolation suite depends on.

**`mvn jetty:run` seeds with no flag to pass.** `pos.seed.dev` still defaults to
`false`; the POM turns it on for the `jetty:run` goal only. That is deliberate and
stronger than an environment variable: nothing but local development runs that
goal, so the deployed WAR **cannot** seed however it is configured, and there is no
"remember to set `POS_SEED_DEV=false` in production" step to forget. The seeder
creates admin accounts whose passwords are printed below, which is why it is worth
the care.

The data is **persistent**. Local dev runs `hbm2ddl.auto=update` and the seeder is
idempotent, so rows survive stopping the app and a restart inserts nothing. An
existing tenant is left exactly as it is — re-seeding will not reactivate a store
you suspended in order to test the 403. To start over, drop `pos_dev`; it is
recreated on the next boot.

Idempotence is checked **per row**, not "does this store have anything?" — so a
database seeded by an earlier C-step picks up what a later one adds on the next boot,
rather than needing to be dropped. A variant you delete by hand comes back; one you
edit is left alone.

Production starts empty; the first tenant is created through `POST /api/tenants` (C8).

| Tenant code | Username | Password |
|---|---|---|
| `mg-road` | `admin` / `cashier` | `admin123` / `cashier123` |
| `airport` | `admin` / `cashier` | `admin123` / `cashier123` |
| `platform` | `superadmin` | `super123` |

Passwords are BCrypt-hashed on insert. The repeated usernames across tenants are
deliberate — they prove uniqueness is per-tenant, and they're isolation-test
fixtures. `DevSeederIT` asserts every seeded hash verifies against the password in
this table, so the two cannot drift apart silently.

## Testing

`mvn test` runs unit tests plus integration tests against `pos_test`. The suites
port the frontend's (`pos-frontend`'s `pricing.test.js`, `isolation.test.js`,
`authService.test.js`, `tenantService.test.js`), which already specify correct
behaviour — see [`prompts/README.md`](./prompts/README.md) for how each backend
suite maps to its frontend original.

As of C8 there are **327 tests, 246 of which need `pos_test`**. The other 81 run with
nothing but a JVM — `MockMvc` covers the controllers and error mapping without Jetty,
`SchemaSqlTest` generates DDL offline, `JwtTokenServiceTest` needs neither, and
`PricingTest` is a pure port of `pricing.test.js` with no Spring context at all.
Reach for a database when the test is genuinely *about* persistence. The suffix is
the marker: `*IT` needs `pos_test`, `*Test` needs nothing.

For coverage, `mvn test -Pcoverage` and open `target/site/jacoco/index.html`. It is
behind a profile so that plain `mvn test` — the command you run before every commit —
stays as fast as it is. There is no threshold that can fail the build.

**`SecurityConfigIT` is small and load-bearing.** It boots the root context *alone*,
the way the servlet container does, because every other suite flattens the root and
servlet contexts into one — which is how C3 briefly shipped a fully green suite
against an application that would not start. Run it whenever `SecurityConfig`
changes, and don't make its assertions easier by adding `WebConfig` to it.

`TenantIsolationIT` matters most: every case is an attempt to reach one tenant's
data with another tenant's token. **Add a case for every new tenant-scoped
endpoint**, and mutation-check it periodically by disabling the Hibernate filter
and confirming the suite goes red. The mutation results are recorded in
[`prompts/c4-tenancy.md`](./prompts/c4-tenancy.md) and
[`prompts/c5-catalogue.md`](./prompts/c5-catalogue.md) — including one that disproved
a claim already written in a comment, and one that showed **a green isolation case
does not prove the entity it exercises is filtered**. That is what the exercise is
for.

Two companions to it, neither redundant. `TenantFilterCoverageTest` needs no
database and fails the build if a tenant-owned entity is missing its `@Filter` —
the failure it guards against has no symptom, since an unannotated entity queries
perfectly and simply returns everyone's rows, and C5 showed the isolation suite can
stay green without it. `TenantThreadLocalIT` runs two tenants and a tenant-less admin
over a **two-thread pool**, which is the only way to see whether a request leaves its
tenant on a thread the next one reuses.

**Concurrency suites are where the real bugs were.** `VariantSequenceIT` runs parallel
creates against one store's QR sequence and found two on its first run: a deadlock in
the sequence generator, and a constraint-name mismatch that turned every raced
duplicate into a 500. In both cases the broken code carried a comment explaining why
it was correct. `StockRaceIT` covers C6's own case — two terminals decrementing the
same variant's stock, exactly one winning. C7 needed a different shape rather than
the identical pair: a returnable-quantity check is a `SUM` over several rows, not one
column, so there's no atomic conditional update to race — `ReturnRaceIT` instead
proves `OrderDao.findForUpdate`'s row lock serializes two returns against the same
order rather than letting both see the same stale baseline. C8's
`LastAdminRaceIT` is the same shape as `ReturnRaceIT` — a lock, not an atomic
column update, because "is this the last active admin" is a count over several
rows — and caught a real bug on its first run: the obvious version locked and
counted *after* checking the user was an `ADMIN`, so two admins racing to
deactivate each other could still both succeed. All five run under `mvn test`.

## Bugs

Bugs found by manual testing go in [`BUGS.md`](./BUGS.md) with `Area = Backend`.
Its "Code smells fixed" section is worth reading before writing a second copy of
anything.
