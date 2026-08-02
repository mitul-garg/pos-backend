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

The unique keys above double as indexes: `uk_variant_tenant_qrcode` is what makes
`lookupByQrCode` — the POS hot path, hit on every scan — an index seek.

## Check constraints

| Table | Constraint | Why |
|---|---|---|
| `variant` | `selling_price <= mrp` | MRP is the legal tax-inclusive ceiling (`requirements.md` §4) |
| `variant` | `stock_quantity >= 0` | Stock can't go negative; the atomic decrement relies on it as a backstop |
| `order_line` | `quantity > 0` | A zero-quantity line is meaningless — removing the line is how you reach zero |

Needs MySQL 8.0.16+; below that they parse and are silently ignored, so the
application-level validation is **not** redundant.

## Foreign keys

Standard parent → child, all named `fk_<child>_<parent>`. `order_line` and
`return_line` cascade on delete from their parent; nothing else cascades — orders
and returns are financial records and are never deleted, only status-transitioned.

**Check your hosting provider supports foreign keys before relying on them.** Some
free-tier MySQL platforms (notably PlanetScale) don't, which would move referential
integrity into the application layer. Worth confirming before C2 rather than after.

## `SchemaConstraintsIT` — the test that guards this file

`hbm2ddl.auto=validate` will happily start an app whose unique keys were never
created. Nothing else in the stack notices either. So a test queries
`information_schema` and asserts the **six unique keys** above exist with the right
columns.

It's a small test guarding a silent failure: without those keys, isolation still
*appears* to work in every manual check, and only breaks under concurrency or on
the first duplicate — long after anyone would connect it to a schema problem.
