# C3 — Auth

**Status: done.** BCrypt, JWT issue/verify, the Spring Security chain, the three
`/api/auth` endpoints, and a dev seeder. `mvn test` runs **96 tests, 50 of which need
`pos_test`**.

Corresponds to `backend-plan.md` C3, and to `requirements.md` §9 (the contract) and §13.4
(tenant resolution at login). The frontend's `authService.test.js` is the specification;
`AuthControllerIT` is the port of it.

> **Still no tenant scoping.** C3 establishes *who* the caller is and *which* tenant they
> belong to. Nothing yet uses that to filter a query — the Hibernate filter is C4, and
> until it lands every query written against these entities is unscoped.

## Key classes

- `com.pos.config.SecurityConfig` — the chain, the `PasswordEncoder`, the URL rules,
  CORS. A **root**-context config.
- `com.pos.config.SecurityWebApplicationInitializer` — an empty class that registers the
  chain with the container. Without it, security is a bean nobody invokes.
- `com.pos.config.JwtAuthenticationFilter` — reads the bearer header, delegates every
  decision to `AuthService`, populates the `SecurityContext`.
- `com.pos.config.ApiErrorResponder` — writes `ApiError` from inside the filter chain,
  where a `@ControllerAdvice` cannot reach.
- `com.pos.service.AuthService` — login, per-request session resolution, the 401/403
  split. **The ordering inside it is the security property.**
- `com.pos.service.DevSeeder` — the demo tenants and users, idempotent.
- `com.pos.util.JwtTokenService` / `JwtPrincipal` — sign and verify; claims in, claims out.
- `com.pos.dao.TenantDao` / `AppUserDao` — the two deliberately un-scoped DAOs.
- `com.pos.model.LoginForm` / `LoginData` / `SessionUserData` — the wire shapes.

## Decisions & gotchas

### The two contexts bite three times, and one of them reached `jetty:run`

Everything below is the same fact from `c1-skeleton.md` — **servlet filters see the root
context, never the servlet one** — and each consequence looks like a different bug.

1. **`SecurityConfig` must be a root-context config.** In the servlet context the chain is
   built, logged at startup, and never invoked. That reads as "security silently does
   nothing", which is the worst available failure mode.
2. **`requestMatchers(String...)` does not work there.** The string overload builds an
   `MvcRequestMatcher` whenever Spring MVC is on the classpath, and that needs
   `@EnableWebMvc`'s `mvcHandlerMappingIntrospector` bean — which is in the servlet
   context. The application refuses to start with *"Please ensure Spring Security & Spring
   MVC are configured in a shared ApplicationContext"*, which they deliberately are not.
   Use explicit `AntPathRequestMatcher`s. Nothing is lost: `MvcRequestMatcher` exists to
   account for a servlet path prefix, and the `DispatcherServlet` is mapped at `/`.
3. **`ApiErrorResponder` cannot use `WebConfig`'s `ObjectMapper`.** Same reason. It holds
   its own, which is safe only because `ApiError` is two strings and a map, so none of that
   mapper's configuration applies to it.

**Point 2 shipped green and would not boot.** Every test passed, because `AuthControllerIT`
flattens both contexts into one for convenience — and anything `SecurityConfig`
accidentally takes from the servlet context resolves fine there and is missing in
production. `SecurityConfigIT` exists solely to close that hole: it boots the root context
**alone**, as a `WebApplicationContext`, and asserts little more than that the context
refreshes. Both halves matter — the Ant-matcher fallback only misbehaves in a web context,
so a plain one would go green on the very bug it catches.

> **If you add anything to `SecurityConfig`, run `SecurityConfigIT`.** It is the only test
> that sees what the container sees.

### CORS moved out of `WebConfig`

MVC's CORS support runs *inside* the `DispatcherServlet`, and the security chain is in
front of it. A preflight `OPTIONS` carries no `Authorization` header by definition, so it
would have been answered **401 before MVC ever saw it**, and every cross-origin call from
the Vite dev server would have failed. Leaving both configured would look like belt and
braces and actually be one dead configuration plus one live one.

### CORS only stays out of the picture in production if the scheme is trusted

`corsConfigurationSource()`'s allowlist only ever names the Vite dev origin — the deployed
frontend is never on it, on purpose (`iac/requirements.md` decision #5: Nginx reverse-proxies
`/api/*` on the same origin, so Spring's `CorsUtils.isCorsRequest` should never see a
cross-origin request from it at all). That check compares the browser's `Origin` header
against what **this app** thinks its own scheme/host/port are — and behind a
TLS-terminating reverse proxy (`iac/prompts/05-https.md`), Jetty only knows what Nginx
tells it. Nginx sets `X-Forwarded-Proto` correctly; without something on this side reading
it, `request.getScheme()` keeps reporting `http` even when the browser is on `https://`, the
scheme "mismatches", every deployed request looks cross-origin, and the dev-only allowlist
rejects all of them — this is exactly what happened the day HTTPS was deployed
(`BUGS.md` #19). `SecurityWebApplicationInitializer` registers `ForwardedHeaderFilter` ahead
of `springSecurityFilterChain` for this reason — **don't remove it, and don't "fix" a future
CORS 403 by widening this allowlist** without first checking whether it's the same
scheme-trust problem instead.

### The status checks must run *after* the password check

`requirements.md` §13.4 lets the 403s name what went wrong — deactivated account,
suspended store — and that is safe **only** because they are unreachable until the
password is proved. Hoist either check above it and a specific 403 becomes an oracle for
*"this tenant exists and this username is in it"*.

The case that pins it: a **wrong** password against a **suspended** tenant must still
return the generic 401. `authService.test.js` singles this out, and so does
`AuthControllerIT.wrongPasswordInASuspendedTenantStaysGeneric`.

### An unknown username still costs a BCrypt verification

BCrypt takes ~100ms by design. Skipping it when no user is found makes an unknown tenant
or username answer in microseconds while a wrong password takes ~100ms — so the uniform
401 leaks through **response time** exactly what it refuses to say in its message.
`AuthService` compares against a fixed hash that can never match.

### `LoginForm` has no bean validation, deliberately

A blank tenant code is a **failed login** (401, generic), not a malformed request (400,
field-level). `@NotBlank` would split the frontend's seven-case 401 list in two and hand a
caller a free oracle: 400 means *"you got the shape wrong"*, 401 means *"the shape was
right and the credentials were not"*. The handler therefore does not use `@Valid` either.

### The token is thin, and the database is authoritative

Claims: subject, tenant, role, timestamps. Nothing else — because **every authenticated
request re-reads the user row anyway**, so a claim that could go stale would be a
liability rather than an optimisation.

That per-request read is deferred obligation 5 (`backend-plan.md` §1): the mock re-checked
tenant status only in `me()`, so a suspended store's open tab kept transacting until
someone refreshed. Here it stops at the next request. The cost is one primary-key lookup,
which is why `AppUserDao.findWithTenant` uses a `JOIN FETCH` — the tenant is `LAZY` and the
caller always reads its status.

The tenant claim is **asserted against the row**; a disagreement is a 401. Unreachable
without the signing key, but that claim is what C4's filter will scope queries by, and a
claim that disagrees with its row must never be the one that wins. The **role** claim gets
no such treatment on purpose: a role *can* change, and there the row simply wins.

A platform user's tenant claim is **omitted entirely** rather than written as null, so
"belongs to no tenant" cannot be confused with a claim that failed to serialize.

### The signing key has no working default

`pos.jwt.secret` ships as `CHANGE_ME`, and `JwtTokenService` refuses to start on it. A key
that works out of the box is one nobody replaces, and anyone with the source could then
mint a token for any tenant. Also refused: a secret under 32 bytes (JWA forbids an HS256
key shorter than its own MAC) and a non-positive TTL (every login would appear to succeed
and every request after it would 401).

Consequence: **a fresh clone cannot `mvn jetty:run` until a secret is set.** That is the
intended loudness — see `../README.md`.

### `logout` requires a token and does nothing

Both halves are deliberate. It is honest about being a no-op: the token is the session, so
**the issued token stays valid until it expires**, and there is no revocation list
(`backend-plan.md` §11). It still requires a token, because the day it gains a denylist or
an audit entry it has to know whose session it is ending — and the cost is nil, since the
frontend clears its own state and redirects to login on any 401, which is the end state a
logout wants anyway.

### `AuthService` uses constructor injection, unlike everything else

Not taste. `WebConfig` component-scans `com.pos.controller`, so **any** test booting the
servlet context alone has to satisfy `AuthController`'s dependency on it. Field injection
makes that unstubbable — Spring applies it to every bean, including one returned from a
`@Bean` method, so the stub drags in the DAOs, an entity manager and a database. A
constructor makes the object constructible outside Spring, which is what
`StubServiceConfig` needs in order to keep `HealthControllerTest`, `OpenApiControllerTest`
and `OpenApiGeneratorTest` database-free.

**Every controller from C5 onwards has this problem.** Add a `@Bean` to `StubServiceConfig`
in the same change that adds the controller.

## Tenant scoping

**Nothing here enforces it — that is C4.** What C3 contributes is the *resolution*: the
token carries a `tenantId`, `JwtAuthenticationFilter` puts the resolved session on the
`SecurityContext`, and `AuthService.currentSession()` is the one accessor. C4 reads that
into `TenantContext` and enables the Hibernate filter from it.

Two DAOs are **deliberately outside scoping, permanently**:

- `TenantDao` — `tenant` carries no `tenant_id` column, because it *is* the discriminator.
- `AppUserDao` — authentication is what establishes which tenant a caller is in, so it
  cannot be scoped by its own answer.

Every DAO from C5 onwards gets the tenant from the filter and **mentions it in no
signature**. These two are the exception that has a reason, not a precedent.

`SessionUserData` reports a platform user's `tenantId`, `tenantCode` and `tenantName` as
**null**, even though the row points at the reserved `platform` tenant. That row is
storage; the wire contract (§3) is null.

## Tests

| Suite | Needs a DB | Proves |
|---|---|---|
| `JwtTokenServiceTest` (13) | no | Round trip, and every way to make a token without the key: another key, edited claims, **signature stripped off**, expired, no role claim, garbage. Plus the four startup refusals |
| `AuthControllerIT` (28) | yes | The port of `authService.test.js` through the **real chain**. All seven 401s, the four 403s, `me()`, mid-session lockout, no hash on the wire, one error envelope |
| `DevSeederIT` (8) | yes | `seeds.test.js`'s invariants, against rows the real startup listener committed |
| `SecurityConfigIT` (2) | yes | The root context refreshes **alone**, as the container builds it |

**Mutation-checked**, per CONVENTIONS.md — not merely observed to pass:

| Mutation | Reddens |
|---|---|
| Hoist the status checks above the password check | exactly `wrongPasswordInASuspendedTenantStaysGeneric` |
| Drop `requireUsable` from `resolveSession` | exactly the two `MidSession` cases |
| Restore `requestMatchers(String...)` | `SecurityConfigIT` only — **all 28 `AuthControllerIT` cases stay green** |

That third row is the one worth remembering: it is the measurement showing the headline
suite is blind to a whole class of failure, and why `SecurityConfigIT` is not redundant.

One assertion was tightened after being written: `jsonPath(...).doesNotExist()` also passes
for a **present-but-null** key, so the platform-login case now asserts the three tenant
fields are present *and* null. Jackson's null inclusion is `ALWAYS` on purpose
(`c1-skeleton.md`) — omitting the key would read as "not provided" rather than "belongs to
no tenant" — and the loose assertion would not have noticed someone switching the mapper
to `NON_NULL`.

Deliberately untested: expiry by elapsed time. The public API cannot backdate a token,
which is itself the point, so the expired-token case mints one directly with jjwt.

## Extension points

- **A new endpoint** — it is protected the moment it exists
  (`anyRequest().authenticated()`). Add it to `PUBLIC_PATHS` only to make it
  *un*protected, and add a `@Bean` to `StubServiceConfig` if its controller has service
  dependencies.
- **Role rules (C8)** — authorities are already `ROLE_<name>`, so `hasRole("ADMIN")` works.
  The `accessDeniedHandler` is wired and currently unreachable.
- **Token revocation** — `AuthController.logout` is where a denylist attaches, and
  `AuthService.resolveSession` is where it would be checked. Both already run per request.
- **Changing the TTL** — `pos.jwt.ttlMinutes`. No rebuild; it is read through `AppProperties`.
- **A new seeded fixture** — `DevSeeder.seed()`, keeping it idempotent. Products and orders
  belong to C5–C7, not here.

## Related

- [CONVENTIONS.md](./CONVENTIONS.md) — layers, the DTO rule, error mapping, tenant scoping
- [c1-skeleton.md](./c1-skeleton.md) — **the two contexts**, the root of three separate
  gotchas above
- [c2-persistence.md](./c2-persistence.md) — the entities this reads, and why `tenant` is
  an association rather than a column
