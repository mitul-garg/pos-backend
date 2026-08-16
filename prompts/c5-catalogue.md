# C5 — The catalogue

**Status: done.** Products gained their writes, variants arrived whole, and QR codes
are minted server-side from a per-tenant sequence. `mvn test` runs **196 tests, 130 of
which need `pos_test`**.

Corresponds to `backend-plan.md` C5, and to `requirements.md` §9 (the contract), §2
(products vs variants), §6 (QR) and §13.3 (per-tenant uniqueness).

> **This is the step where the C4 spine stopped being the interesting part.** Scoping
> needed no new work — the filter was already there — so what C5 was actually about is
> everything a *write* has to get right that a read does not: stamping the tenant,
> generating an identifier that has to be unique, and losing a race cleanly.

## Key classes

- `com.pos.model.ProductForm` / `VariantForm` — one form per resource, serving both
  `POST` and `PUT`, every field nullable. Read either Javadoc before adding a constraint
  annotation to one.
- `com.pos.service.ProductService` / `VariantService` — the ported `productService.js` and
  `variantService.js`, including their messages word for word.
- `com.pos.dao.TenantSequenceDao` — **read this before C6.** The per-tenant counter, its
  lock order, and why it has one.
- `com.pos.util.QrCodes` — the payload's shape, and the fallback SKU derived from the same
  sequence value.
- `com.pos.dao.VariantDao` — every read ad-hoc joins the product (`JOIN ProductPojo p ON
  p.id = v.productId`, returning a `VariantWithProduct(variant, product)` tuple —
  peer-review Phase 2 replaced the original `JOIN FETCH v.product`, since
  `VariantPojo.product` is no longer a navigable association); `insert` flushes
  deliberately.
- `com.pos.exception.ApiExceptionHandler` — now maps a unique-index violation onto the same
  400 the service's pre-check produces.
- `com.pos.config.SecurityConfig#adminMatchers` — the first role rule in the application.

## Decisions & gotchas

### `PUT` is a merge patch, which is why the forms carry no bean validation

The frontend reactivates a soft-deleted row by sending `{"isActive": true}` and nothing
else. A `@NotBlank` on `name` would reject that — a legal request — so the rule being
enforced is not "this request names a product" but **"the product that results is valid"**,
and the service validates the *merged* row. Exactly what the mock does with
`validateProduct(merged)`.

That also decides where validation lives. `ProductWriteIT.validatesTheMergedRow` sends
`{"name": "   "}` at a valid product: the request is well-formed, and what it would
*produce* is a nameless row.

### The tenant is stamped, and nothing else protects a create

The filter appends to `WHERE` clauses and an `INSERT` has none. So a create is scoped by
`ProductService` reading `AuthService.currentSession()` and by nothing else — no annotation,
no interceptor, no test that passes for a different reason. Mutation-checked: making
`create` trust a `tenantId` from the body reddens **exactly one case in each of two
suites**, and every filter-backed case stays green.

A variant does it differently and more strictly: it **inherits its parent product's
tenant**, because the product was loaded through a filtered read. Same rule a return will
follow from its order in C7.

### The server mints the QR code, and `VariantForm` has no field for one

Requirements §6 puts generation on the server. The payload embeds the tenant
(`POS-QR-{tenantId}-000001`), so a client that could supply one could mint into another
store's run — which is a sharper version of the `tenantId`-in-the-body problem, since the
result gets printed and stuck to a shelf.

A blank `sku` falls back to `SKU-000001` from the **same sequence value** as the code, so a
row's two identifiers agree. The mock used the variant's own id; that does not exist yet
under `AUTO_INCREMENT` — the row has to be built before the database will name it.

### `TenantSequenceDao` deadlocked, and the Javadoc explaining why it could not was wrong

**The most important thing in this step.** The first version took
`SELECT … FOR UPDATE` on `tenant_sequence`, and where the row did not exist yet, inserted
it. The comment said a gap lock would make a second concurrent caller block.

Gap locks are **shared**. Both transactions take one, then each tries to insert, and an
insert needs an intention lock that conflicts with the *other's* gap lock. Neither can
proceed; MySQL kills one with `Deadlock found when trying to get lock`, and the caller gets
a 500 — on the first pair of concurrent creates in a fresh store.

The fix is the textbook one: **lock in a fixed order, starting from a row that always
exists.** Every caller now locks the `tenant` row before touching the sequence, so only one
transaction per store is ever in that section, the gap lock is uncontended, and there is no
cycle.

Cost: a store's QR codes, order numbers and return numbers serialize against each other
rather than only against their own kind. For a shop with a few tills that is nothing, and
it buys a lock order that fits in one sentence.

**Still true, and still the reason the class exists:** take the number in the same
transaction as the insert it numbers. Both locks are held until that transaction commits,
and that is what reserves the value.

### The unique index is the referee, and its name arrives table-qualified

The service pre-check (`VariantDao.skuExists`) is a read and a write with a gap. The unique
index is what actually prevents the duplicate, so `ApiExceptionHandler` maps the violation
onto the **same field and the same sentence** — a lost race is then invisible to the caller
rather than a different bug to report.

MySQL 8 reports the index as `variant.uk_variant_tenant_sku`, **table-qualified**, and
Hibernate passes that through. The lookup missed every entry, so every raced duplicate
answered 500 while the identical uncontended one answered a clean 400. The qualifier is now
stripped.

Worth knowing *how* that hid: the unit test written for the mapping used the bare name,
passed, and proved nothing. **A fixture that is not shaped like production agrees with
whatever the code does.**

An unmapped constraint deliberately stays a **500**. Only the entries in
`CONSTRAINT_FIELDS` have a field to blame and a sentence a user can act on; a foreign key
or a check constraint failing is a bug in this application rather than in the request, and
answering 400 would blame the caller and hide it. **Add a row there in the same change that
adds a unique constraint a request can trip.**

### Enrichment is part of the variant's shape

Every variant response carries the parent's name (`"Amul Taaza Toned Milk — 500 ml"`), GST
slab and HSN code — including the by-product list, where the mock returned bare rows. One
shape per resource, and a scan must not need a second round trip while a customer waits at
a till. Hence a join on every read: left unjoined, a 34-row list is 35 statements. (Written
as `JOIN FETCH` at the time; peer-review Phase 2 swapped it for an ad-hoc `JOIN ... ON`
returning a tuple record once `VariantPojo.product` stopped being navigable — same single
SQL join, same reasoning, see the `VariantDao` key-classes entry above.)

### Deactivate/reactivate cascade between a product and its variants (peer-review Phase 3)

`ProductService.deactivate`/`update` and `VariantService.deactivate`/`update` keep
`isActive` in sync across the relationship — deactivating a product deactivates every
variant, deactivating a product's last active variant deactivates the product, and
reactivating cascades the same way in both directions. Not documented here in full;
see `database/constraints-and-indexes.md`'s "The product/variant active-status sync"
section for the four rules, the zero-variant exemption, and why it's a bulk `UPDATE`
(`VariantDao.setActiveByProduct`) plus a count-before-flip check rather than a
cascade annotation — this project has none of those left, per the "Not
Java-navigable" section right above it.

### Product images (peer-review Phase 3)

Three new `ADMIN`-only endpoints (`POST .../image-upload-url`, `PUT .../image`,
`DELETE .../image`) and one new nullable column, `ProductPojo.imageUpdatedAt` —
`NULL` means no image. The image bytes never transit the backend: `mintImageUploadUrl`
mints a short-lived signed GCS `PUT` URL scoped to a fixed, deterministic object path
(`{tenantId}/{productId}/image`, `ProductService.imageObjectPath`), the frontend PUTs
directly to GCS, then `PUT .../image` (`confirmImage`) stamps the timestamp once that
upload has actually completed. `ProductService.toData` mints a fresh signed **read**
URL on every response when the timestamp is set — never stored, since a stored signed
URL would eventually go stale.

One image per product means "replace" is a plain GCS object overwrite at the same
path (no old object to separately delete), and "remove" (`DELETE .../image`) deletes
the GCS object synchronously in the same request rather than leaving it orphaned.
`GcsImageSigner`/`NoopImageSigner`/`ImagesConfig` (`com.pos.util.images`,
`com.pos.config`) are the reCAPTCHA/mail-shaped enabled/disabled pair — see those
classes' own Javadoc, and `iac/prompts/06-product-images.md` for the infra half
(bucket, signing key delivery, why local RSA signing over IAM `signBlob`).

**Check order in all three service methods matters, and is deliberate**: existence/
tenant (404) → request-shape validation, e.g. content-type (400, cheap and
config-independent) → `pos.images.enabled` (400) → the actual GCS call. Existence
first is what lets a cross-tenant id answer its usual 404 regardless of whether images
are enabled at all on this deployment — the same reasoning every other by-id method in
this class already follows, not a new rule for this feature. It also happens to be
what makes tenant isolation for these three endpoints testable by `ProductImageIT`
without a real GCS key, since `pos.images.enabled=false` in every test environment
would otherwise mask a 404 behind a blanket 400.

`ProductImageIT` proves auth/role gating, tenant isolation, and request validation —
not the real signing/upload/read/delete round trip, which is structurally unreachable
from `mvn test` (`NoopImageSigner` throws by design) and was instead verified manually
against the real live bucket. See the peer-review commit message for exactly what that
covered.

### The role rule lives in `SecurityConfig`, not on the handlers

Catalogue management is an `ADMIN`'s (§13.2). The matchers are **method-scoped**, so
`GET /api/products` stays open to a `CASHIER` and the rules cannot be collapsed into one
path pattern. Kept as URL rules rather than `@PreAuthorize` so the whole role surface is one
greppable file — the same argument as the `@PlatformOperation` marker C8 will need.

This is also the first rule that makes C3's `accessDeniedHandler` reachable.

### 201, and the generator had to learn about it

`POST` answers **201 Created**. `OpenApiGenerator` documented every handler as 200, which
was *already* wrong for `logout`'s 204; it now reads `@ResponseStatus`. A document that
promises 200 is worse than a missing one, because a generated client treats the real answer
as an error.

## Tenant scoping

Nothing new — which was the point of C4. Three things C5 did have to do by hand:

- **Writes stamp the tenant.** Products from the session, variants from the parent.
- **`TenantSequenceDao` names no tenant in its sequence query.** The filter scopes it, so it
  can only ever advance the caller's own counter. Its `TenantPojo` argument exists to lock the
  tenant row and to point a foreign key, never to select by.
- **New cases in `TenantIsolationIT`**, including the scan.

## Tests

| Suite | Needs a DB | Proves |
|---|---|---|
| `ProductWriteIT` (24) | yes | Create/update/deactivate inside one store, the merge-patch semantics, and the ADMIN rule |
| `VariantIT` (28) | yes | The identity of a variant: who chooses the SKU and the code, what a duplicate does, what re-issuing does to the old value |
| `VariantSequenceIT` (2) | yes | The sequence under real contention, and a raced SKU |
| `TenantIsolationIT` (+10, now 24) | yes | Cross-tenant **writes**, and the variant cases from `isolation.test.js` — the scan above all |
| `ApiExceptionHandlerTest` (+3, now 9) | no | The constraint mapping, in both the bare and table-qualified forms |
| `OpenApiGeneratorTest` (+1, now 8) | no | `@ResponseStatus` reaches the document |

### The concurrency suite earned its place immediately

`VariantSequenceIT` found **both** of the bugs above on its first run — the deadlock and the
qualified constraint name. Neither is reachable from a single-threaded test, and neither was
visible by reading the code, because in both cases the code came with a comment explaining
why it was fine.

### Mutation results, and one that was not expected

| Mutation | Reddens |
|---|---|
| `create` stamps the tenant from the request body | **only** the two create cases (`ProductWriteIT` + `TenantIsolationIT`); every filter-backed case stays green |
| `applyToLoadByKey = false` | the three by-id cases in `TenantIsolationIT`, plus the C4 read ones — **not** the create case |
| Drop `@Filter` from `VariantPojo` | **only 2** isolation cases, plus both coverage assertions |

**The third row is the surprise, and it changes what a green run means.** The scan, the
search and the by-product list stay green without the variant's filter, because every read
in `VariantDao` is `JOIN FETCH v.product` and `ProductPojo` is filtered — Hibernate scopes those
queries through the join regardless. What reddens is exactly the two paths that join no
product: `em.find` by id, and `VariantDao.skuExists`, whose count then spans every store and
reports another tenant's SKU as taken.

*(As tested at C5. Peer-review Phase 2 later swapped `JOIN FETCH v.product` for an ad-hoc
`JOIN ProductPojo p ON p.id = v.productId` — see the `VariantDao` key-classes entry above —
without re-running this specific mutation suite; the reasoning still holds, since both sides
of an ordinary JPQL join stay independently `@Filter`ed regardless of `FETCH`, confirmed
directly for the equivalent join in `ReturnLineDao.returnedQuantitiesByVariant` when that
pattern was extended to a filter-only join in the same effort.)*

Two consequences for C6 and C7:

1. **A green isolation case does not prove the child entity is annotated.**
   `TenantFilterCoverageTest` is what actually holds that line — which is why it exists and
   why its count must be bumped in the same change as a new entity.
2. **An aggregate over a child alone is protected by nothing but that annotation.** That is
   the shape `skuExists` has, and the shape a stock check or an order-total query will have.

### A fixture bug worth not repeating

`TenantIsolationIT` writes QR codes as literals so they are predictable in assertions. Doing
that while leaving `tenant_sequence` at 1 means the next *real* create mints `000001` again
and trips `uk_variant_tenant_qrcode`. It surfaced as a 400 on the SKU case and read, at a
glance, exactly like a leak. The fixture now sets the counter where the generator would have
left it.

## Extension points

- **A new endpoint** — `requireTenant()`, `@Transactional`, a case in `TenantIsolationIT`,
  and a `@Bean` in `StubServiceConfig` if it adds a controller dependency.
- **A new unique constraint a caller can trip** — add a row to
  `ApiExceptionHandler.CONSTRAINT_FIELDS` in the same change, or its violation answers 500.
- **Order and return numbers (C6/C7)** — `TenantSequenceDao.next(SequenceKind.ORDER, tenant)`,
  called inside the transaction that inserts the row it numbers. The lock order is already
  set; do not add a second thing that locks the tenant row in a different order.
- **A role rule** — `SecurityConfig.adminMatchers()`, method-scoped.
- **The stock check (C6)** — an atomic conditional update, which is a *bulk* statement and
  therefore **not** filtered. Scope it by hand.

## Related

- [CONVENTIONS.md](./CONVENTIONS.md) — the cross-cutting rules this follows
- [c4-tenancy.md](./c4-tenancy.md) — the filter this relies on and adds nothing to
- [c2-persistence.md](./c2-persistence.md) — the entities, and `tenant_sequence`'s shape
- [database/constraints-and-indexes.md](./database/constraints-and-indexes.md) — the two
  unique keys this step made reachable from a request
