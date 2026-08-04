# prompts/ — index for agents

Read this file first, before opening any source file, when asked to change
something in `backend/`.

> **Status: C2 done** (skeleton + persistence — Spring MVC on Jetty, Hibernate on
> MySQL, all nine entities, a committed `schema.sql`; `mvn jetty:run`, 44 tests,
> **21 of them needing a local `pos_test` database**). The plan is
> `../../backend-plan.md` (steps C1–C9); the spec is `../../requirements.md`.
> The database in [database/](./database/) is **implemented as documented** —
> `SchemaConstraintsIT` proves the isolation-critical parts of it exist in MySQL.
> **No auth and no tenant filter yet** (C3, C4), so every query written against
> these entities today is unscoped.

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
"isolated" means, and `TenantIsolationIT` has to reproduce all of them.
