# Conventions

Cross-cutting patterns for `backend/`. Check here before inventing a new pattern
or grepping the source tree for "how do we usually do X".

> **Status: substantiated (C1–C7 landing).** The layout, error mapping, DTO naming,
> config-package, **persistence**, **security**, **tenant scoping**, **writes,
> validation and per-tenant sequences** (C5), **atomic stock mutation and
> server-side price recomputation** (C6) and — as of C7 — **a pessimistic row lock
> as the referee for a check an atomic statement can't express** all describe real
> classes. See [c1-skeleton.md](./c1-skeleton.md),
> [c2-persistence.md](./c2-persistence.md), [c3-auth.md](./c3-auth.md),
> [c4-tenancy.md](./c4-tenancy.md), [c5-catalogue.md](./c5-catalogue.md),
> [c6-orders.md](./c6-orders.md) and [c7-returns.md](./c7-returns.md).
> **Scoping is now enforced rather than intended**: a query written against a
> tenant-owned entity is scoped whether or not its author thought about tenancy.
> As each C-step lands, replace intent with what was actually built and link the
> class that establishes it; an unsubstantiated convention is worse than none.

## Working rhythm

Four rules, in the order they bite. **They are the process the project actually
runs on** — C1 and C3 each broke one and paid for it, and both costs are recorded
below so the rules read as consequences rather than preferences.

### 1. Commit forward, never backward

**Stop and commit at each boundary as you reach it.** A boundary is any change
that leaves the app runnable and is describable in one commit subject — a slice of
a C-step, a bug fix, a refactor, a docs pass.

> **Never reconstruct history afterwards.** If you find yourself stashing, moving
> files aside, re-applying them one group at a time and re-editing as you go, the
> mistake already happened — it happened when you kept writing past a boundary.
> The replay is not a fix; it is an error-prone dance over files that were already
> finished, and it can silently reorder or drop work.

Two occasions taught this. **C1 was built and then offered as a single 32-file
commit** and had to be retroactively split into six. **C3 repeated it**: six
commits' worth of code was written before the first commit, then reconstructed by
stashing everything and replaying it in groups — which meant editing the same
files two and three times to produce history that could have just been written in
order.

A size check, since "commit often" gave nothing to check against:

> **Once uncommitted work passes roughly 10 files or 500 lines, stop and find a
> commit boundary.** Past that, a diff stops being reviewable in one sitting.

A smell, not a gate — a genuinely atomic change that runs long is fine, and so is
committing at three files. The point is to notice *at the time*.

If you are ever splitting after the fact anyway, **build each commit rather than
just splitting the `git add`.** Doing that for C1 surfaced a real ordering bug:
`HealthController` carried an annotation whose dependency arrived two commits
later, so the "health endpoint" commit didn't compile on its own. A split that is
only a staging exercise produces history that has never been verified.

**Green every commit is the goal, not a gate.** Prefer a boundary where `mvn test`
passes. If a commit has to land red — a controller whose collaborator arrives in
the next one — say so in the message, say which suites and why, and fix it in the
commit that completes the slice. A red commit that is *explained* is better than a
900-line green one.

### 2. Manual testing comes before automated tests

The order for a new endpoint or feature:

1. **Write the code.**
2. **Propose the manual test steps** — every endpoint added, the happy path, and
   the failure cases worth exercising by hand. Concrete `curl`s or UI steps, not
   "try logging in".
3. **Wait for the user to confirm it behaves.**
4. **Then write the automated tests.**

Not ceremony. A test written before anyone has seen the thing work encodes what
the author *assumed* the behaviour is, and then passes forever — C3 shipped a
security chain whose 28-case suite was fully green against an application that
**would not start**, because the suite's context was not the container's. Ten
minutes of `curl` found it immediately. Automated tests are for keeping behaviour
correct; they are poor at establishing that it ever was.

### 3. Edit files so the change is reviewable

**Agents: make edits through the editing tool, one change at a time.** They render
as diffs in the user's review editor, which is where the change actually gets
looked at.

Driving edits through a `python`/`sed` heredoc that string-replaces several places
at once is faster to write and **invisible to review** — the user sees a script and
a success message rather than what changed. On a project that reviews this closely,
that trade is backwards.

### 4. Offer the commit, don't run it

**Offer the message** (subject + body, `Cn: <what>` for build steps) and say *why*,
plus any deliberate deviation from `../../backend-plan.md`. **Don't run
`git commit` unless asked** — but do offer, every time a boundary is reached,
rather than accumulating.

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
Repository   Persistence only. Hand-written DAOs over an injected EntityManager,
             with explicit JPQL. No business logic.
```

The rules that make this testable rather than decorative:

- **A controller never touches a repository.** If it needs data, a service provides it.
- **A repository never calls a service.** No upward dependencies, ever.
- **Services throw domain exceptions** (`ProductNotFoundException`,
  `InsufficientStockException`), never `ResponseStatusException`. HTTP status is
  the `@ControllerAdvice`'s job — a service that knows about 404s can't be reused
  or unit-tested without a servlet.
- **`@Autowired` for injection.** Beans are Spring-managed singletons; let the
  container wire them rather than constructing collaborators by hand. **Prefer a
  constructor over fields for a service a controller depends on** — `WebConfig`
  component-scans `com.pos.controller`, so every controller has to be satisfiable
  by a test that boots the servlet context alone, and field injection is applied
  even to a hand-built `@Bean`, which makes such a service impossible to stub
  without dragging in a database (C3, `AuthService` + `StubServiceConfig`).
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

Tenant scoping is **infrastructure, below the repository layer** (C4): the security
filter puts `tenantId` into `com.pos.util.TenantContext`, and Hibernate's
`tenantFilter` — auto-enabled, its parameter resolved per query — appends
`tenant_id = ?` to every statement. No layer participates.

That's not a goal any more, it's the mechanism — **no layer has to remember**. So:

- **Never pass `tenantId` down through layers as a parameter.** A service method
  taking a `tenantId` argument is the same mistake as an endpoint taking one: the
  moment it's a parameter, it can be the wrong value. `ProductDao` is the worked
  example — not one of its methods mentions a tenant.
- **`TenantContext` is read deliberately in exactly two places**, and both are
  documented: `requireTenant()` as a guard (never as a filter), and `DevSeeder`,
  which runs at startup with no request. The platform (`Tenant*`) classes will
  join them in C8, to *disable* the filter.
- **Every service method that reads tenant-owned data is `@Transactional`**,
  read-only ones included. The filter lives on a Hibernate session, and outside a
  transaction there is no session to enable it on — so a read that escapes its
  transaction is an **unscoped read**. Nothing enforces this automatically; it is
  the sharpest edge in the design.
- **Writes are not scoped by the filter.** It appends to `WHERE` clauses and an
  `INSERT` has none, so a write is scoped by whoever builds the entity. Stamp
  `tenant_id` from the context, never from the request body.
- **Bulk HQL `UPDATE`/`DELETE` is not filtered either.** Nothing issues one yet;
  C6/C7 must scope one by hand if they do.

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
  ├─ exception/       domain failures + the @ControllerAdvice that maps them (C1)
  └─ util/            shared helpers — pricing, sequences, QR payloads
```

`exception/` was added in C1. The rule below — services throw domain exceptions,
never HTTP ones — needed somewhere for them to live, and `model/` is the wire
contract, not the failure vocabulary.

`dao/` and "repository" are the same layer; the course uses both names, the
annotation is `@Repository`.

**Not Spring Data JPA** (decided in C3, replacing this file's earlier claim).
`dao/` + `@Repository` was already the shape of the tree; C6 and C7 need
`SELECT … FOR UPDATE` for the per-tenant sequences and an atomic conditional
update for stock, neither of which a derived method name expresses, so those
queries would be hand-written regardless; and another auto-configuration-shaped
layer cuts against the premise that this project exists to show what Boot hides.

**`Form` / `Data` naming for DTOs** — `ProductForm` in, `ProductData` out. Clearer
than a single `Dto` suffix, and it makes an accidental entity in a method
signature obvious at a glance.

**One `Form` serves both `POST` and `PUT`, and `PUT` is a merge patch** (C5). Every
field is nullable, wrapper types included, because absent has to be distinguishable
from "set it to null" — the frontend reactivates a row with `{"isActive": true}` and
nothing else. Two consequences:

- **A `Form` that serves a `PUT` carries no bean-validation annotations.**
  `@NotBlank` on a name would reject that reactivation, which is a legal request.
  The service validates the **merged row** instead, which is also the rule it should
  have been enforcing: not "this request names a product" but "the product that
  results is valid".
- **What a `Form` leaves out is part of the contract.** No `id`, no `tenantId`, no
  `createdAt`, no `qrCode` — the mapper drops unknown properties, so a client
  supplying one is a no-op rather than an error. `ProductWriteIT` and `VariantIT`
  each assert the outcome rather than the mechanism, because "the DTO happens not to
  have a setter" evaporates the day someone adds one for convenience.

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
| `WebAppInitializer` | Replaces `web.xml` — bootstraps the `DispatcherServlet`. **Built (C1)** |
| `RootConfig` | The root context: services, DAOs, and later persistence + security. **Built (C1)** |
| `WebConfig` | MVC: message converters, Jackson, CORS for the frontend dev server. **Built (C1)**. Ids-as-strings landed in C2 as `com.pos.model.JsonId`, per field rather than as a mapper rule |
| `OpenApiConfig` | Swagger UI + the generated spec. Servlet context, because it reads `RequestMappingHandlerMapping`. **Built (C1)** |
| `PersistenceConfig` | `DataSource` + HikariCP, `EntityManagerFactory`, `JpaTransactionManager`, Hibernate properties. **Built (C2)** — and note it deliberately does *not* set `hibernate.dialect`; see [c2-persistence.md](./c2-persistence.md) |
| `AppProperties` | Typed access to externalized settings. **Built (C2)** |
| `SecurityConfig` | Filter chain, the JWT filter, `PasswordEncoder` (BCrypt), URL-level rules, **and CORS**. **Built (C3)** — see [c3-auth.md](./c3-auth.md) for why CORS had to move here from `WebConfig`, and why its URL rules use explicit `AntPathRequestMatcher`s |
| `SecurityWebApplicationInitializer` | Registers the chain with the container — the `<filter>` element a `web.xml` would hold. **Built (C3)** |
| `JwtAuthenticationFilter` · `ApiErrorResponder` | The bearer-token filter, and the JSON error writer for failures a `@ControllerAdvice` can never see. **Built (C3)** |
| ~~`TenantConfig`~~ | **Never needed (C4).** Hibernate 6.5+ auto-enables a filter and resolves its parameter from a `Supplier` it instantiates itself, so there was nothing to wire. `com.pos.util.TenantContext` holds the ThreadLocal *and* that supplier; the `@FilterDef` lives on `com.pos.pojo`'s `package-info` — see [c4-tenancy.md](./c4-tenancy.md) |
| ~~`SeedConfig`~~ | Superseded: the dev seeder landed in C3 as `com.pos.service.DevSeeder`, self-gated and idempotent, since it is transactional work rather than wiring |

#### Which context a config class belongs to (C1)

`WebAppInitializer` creates **two** contexts, and putting a bean in the wrong one
fails in ways that look like the bean is broken:

- **Root** (`RootConfig`, `PersistenceConfig`, `SecurityConfig`, and later
  `TenantConfig`) — services, DAOs, transactions, security.
- **Servlet** (`WebConfig`, `OpenApiConfig`) — controllers, converters, handler
  mappings.

The servlet context can see the root; **the root cannot see the servlet context.**
Two consequences worth knowing before C3:

- **Servlet filters are root-context beans.** Spring Security's chain is a filter,
  so `SecurityConfig` goes in the root. A filter bean declared in the servlet
  context is invisible to the container.
- **Anything reading `RequestMappingHandlerMapping` must be in the servlet
  context** — it's created by `@EnableWebMvc`. That's the whole reason
  `OpenApiConfig` sits there rather than with the other wiring.

`RootConfig` deliberately does **not** scan `com.pos.controller`. If both contexts
scanned it, there would be two sets of controller beans and `@ControllerAdvice`
would appear to stop working on one of them.

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
  `tenantId` parameter. **Built in C3:** the JWT carries the tenant, and
  `JwtAuthenticationFilter` resolves it — against the *database row*, not the
  claim — onto the `SecurityContext`. **Completed in C4:** the same filter copies
  it into `TenantContext`, and Hibernate's `tenantFilter` scopes every query from
  there.
- **A tenant-owned entity carries `@Filter`, and `TenantFilterCoverageTest`
  enforces it.** Add the annotation *and* bump that test's expected count in the
  same change. The failure it guards against has no symptom: an unannotated entity
  compiles, persists and queries perfectly — it just returns everyone's rows.
- **An absent tenant resolves to `NO_TENANT` (`-1`), not to "unfiltered".** A
  forgotten guard returns nothing rather than everything. Fail-closed is a
  property of the sentinel, not of anyone remembering.
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
- **Two DAOs are permanently outside scoping, and only two.** `TenantDao`, because
  `tenant` carries no `tenant_id` — it *is* the discriminator; and `AppUserDao`,
  because authentication establishes which tenant a caller is in and so cannot be
  scoped by its own answer. `AppUser` is correspondingly the one entity with a
  `tenant_id` and no `@Filter`; filtered, every login would be evaluated against
  `NO_TENANT` and fail. Every DAO from C5 onwards takes the tenant from the filter
  and names it in no signature. The exception has a reason; it is not a
  precedent (C3), and C8's user management pays for it by re-establishing that
  scoping by hand.
- **Isolation is fail-closed *by id* too.** The `@FilterDef` sets
  `applyToLoadByKey`, without which `em.find()` is unfiltered and a by-id lookup
  returns another tenant's row while every other query is scoped. Do not remove
  it, and do not paper over it by writing by-id lookups as JPQL — that hides the
  regression from the test that catches it.
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
- **Enum fields carry `@JdbcTypeCode(SqlTypes.VARCHAR)`.** Without it Hibernate 6
  emits MySQL's native `ENUM` type, which `hbm2ddl.auto=update` can never extend.
  There's no global setting for it; `SchemaSqlTest` fails the build if one is
  forgotten (C2).
- **Associations are `LAZY`, including `tenant`.** Nothing navigates
  `product.getTenant()` — scoping happens on the column — and `EAGER` would make
  every list query an N+1. Safe only because DTOs are mapped in-transaction.
- **Regenerate `schema.sql` in the same change as the entity**
  (`mvn test -Dpos.schema.write=true`). The build fails on drift, so it isn't
  optional (C2).
- **Snapshot columns on order/return lines are deliberate**, not denormalisation
  to clean up: a later price change must not rewrite history.
- **Uniqueness is enforced by the database, not by the service check.** Keep the
  pre-check for a clean field-level 400, *and* catch
  `DataIntegrityViolationException` — a read-then-write check is racy, and the
  unique index is the only thing that makes it atomic.
- **Per-tenant sequences** (`order_number`, `return_number`, QR) come from
  `TenantSequenceDao.next()`, **in the same transaction as the insert they
  number** — the value is reserved by locks held until that transaction commits.
  `AUTO_INCREMENT` can't produce per-tenant runs. **Lock the tenant row first**,
  which that method does for you: it is not belt-and-braces, it is the fix for a
  real deadlock, and anything else that ever locks a tenant row must take it in the
  same order (C5).
- **A unique constraint a request can trip needs a row in
  `ApiExceptionHandler.CONSTRAINT_FIELDS`,** added in the same change as the
  constraint. Without one its violation answers **500**, deliberately — an unmapped
  constraint has no field to blame and is more likely a bug here than in the
  request. Note MySQL 8 reports the name **table-qualified**
  (`variant.uk_variant_tenant_sku`); the handler strips that, and the bare form is
  not what production sends (C5).

## Transactions

- Service layer owns `@Transactional`; controllers never open transactions.
- Anything that mints a sequence value **and** inserts the row using it must be
  one transaction, or two terminals can collide.
- Stock changes use an atomic conditional update
  (`… WHERE stock_quantity >= ?`) and treat zero affected rows as the rejection —
  not a read, a check, and a write.
- **When the check is a `SUM`/aggregate rather than one row's column, an atomic
  conditional update has nothing to bind to — lock the parent row instead**
  (`OrderDao.findForUpdate`, C7). Read the aggregate only after taking the lock, so
  a second racing transaction waits and then reads the *updated* value rather than
  the same stale one the first transaction started from. Scope the lock to the
  narrowest row that makes two conflicting callers actually conflict — a return
  locks the one order it's returning against, not (like `TenantSequenceDao`) the
  whole tenant, because only two returns against the *same* order can race.

## API and errors

- Paths and payloads follow `../../requirements.md` §9 exactly. The frontend's
  service signatures are the contract; **what's absent from them is part of it.**
- One `@ControllerAdvice` maps exceptions to status codes — `ApiExceptionHandler`
  (C1), in `com.pos.exception` beside the failures it translates:
  **401** bad credentials / unknown or blank tenant code (one generic message) ·
  **403** deactivated user, suspended tenant, wrong role ·
  **404** cross-tenant or missing ·
  **400** validation, field → message.
- **Every error response is `ApiError`** — `{message, fields}`, with `fields`
  omitted unless the failure is field-level. One shape, so the frontend's HTTP
  layer needs exactly one error path. Don't invent a second envelope.
- **There are two writers of that envelope, and there have to be.** A
  `@ControllerAdvice` only sees exceptions the `DispatcherServlet` dispatched, and
  the security chain is a servlet *filter* that runs in front of it — so
  `ApiErrorResponder` writes the envelope for authentication failures (C3). The
  shape cannot drift, because both serialize the same `ApiError` class, and
  `AuthControllerIT` asserts a filter-produced 401 and an advice-produced 401 are
  byte-identical. **Don't add a third.**
- **`InvalidCredentialsException` takes no message argument, deliberately.** The
  401 body is a constant. A constructor that accepted a message would eventually be
  called with a helpful one, and "unknown tenant" vs "wrong password" is an
  enumeration oracle.
- **Unexpected exceptions return `"Something went wrong"`** and log at ERROR with
  the stack trace. Detail is for the operator; a leaked message carries SQL
  fragments, schema names and library versions.
- **The 401's uniformity is a security property, not a UX choice** — naming the
  tenant would confirm which tenants exist. The specific 403s are safe only
  because they're unreachable until the password is proved, so **status checks run
  after the password check**, never before.
- **Ids serialize as JSON strings** (`BIGINT` in the database). The frontend's
  mock ids are strings and `useParams` yields strings; string-on-the-wire keeps
  every existing `===` comparison working.
- **A create answers 201**, and `@ResponseStatus` is read by `OpenApiGenerator`, so
  the document says what the handler actually returns (C5). Before that it promised
  200 everywhere — already wrong for `logout`'s 204 — and a document that lies about
  a status is worse than a missing one, because a generated client treats the real
  answer as an error.
- **Role rules are URL rules in `SecurityConfig.adminMatchers()`, not
  `@PreAuthorize` on handlers** (C5). One greppable file answers "what can a CASHIER
  not reach?"; annotations scattered across controllers do not. They are
  **method-scoped** — `GET /api/products` is open to both roles and `POST` is not —
  so the rules cannot be collapsed into one path pattern.

## Testing

- **Not every test needs a database.** 66 of the current 196 run with none —
  `MockMvc` over the real `WebConfig` exercises controllers, converters and the
  advice without Jetty or MySQL. Reach for a database when the test is *about*
  persistence. The `*Test` / `*IT` suffix is the marker: `IT` means it needs
  `pos_test`, nothing more. (This file previously claimed 52 of 128 needed a
  database; the real split was 66, counted rather than estimated in C5.)
- **A servlet-context-only test must stub the service layer.** `WebConfig`
  component-scans `com.pos.controller`, so every controller is built whether or
  not a suite is about it. `StubServiceConfig` (test sources) supplies inert
  stubs; **add a `@Bean` to it in the same change that adds a controller**, or
  three unrelated suites go red.
- **A test context is not the container.** `AuthControllerIT` flattens the root
  and servlet contexts into one, which is convenient and, for one class of
  failure, actively misleading — anything a root-context config accidentally takes
  from the servlet context resolves there and is missing in production. That is
  how C3 shipped a fully green suite against an application that would not start.
  `SecurityConfigIT` boots the root context **alone** for exactly this reason;
  **run it whenever `SecurityConfig` changes**, and don't "fix" it by adding
  `WebConfig`.
- JUnit against a **local MySQL** (`pos_test`), `create-drop` per run,
  `@Transactional` rollback per test. Setup is documented in `../README.md`.
- **A test that deliberately triggers an ERROR log silences that logger** in
  `src/test/resources/log4j2-test.xml`. A green run that prints stack traces trains
  people to ignore real ones.
- **Port the frontend's suites** rather than reinventing them — see
  `../../backend-plan.md` §8 for the mapping. `pricing.test.js` ports
  case-for-case; `isolation.test.js` is the specification for `TenantIsolationIT`.
- **Add a case to `TenantIsolationIT` for every new tenant-scoped endpoint.** The
  browser can't show you a leak you didn't think to look for.
- **Mutation-check the isolation suite**: disable the Hibernate filter and confirm
  it goes red. A green isolation suite that stays green with isolation off is
  worse than none. Done for C4, and it paid twice — see the table in
  [c4-tenancy.md](./c4-tenancy.md). One mutation showed the by-id cases cover
  something no list case does; another **disproved a claim already written in a
  comment**, which is the outcome to hope for.
- **A green isolation case does not prove the entity it exercises is filtered**
  (C5). A query that joins a filtered parent — `JOIN FETCH v.product` — is scoped
  through the join whether or not the child carries `@Filter`. Dropping it from
  `Variant` left the scan, the search and the by-product list green; what reddened
  was the two paths joining no product, `em.find` and a `count` over the child
  alone. **`TenantFilterCoverageTest` is what actually holds that line**, and an
  aggregate over one entity is the shape with nothing else behind it.
- **A test fixture must be shaped like production or it agrees with whatever the
  code does** (C5). The constraint-mapping test used a bare constraint name, passed,
  and proved nothing — MySQL sends a table-qualified one, and every raced duplicate
  was answering 500. Two fixtures in `TenantIsolationIT` carry the same warning:
  writing a QR code by hand means moving `tenant_sequence` to match.
- **Concurrency tests find what review cannot.** `VariantSequenceIT` caught both of
  C5's bugs on its first run, and in each case the broken code carried a comment
  explaining why it was correct. Write one for anything that mints a number, decrements
  stock, or checks uniqueness.
- **A `@Transactional` IT can assert nothing without `em.flush()` / `em.clear()`
  after its fixtures.** Rows persisted in the test's own transaction are managed,
  so `em.find()` answers from the persistence context without running SQL — and a
  filter only scopes a query that runs. Three `TenantIsolationIT` cases passed
  while asserting nothing until this was added.
- **Coverage** is `mvn test -Pcoverage`, then `target/site/jacoco/index.html`.
  Behind a profile so plain `mvn test` stays fast; no threshold, deliberately.
  Keep `jacoco.version` ahead of the JDK Maven runs on, or the agent cannot read
  the class files and silently drops them from the report on a green build.
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
