# C9 — Tenant self-registration

`POST /api/tenants/register|verify|resend-verification` — a public, unauthenticated
path to create a tenant, sitting **beside** the existing `SUPER_ADMIN` platform surface
(C8) rather than replacing it. Corresponds to the root-level `../../tenant-registration-plan.md`
(the up-front design doc — decisions, GCP-email research, the frontend's own build
record) and to `requirements.md` §5.11, §9, §12.

> **Status: built (a)–(g), manually verified end-to-end, automated.** `mvn test`:
> **370 total**, up from 327 — C9 added 43 (10 `MailConfigTest`, 7 `RegistrationRateLimiterTest`,
> 3 `HoneypotTest`, 24 `TenantRegistrationIT`, 2 `TenantRegistrationCommitOrderingIT`).
> Manually verified against a real `mvn jetty:run` + MySQL at every step (b)–(f), which is
> how `BUGS.md` #18 was found — `tenant.status` didn't fit `PENDING_VERIFICATION` until
> it did.

## Key classes

- `com.pos.service.TenantRegistrationService` — the public surface: `register`/
  `verify`/`resendVerification`. Read this before touching either of the first two:
  they carry **no `@Transactional`** on purpose, which is the single most
  load-bearing, easiest-to-accidentally-regress fact in this feature — see below.
- `com.pos.service.TenantRegistrationWriter` — the actual `@Transactional` writes.
  A second bean, not a second method on `TenantRegistrationService`, because Spring's
  `@Transactional` is proxy-based: a method calling another method of the *same*
  class bypasses the proxy, so one class cannot have a non-transactional method that
  calls a transactional one and observe a real commit in between. `public`, not
  package-private, despite one real caller — `StubServiceConfig` (test sources) has
  to name the type from `com.pos.config` to stub `TenantRegistrationService` for the
  servlet-context-only suites.
- `com.pos.service.TenantCodeRule` — the code validation (required/format/reserved/
  duplicate), extracted out of `TenantService` in the same change that gave it a
  second caller. Read this before touching either `TenantService.create`'s or
  `TenantRegistrationWriter.register`'s code validation — they call the same method.
- `com.pos.service.RegisteredTenant` — the commit-then-email handoff record between
  the writer and the service. Not a `model` DTO; never serialized.
- `com.pos.util.EmailSender` / `LoggingEmailSender` / `JavaMailEmailSender` — the
  mail abstraction, selected by `com.pos.config.MailConfig` on `pos.mail.enabled`
  (default off, so `mvn test`/`mvn jetty:run` never need a real mailbox).
- `com.pos.util.RegistrationRateLimiter` — hand-rolled fixed-window limiter
  (`ConcurrentHashMap#compute`, 5/hour/key), keyed by the caller
  (`TenantRegistrationController` composes `"register:" + ip` / `"resend-verification:"
  + ip`, so the two routes get independent budgets from the same IP).
- `com.pos.util.Honeypot` — the `website`-field check. A trip is handled entirely
  inside `TenantRegistrationService.register`, before `TenantRegistrationWriter` is
  ever reached — the database is untouched.
- `com.pos.util.VerificationTokens` / `EmailFormat` — small, named, unit-tested
  pieces pulled out of the writer rather than left as inline regexes/`SecureRandom`
  calls: 256-bit tokens (base64url), and the frontend's own permissive email pattern
  ported verbatim.
- `com.pos.controller.TenantRegistrationController` — the three routes. A separate
  controller from `TenantController` (which is entirely `SUPER_ADMIN`-gated), so "is
  this endpoint gated?" stays a per-class question the way C8 established it.
- `com.pos.exception.TooManyRequestsException` → 429 (`ApiExceptionHandler`).
- `com.pos.service.AuthService` — `requireUsable` gained a third branch,
  `PENDING_VERIFICATION`, checked before the general not-`ACTIVE` → `SUSPENDED` one.

## Decisions & gotchas

### The write and the email are two separate transactions, and that needed a second bean

`register`/`resend-verification` must never roll back the tenant/admin row because an
outbound SMTP send was slow or failed (`tenant-registration-plan.md` §4) — and
holding a database transaction open across a network round trip to Gmail's SMTP
server is its own problem on a connection pool this project already sizes tightly
for free-tier MySQL (`requirements.md` §1).

The natural-looking fix — make `TenantRegistrationWriter.register` `@Transactional`
and have `TenantRegistrationService.register` call it, then send the email — only
works if the two are in **different beans**. Spring's `@Transactional` is a proxy:
it only takes effect on a call that arrives through the bean's proxy, and a method
calling another method of the *same* class via `this.` bypasses that proxy entirely.
If `register()` carried `@Transactional` itself, Spring would fold the writer's
transaction into it (`REQUIRED` propagation, the default), and nothing would commit
until `register()` itself returned — i.e., not until after the email attempt anyway,
defeating the entire point.

**Proven, not just reasoned about** — `TenantRegistrationCommitOrderingIT`, not
`@Transactional` for the identical reason `StockRaceIT` isn't (a wrapping test
transaction would make the ordering trivially "correct" regardless of whether the
code actually crosses a bean boundary). Two claims:

1. The row survives a `RuntimeException` thrown from `EmailSender.send()` —
   `sendVerificationEmail`'s `try/catch` (log-and-continue) is what makes this true,
   asserted via a fresh, real, committed read afterward.
2. By the time `send()` runs, the row is *already* visible from a completely
   separate, freshly-opened transaction — not merely the same Hibernate session,
   which would be true even if the ordering were backwards.

Case 2's probe needed `PROPAGATION_REQUIRES_NEW`, found the hard way: the first
version used the default `REQUIRED`, and when `register()` was deliberately mutated
back to carrying `@Transactional` (to prove the test would catch that regression),
the probe **silently joined the same now-ambient transaction** instead of opening a
genuinely separate one — so it "saw" the uncommitted write and the test kept passing
even though the ordering was wrong. `REQUIRES_NEW` forces the probe to suspend
whatever transaction (if any) is already on the thread, which is the only way it can
tell "committed" from "visible within the same transaction" apart.

### The tenant-code rule is shared; the other required-field messages are not

`tenant-registration-plan.md` §4 says registration "reuses `TenantService.create`'s
validation shape." Taken literally for the *code* field — `TenantCodeRule.validate`
is now the one place `required`/format/reserved/duplicate lives, called by both
`TenantService.create` (`"code"`) and `TenantRegistrationWriter.register`
(`"tenantCode"`) with the field key parameterized, since the two wire contracts
disagree on the name.

The other required-field messages (`storeName`, `adminUsername`, `adminPassword`)
are **not** shared, deliberately — the frontend's own `validateTenantRegistration`
(already shipped, Phase 9) uses different wording from `tenantService.js`'s
`validateTenant` for these (`"Store name is required"` vs `"Tenant name is required"`,
`"The admin's username is required"` vs `"The first admin's username is required"`),
and since `backend/CLAUDE.md` treats the frontend's already-built service as the
contract, C9 matches *that* wording rather than reusing `TenantService`'s constants
for fields whose text was never actually shared to begin with. Confirmed by reading
`validateTenantCode`/`validateTenantRegistration` side by side in
`frontend/src/domain/validators.js` before writing a line of Java.

### `TenantRegistrationWriter` had to become `public`

Package-private felt right at first — it's an implementation detail with exactly one
real caller. It doesn't compile: `StubServiceConfig` (test sources, `com.pos.config`)
has to *name* the type to stub `TenantRegistrationService` for `HealthControllerTest`/
`OpenApiControllerTest`-shaped suites (`WebConfig` component-scans `com.pos
.controller`, so every controller — including `TenantRegistrationController` — gets
built whether or not a suite is about it), and Java can't reference a package-private
type from a different package even just to pass `null`. Made `public`, matching
every other service class in this codebase.

### Adding `TenantRegistrationService` to the scanned services rippled into 16 test files

`RootConfig` component-scans `com.pos.service` unconditionally, so the moment
`TenantRegistrationService` existed, every `IT` that boots `RootConfig` had to be
able to construct it — which meant an `EmailSender` bean had to be present in every
one of those contexts too. `MailConfig` (C9(b)) had no consumer yet at that point, so
it hadn't broken anything; C9(d) is what made the dependency real, and 16 existing
`@ContextConfiguration` lists needed `MailConfig.class` added, one line each. Worth
knowing before adding the next `com.pos.service` class with a new kind of dependency:
the blast radius is "every IT that boots `RootConfig`," found by running `mvn test`
immediately after adding the class, not by reasoning about it in advance.

### `tenant.status` needed widening, and only manual testing found it (`BUGS.md` #18)

`PENDING_VERIFICATION` is 21 characters; `Tenant.status` was still `@Column(length =
16)` from C8, sized for `ACTIVE`/`SUSPENDED`. MySQL rejects an over-length `VARCHAR`
insert outright (`MysqlDataTruncation`), not a silent truncate, so every
`POST /api/tenants/register` call answered 500 at the very first insert. Invisible to
`mvn test` at the time it was introduced (C9(a)) and for two steps after (C9(b), (c))
— nothing in the automated suite persists a `PENDING_VERIFICATION` tenant until
`register()` exists to produce one, which didn't happen until C9(d), and the bug
wasn't actually *exercised* until C9(e) gave it an HTTP endpoint to curl. Widened to
`VARCHAR(32)` the moment manual testing hit it. The general lesson — a schema change
that's only wrong for a value nothing yet produces has no automated tripwire — is
worth remembering the next time an enum gains a long value.

### The honeypot's response deliberately diverges from the frontend mock's shape

The mock's `register()` returns `{ tenantCode }` only on a honeypot trip — no
`adminEmail` key at all, distinct from the real-success shape which has both. The
backend instead **echoes back the submitted `adminEmail`** on a trip, so the response
shape is identical (`{ tenantCode, adminEmail }`) whether or not the honeypot fired.
Recorded as a deliberate divergence, not an oversight: a `null`/absent field is
itself a distinguishing signal a sufficiently observant bot could correlate with
"this field is fake," which is exactly what the honeypot's silence is supposed to
prevent. Neither shape leaks the field's *purpose* to a naive scraper (the premise
the honeypot actually defends against), but matching the response shape byte-for-byte
costs nothing and closes the sharper-bot case too.

## Tenant scoping

- **Registration touches no tenant-scoped, `@Filter`ed entity.** `Tenant` and
  `AppUser` are the two permanently-unfiltered entities (C4, C8) for reasons that
  predate this feature; `TenantRegistrationWriter`/`Service` read/write only those
  two, so nothing here needed a new scoping decision.
- **No new `@PlatformOperation`.** Unlike C8's platform surface, self-registration
  doesn't reach *across* tenants — `register` creates exactly one new tenant it then
  operates on, `verify`/`resendVerification` each act on exactly one tenant resolved
  by code/token. There is no aggregate-across-every-tenant read here the marker
  exists to flag.
- **`TenantRegistrationIT`'s sanity check, not a new isolation suite.** The plan asks
  for confirmation that "nothing about registration should special-case isolation" —
  satisfied by one test (`selfRegisteredTenantIsOrdinaryOnceVerified`) that verifies,
  logs in, and does one ordinary scoped read (`GET /api/products`, empty), rather
  than duplicating `TenantIsolationIT`'s whole suite against a second tenant-creation
  path that exercises no isolation code of its own.

## Tests

Manual first, per step, against a real `mvn jetty:run` + MySQL — not one end-to-end
pass at the finish: (b) a clean root-context boot with `MailConfig` wired in; (d) a
second boot proving the full `TenantRegistrationService` bean graph resolves; (e) the
first real curl pass — happy path, duplicate/malformed/reserved code, malformed
email, honeypot (confirmed absent from the server log), wrong/expired/spent token,
resend real-vs-fabricated pair, the rate limit tripping on the 6th call, and a
regression pass (existing login, platform-surface 401) — which is where `BUGS.md` #18
was found and fixed before anything downstream was built on top of the broken
`register()`; (f) the exact `BUGS.md`/frontend#17 sequence — suspend a still-pending
tenant, confirm its still-unexpired token can no longer reactivate it, confirm the
status stays `SUSPENDED`.

Automated coverage added once each manual pass held:

- **`MailConfigTest`** (10) — the `EmailSender` selection logic (`pos.mail.enabled`
  on/off, missing-setting startup failures), no Spring context.
- **`RegistrationRateLimiterTest`** (7, plus a 50-thread concurrency case) — window
  behavior with a controllable `Clock`, independent keys, independent per-endpoint
  budgets, window-boundary edges. Mutation-checked: swapped the atomic `compute()`
  for a naive get-then-put, reran — failed `expected: <5> but was: <50>` as
  predicted, confirming the concurrency case has teeth.
- **`HoneypotTest`** (3) — the one-line predicate, given a name and cases anyway.
- **`TenantRegistrationIT`** (24) — the plan's own test list, ported: happy path
  end-to-end (register → email captured via a `CapturingEmailSender` test double →
  login blocked → verify → login succeeds), every required-field/format/reserved/
  duplicate-code case, missing/malformed email, honeypot (creates nothing, proven by
  registering the same code again right after), wrong/expired/already-spent token,
  the `BUGS.md`/frontend#17 suspend-while-pending regression, resend regenerating and
  invalidating the old token, resend's identical ack for a fabricated pair and for an
  already-verified tenant, the rate limit tripping independently per endpoint, and
  the isolation sanity check. Every `register`/`resend-verification` call in the
  suite carries its own fake source IP (`freshIp()`) unless a test is deliberately
  exercising the rate limiter — otherwise the 5/hour budget is shared across every
  unrelated test in the class, since `RegistrationRateLimiter` is one singleton bean
  for the whole (cached) test context.
- **`TenantRegistrationCommitOrderingIT`** (2) — the seventh concurrency-shaped
  suite, after `TenantThreadLocalIT`, `VariantSequenceIT`, `StockRaceIT`,
  `ReturnRaceIT`, `LastAdminRaceIT` (and now this one, which isn't a race so much as
  an ordering proof, but shares their "not `@Transactional`, real commits via
  `TransactionTemplate`" shape for the same reason). See "Decisions & gotchas" above.

`mvn test`: 370 total, up from 327 — 43 new, all seven suites needing `pos_test`.

## Extension points

- **A dedicated transactional email provider** (Resend/Mailgun/SendGrid) — noted as
  the upgrade path in the plan's §2, not built; would mean a second `EmailSender`
  implementation calling an HTTPS API instead of SMTP, selected the same way
  `MailConfig` already picks between the two that exist.
- **Reclaiming an abandoned registration's tenant code** — deliberately out of scope
  (plan §8): `code` is globally unique with no expiry-driven reclaim, so a
  never-verified registration permanently squats its code. The token itself *is*
  cleaned up correctly; only the surrounding row's `code` has no reclaim path.
- **`SUPER_ADMIN` visibility into pending registrations** — `TenantService.list`
  already returns every non-platform tenant regardless of status, so a
  `PENDING_VERIFICATION` row is already visible on `GET /api/tenants`; nothing more
  was asked for or added.
- **iac** — the three SMTP secret variables + startup-script wiring, deliberately
  last (plan §7 step 4): no point provisioning secrets for code that didn't exist
  until this step landed.

## Related

- `../../tenant-registration-plan.md` — the up-front plan this doc is the "how it
  actually landed" record for: resolved decisions, GCP-email-sending research, the
  frontend's own build record (Phase 9, already shipped mock-backed before this).
- `../BUGS.md` #18 — the `tenant.status` length bug this step found and fixed.
- [c8-users-tenants.md](./c8-users-tenants.md) — the platform surface this sits
  beside; `TenantCodeRule`'s extraction touches `TenantService.create` directly.
- [c4-tenancy.md](./c4-tenancy.md) — why `Tenant`/`AppUser` carry no `@Filter`, which
  is why this step needed no new scoping decision of its own.
- [CONVENTIONS.md](./CONVENTIONS.md) — "Transactions" (the propagation/proxy
  reasoning `TenantRegistrationWriter`'s split relies on), "Testing" (mutation
  -checking a concurrency test, the `*Test`/`*IT` database-need convention).
