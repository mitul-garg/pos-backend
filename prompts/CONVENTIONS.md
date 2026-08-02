# Conventions

Cross-cutting patterns for `backend/`. Check here before inventing a new pattern
or grepping the source tree for "how do we usually do X".

> **Status: forward-looking.** Nothing is built yet, so most of this states the
> intended pattern rather than describing existing code. As each C-step lands,
> replace the intent with what was actually built and link the class that
> establishes it — an unsubstantiated convention is worse than none.

## Working rhythm — small steps, commit often

**Agents: after each self-contained iteration lands and `mvn test` is green and
the app boots, remind the user to commit before starting the next one.** Don't
wait for a whole C-step if it was a big one. A self-contained iteration is any
change that leaves the app runnable and is describable in one commit subject — a
C-step or a slice of one, a bug fix, a refactor, a docs pass.

Why it's worth the nagging: this project builds in verify-then-proceed steps, and
an uncommitted step means a manual-testing session that goes sideways has no clean
point to fall back to. Small commits also keep the diff reviewable — a whole
C-step in one commit is unreviewable by the time it's written.

**Offer the commit message** (subject + body, `Cn: <what>` for build steps), and
say *why*, plus any deliberate deviation from `../../backend-plan.md`. **Don't run
`git commit` unless asked.**

⚠️ **`backend/` is its own git repo. The parent `pos-application/` directory is
not.** So `../backend-plan.md`, `../requirements.md` and `../BUGS.md` are **not**
versioned by this repo — editing them is not captured by a backend commit, and
`git status` here will never show them. Mention it when a change spans both.

**No cross-cutting rewrites.** If a change starts touching every package, stop and
split it — the frontend's Phase 8 worked because each of its seven steps was
independently reviewable and independently revertible.

Verification before every commit: `mvn test` green, the app boots, and
`TenantIsolationIT` specifically re-run if anything in persistence or security
moved.

## Stack

Spring MVC (**not** Spring Boot) · Spring Security · Hibernate · MySQL 8 · Jetty ·
Maven · Log4j2 · JUnit. Java, no Kotlin. See `../../requirements.md` §1.

Because there's no Boot, there's no auto-configuration: wiring is explicit Java
config. That's deliberate — the point of the project includes learning what Boot
normally hides.

## Architecture — layers

Three layers, and **dependencies only ever point downward**:

```
Controller   HTTP only — map the request, validate the DTO, delegate, map the response.
    ↓        No business logic. No repository access. No transactions.
Service      All business logic. Owns @Transactional. Throws DOMAIN exceptions,
    ↓        never HTTP ones. The only layer that knows the rules.
Repository   Persistence only. Spring Data JPA interfaces; hand-written queries
             where derived method names stop being readable. No business logic.
```

The rules that make this testable rather than decorative:

- **A controller never touches a repository.** If it needs data, a service provides it.
- **A repository never calls a service.** No upward dependencies, ever.
- **Services throw domain exceptions** (`ProductNotFoundException`,
  `InsufficientStockException`), never `ResponseStatusException`. HTTP status is
  the `@ControllerAdvice`'s job — a service that knows about 404s can't be reused
  or unit-tested without a servlet.
- **`@Autowired` for injection.** Beans are Spring-managed singletons; let the
  container wire them rather than constructing collaborators by hand.
- **No `ServiceImpl` unless there are two implementations.** The `Interface` +
  `Impl` pair is tradition, not design — a one-implementation interface is
  indirection with no payoff. Add it the day a second implementation exists.

### Entities never leave the service layer

Controllers accept and return **DTOs**, not JPA entities. Not ceremony — three
concrete reasons here:

1. **`app_user` has `password_hash`.** Serializing the entity leaks it. Every
   "we accidentally returned the password hash" incident starts with returning an
   entity.
2. **Lazy associations blow up in the serializer** — Jackson touching a lazy
   collection after the transaction closed is a `LazyInitializationException`, or
   an N+1 storm if it isn't.
3. **The wire contract is fixed** (`requirements.md` §9) and the schema isn't.
   Coupling them makes a column rename a frontend change.

Map manually at first — explicit and obvious. MapStruct is the enterprise default
and worth adopting if the mapping code becomes tedious, but not before.

### Where tenant scoping lives — and where it must not

Tenant scoping is **infrastructure, below the repository layer**: the security
filter puts `tenantId` into `TenantContext`, an interceptor enables the Hibernate
filter, and every query is scoped without any layer participating.

That's the design goal — **no layer has to remember**. So:

- **Never pass `tenantId` down through layers as a parameter.** A service method
  taking a `tenantId` argument is the same mistake as an endpoint taking one: the
  moment it's a parameter, it can be the wrong value.
- **Only the platform (`Tenant*`) classes read `TenantContext` deliberately**, to
  disable the filter. Everywhere else the tenant is invisible, which is what makes
  it safe.

## Packages — one per layer, plus config

The layout the Increff tutorials teach ("Spring Tutorial Pojo Repository Service
Controller"): **a package per layer**, so the architecture is visible in the
directory tree.

```
com.pos
  ├─ config/          ALL wiring. Nothing else. See below.
  ├─ controller/      @RestController — HTTP in, DTOs out
  ├─ service/         @Service — business logic, @Transactional
  ├─ dao/             @Repository — persistence only
  ├─ pojo/            @Entity — the persisted objects
  ├─ model/           Form (input) / Data (output) DTOs
  └─ util/            shared helpers — pricing, sequences, QR payloads
```

`dao/` and "repository" are the same layer; the course uses both names, the
annotation is `@Repository`.

**`Form` / `Data` naming for DTOs** — `ProductForm` in, `ProductData` out. Clearer
than a single `Dto` suffix, and it makes an accidental entity in a method
signature obvious at a glance.

### The multi-tenancy adaptation this layout needs

A layer-first tree scatters the platform (cross-tenant) classes across
`controller/`, `service/` and `dao/` — so, unlike the frontend where
`tenantService.js` quarantined the whole cross-tenant surface in one file, **the
directory tree no longer shows you where the boundary is crossed**.

Replace it with a marker that's greppable:

- **Every filter-disabled operation carries `@PlatformOperation`** (or whatever the
  C8 marker ends up being). `grep -r "@PlatformOperation"` must return the
  *complete* cross-tenant surface — that's the audit.
- **Platform classes are named `Tenant*`** (`TenantController`, `TenantService`,
  `TenantDao`) so they cluster alphabetically inside each layer package.
- **A test asserts the marker is the only way the filter gets disabled**, so the
  grep can be trusted rather than merely believed.

Without this, "which code can read another tenant's rows?" has no cheap answer —
and that question needs a cheap answer every time someone touches the service layer.

### `com.pos.config` — configuration is a first-class area

**There is no Spring Boot, so there is no auto-configuration.** Everything Boot
normally does invisibly is written here by hand — which is precisely the part of
Spring worth learning, so it gets its own namespace rather than being scattered.

| Class | Responsibility |
|---|---|
| `WebAppInitializer` | Replaces `web.xml` — bootstraps the `DispatcherServlet` |
| `WebConfig` | MVC: view resolution, message converters, Jackson (including **ids-as-strings**), CORS for the frontend dev server |
| `PersistenceConfig` | `DataSource` + HikariCP, `EntityManagerFactory`, `JpaTransactionManager`, Hibernate properties (`hbm2ddl`, dialect) |
| `SecurityConfig` | Filter chain, the JWT filter, `PasswordEncoder` (BCrypt), URL-level role rules |
| `TenantConfig` | `TenantContext` and the interceptor that enables the Hibernate tenant filter |
| `SeedConfig` | The dev seeder, gated on `pos.seed.dev` |
| `AppProperties` | Typed access to externalized settings |

Rules for this package:

- **Wiring only, no business logic.** If a config class starts making decisions
  about products or orders, that logic belongs in a service.
- **One class per concern.** A single `AppConfig` doing persistence *and* security
  *and* MVC is the thing this layout exists to prevent.
- **Nothing outside `config/` reads raw configuration.** Inject `AppProperties`,
  don't sprinkle `@Value` across services.
- **Environment-specific values are externalized** — DB URL, credentials, JWT
  secret, pool size, `hbm2ddl.auto` — via properties with env-var overrides, so
  the same artifact runs locally and deployed. `maximumPoolSize` in particular has
  to be set explicitly for the free-tier connection cap.
- **Never commit a secret.** The JWT signing key comes from an environment
  variable with no usable default; a placeholder that happens to work in
  production is how signing keys leak.

## Tenant scoping (the rule everything else serves)

- **`tenantId` comes from the session, never the caller.** No endpoint takes a
  `tenantId` parameter. The Spring Security filter reads it from the JWT into a
  request-scoped `TenantContext`; an interceptor enables the Hibernate
  `tenantFilter` from that.
- **Writes stamp `tenant_id` from the context, never from the request body.**
- **The acting user is session-derived too** — `cashierId` / `processedBy` come
  from the JWT subject, never a body field. (They remain legitimate *filter*
  arguments on list endpoints; that's a query, not an identity claim.)
- **Isolation is fail-closed:** a cross-tenant id resolves as **404**, never 403.
  A 403 would confirm the id exists in another tenant.
- **Clear `TenantContext` in a `finally`.** Jetty pools threads; a leaked context
  means the next request on that thread inherits another tenant. This is the
  highest-severity bug available in this design and single-threaded tests will
  never show it.
- **Cross-tenant reads live only in the platform package** (`/api/tenants/**`,
  `SUPER_ADMIN`-gated, filter disabled). Nowhere else. The frontend quarantined
  the equivalent in one module for the same reason — if the reach is visible in
  the code layout, it can be audited.

## Persistence

- **Money is `BigDecimal` + `DECIMAL(12,2)`. Never `double`.** All arithmetic goes
  through the pricing service, ported from `frontend/src/domain/pricing.js`;
  `RoundingMode.HALF_UP`.
- **The schema is generated from entities** — declare every unique constraint and
  index explicitly in `@Table`. Nothing else will create them, and `validate`
  won't notice if they're missing. See [database/](./database/).
- **Snapshot columns on order/return lines are deliberate**, not denormalisation
  to clean up: a later price change must not rewrite history.
- **Uniqueness is enforced by the database, not by the service check.** Keep the
  pre-check for a clean field-level 400, *and* catch
  `DataIntegrityViolationException` — a read-then-write check is racy, and the
  unique index is the only thing that makes it atomic.
- **Per-tenant sequences** (`order_number`, `return_number`, QR) come from
  `tenant_sequence` under `SELECT … FOR UPDATE` in the same transaction as the
  insert. `AUTO_INCREMENT` can't produce per-tenant runs.

## Transactions

- Service layer owns `@Transactional`; controllers never open transactions.
- Anything that mints a sequence value **and** inserts the row using it must be
  one transaction, or two terminals can collide.
- Stock changes use an atomic conditional update
  (`… WHERE stock_quantity >= ?`) and treat zero affected rows as the rejection —
  not a read, a check, and a write.

## API and errors

- Paths and payloads follow `../../requirements.md` §9 exactly. The frontend's
  service signatures are the contract; **what's absent from them is part of it.**
- One `@ControllerAdvice` maps exceptions to status codes:
  **401** bad credentials / unknown or blank tenant code (one generic message) ·
  **403** deactivated user, suspended tenant, wrong role ·
  **404** cross-tenant or missing ·
  **400** validation, field → message.
- **The 401's uniformity is a security property, not a UX choice** — naming the
  tenant would confirm which tenants exist. The specific 403s are safe only
  because they're unreachable until the password is proved, so **status checks run
  after the password check**, never before.
- **Ids serialize as JSON strings** (`BIGINT` in the database). The frontend's
  mock ids are strings and `useParams` yields strings; string-on-the-wire keeps
  every existing `===` comparison working.

## Testing

- JUnit against a **local MySQL** (`pos_test`), `create-drop` per run,
  `@Transactional` rollback per test. Setup is documented in `../README.md`.
- **Port the frontend's suites** rather than reinventing them — see
  `../../backend-plan.md` §8 for the mapping. `pricing.test.js` ports
  case-for-case; `isolation.test.js` is the specification for `TenantIsolationIT`.
- **Add a case to `TenantIsolationIT` for every new tenant-scoped endpoint.** The
  browser can't show you a leak you didn't think to look for.
- **Mutation-check the isolation suite**: disable the Hibernate filter and confirm
  it goes red. A green isolation suite that stays green with isolation off is
  worse than none.
- Concurrency deserves real tests here — thread-pool context leakage, parallel
  order numbering, two terminals racing the last unit in stock. The frontend mock
  couldn't exercise any of it.

## Naming

- `order` and `return` are SQL reserved words — tables are `pos_order` and
  `sales_return`.
- Roles are an enum (`SUPER_ADMIN`, `ADMIN`, `CASHIER`); tenant-assignable roles
  are a separate constant, mirroring the frontend's `TENANT_ROLES`. **A tenant
  admin must never be able to mint a `SUPER_ADMIN`** — that exact hole appeared in
  the frontend when the role was added to the shared list (BUGS.md, Phase 8/B4).
