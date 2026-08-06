# prompts/ — index for agents

Read this file first, before opening any source file, when asked to change
something in `backend/`.

> **Status: C7 landing** (skeleton + persistence + auth + the tenant spine + the
> catalogue + orders/payment + **returns** — Spring MVC on Jetty, Hibernate on MySQL,
> all nine entities, a committed `schema.sql`, a JWT security chain, a Hibernate
> filter that scopes every query to the caller's tenant, products and variants with
> server-minted QR codes, orders that recompute their own pricing and atomically
> decrement stock at payment, and now returns that refund against the original
> sale's own snapshot, restore stock, and bound a request to what remains
> unreturned under real concurrency; `mvn jetty:run`, **280 automated tests**
> — C7 was verified manually per CONVENTIONS.md's testing order first, then
> covered by its own automated suite (pricing, return writes, six new tenant
> isolation cases, and a return-race concurrency test). The plan is `../../backend-plan.md` (steps
> C1–C9); the spec is `../../requirements.md`. The database in
> [database/](./database/) is **implemented as documented** — `SchemaConstraintsIT`
> proves the isolation-critical parts of it exist in MySQL.
> **Scoping is now enforced, not intended:** a query written against a
> tenant-owned entity is scoped whether or not its author thought about tenancy.
> The one thing a new entity must not forget is `@Filter`, and
> `TenantFilterCoverageTest` fails the build if it does.
> **What a write still has to do by hand** is in [c5-catalogue.md](./c5-catalogue.md):
> stamp the tenant, take a sequence value in the same transaction as the insert it
> numbers, and map any new unique constraint to a field.
> **What a payment adds on top** is in [c6-orders.md](./c6-orders.md): recompute
> every amount from the variant's current row rather than the request, and the hard
> stock check is one atomic conditional update, never a read-then-write.
> **What a return adds on top of that** is in [c7-returns.md](./c7-returns.md): a
> returnable-quantity check has no single-statement referee the way stock does, so
> it locks the original order row instead — read that before writing anything else
> that reads-then-acts against an order.

## How to use this folder

1. Check the tables below for the area you're touching. Open only that doc.
2. Check [CONVENTIONS.md](./CONVENTIONS.md) for cross-cutting patterns — tenant
   scoping, transactions, error mapping, naming, testing. This is where "how do we
   usually do X" lives; don't grep the source tree for it.
3. If you're touching the database in any way, [database/](./database/) is not
   optional reading.
4. If none of it covers what you need, *then* explore the code — and once you
   understand it, write down what you learned using [_template.md](./_template.md)
   so the next agent doesn't repeat the exploration.

## Database

| Doc | Covers |
|---|---|
| [database/README.md](./database/README.md) | Conventions — naming, types, money, the `tenant_id` rule, how the schema is generated |
| [database/er-diagram.md](./database/er-diagram.md) | Mermaid ER diagram — the whole model on one screen |
| [database/schema.md](./database/schema.md) | Table-by-table columns, types and nullability |
| [database/constraints-and-indexes.md](./database/constraints-and-indexes.md) | Every unique key and index, **and why each exists**. The isolation-critical ones are called out |

## Feature docs

| Doc | Covers |
|---|---|
| [c1-skeleton.md](./c1-skeleton.md) | The two Spring contexts, JSON/CORS, error mapping, package layout, and **why API docs are a class (`OpenApiGenerator`) rather than a dependency** |
| [c2-persistence.md](./c2-persistence.md) | Hibernate/Hikari wiring, the entities, `schema.sql` and its drift test, ids-as-strings — and the traps: **naming the dialect silently drops the check constraints**, and Hibernate 6 emits MySQL's native `ENUM` unless told otherwise |
| [c3-auth.md](./c3-auth.md) | BCrypt, JWTs, the security chain, the 401/403 matrix and the dev seeder — and **the two contexts biting three separate times**, one of which shipped green and would not boot. Read before touching `SecurityConfig` |
| [c4-tenancy.md](./c4-tenancy.md) | `TenantContext`, the Hibernate `@Filter`, and `/api/products` as the first scoped resource. **Read before adding any entity or endpoint** — it says what a new one must carry, why `applyToLoadByKey` is not optional, why a read outside a transaction is unscoped, and which mutations the isolation suite actually catches |
| [c5-catalogue.md](./c5-catalogue.md) | Products and variants, the merge-patch forms, server-minted QR codes and the per-tenant sequence. **Read before writing anything that inserts a row or mints a number** — it says why `TenantSequenceDao` locks the tenant first (it deadlocked without it), why a unique-constraint name arrives table-qualified, and why a green isolation case does not prove a child entity is filtered |
| [c6-orders.md](./c6-orders.md) | Orders, hold/resume/cancel, and payment. **Read before touching stock or order pricing** — it says why every amount is recomputed from the variant's current row, why the hard stock check is one atomic conditional update at payment (not a check at order creation), and how `DevSeeder` fakes an actor for a startup task that has no request to read one from |
| [c7-returns.md](./c7-returns.md) | Returns — lookup, create, get, list. **Read before touching return math or a returnable-quantity check** — it says why a return locks its original order rather than using an atomic update (the check is a `SUM` over several rows, not one column), why the same request can't split one returnable quantity across two lines, and why lookup/create split "missing" from "not completed" unlike the mock |

Keep these tables in sync — they're the only thing agents read unconditionally.

## Keeping this folder honest

- One doc per feature/step, not per class. If a doc would just restate the code,
  don't write it — link the class and note only the non-obvious parts (decisions,
  gotchas, extension points).
- Update the relevant doc **in the same change** that touches the feature. Stale
  docs are worse than none — an agent trusts what's written here over re-deriving
  it.
- **Any change to an entity updates [database/](./database/) in the same commit.**
  The schema is generated from entities, so those docs are the only human-readable
  record of what the database actually looks like.
- **Whenever a decision is made that `requirements.md` doesn't already cover** (a
  new resolved decision, a changed data shape, a swapped library, a scope change),
  update `requirements.md` itself in the same change — it's the spec, this folder
  is implementation notes. They should never disagree.
- **When the user reports a bug found by manually testing**, log it in
  [`../../BUGS.md`](../../BUGS.md) with `Area = Backend` and update its status when
  fixed. That file covers frontend and backend, and its "Code smells fixed" section
  is worth reading before you write a second copy of anything.

## The one rule you cannot get wrong

**`tenantId` comes from the session, never from the caller.** No endpoint accepts
a `tenantId` parameter; the Hibernate filter scopes every query from the JWT. An
out-of-tenant id must resolve as **404**, never 403.

The frontend already proved this contract end-to-end —
`frontend/src/services/isolation.test.js` is 23 executable statements of what
"isolated" means, and `TenantIsolationIT` has to reproduce all of them. The
product-shaped ones landed in C4, the variant ones in C5, the order ones in C6, and
the return ones join them in C7 — see [c7-returns.md](./c7-returns.md)'s Tenant
scoping section for what they cover. The user ones join last, as C8 gives them
endpoints to aim at. **Adding an endpoint without adding its case there is how a
leak ships**, because the browser cannot show you one you did not think to look for.

C5 added a corollary worth knowing before you write that case: **a passing isolation
case does not prove the entity it exercises is filtered.** A query that joins a
filtered parent is scoped through the join either way — see
[c5-catalogue.md](./c5-catalogue.md)'s mutation table.
