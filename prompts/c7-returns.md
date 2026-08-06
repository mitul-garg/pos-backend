# C7 — Returns

`GET /api/orders/lookup`, `POST /api/returns`, `GET /api/returns/{id}`,
`GET /api/returns?processedBy=&page=` — partial/repeat returns bounded by
already-returned quantity, refund on the original sale's own snapshots, stock
restore, per-tenant return numbers. Corresponds to `backend-plan.md` C7, and to
`requirements.md` §9 (the contract), §4/§5 (refund math and the return lifecycle),
§10 ("can't return more than purchased") and §13 (tenancy).

> **Nothing new to build at the persistence layer.** `SalesReturn`/`ReturnLine`
> landed in C2 with all nine tables, already `@Filter`ed and already counted in
> `TenantFilterCoverageTest`'s `EXPECTED_FILTERED_ENTITIES`. C7 is service and
> controller work over an entity shape that was correct from the start.

## Key classes

- `com.pos.service.ReturnService` — `lookupOrder`/`create`/`get`/`list`, the port of
  `returnService.js`. Read this before touching return math or the returnable-quantity
  check: it is where a request line gets bounded against what the *original order*
  says was purchased, not against anything the client asserts.
- `com.pos.dao.ReturnDao` — persistence, and `returnedQuantitiesByVariant`, the
  aggregate both `lookupOrder` and `create` read.
- `com.pos.dao.OrderDao#findForUpdate` — the pessimistic lock a return takes on its
  original order before trusting that aggregate. **Read this before writing anything
  else that reads-then-writes against an order.**
- `com.pos.dao.VariantDao#restoreStock` — the unconditional mirror of C6's
  `decrementStock`.
- `com.pos.model.OrderLookupData`/`OrderLookupLineData` — the `/lookup` response
  shape, standalone rather than reusing `OrderData`/`OrderLineData` (see their
  Javadoc).
- `com.pos.model.ReturnForm`/`ReturnLineForm`/`ReturnData`/`ReturnLineData` — the wire
  shapes, requirements.md §3's `Return` verbatim.

## Decisions & gotchas

### A return locks its original order before trusting how much of it remains

The check this step exists to enforce — "can't return more than purchased" — is a
`SUM` over `return_line` compared against a purchased quantity, read and then acted
on. That is a read-then-write with a gap exactly like every other race in this
application (`backend-plan.md` §4), except the referee available for the stock case
(one atomic conditional `UPDATE`) doesn't fit here: the quantity being checked is a
sum across possibly-several prior returns, not one column on one row, so there is no
single statement that can both check and reserve it atomically.

The fix is a lock instead of an atomic statement: `ReturnService.create` calls
`OrderDao.findForUpdate`, a `PESSIMISTIC_WRITE` `em.find` on the original order row,
**before** reading `returnedQuantitiesByVariant`. Two returns against the *same*
order submitted at the same instant now serialize — the second waits for the
first's transaction to commit, and then reads the updated baseline rather than the
stale one. Scoped to one order rather than a whole tenant (unlike
`TenantSequenceDao.next`'s tenant-row lock): only two returns against the identical
order can ever conflict, so nothing wider needs to queue.

Proven under real contention by `ReturnRaceIT`, not just reasoned about: two returns
for 3 of a 4-unit order fired at once, exactly one completing and one getting the
ordinary "cannot return more than 1" 400, five rounds running. This is `C7`'s
answer to the pattern that bit C5 (the sequence deadlock) and C6 (the stock race) —
`README.md`'s standing rule, "write a concurrency test for anything that mints a
number, decrements stock, or checks uniqueness," extended to *any* read-then-act
bound, not only those three shapes.

### The same request can split one returnable quantity across two lines

`alreadyReturned` (read once, from *prior, committed* returns) is not enough on its
own: two `items` entries in the *same* `POST /api/returns` naming the same
`variantId` would each check against that identical baseline and both pass, letting
a client return twice what remains by splitting one line into two. `create` also
tracks a `consumedThisRequest` map, incremented as each line is validated, and folds
it into the baseline for every later line in the same call. Covered by
`ReturnWriteIT.splittingAcrossTwoLinesOfOneRequestIsStillBounded`. Not something the
mock's `returnService.create` guards against — a small addition beyond the mock,
recorded here rather than silently taken, in the same spirit as C6's stock-check and
status-transition additions.

### `lookupOrder` and `create` split "missing" from "not completed", unlike the mock

`returnService.lookupOrder` and `returnService.create` throw one message
("No completed order found with that number" / "Original order not found or not
completed") for two different situations: the order genuinely doesn't exist (or
belongs to another tenant), or it exists but was never paid. This application
already tells those apart everywhere else a resource is present but in the wrong
state — an unpaid order is a 404, but a `COMPLETED` one you try to pay again is a
400 (`PaymentService.ALREADY_PAID`) — so C7 follows that precedent rather than the
mock's undifferentiated message: a missing/cross-tenant order is `NotFoundException`
(404, "Order not found"), and an existing-but-not-`COMPLETED` order is
`ValidationException` (400, "Only a completed order can be returned"). Neither
message is dictated unambiguously by requirements.md; both are recorded here and in
`requirements.md` §9 as a deliberate deviation, open for reconsideration like C6's
two.

### `GET /api/orders/lookup` lives on `OrderController`, not `ReturnController`

Requirements.md §9 names the path `/api/orders/lookup`, and a `@RequestMapping`
class prefix can't be overridden per-method — so the endpoint is declared on
`OrderController` and delegates straight to `ReturnService.lookupOrder`. The
alternative (duplicating the path literally on `ReturnController` with an absolute
override) doesn't exist as a Spring MVC option once a class-level
`@RequestMapping` is set; both controllers' Javadoc cross-references the split so
it isn't a surprise reading either file. Spring resolves the literal `/lookup`
segment ahead of `GET /api/orders/{id}`'s path variable by pattern specificity, the
same way every REST framework with a segment-based router does — no ordering
trick needed in the handler registration.

### A return inherits its tenant from the order, not the session

`SalesReturn.tenant` is stamped from `order.getTenant()`, mirroring the entity's own
Javadoc ("a return inherits its tenant from the original order rather than
re-reading the session"). In practice the two are the same value — `order` was only
reachable in the first place through `OrderDao.findForUpdate`, which is filtered —
but the *rule* is the inheritance, the same distinction `OrderLine` draws from its
parent order rather than re-reading the session per line.

### Refund math is `Pricing.computeOrderTotals`, not a second method

`ReturnService.create` builds `Pricing.LineInput`s from the **original order line's
own snapshot** (`unitPrice`, `taxRatePercent`, both already frozen at sale time) and
calls the identical `computeOrderTotals` orders and payment already use, with
`BigDecimal.ZERO` for the order-level discount — a refund never discounts. The
frontend's `computeRefundTotals` is a thin wrapper doing the same call and then a
field-name remap (`subtotal` → `refundSubtotal`, `lineTotal` → `lineRefund`, and so
on); the backend does that remap directly in `ReturnService.toData`/`create` rather
than adding a second `Pricing` method with no behaviour of its own. `PricingTest`'s
`computeRefundTotals` case is ported as a `computeOrderTotals` assertion for exactly
this reason.

### Stock restore is unconditional, unlike the decrement it mirrors

`VariantDao.restoreStock` is one bulk `UPDATE ... SET stock_quantity =
stock_quantity + ?`, with no `WHERE stock_quantity >= ?` guard — a return has no
"insufficient" case to reject, so there's nothing to check, only the same
atomicity requirement `decrementStock` has: a read-then-add-then-write would lose
an update racing a concurrent sale's decrement on the same row. Like
`decrementStock`, it is a bulk statement and therefore not tenant-filtered, and
safe for the identical reason: every `variantId` it's called with was already
resolved through a filtered read earlier in the same transaction (the original
order's own line).

## Tenant scoping

Nothing new in kind, all inherited from C4–C6:

- **`lookupOrder` reads through `OrderDao.findByOrderNumber`**, a JPQL query (not
  `em.find`), so ordinary filter scoping applies — this is what makes the same
  order number resolve to *each* tenant's own row rather than whichever tenant
  inserted it first (`TenantIsolationIT.sameNumberResolvesToTheCallersOwnOrder`).
- **`create` reads through `OrderDao.findForUpdate`**, `em.find` with a lock mode —
  scoped by the `@FilterDef`'s `applyToLoadByKey`, the identical mechanism every
  other by-id lookup in the application relies on.
- **`returnedQuantitiesByVariant` is filtered directly on `ReturnLine`'s own
  `tenant_id`**, not through a join to a filtered parent — see its Javadoc. This is
  the aggregate-over-one-entity shape `c5-catalogue.md` and `c6-orders.md` both
  flagged in advance as "the shape a totals query will have": nothing but
  `ReturnLine`'s own `@Filter` stands behind it, which is exactly why
  `TenantFilterCoverageTest` (not a passing isolation case) is what actually proves
  the annotation is there.
- **Writes stamp the tenant from the order, never the session or the body** —
  `ReturnForm` has no `tenantId` field to begin with, matching `OrderForm`.
- **`processedBy` comes from the JWT subject**, via `AppUserDao.reference`, never a
  body field — `ReturnForm` has no such field either.
- **A `CASHIER`'s `processedBy` on `list` is forced to their own session id**, the
  identical override `OrderService.list` applies to `cashierId`, for the identical
  requirements.md reason (§9: "applies identically... in C7").
- New `TenantIsolationIT` cases: the same order number resolving per-tenant, a
  foreign-only order number 404ing byte-identical to one that never existed,
  cross-tenant return create (rejected, stock left decremented), cross-tenant return
  get, list never crossing over, and return numbers scoped per tenant — six cases,
  matching `README.md`'s note that the return ones were still owed after C6.

## Tests

Manual first, then automated — `CONVENTIONS.md`'s order followed literally. Verified
by hand against a running `mvn jetty:run` instance before any suite was written:
lookup (fresh order, after a partial return, on a `DRAFT` order, on an unknown
number), create (partial return, the split-request guard, over-return rejection,
refund-after-reprice, stock restore, `refundMethod` default and override, cross-tenant
404 for both the order id and the return id), `SUPER_ADMIN` 403 on all three
endpoints, and — the concurrency case — two returns racing the same order fired with
real `curl` processes, which is what first showed `findForUpdate`'s lock actually
serializing the loser rather than merely reasoning that it should.

Automated coverage added the same day, once the manual pass held:

- **`PricingTest`** — `computeOrderTotals` used as a refund reproduces
  `pricing.test.js`'s `computeRefundTotals` case, field-remapped by hand in the
  assertion the way `ReturnService` remaps it in code.
- **`ReturnWriteIT`** (22 cases) — lookup and create/get/list inside one store:
  refund-against-original-price after a reprice, stock restored by exactly the
  returned quantity, `refundMethod` default and override, the return number and
  `processedBy` on a fresh return, partial/repeat returns, over-return rejection,
  the same-request split-line guard, an unknown variant on the order, validation
  (`originalOrderId` required, `items` required, a `DRAFT` order's id), an `ADMIN`
  processing a return too, and `SUPER_ADMIN` refused on all three endpoints.
- **`TenantIsolationIT`** (+6 cases) — see Tenant scoping above.
- **`ReturnRaceIT`** — the concurrency case, structured identically to
  `StockRaceIT`: not `@Transactional`, fixtures built and torn down through a real
  committed `TransactionTemplate`, two `POST /api/returns` calls fired from a
  two-thread pool at the same order, five rounds.

`mvn test`: 280 total, up from 250 — the 30 new cases above.

## Extension points

- **A future return field derived from the order line** — add it to
  `Pricing.LineInput` (already shared with orders) and thread it through
  `ReturnService.create`'s loop; no new `Pricing` method needed unless the refund
  math itself diverges from order math, which nothing in requirements.md suggests
  it ever should.
- **Refund approval / a `MANAGER` tier** (`backend-plan.md` §12, explicitly
  deferred) — would sit as a new guard inside `ReturnService.create`, likely a
  status field on `SalesReturn` gating `PATCH`-style approval before stock moves;
  no schema surprises, since the entity already carries everything a plain refund
  needs.
- **A QR code on receipts for faster returns** (`backend-plan.md` §12, deferred) —
  changes what `lookupOrder` is keyed on (an order id or QR payload instead of a
  typed order number), not how it's scoped or how refunds are computed.

## Related

- [CONVENTIONS.md](./CONVENTIONS.md) — the cross-cutting rules this follows,
  particularly "uniqueness is enforced by the database, not the service check" and
  the per-tenant-sequence rules, both of which this step's return-number minting
  reuses unchanged
- [c6-orders.md](./c6-orders.md) — `decrementStock`'s Javadoc is the direct model
  for `restoreStock`, and its "Extension points" section is where this step's shape
  was sketched in advance
- [c5-catalogue.md](./c5-catalogue.md) — `TenantSequenceDao`'s lock order, which
  `RET-YYYY-NNNN` minting inherits rather than re-derives
- [c4-tenancy.md](./c4-tenancy.md) — the filter this relies on and adds nothing to
