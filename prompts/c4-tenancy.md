# C4 — The spine

**Status: done.** A request-scoped `TenantContext`, a Hibernate `@Filter` on every
tenant-owned entity, the read half of `/api/products` as the first scoped resource, and
`TenantIsolationIT`. `mvn test` runs **128 tests, 52 of which need `pos_test`**.

Corresponds to `backend-plan.md` C4, and to `requirements.md` §13.3 (scoping rules) and §9
(the contract). The frontend's `services/isolation.test.js` is the specification.

> **This is the step everything after it repeats against.** C5–C8 add entities and
> endpoints; none of them add scoping, because scoping is no longer a thing an endpoint
> does. The one thing they must not forget is `@Filter` on a new entity, and
> `TenantFilterCoverageTest` is what remembers it for them.

## Key classes

- `com.pos.util.TenantContext` — the ThreadLocal, the `NO_TENANT` sentinel, the
  `requireTenant()` guard, and the nested `Resolver` Hibernate calls. One class, because it
  is one idea: *the tenant of the request in flight, and how Hibernate gets it*.
- `com.pos.pojo.package-info` — the `@FilterDef`. Three attributes carry the whole design;
  see below.
- `com.pos.config.JwtAuthenticationFilter` — sets the tenant after resolving the session,
  and clears it in a `finally`.
- `com.pos.dao.ProductDao` / `service.ProductService` / `controller.ProductController` —
  the first scoped resource. Read the DAO for what is *absent*: no method takes a tenant.
- `com.pos.model.ProductData` / `PageData` — the wire shapes, `{items, total, page, pageSize}`.

## Decisions & gotchas

### There is no `TenantConfig`, and that is the interesting part

`CONVENTIONS.md` predicted a config class holding "`TenantContext` and the interceptor that
enables the Hibernate tenant filter". **No such class exists.** Hibernate 6.5+ can
auto-enable a filter and resolve its parameter itself, so there is nothing to wire:

```java
@FilterDef(name = "tenantFilter", autoEnabled = true, applyToLoadByKey = true,
           parameters = @ParamDef(name = "tenantId", type = Long.class,
                                  resolver = TenantContext.Resolver.class))
```

Each attribute is load-bearing:

- **`autoEnabled`** — the filter is on for every session. The alternative is a
  `session.enableFilter(...)` somewhere per request, which is one more thing to forget, and
  "the query that forgot" is the failure this whole mechanism exists to prevent. Nothing
  switches it on, so nothing can fail to.
- **`applyToLoadByKey`** — **the one that would have been missed.** Hibernate filters do
  not apply to `em.find()` by default. Without it, `ProductDao.find(id)` returns another
  tenant's row while every other query is scoped — and by-id is exactly C4's "done when".
- **`resolver`** — a `Supplier<Long>` Hibernate calls **per query**, not once per session.
  That timing is what lets one pooled thread and one Hibernate session serve two tenants in
  succession, and it is not a property that reading the annotation tells you.
  `TenantThreadLocalIT` is there because it needed proving.

The alternative considered was a `HibernateJpaDialect` subclass enabling the filter in
`beginTransaction`. It works, but it resolves the tenant once per transaction, which makes
a `@Transactional` integration test unable to see the request's tenant at all.

### Fail-closed by sentinel, not by absence

When no tenant is on the thread, `Resolver` returns **`-1`** rather than leaving the filter
unparameterised. A forgotten guard then returns **nothing** instead of **everything**. Ids
are `BIGINT AUTO_INCREMENT` from 1, so the value can never match a row.

This is why `requireTenant()` is a guard rather than the scoping mechanism: if someone adds
an endpoint and forgets it, the failure is an empty list, not a breach.

### `requireTenant()` returns nothing, deliberately

The frontend's `requireTenantId()` returns the id. This one returns `void`. A method handing
back the `tenantId` would eventually have it passed into a service or a DAO, which is the
thing the design exists to make impossible.

It is also **the only place any layer reads `TenantContext`** — plus `DevSeeder`, below.

### The SUPER_ADMIN 403 is the one non-404 answer, and it is safe

Everything else cross-tenant is a 404. `requireTenant()` throws **403** instead, because
§13.2 says the POS surface is *unavailable* to a platform admin rather than empty, and
"this store has no products" is the wrong thing to show someone on the wrong surface.

Safe because **nothing is concealed**: a `SUPER_ADMIN` is not being told whether an id
exists — it gets the identical answer for an id that never did. `TenantIsolationIT` asserts
exactly that, so the exception cannot quietly become a precedent.

### `AppUser` is unfiltered, permanently

The one entity with `tenant_id` and no `@Filter`. Authentication is what *establishes* the
tenant, so it runs before there is one to scope by — filtered,
`AppUserDao.findByTenantAndUsername` would be evaluated against `NO_TENANT` and **every
login in the application would fail**. `TenantFilterCoverageTest` asserts the absence from
both sides so it cannot be "fixed".

C8's user management therefore has to re-establish by hand the scoping every other DAO gets
free. That is the cost of this exception, and it is the whole cost.

### A read outside a transaction is an unscoped read

The filter lives on a Hibernate session. Outside a transaction, Spring hands out a fresh
`EntityManager` per call with no session to enable it on. **Every service method that reads
tenant-owned data must be `@Transactional`**, including read-only ones. This is the sharpest
remaining edge in the design and nothing enforces it automatically.

### `DevSeeder` sets the context, and has to

The only place outside a request that does. The seeder runs at startup with no request, so
its "does this store already have products?" check would otherwise be evaluated against
`NO_TENANT`, answer "none" every time, and re-insert 23 rows on every boot under
`ddlAuto=update`. `seedUser` needs none of this, because `AppUser` is unfiltered — a neat
demonstration of exactly where the filter's reach starts and stops.

### Writes are not scoped by the filter

It appends to `WHERE` clauses, and an `INSERT` has none. **Writes are scoped by whoever
builds the entity**, which is why the rule is stated separately: stamp `tenant_id` from the
context, never from the request body.

## Tenant scoping

This step *is* the tenant scoping. The rule for everything after it:

- A new entity with a tenant gets `@Filter(name = TenantContext.FILTER_NAME, condition = TenantContext.CONDITION)`
  and a bump to `TenantFilterCoverageTest.EXPECTED_FILTERED_ENTITIES`.
- A new DAO method mentions no tenant, in no signature.
- A new tenant-scoped service method calls `TenantContext.requireTenant()` and is
  `@Transactional`.
- A new endpoint gets a case in `TenantIsolationIT`. The browser cannot show you a leak you
  did not think to look for.

## Tests

| Suite | Needs a DB | Proves |
|---|---|---|
| `TenantIsolationIT` (14) | yes | The port of `isolation.test.js`. Lists, counts, categories, search and by-id, each attempted across the boundary with a real token through the real chain |
| `TenantThreadLocalIT` (2) | yes | Two tenants and a tenant-less admin over a **two-thread pool**, 40 requests. The per-query resolution claim, under real reuse |
| `TenantFilterCoverageTest` (4) | no | Every tenant-owned entity is filtered, names the right filter, and `AppUser` still is not |
| `JwtAuthenticationFilterTest` (7) | no | The tenant is set for the chain and cleared on **every** exit — completion, exception, 401, 403, no token |
| `TenantContextTest` (5) | no | The sentinel, `null` meaning platform, per-thread isolation |

### Mutation-checked, not merely observed to pass

| Mutation | Reddens |
|---|---|
| `applyToLoadByKey = false` | exactly the 3 by-id cases; **all 6 list cases stay green** |
| Drop `@Filter` from `Product` | 13 cases across all five suites, including both coverage assertions |
| Drop the `finally` from `JwtAuthenticationFilter` | 3 cases — and **not** the concurrent one |

**Row 1 is why the by-id cases are not redundant** with the list cases: they are the only
thing standing between you and the `em.find()` hole.

**Row 3 changed what is written down.** The concurrent suite was *assumed* to be what covered
the ThreadLocal clear. It is not, and the reason is real: every request in it carries a
token, and `TenantContext.set()` removes on null, so each request overwrites or clears
whatever it inherited before doing any work. A leak is only observable to a request that
sets nothing — and those are 401'd by the chain before reaching a query. So with the current
chain the `finally` is **defence in depth rather than load-bearing**, and it becomes
load-bearing the moment a public endpoint reads filtered data. `aRequestLeavesNothingBehind`
and `JwtAuthenticationFilterTest` pin it; the limitation is recorded in
`TenantThreadLocalIT`'s own Javadoc so a green run is not read as covering more than it does.

### `TenantIsolationIT` needs `em.flush()` / `em.clear()`, and three cases assert nothing without it

Worth its own heading because it cost a debugging cycle and will again. Fixtures persisted
in the test's own transaction are **managed**, so `em.find()` answers from the persistence
context without issuing SQL — and a filter can only scope a query that actually runs. The
cross-tenant `get` returned **200**, not because scoping failed but because the setup had
pre-loaded the row.

Production cannot reach that state: every request gets its own session, and nothing can load
another tenant's row into it, because every load is filtered. Which is exactly why the
manual `curl` checks passed while the suite failed — **a divergence between the two is
information, not noise**.

### Deliberately untested

Bulk HQL `UPDATE`/`DELETE` against a filtered entity. Hibernate does not apply filters to
them, so they are unscoped — but nothing in the application issues one yet.
`TenantThreadLocalIT`'s teardown uses native SQL for the same reason. **C6/C7 must not reach
for a bulk statement without scoping it by hand.**

## Extension points

- **A new tenant-scoped entity (C5–C7)** — `@Filter`, plus the count in
  `TenantFilterCoverageTest`. The test fails loudly if you forget either.
- **A new scoped endpoint** — `requireTenant()`, `@Transactional`, and a case in
  `TenantIsolationIT`.
- **The platform surface (C8)** — `session.disableFilter(TenantContext.FILTER_NAME)`, only
  inside the `Tenant*` classes, behind the `@PlatformOperation` marker CONVENTIONS.md
  describes. That marker plus `grep` is meant to be the complete audit of cross-tenant reach.
- **Role rules on `/api/products`** — nothing discriminates by role yet; a `CASHIER` and an
  `ADMIN` see the same catalogue, which matches the frontend.
- **Coverage** — `mvn test -Pcoverage`, then `target/site/jacoco/index.html`.

## Related

- [CONVENTIONS.md](./CONVENTIONS.md) — the scoping rules this implements
- [c3-auth.md](./c3-auth.md) — where the tenant is resolved; C4 only copies it onto the thread
- [c2-persistence.md](./c2-persistence.md) — the entities this filters
- [database/constraints-and-indexes.md](./database/constraints-and-indexes.md) — the
  `tenant_id`-leading composites the filtered queries rely on
