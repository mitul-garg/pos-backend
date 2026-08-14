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
| `uk_tenant_verification_token` | `tenant` | `verification_token` | C9: two self-registrations must never mint the same token. Nullable and unique is safe — MySQL treats `NULL` as distinct in a unique index, the same fact that motivates the reserved platform tenant row below, so any number of verified/never-registered tenants can carry `NULL` here at once |
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
`idx_user_email` below is the one exception: `AppUserPojo` is already the one entity C4
never filters (see its class Javadoc), and the query it serves runs at
self-registration, before any tenant exists to filter by.

| Name | Table | Columns | Serves |
|---|---|---|---|
| `idx_user_tenant` | `app_user` | `tenant_id` | User list |
| `idx_user_email` | `app_user` | `email` | Peer-review Phase 0's tenants-per-email resource-creation guardrail — `AppUserDao.countByEmail`, on the public self-registration path, so unlike every other index above it serves a query with **no** `tenant_id` predicate at all |
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
| `idx_return_tenant_created` | `sales_return` | `tenant_id, created_at` | Return history is newest-first (peer-review Phase 1) — the `sales_return` equivalent of `idx_order_tenant_created`. `ReturnDao.list()` sorts `ORDER BY created_at DESC` for every `GET /api/returns` call; `idx_return_tenant_processor`'s leftmost prefix only covers a `processedBy`-scoped read, not the ADMIN "all returns" case, which fell back to a `tenant_id`-only index scan plus a filesort |
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

### Catalogue search indexing strategy (peer-review Phase 1, not yet implemented)

**Neither catalogue search path can use a B-tree index today, and that's fine at
today's scale.** `ProductDao.where()` (the products screen) and `VariantDao.search()`
(the checkout manual-add search) both build `lower(col) LIKE :term` with `term` wrapped
in a leading **and** trailing `%` — a leading wildcard is exactly the shape no B-tree
index, including `idx_product_tenant_category` and `uk_variant_tenant_sku`, can serve;
MySQL falls back to scanning every row the `tenant_id` predicate leaves (already bounded
to one tenant's catalogue by the filter, not a cross-tenant scan) and testing each one.
Acceptable today given the project's own no-pager, `pageSize: 200`-scale ground rule —
the scan is over the underlying table, before any `LIMIT`, so it isn't bounded by that
200 the way a paged result is. It will be the first thing to slow down as a single
tenant's catalogue grows past a few thousand SKUs, which a real store selling
ambient/FMCG goods can plausibly reach.

**The strategy, when it's needed:** a MySQL `FULLTEXT` index on `product(name, brand)`
for the products screen, and a second on `variant(sku, variant_label)` for the
checkout search — two indexes because the two searches span two different tables (the
product-name/brand half of `VariantDao.search()`'s `OR` would run against the first,
the sku/label half against the second). Boolean mode with a trailing wildcard
(`MATCH(...) AGAINST('term*' IN BOOLEAN MODE)`) is the closest match to today's UX.

**Two real costs, not just "add an index" — the reason this is a strategy note and not
a change:**

1. **The match semantics change, not just the plan.** `LIKE '%term%'` is a true
   substring match anywhere in the field; `FULLTEXT` is word/token-based — MySQL's
   built-in InnoDB parser splits on non-alphanumeric boundaries and enforces a minimum
   token length (`innodb_ft_min_token_size`, default 3), so a boolean-mode prefix search
   matches from the *start* of a word, not an arbitrary infix. Typing "isc" would no
   longer find "Biscuit" the way `LIKE` does today — a real, user-visible UX change to
   sign off on, not a transparent performance swap. A hyphenated SKU like
   `BISLERI-1L` tokenizes into `BISLERI` and `1L` under the default parser, which is
   arguably *better* for search than today's pure substring match, but is still a
   behavior change worth naming rather than discovering after the fact.
2. **`FULLTEXT` doesn't fit this project's schema pipeline as it stands.** "The schema
   is generated from entities" (`CONVENTIONS.md`) — every index here is a plain JPA
   `@Index` in `@Table`, which Hibernate emits as an ordinary `BTREE` index; there is no
   portable annotation for a MySQL `FULLTEXT` index, so adding one would mean either a
   one-off `ALTER TABLE ... ADD FULLTEXT` run outside the `mvn test -Dpos.schema.write=true`
   regeneration this project relies on for every other index (immediately a schema-drift
   risk `SchemaSqlTest` can't catch), or introducing a migration tool for the first
   time. That is a bigger decision than the index itself, and belongs in its own
   discussion when the scale actually calls for this.

**Revisit trigger:** a tenant's live catalogue growing into the thousands of SKUs, or a
real, measured search-latency complaint — not a date or a row-count guess. Until then,
`idx_product_tenant_category` and `uk_variant_tenant_sku` continue to serve every
*exact* lookup (category filter, SKU uniqueness, `findBySku`) — only the free-text
`LIKE` paths are affected.

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

### Not Java-navigable, but still real constraints (peer-review Phase 2)

**Every entity in `com.pos.pojo` had its `@ManyToOne`/`@OneToMany` fields removed**
— a full retroactive sweep, not just new code going forward. `product.getTenant()`,
`order.getLines()`, `variant.getProduct()` and every equivalent across all 9 mapped
entities no longer exist. None of that touched this table's actual content: every
constraint above — name, parent/child, `ON DELETE` behavior — is exactly as it was,
because the mechanism that keeps a Java-level relationship from existing is
deliberately DDL-only.

**The mechanism, once per relationship:** the real, writable field becomes a plain
`Long` (`tenantId`, `productId`, `orderId`, ...), and a *second*, no-accessor
`@ManyToOne` sits alongside it on the identical column —
`@JoinColumn(insertable = false, updatable = false, foreignKey = @ForeignKey(name =
"fk_..."))`. Hibernate's schema generation reads a mapping's join-column/
foreign-key metadata to emit the DDL regardless of whether that mapping
participates in reads or writes, so this shadow field still produces the exact same
`alter table ... add constraint fk_... foreign key ... references ...` — `@OnDelete
(action = OnDeleteAction.CASCADE)` included, confirmed unchanged for
`fk_order_line_pos_order` and `fk_return_line_sales_return`. Every entity here uses
field access (`@Id` is placed on the field, not a getter), so Hibernate never needs
an accessor for the shadow field either — with none exposed, nothing outside the
entity file can reach it. Unreachability is compiler-enforced, not a discipline
rule to remember.

**Reading the related row now means an explicit `DAO` call**, never a lazy
getter: `TenantDao.find(getTenantId())` for a single row a caller actually needs,
or the owning entity's own DAO for a list/search read that used to `JOIN FETCH` a
parent — `VariantDao.findWithProduct`/`findByProduct`/`search` etc. return a small
package-visible record tuple (`VariantWithProduct(VariantPojo variant, ProductPojo
product)`) built from an ad-hoc `JOIN ProductPojo p ON p.id = v.productId` instead,
same single SQL join, same one round trip. A bidirectional parent/child pair whose
child rows used to be written via cascade (`PosOrderPojo.lines`,
`SalesReturnPojo.lines`) now goes through the child's own DAO instead
(`OrderLineDao`/`ReturnLineDao`: `insertAll`, `findByOrder`/`findByReturn`, and for
the order side — which supports editing a held cart, unlike an insert-only return —
`deleteByOrder`).

**Why DDL-only rather than the alternative** (hand-maintaining the 16 FK
constraints as raw `ALTER TABLE` statements outside `schema.sql`): it keeps every
existing automated safety net intact — `SchemaSqlTest`'s drift check, the
generated `schema.sql`, and the stable `fk_*` names other tests already depend on
by literal string — while genuinely achieving "no relationship navigation in
application code," verified by the compiler rather than review discipline.

### Deleting a product/variant referenced by order or return history (peer-review Phase 1)

**Not reachable, checked at both layers.** `ProductDao`/`VariantDao` expose no
`delete()` method — `DELETE /api/products/{id}` and `DELETE /api/variants/{id}` are
both `deactivate()`, which flips `is_active` and nothing else (see
`ProductService`/`VariantService`). So there is no code path that issues a SQL
`DELETE` against `product` or `variant` at all today.

Even if one existed, `fk_order_line_variant`, `fk_return_line_variant` and
`fk_variant_product` carry no `ON DELETE` clause — MySQL's default is
`NO ACTION`/`RESTRICT` — so a hard delete of a variant or product still referenced
by an order line, return line, or (for a product) any of its variants would be
rejected by the database itself. This is the correct backstop precisely because
"nothing cascades" above already covers it: order/return lines are financial
history and must never silently disappear because a catalogue row did.

Revisit only if a `delete()`/hard-delete path is ever added to either DAO — at
that point this section needs a real answer for "what happens to the FK
violation", not just a description of why it can't happen yet.

## `SchemaConstraintsIT` — the test that guards this file

**Built in C2** (`src/test/java/com/pos/pojo/SchemaConstraintsIT.java`).

`hbm2ddl.auto=validate` will happily start an app whose unique keys were never
created. Nothing else in the stack notices either. So the test queries
`information_schema` and asserts the **seven unique keys** above exist, on the right
tables, covering the right columns **in order** — order matters, because
`(username, tenant_id)` would be a different key wearing the same name.

It also asserts two things beyond the list:

- **the rule, not just the examples** — every unique key except `uk_tenant_code` and
  `uk_tenant_verification_token` (both on `tenant` itself, which has no `tenant_id`
  column to lead with) must *lead* with `tenant_id`, so a future global constraint
  where a per-tenant one belongs fails immediately rather than at seed time;
- **the three check constraints**, which are equally invisible to `validate` and
  additionally depend on the server version.

Mutation-checked when written, per `CONVENTIONS.md`, rather than trusted for
passing: removing `uk_variant_tenant_sku` from `VariantPojo` failed exactly the
covering case and nothing else.

It's a small test guarding a silent failure: without those keys, isolation still
*appears* to work in every manual check, and only breaks under concurrency or on
the first duplicate — long after anyone would connect it to a schema problem.
