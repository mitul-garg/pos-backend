# PoS — backend

Spring MVC + Hibernate + MySQL API for the point-of-sale app, served by Jetty.
Implements the contract the frontend already proves out — see
[`../requirements.md`](../requirements.md) §9 for the endpoints and §13 for the
multi-tenancy rules.

> **Status: C3 done** — the app boots on Jetty, serves JSON, persists to MySQL
> through Hibernate, and authenticates with JWTs. All nine tables exist and
> `schema.sql` is committed. **A local MySQL is required to run `mvn test`**, and
> **a JWT signing key is required to run the app at all** (see Credentials below).
> **No tenant filter yet** (C4): a caller's tenant is known from their token, but
> nothing uses it to scope a query. Build sequence is
> [`../backend-plan.md`](../backend-plan.md) (steps C1–C9).

Before changing anything here, read [`prompts/README.md`](./prompts/README.md) —
it indexes the conventions and the database documentation.

## Stack

Spring MVC (**not** Spring Boot) · Spring Security · Hibernate · MySQL 8 · Jetty ·
Maven · Log4j2 · JUnit.

No Boot means no auto-configuration — wiring is explicit Java config. That's
deliberate: seeing what Boot normally hides is part of the point.

Requires **JDK 17+** (the build targets 17) and Maven.

## Running it

```sh
mvn clean install
mvn jetty:run          # http://localhost:8080
mvn test
```

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

> **As of C2 only the first sentence is built.** The tables carry `tenant_id` and
> every uniqueness rule is per-tenant, but there is no `TenantContext`, no filter
> and no security chain yet — **any query written today is unscoped**. C3 brings
> auth, C4 the spine. The rest of this paragraph is the target, not the state.

Every tenant-owned row carries `tenant_id`. A Spring Security filter reads the
`tenantId` claim from the JWT into a request-scoped `TenantContext`, and a
Hibernate `@Filter` scopes **every** query from it — so a forgotten `WHERE` clause
can't leak. `tenantId` is never a request parameter, and neither is the acting user
(`cashierId` / `processedBy` come from the token subject). A cross-tenant id
resolves as **404**, never 403, so ids in one tenant reveal nothing about another.
`/api/tenants/**` is the only cross-tenant surface: `SUPER_ADMIN`-only, filter
disabled, quarantined in its own package.

## Seed data (dev only)

`com.pos.service.DevSeeder` loads the frontend's demo tenants and users at startup,
so the same logins work end to end and the B6 isolation checklist can be re-run
against real persistence. **Users and tenants only** — products, variants and orders
arrive with the steps that own them (C5–C7).

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
port the frontend's, which already specify correct behaviour — see
[`../backend-plan.md`](../backend-plan.md) §8 for the mapping.

As of C4 there are **128 tests, 52 of which need `pos_test`**. The rest run with
nothing but a JVM — `MockMvc` covers the controllers and error mapping without Jetty,
`SchemaSqlTest` generates DDL offline, and `JwtTokenServiceTest` needs neither.
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
and confirming the suite goes red. C4's mutation results are recorded in
[`prompts/c4-tenancy.md`](./prompts/c4-tenancy.md), including one that disproved a
claim already written in a comment — which is what the exercise is for.

Two companions to it, neither redundant. `TenantFilterCoverageTest` needs no
database and fails the build if a tenant-owned entity is missing its `@Filter` —
the failure it guards against has no symptom, since an unannotated entity queries
perfectly and simply returns everyone's rows. `TenantThreadLocalIT` runs two
tenants and a tenant-less admin over a **two-thread pool**, which is the only way
to see whether a request leaves its tenant on a thread the next one reuses.

## Bugs

Bugs found by manual testing go in [`../BUGS.md`](../BUGS.md) with `Area = Backend`.
Its "Code smells fixed" section is worth reading before writing a second copy of
anything.
