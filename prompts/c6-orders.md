# C6 — Orders and payment

Order create/list/get/update, hold/resume/cancel, `POST /orders/{id}/payments`, the hard
stock check, stock decrement, per-tenant order numbers under concurrency, actor from the
JWT. Corresponds to `backend-plan.md` C6, and to `requirements.md` §9 (the contract), §4
(pricing), §5 (hold/resume lifecycle) and §10 (the stock edge cases).

> **This is the step where money and inventory actually move.** Every write before this
> point (a product, a variant, even a `DRAFT` order) is inert — nothing is sold and
> nothing is committed. `PaymentService.pay` is the one moment stock is authoritative and
> decremented, and it is the only place in the application where two requests racing each
> other can make the difference between a legitimate sale and an oversold shelf.

## Key classes

- `com.pos.util.Pricing` — the port of `frontend/src/domain/pricing.js`. Every order and
  payment total is recomputed here, never trusted from a request.
- `com.pos.service.OrderService` — create/list/get/update, the ported `orderService.js`.
  Read this before touching order lifecycle: it is where a line gets re-priced from the
  variant's current row.
- `com.pos.service.PaymentService` — `pay()`, the ported `paymentService.js`, plus the
  hard stock check the mock had no ledger to enforce.
- `com.pos.dao.VariantDao#decrementStock` — the atomic conditional update. **Read this
  before writing anything else that mutates stock.**
- `com.pos.dao.OrderDao` — persistence, and the reasoning for *not* `JOIN FETCH`ing lines
  under pagination.
- `com.pos.model.OrderForm` / `OrderLineForm` / `PaymentForm` — the input DTOs, and what
  they deliberately don't carry.

## Decisions & gotchas

### Every amount is recomputed, and `OrderLineForm` has nothing to trust

`OrderLineForm` carries only `variantId`, `quantity` and `lineDiscount`. No price, no tax
rate, no name, no `qrCode` — a client sending them has those fields silently dropped by
the parser (`FAIL_ON_UNKNOWN_PROPERTIES` is off), exactly like an unexpected `tenantId` on
`ProductForm`. `OrderService.resolveVariant` re-derives the other four from the variant's
*current* row, read through the tenant filter via `VariantDao.findWithProduct`. This is
backend-plan.md's deferred obligation 2 ("recompute every amount") applied literally: a
forged price has nothing to land in.

### The hard stock check lives at payment, not at order creation

Holding a cart, or even creating a `DRAFT` order on the way to Payment, commits nothing —
no stock moves until `PaymentService.pay` succeeds. That is deliberate: requirements.md
§10 says the frontend only *warns* when a cart quantity exceeds stock, because stock is
authoritative server-side and can change between terminals right up until the till
actually rings the sale up. Checking at creation time would check a number that might be
stale by the time payment happens anyway; checking at payment is the only check that
matters.

`VariantDao.decrementStock` is one statement —
`UPDATE variant SET stock_quantity = stock_quantity - ? WHERE id = ? AND stock_quantity >=
?` — and zero affected rows **is** the rejection, not a read-then-write with a gap for a
second terminal to land in. `PaymentService.decrementStock` loops the order's lines and
throws on the first shortfall; because the loop runs inside `pay()`'s own transaction, the
exception rolls back every decrement already applied in that call, not just the failing
line. Verified by hand: a two-line order where only the second line is short leaves the
first line's stock **untouched** after the 400.

### A bulk statement is not filtered, and this one doesn't need to be

`decrementStock` names no entity alias for Hibernate to filter — it's JPQL bulk UPDATE,
and CONVENTIONS.md is explicit that bulk statements are not scoped automatically. Safe
anyway: every caller reaches it with a `variantId` already resolved through a filtered
read earlier in the *same* transaction (when the line was priced), so the id in hand is
already proven to belong to the caller's tenant. Same reasoning `TenantSequenceDao.next`
relies on for the `TenantPojo` argument it only ever locks, never selects by. This method must
never be called with an id taken from anywhere else — there is nothing else that would
catch it if it were.

### Order numbers reuse C5's sequence machinery unchanged

`OrderService.create` calls `tenantSequenceDao.next(SequenceKind.ORDER, tenant)` inside
the same transaction as `orderDao.insert` — the identical shape C5 used for QR codes, and
the identical hazard: skip the "same transaction" rule and two terminals can mint the same
`ORD-YYYY-NNNN`. The tenant-row-first lock order C5's deadlock fix established is inherited
as-is; nothing here adds a second thing that locks a tenant row.

### `create` and `update` restrict `status` beyond what the mock enforces

The mock's `orderService.create` accepts whatever `status` string it's handed; a real
client only ever sends `DRAFT` (Payment's first save) or `HELD` (Checkout's Hold button).
`OrderService.create` now rejects anything else with a 400 — otherwise a request could
synthesize a `COMPLETED` order without ever calling `pay()`, or a `CANCELLED` one without
going through `update`. `update`'s own `status` transitions are narrowed the same way, to
`HELD` or `CANCELLED` only: `DRAFT` is where an order starts and never returns to, and
`COMPLETED` is `PaymentService`'s alone to set. Neither restriction is in
`requirements.md` by name; both follow directly from backend-plan.md's "re-enforce every
rule" obligation, and are recorded here because nothing else says so.

### A `CASHIER`'s `cashierId` on `list` is forced, not merely defaulted

Requirements.md §9 describes `cashierId` as "a filter argument, not an identity claim" —
read in isolation, that would let a cashier page through a colleague's sales by passing
their id. §5's RBAC table says otherwise: "a CASHIER sees only their own orders." Read as
an RBAC promise rather than a UI convenience, `OrderService.list` now forces a `CASHIER`'s
`cashierId` to their own session id regardless of what the query string says; an `ADMIN`'s
value passes through unchanged (`null` for "all", or a specific operator's id). Verified
by hand: a cashier passing another user's id still sees only their own orders. Worth a
second look if the looser reading was actually intended — flagged rather than silently
decided.

### `GET /api/orders/{id}` is not cashier-scoped, unlike `list`

A receipt reprint or a resume-by-id has to work regardless of who rang the sale up — the
same openness `ProductService.get` already has across roles. Only the *list* view is "my
orders"; a direct id is either in the caller's tenant or it 404s, full stop.

### No `SecurityConfig` matcher for `/api/orders`, and that's the point

Unlike the catalogue, checkout/payment/hold-resume belong to **both** tenant roles
(requirements.md §5's RBAC table). `SecurityConfig.adminMatchers()` gets no new entries;
the path falls through to the default `anyRequest().authenticated()`. A `SUPER_ADMIN` is
still turned away, but by `TenantContext.requireTenant()` in the service layer (403, "This
action requires a store account"), not by a role rule — it has no store to hold an order
in, which is a tenancy fact, not an RBAC one.

### `PaymentService` is its own class, not a method on `OrderService`

Mirrors the frontend's `orderService.js` / `paymentService.js` split, the same choice C5
made for `ProductService`/`VariantService`. `PaymentService` reuses
`OrderService.toData` (package-private) rather than a second mapper, so the wire shape of
a paid order can't drift from an unpaid one.

### Seeding an order needs an actor, and there is no request to take one from

`DevSeeder.seedOrders` runs both new orders through `OrderService.create` and
`PaymentService.pay` rather than inserting rows directly — the same choice C5 made for
variants, so a bug in either service shows up as broken seed data at startup. But both
services read the caller from `AuthService.currentSession()`, which reads
`SecurityContextHolder`, and startup has no request to populate it from — the identical
problem `TenantContext.set()` already solves for tenancy, one layer up. The fix is the
identical shape: build the `Authentication` `JwtAuthenticationFilter` would have built for
the seeded cashier (a `SessionUserData` principal, a `ROLE_*` authority) and install it for
the method's duration, clearing both `SecurityContextHolder` and `TenantContext` in a
`finally`. `SecurityContextHolder` is a `ThreadLocal` exactly like `TenantContext`, so the
same clearing discipline applies even though the startup thread never goes on to serve a
pooled Jetty request. **Any future seeder needing an actor (C7's returns) should reuse this
shape rather than reinvent it.**

Seeded orders are dated when the app boots, not with the mock's back-dated July
timestamps — `PosOrder.createdAt` is `@CreationTimestamp`, which Hibernate always
overwrites on insert. Nothing downstream reads a seeded order for its age, so this is
recorded as a known difference rather than worked around.

## Tenant scoping

Nothing new — C4's filter and C5's write-scoping rules both apply unchanged:

- **Writes stamp the tenant from the session**, never the body: `OrderService.create`
  reads `AuthService.currentSession().getTenantId()`, exactly like `ProductService`.
- **`OrderLinePojo` inherits its order's tenant**, the same rule a variant inherits its
  product's — never re-read from the session inside `rebuildLines`.
- **The cashier is stamped from the session too**, via `AppUserDao.reference`, never from
  a body field — `OrderForm` has no such field to begin with.
- **`decrementStock`'s bulk statement is the one write that isn't filtered**, and the
  reasoning for why that's still safe is above.
- New cases belong in `TenantIsolationIT`: create, get-by-id, list, hold/patch, pay — all
  verified by hand cross-tenant during manual testing (404 on get and pay; a cashier's
  list never crosses either, since it's forced to their own id within their own tenant
  already).

## Tests

Not yet written — this step follows CONVENTIONS.md's "manual testing before automated
tests" order. Manual verification (via `mvn jetty:run` + `curl`, recorded here rather than
only in chat) covered:

- Create (DRAFT), hold (PATCH → HELD), re-price (PATCH with new `items` + `orderDiscount`,
  totals reconciled correctly), get, list (`status` filter).
- Pay by CASH: correct `amount`/`amountTendered`/`change`, stock decremented by exactly
  the paid quantity, order flips `COMPLETED`.
- Re-paying a `COMPLETED` order → 400 "Order is already paid".
- Patching a `COMPLETED` order → 400 "A completed order can no longer be modified".
- Insufficient stock on a multi-line order, paid by CARD (no tendered check in the way):
  400 naming the short line, and the *other* line's stock confirmed unchanged afterward —
  the transactional rollback working as designed, not merely asserted.
- Cross-tenant: `GET` and `POST .../payments` on another tenant's order id → 404, from a
  real second tenant's token, not a stub.
- A `CASHIER` passing another user's `cashierId` on `list` → still only their own orders.
- An `ADMIN` with no `cashierId` → all of the tenant's orders, including the seeded ones.
- `SUPER_ADMIN` on `/api/orders` → 403, "This action requires a store account."
- Validation: unknown `variantId` → 404 "Variant not found"; `quantity: 0` → 400; `status:
  COMPLETED` on create → 400; `status: DRAFT` on patch → 400; `status: CANCELLED` on patch
  → succeeds.

Planned automated coverage, once confirmed: `PricingTest` (pure JUnit, porting
`pricing.test.js`'s applicable cases — `computeRefundTotals`'s case waits for C7),
`OrderWriteIT` / `PaymentIT` (the shapes above as real MySQL-backed tests), a
`StockRaceIT` for two terminals decrementing the same variant concurrently (the
concurrency case backend-plan.md §4 exists to force), and the `TenantIsolationIT` cases
listed above.

## Extension points

- **A new order field derived from the variant** — add it to `Pricing.LineInput`, thread
  it through `OrderService.lineInputOf`/`lineInputOfExisting`, and it's available to both
  `computeLineTotals` and the snapshot written onto `OrderLinePojo`.
- **Split tender, if it ever arrives** — the payment fields are embedded on `PosOrderPojo`
  precisely so this is the join to extract into a child table; see `PosOrderPojo`'s Javadoc.
- **Returns (C7)** — `TenantSequenceDao.next(SequenceKind.RETURN, tenant)` is the identical
  call with a different enum value; refund math is `Pricing.computeOrderTotals` on
  snapshotted lines, the same function this step already uses; stock *restore* is the
  mirror of `decrementStock` and should live next to it in `VariantDao`. `DevSeeder`'s
  `authenticationFor` shape is reusable as-is for seeding returns' `processedBy`.

## Related

- [CONVENTIONS.md](./CONVENTIONS.md) — the cross-cutting rules this follows
- [c5-catalogue.md](./c5-catalogue.md) — `TenantSequenceDao`'s lock order, which this step
  inherits rather than re-derives
- [c4-tenancy.md](./c4-tenancy.md) — the filter this relies on and adds nothing to
