# C2 — Persistence and the data model

**Status: done.** Hibernate + MySQL wiring, all nine entities, a committed
`schema.sql` that cannot drift, `SchemaConstraintsIT`, and ids-as-JSON-strings.
`mvn test` runs 44 tests, **21 of which now need a database** (`pos_test`).

Corresponds to `backend-plan.md` C2. The schema itself is documented in
[database/](./database/) — this file is the *how* and the *why*, that folder is
the *what*.

## Key classes

- `com.pos.config.PersistenceConfig` — `DataSource` (HikariCP), `EntityManagerFactory`,
  `JpaTransactionManager`, Hibernate settings. A **root**-context config.
- `com.pos.config.AppProperties` — typed access to `application.properties`, so nothing
  outside `config/` reads raw configuration.
- `com.pos.pojo.*` — nine entities and six enums. `Tenant` is the boundary; everything
  else hangs off it.
- `com.pos.model.JsonId` — the Jackson meta-annotation that makes an id serialize as a
  string.

## Decisions & gotchas

### Do not set `hibernate.dialect` — it drops the check constraints

The one that cost real time. Hibernate resolves **both** the dialect and the server
version from JDBC metadata. Naming the dialect class without also naming a version pins
it to `MySQLDialect`'s *minimum supported* version, 5.7 — and `MySQLDialect` emits
`CHECK` constraints only from **8.0.16**. So the "explicit, safer-looking" line

```java
properties.put(AvailableSettings.DIALECT, "org.hibernate.dialect.MySQLDialect");  // DON'T
```

silently deletes every check constraint in
[constraints-and-indexes.md](./database/constraints-and-indexes.md), with no error
anywhere. Autodetection is the correct choice here, not the lazy one.

`SchemaSqlTest` generates DDL **offline**, where there is no connection to autodetect
from, so it has to pin the version by hand — it constructs a `MySQLDialect` *instance*
at 8.0.16, and `emitsCheckConstraints()` is the guard that the pin still works.

### Enums are `VARCHAR` because Hibernate 6 would otherwise use MySQL's `ENUM`

`@Enumerated(EnumType.STRING)` alone produces `role enum('ADMIN','CASHIER','SUPER_ADMIN')`
on MySQL under Hibernate 6. That is the one thing
[database/README.md](./database/README.md) rules out by name: adding a value to a MySQL
`ENUM` is a table *alter*, and `hbm2ddl.auto=update` does not perform alters.

There is **no global setting** for this in Hibernate 6.6, so every enum field carries
`@JdbcTypeCode(SqlTypes.VARCHAR)` individually. `SchemaSqlTest.storesEnumsAsVarchar`
fails the build if one is forgotten.

**Know what this does and does not buy you.** Hibernate still emits a check constraint
listing the values (`check (role in ('SUPER_ADMIN','ADMIN','CASHIER'))`), so extending an
enum *still* needs a schema change — the win is that the column is a portable `varchar`
that `validate` reports usefully, and that the constraint is a named check you can drop
and re-add rather than a column type you must alter. Under the drop-and-recreate deploy
strategy neither costs anything in production; locally, adding an enum value means
recreating the table rather than relying on `update`.

### Booleans

Hibernate emits `bit` on MySQL by default; `BOOLEAN` in MySQL is an alias for
`TINYINT(1)`, which is what the database docs describe. One global setting
(`hibernate.type.preferred_boolean_jdbc_type=TINYINT`) fixes it, and
`SchemaSqlTest.storesBooleansAsTinyint` holds it.

### The tenant is a `@ManyToOne`, not a `Long`

Hibernate cannot emit a foreign key for a bare scalar column — only `@JoinColumn` on an
association does that. Since the FKs are meant to be real and database-enforced, the
tenant is modelled as an association on every entity, including the two line tables.

Nothing ever navigates `product.getTenant()`: scoping is enforced on the *column* by the
C4 filter. So every association here is **`LAZY`**, and `EAGER` would turn each list
query into an N+1 for a field nobody reads. That is safe precisely because controllers
return DTOs mapped in-transaction — the two decisions are a pair, and
`CONVENTIONS.md`'s second reason for the DTO rule is exactly this.

### `SchemaExport` no longer exists

The Hibernate 5 class every tutorial uses to write DDL to a file was removed in
Hibernate 6. The supported route is `SchemaManagementToolCoordinator.process(...)` driven
by the Jakarta `jakarta.persistence.schema-generation.scripts.*` settings — see
`SchemaSqlTest.generateDdl`.

### Credentials never enter the repository

`application.properties` is tracked and defaults the password to **empty**;
`application-local.properties` is gitignored, loaded second by a `@PropertySource`, and
holds the real one. Deployed environments set `POS_DB_*` instead and ship no local file.
An empty password fails at connect with an authentication error, which is the right kind
of loud.

### The database creates itself

`createDatabaseIfNotExist=true` on the local and test JDBC URLs, so a fresh clone needs
no manual `CREATE DATABASE`. It is deliberately **absent from deployed configuration**:
there, a typo'd database name would create an empty schema and boot against it instead of
failing, and it would force the app user to hold `CREATE` against least privilege.

### `tenant_sequence`'s primary key is `(kind, tenant_id)`, not `(tenant_id, kind)`

Hibernate orders `@Embeddable` attributes alphabetically, and there is no annotation to
override it. The key is on the right two columns and every access is by the *full* key
(`SELECT ... FOR UPDATE` on one tenant's one counter), so no query wants a
`tenant_id`-only prefix of it. Noted because the database docs describe the other order.

## Tenant scoping

**Nothing here enforces it yet — that is C4.** What C2 contributes is the *shape* that
makes enforcement possible:

- Every tenant-owned table has a non-null `tenant_id`, **including `order_line` and
  `return_line`**, which are reachable only through their parent. That mild
  denormalisation is deliberate: it lets the Hibernate filter apply uniformly to every
  entity, so no table depends on a *join* being scoped correctly to stay isolated.
- Every uniqueness rule is `(tenant_id, ...)` rather than global, except `tenant.code`.
  `SchemaConstraintsIT.scopesUniquenessToTheTenant` asserts that as a rule, not as six
  examples.

Until C4 lands, **any query written against these entities is unscoped.** Don't add a DAO
before the filter exists.

## Tests

| Suite | Needs a DB | Proves |
|---|---|---|
| `SchemaSqlTest` | no | `schema.sql` matches the entities; checks, `varchar` enums, `tinyint` booleans and snake_case columns all survive generation |
| `PersistenceConfigIT` | yes | Properties resolve, Hikari pools, MySQL is reachable, and tests point at `pos_test` rather than `pos_dev` |
| `SchemaConstraintsIT` | yes | All nine tables exist, and the six unique keys and three checks exist **in MySQL**, on the right tables, covering the right columns in order |
| `JsonIdTest` | no | Ids serialize as strings, non-ids don't, nulls stay null, and no `com.pos.model` id field is missing `@JsonId` |

`SchemaConstraintsIT` was **mutation-checked**, not merely observed to pass: removing
`uk_variant_tenant_sku` from `Variant` failed exactly the covering case and nothing else,
and renaming `return_line`'s table failed only `createsEveryTable`.

`createsEveryTable` exists to separate two failures the constraint assertions otherwise
conflate — an entity Hibernate never scanned versus a constraint never declared. Without
it the first reads as *"`uk_variant_tenant_sku` does not exist"*, which points at the
constraint rather than at the missing table. See `BUGS.md` smell C4 for how it came about.

Deliberately untested: reading and writing rows. There is no DAO or service yet, and a
persistence test with no persistence logic to exercise would only assert that Hibernate
works.

## Extension points

- **Adding an entity** — put it in `com.pos.pojo` (`PersistenceConfig` scans the package,
  and `SchemaSqlTest` finds it by scanning too, so neither needs editing). Declare every
  index and unique key explicitly, add `@JdbcTypeCode(SqlTypes.VARCHAR)` to any enum
  field, update [database/](./database/), and regenerate `schema.sql`.
- **Regenerating `schema.sql`** — `mvn test -Dpos.schema.write=true`, then commit it with
  the entity change. The build fails on drift, so this is not optional.
- **A new isolation-critical constraint** — add it to
  `SchemaConstraintsIT.expectedUniqueKeys()` at the same time. `validate` will never tell
  you it went missing.
- **Changing the minimum MySQL version** — `SchemaSqlTest.MINIMUM_MYSQL`, and check
  whether the checks still generate.

## Related

- [CONVENTIONS.md](./CONVENTIONS.md) — layers, DTO rule, transactions
- [database/](./database/) — the schema itself, and why each constraint exists
- [c1-skeleton.md](./c1-skeleton.md) — the two Spring contexts, which is why
  `PersistenceConfig` is a root-context bean
