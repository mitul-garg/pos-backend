# C8 — Users and the platform surface

`GET/POST/PUT/DELETE /api/users`, `GET/POST/PATCH /api/tenants` — tenant-scoped user
management re-established by hand against `AppUser`'s deliberate absence of `@Filter`,
and the platform surface: atomic tenant + first-admin creation, reserved/duplicate/
malformed code rejection, suspend/reactivate, and the `@PlatformOperation` marker
`c4-tenancy.md` promised it. Corresponds to `backend-plan.md` C8, and to
`requirements.md` §9 (the contract), §5.7/§5.10 (the two screens), §13.2 (roles) and
§13.4 (reserved codes).

> **The one entity C4–C7 never had to scope by hand.** `AppUser` carries no `@Filter`
> (see its own class Javadoc) — authentication has to read it before there is a tenant
> to scope by. Every other tenant-scoped service leans entirely on the Hibernate
> filter and calls `TenantContext.requireTenant()` only as a guard; `UserService` is
> the one place in this codebase that also filters, the way every service would have
> had to before C4 existed.

## Key classes

- `com.pos.service.UserService` — `list`/`create`/`update`/`deactivate`, the port of
  `userService.js`. Read this before touching the last-active-admin guard: it locks
  the tenant row **before any other read in the transaction**, not merely before the
  count, and the reason is a real MySQL/InnoDB gotcha `LastAdminRaceIT` found on its
  first run.
- `com.pos.dao.AppUserDao` — `findByTenant`/`findInTenant` (the by-hand equivalent of
  the filter's scoping and `applyToLoadByKey`), `countActiveAdmins`, `lockTenant`
  (read its Javadoc before writing anything else that locks a tenant row for a
  read-then-act check), `countByTenant` (`@PlatformOperation` — see below).
- `com.pos.service.TenantService` / `com.pos.controller.TenantController` — the
  platform surface. Gated entirely by `SecurityConfig.platformMatchers()`, not by a
  check inside the service — mirrors how `ProductService` leaves `ADMIN`-gating to
  `adminMatchers()` rather than repeating it.
- `com.pos.dao.TenantDao#productCount`/`#orderCount` — the two places in the whole
  application that call `session.disableFilter(...)`. Read before adding a third.
- `com.pos.util.PlatformOperation` — the cross-tenant-reach marker `c4-tenancy.md`
  named in advance. `grep -r "@PlatformOperation" src/main` is the audit.
- `com.pos.model.UserForm`/`UserData`, `TenantForm`/`TenantStatusForm`/`TenantData` —
  the wire shapes.

## Decisions & gotchas

### The last-active-admin guard needed a lock *before its first read*, not before its count — found by a failing test

`UserService.deactivate` has to answer "is this the tenant's last active admin?" —
`AppUserDao.countActiveAdmins`, a `SUM`-shaped check with no atomic `UPDATE` to bind
to, the identical problem C7 solved for a return's returnable-quantity check. The
fix is the same shape: lock a row first (`AppUserDao.lockTenant`, `PESSIMISTIC_WRITE`
on the tenant row — there is no single `AppUser` row the way an order is one row for
a return, since the aggregate spans every admin in the tenant and a concurrent
`POST /api/users` could be adding one mid-check).

The first version locked only once the target was confirmed to be an `ADMIN` —
which meant `AppUserDao.findInTenant` (an ordinary read) ran *before* the lock.
`LastAdminRaceIT` failed on its first run: two admins racing to deactivate each
other both read "2 active admins" and **both succeeded**, leaving the store with
zero. The reason is a real MySQL REPEATABLE READ subtlety, not a logic bug in the
guard itself — every *plain* (non-locking) `SELECT` in a transaction is answered
from the snapshot taken at that transaction's **first** read. With `findInTenant`
as that first read, `countActiveAdmins`'s plain `SELECT` kept answering from a
snapshot taken *before* the lock's wait, even after the wait ended and the other
transaction had committed. A locking read (`lockTenant`) always reads the latest
committed row regardless of snapshot, which is what makes it safe to go first —
nothing else in the transaction has that property.

`ReturnService.create` never hit this because `OrderDao.findForUpdate` already
happens to be the first statement in its transaction, by construction. C8 needed it
*stated*, because the guard's natural reading order — "load the user, check if it's
an admin, then lock and count" — puts the read first. The fix:
`UserService.deactivate` now calls `lockTenant` unconditionally, before
`findInTenant`, on **every** deactivation, not only an `ADMIN`'s — there is no way
to know the role without a read, and any read before the lock reopens the bug. Cheap
on a low-throughput admin screen; the same trade `TenantSequenceDao.next()` makes
locking a whole tenant rather than a narrower row that doesn't exist yet.

**Read `AppUserDao.lockTenant`'s Javadoc before writing anything else that locks a
row to protect a read-then-act aggregate** — it names the ordering requirement
explicitly, which C7's version of this pattern satisfied without ever having to
say it.

### `AppUser` unfiltered means `UserService` scopes twice over: a guard *and* a filter

Every other C5–C7 service calls `TenantContext.requireTenant()` purely as a guard —
a `SUPER_ADMIN`-shaped 403, never a scoping mechanism — because the Hibernate filter
already scopes every query underneath it. `UserService` still calls
`requireTenant()` for the same guard, but *additionally* passes
`authService.currentSession().getTenantId()` into every `AppUserDao` call by hand,
because `AppUser` has no filter to lean on. This is the cost `c4-tenancy.md`
predicted C8 would pay for `AppUserDao`'s pre-C4 exception, and it is the whole
cost — nothing else in the application scopes this way.

### `@PlatformOperation` marks two distinct kinds of cross-tenant reach, not one

`TenantDao.productCount`/`orderCount` disable the Hibernate filter outright — a
`SUPER_ADMIN` has no tenant on the thread, so the filter would otherwise scope
those two queries to `NO_TENANT` and answer zero for every store. That is the
narrow, mechanical claim a future coverage test can check: every `disableFilter`
call site is inside a method carrying the marker.

But `Tenant`/`AppUser` were never filtered in the first place — `Tenant` because it
*is* the discriminator, `AppUser` because authentication runs before there is a
tenant to scope by. Reading or writing either across the caller's own boundary
(`TenantService.list`/`get`/`create`/`updateStatus`, `AppUserDao.countByTenant`
when called once per store rather than for a login) is exactly as cross-tenant in
effect, so it carries the marker too, even with no filter to disable. See
`PlatformOperation`'s own Javadoc for the two-part reasoning — the grep is meant to
answer "what can observe more than one tenant's data", and narrowing the marker to
only the filter-disabling half would make that grep incomplete.

### The platform tenant row is excluded, not merely hidden

`Tenant.isPlatform()` says the reserved row "is excluded from `GET /api/tenants` and
cannot be suspended" — C8 makes both halves literal. `TenantDao.list()` filters
`WHERE t.platform = false`, and `TenantService`'s `requireManaged` helper (shared by
`get` and `updateStatus`) answers **404**, not merely omitting the row from a list,
for a direct-by-id `GET` or `PATCH` against it. An id that resolves to platform
storage is, from this surface's point of view, indistinguishable from one that was
never issued — the same fail-closed shape every cross-tenant id in this application
takes, applied to a row that isn't even tenant-shaped.

### Role gating stays a `SecurityConfig` URL rule, including for the platform surface

`adminMatchers()` grew four entries for `/api/users/**` — and unlike the catalogue,
`GET /api/users` joins the write verbs there rather than staying open, because
requirements.md §5.7 gates the **whole screen**, not just its writes. A new
`platformMatchers()` array covers every `/api/tenants/**` verb, gated on
`ROLE_SUPER_ADMIN`. Neither service repeats the check — `TenantService`'s Javadoc
spells out why explicitly, since it is the first service in the codebase with no
`requireTenant()`-shaped guard of its own to fall back on.

### The mock's `"Only a platform administrator..."` message doesn't survive the port

`tenantService.js`'s `requireSuperAdmin()` throws a specific string. The backend's
equivalent is a URL rule, so a non-`SUPER_ADMIN` gets the same generic
`"You do not have permission to perform this action."` `SecurityConfig`'s
access-denied handler already produces for every other role-gated endpoint —
`ProductWriteIT.RoleRules` pins the identical shape for the catalogue. Recorded as a
deliberate divergence rather than reproduced, the same way C6 and C7 recorded their
own.

### An unrecognised tenant status is a malformed-body 400, not the mock's field message

`TenantStatusForm.status` is typed as the `TenantStatus` enum, matching
`OrderForm.status`'s identical choice (C6). Jackson rejects an unrecognised value
before `TenantService` ever runs, answering `"Malformed request body"` rather than
the mock's `"Invalid tenant status"`. Both are 400s a client can act on; the field
message is not the one C8 chose to reproduce, and this is recorded rather than
silently taken, matching every other status-string decision C6 and C7 made the same
way.

### Two `insert` methods needed the C5 flush fix, dormant until C8 made them reachable

`AppUserDao.insert` and `TenantDao.insert` did not flush before C8 — harmless, since
nothing before this step ever inserted a row whose uniqueness a request could race.
`UserService.create` (username) and `TenantService.create` (tenant code) both do now,
so both `insert` methods gained the identical fix C5 applied to `VariantDao.insert`:
flush immediately, so a raced duplicate's constraint violation lands inside the call
that caused it — where `ApiExceptionHandler.CONSTRAINT_FIELDS` can still turn it into
the same field-level 400 the pre-check produces for the uncontended case — rather
than at commit, wrapped in a `TransactionSystemException` the handler never sees.
`uk_user_tenant_username` and `uk_tenant_code` join the map in the same change.

## Tenant scoping

- **`UserService` scopes by hand** — see "`AppUser` unfiltered" above. This is the
  one service in the codebase where that sentence is true.
- **`TenantService` scopes nothing** — the whole point of the platform surface is
  that it reaches across every tenant, gated by role rather than by the filter.
- **Writes stamp the tenant from the session** (`UserService.create`) or point at a
  brand-new row that has no other tenant to be confused with
  (`TenantService.create`'s admin) — never from the request body. Neither `UserForm`
  nor `TenantForm` carries a `tenantId` field.
- **New `TenantIsolationIT` cases** (`UsersAreScopedToo`): list never crosses over,
  and a t2 user id is 404 for a t1 admin on both `PUT` and `DELETE`, byte-identical
  to one that never existed — the user ones `README.md` said would "join last, as C8
  gives them endpoints to aim at". `AppUser` being unfiltered means these are the
  *only* automated check that a t1 admin cannot reach a t2 user;
  `TenantFilterCoverageTest` explicitly excludes `AppUser` as unfiltered by design,
  so it proves nothing here.
- **The platform surface itself needs no isolation cases** — it has no "own tenant"
  to leak out of. What it needs instead is the role gate (`TenantAdminIT.RoleGate`)
  and the platform-row exclusion (`TenantAdminIT.Listing`/`SuspendReactivate`).

## Tests

Manual first, then automated. Verified by hand against a running `mvn jetty:run`
instance: user list/create/validation (blank username/password, `SUPER_ADMIN`
escalation attempt, duplicate username)/update/deactivate/last-admin-guard/
cross-tenant 404s/`CASHIER`-refused-the-whole-screen, then tenant list-with-counts/
atomic-create/login-immediately-works/reserved-duplicate-malformed-codes/suspend-
reactivate/platform-row-404/RBAC/401. One bug fell out of the manual pass and was
fixed separately before this step's own tests were written: BUGS.md #15,
`JwtAuthenticationFilter` rejecting `POST /api/auth/login` outright whenever a
stale/expired token happened to be attached to the request, because the filter
processed *any* present token before `authorizeHttpRequests` ever got to decide the
path was public.

Automated coverage added once the manual pass held:

- **`UserWriteIT`** (25 cases) — create/update/deactivate inside one store: active
  row + no password on the wire, identity-field-ignoring, validation (blank
  username/password, `SUPER_ADMIN` refused, duplicate username case-insensitively),
  merge-patch semantics (`username` never changes even if sent), promote to `ADMIN`
  and back, the last-admin guard from every angle (a `CASHIER` never trips it, two
  admins means either can go, an already-inactive admin doesn't protect the real
  last one), idempotent deactivate, and the role rule (`CASHIER` refused the whole
  screen including `GET`, `SUPER_ADMIN` refused too).
- **`TenantAdminIT`** (18 cases) — the port of `tenantService.test.js`'s 12: the
  role gate (tenant `ADMIN`/`CASHIER`/anonymous all refused), listing with counts,
  atomic creation + immediate login, reserved/duplicate/malformed codes, no-admin
  rejected with no orphan tenant left behind, every broken field reported together,
  suspend → locked out → reactivate without touching another tenant, the
  malformed-body divergence on an unknown status, unknown tenant 404, and the
  platform row's own 404 on both `GET` and `PATCH`.
- **`TenantIsolationIT`** (+2 cases) — `UsersAreScopedToo`, see Tenant scoping above.
- **`LastAdminRaceIT`** — the sixth concurrency suite, after `TenantThreadLocalIT`,
  `VariantSequenceIT`, `StockRaceIT` and `ReturnRaceIT`. Not `@Transactional`,
  fixtures built and torn down through a real committed `TransactionTemplate` (same
  reasoning as `StockRaceIT`/`ReturnRaceIT`): a fresh tenant with exactly two admins
  every round (unlike `ReturnRaceIT`'s fresh order in one reused tenant — a leftover
  admin from a previous round would pad the count and mask the race for the next
  one), each asked at the same instant to deactivate the *other*. Failed on its
  first real run — see "The last-active-admin guard" above — and is the reason that
  fix exists rather than a reasoned-about lock nobody proved.
- **`JwtAuthenticationFilterTest`** (+1 case) — the BUGS.md #15 regression: a
  request matching a public-path matcher must reach the handler even when
  `resolveSession` would throw.

`mvn test`: 327 total, up from 280 — the 47 new cases above, 246 of the 327 needing
`pos_test`.

## Extension points

- **`SUPER_ADMIN` impersonation of a tenant** (`backend-plan.md` §11, explicitly
  deferred) — would need a second, narrower `@PlatformOperation` path that mints a
  session for a chosen tenant, audited separately from the counting/creation reach
  this step adds.
- **Audit logging for tenant creation and suspension** (`backend-plan.md` §11) —
  both already funnel through single `TenantService` methods, so a log line has
  exactly one place to go per action.
- **A user self-protection guard** (can't deactivate your own account) — requirements.md
  §5.7 keeps this client-side only, deliberately; nothing here stops an admin
  deactivating themselves server-side, matching the mock.

## Related

- [CONVENTIONS.md](./CONVENTIONS.md) — "Transactions", the SUM-shaped-check-needs-a-
  lock rule this step's guard follows; "The multi-tenancy adaptation this layout
  needs", where `@PlatformOperation` was named in advance
- [c7-returns.md](./c7-returns.md) — `OrderDao.findForUpdate`'s lock-first ordering,
  which this step's guard needed restated rather than merely copied
- [c5-catalogue.md](./c5-catalogue.md) — `TenantSequenceDao`'s lock-the-wider-row
  reasoning, and the `insert`-must-flush fix both `AppUserDao` and `TenantDao`
  needed once C8 made their uniqueness checks reachable by a request
- [c4-tenancy.md](./c4-tenancy.md) — `AppUser`'s unfiltered exception and the
  `@PlatformOperation` marker, both named there and built here
