# Constraints and indexes

Every unique key, index and check — **and why each exists**. Column definitions are
in [schema.md](./schema.md).

> Read this before changing an entity. The schema is generated from annotations,
> so **nothing here happens unless it's declared in `@Table`** — and
> `hbm2ddl.auto=validate` checks tables and columns only, never indexes or unique
> keys. A missing constraint is silent until it's a data-integrity bug.

## Unique constraints

| Name | Table | Columns | Why |
|---|---|---|---|
| `uk_tenant_code` | `tenant` | `code` | **The one globally-unique field in the model.** It's the login discriminator, so it has to be unambiguous across the platform |
| `uk_user_tenant_username` | `app_user` | `tenant_id, username` | Two stores can each have an `admin`. Global uniqueness here would be wrong |
| `uk_variant_tenant_sku` | `variant` | `tenant_id, sku` | Same SKU may exist in both stores — the seeds use `BISLERI-1L` in both deliberately |
| `uk_variant_tenant_qrcode` | `variant` | `tenant_id, qr_code` | Per-tenant, though the payload embeds the tenant so codes are distinct in practice anyway |
| `uk_order_tenant_number` | `pos_order` | `tenant_id, order_number` | Each store runs its own `ORD-YYYY-####`; both have an `ORD-2026-0001` |
| `uk_return_tenant_number` | `sales_return` | `tenant_id, return_number` | Same, for `RET-YYYY-####` |

**These are enforcement, not documentation.** A uniqueness check in service code is
a read and a write with a gap in it — two concurrent requests both see "no such
row" and both insert. MySQL's default REPEATABLE READ doesn't help, because a plain
`SELECT` is a non-locking snapshot read. The unique index is the only thing that
makes the check atomic.

So the pattern is: keep the service-level pre-check for a clean field-level 400,
**and** catch `DataIntegrityViolationException` for the contended case. The
constraint is the referee; the pre-check is a friendlier error message.

### The collisions these keys must permit

The seed data deliberately contains, across the two tenants: the same usernames
(`admin`, `cashier`), the same SKU (`BISLERI-1L`), and the same first order number
(`ORD-2026-0001`). Every one is legal, and each is an isolation test fixture.

**If a seed insert fails on a unique key, the constraint is global where it should
be per-tenant.** That's the fastest way to catch the mistake, which is why
`DevSeederIT` is worth having.

### Why not `NULL` for platform users

`UNIQUE(tenant_id, username)` with a nullable `tenant_id` would place **no
constraint at all** on `SUPER_ADMIN` rows — MySQL treats NULLs as distinct in a
unique index, so ten `superadmin` rows would all be accepted. The fix is a
generated column, which Hibernate can't emit from annotations. Hence the reserved
`platform` tenant row, which keeps `tenant_id NOT NULL` and lets the plain
composite key work. See [README.md](./README.md).

## Indexes

Every `tenant_id` is indexed, because **every query the application makes is
filtered on it** — the Hibernate `tenantFilter` appends `tenant_id = :tenantId` to
literally everything. An unindexed discriminator would mean a full scan per request.

| Name | Table | Columns | Serves |
|---|---|---|---|
| `idx_user_tenant` | `app_user` | `tenant_id` | User list |
| `idx_product_tenant` | `product` | `tenant_id` | Every catalogue read |
| `idx_product_tenant_category` | `product` | `tenant_id, category` | Category filter + the `DISTINCT` categories dropdown |
| `idx_variant_tenant` | `variant` | `tenant_id` | Every variant read |
| `idx_variant_product` | `variant` | `product_id` | Variants of a product |
| `idx_order_tenant_status` | `pos_order` | `tenant_id, status` | Order history's status filter; the held-orders list |
| `idx_order_tenant_cashier` | `pos_order` | `tenant_id, cashier_id` | Cashier-scoped history |
| `idx_order_tenant_created` | `pos_order` | `tenant_id, created_at` | History is newest-first |
| `idx_orderline_order` | `order_line` | `order_id` | Loading an order's lines |
| `idx_return_tenant_processor` | `sales_return` | `tenant_id, processed_by` | Cashier-scoped return history |
| `idx_return_order` | `sales_return` | `original_order_id` | Already-returned quantities per order — read on every return lookup |
| `idx_returnline_return` | `return_line` | `return_id` | Loading a return's lines |

Composite indexes lead with `tenant_id` because that predicate is always present —
a leftmost-prefix match, so the same index also serves a plain `tenant_id` lookup.
That's why there's no separate `idx_order_tenant`.

> **Three of the indexes above are redundant by that same rule, and were built
> anyway** (C2, as documented rather than silently corrected):
>
> | Redundant | Already covered by |
> |---|---|
> | `idx_user_tenant` | `uk_user_tenant_username (tenant_id, username)` |
> | `idx_variant_tenant` | `uk_variant_tenant_sku (tenant_id, sku)` |
> | `idx_product_tenant` | `idx_product_tenant_category (tenant_id, category)` |
>
> A unique key is also an index, so each of those already serves a `tenant_id`
> lookup as a leftmost prefix. The cost is write amplification and storage on the
> three hottest tables, not correctness. **Dropping them is a one-line change per
> entity plus a `schema.sql` regeneration** — deferred rather than taken during C2
> because it changes a reviewed design and belongs in its own commit.

The unique keys above double as indexes: `uk_variant_tenant_qrcode` is what makes
`lookupByQrCode` — the POS hot path, hit on every scan — an index seek.

## Check constraints

| Table | Constraint | Why |
|---|---|---|
| `variant` | `ck_variant_price_within_mrp` — `selling_price <= mrp` | MRP is the legal tax-inclusive ceiling (`requirements.md` §4) |
| `variant` | `ck_variant_stock_not_negative` — `stock_quantity >= 0` | Stock can't go negative; the atomic decrement relies on it as a backstop |
| `order_line` | `ck_order_line_quantity_positive` — `quantity > 0` | A zero-quantity line is meaningless — removing the line is how you reach zero |

Needs MySQL 8.0.16+; below that they parse and are silently ignored, so the
application-level validation is **not** redundant.

**That version floor is load-bearing in a second, less obvious way.** Hibernate's
`MySQLDialect` emits `CHECK` only when it believes the server is 8.0.16 or newer,
and it works that out from JDBC metadata. Naming the dialect class in configuration
*without* a version pins it to Hibernate's minimum supported MySQL (5.7) and drops
all three of these silently — which is why `PersistenceConfig` deliberately leaves
`hibernate.dialect` unset, and why the offline generator in `SchemaSqlTest` pins
8.0.16 by hand. See [c2-persistence.md](../c2-persistence.md).

Hibernate additionally generates an **unnamed check per enum column**
(`check (status in ('DRAFT','HELD','COMPLETED','CANCELLED'))`). Not designed here,
but worth knowing it exists: it means adding an enum value is a schema change even
though the column is `VARCHAR`. See [README.md](./README.md).

## Foreign keys

Standard parent → child, all named `fk_<child>_<parent>`. `order_line` and
`return_line` cascade on delete from their parent; nothing else cascades — orders
and returns are financial records and are never deleted, only status-transitioned.

**The deployment target is assumed to support foreign keys** (decided 2026-08-02),
so these are real database-enforced constraints, not documentation.

Noted only because some free-tier MySQL platforms — PlanetScale being the notable
one — don't support them. If the provider turns out to be one of those, the FK
declarations become inert: nothing errors, but orphaned rows become possible and
the integrity has to move into the service layer. Worth re-checking at deploy time
rather than assuming the assumption held.

## `SchemaConstraintsIT` — the test that guards this file

**Built in C2** (`src/test/java/com/pos/pojo/SchemaConstraintsIT.java`).

`hbm2ddl.auto=validate` will happily start an app whose unique keys were never
created. Nothing else in the stack notices either. So the test queries
`information_schema` and asserts the **six unique keys** above exist, on the right
tables, covering the right columns **in order** — order matters, because
`(username, tenant_id)` would be a different key wearing the same name.

It also asserts two things beyond the list:

- **the rule, not just the examples** — every unique key except `uk_tenant_code`
  must *lead* with `tenant_id`, so a future global constraint where a per-tenant
  one belongs fails immediately rather than at seed time;
- **the three check constraints**, which are equally invisible to `validate` and
  additionally depend on the server version.

Mutation-checked when written, per `CONVENTIONS.md`, rather than trusted for
passing: removing `uk_variant_tenant_sku` from `Variant` failed exactly the
covering case and nothing else.

It's a small test guarding a silent failure: without those keys, isolation still
*appears* to work in every manual check, and only breaks under concurrency or on
the first duplicate — long after anyone would connect it to a schema problem.
