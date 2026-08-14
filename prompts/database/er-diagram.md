# ER diagram

The whole model on one screen. Column lists are trimmed to keys and the fields
that carry meaning — see [schema.md](./schema.md) for the full definitions.

> **Read `tenant` as the boundary, not just another table.** Every relationship
> below lives *inside* one tenant; nothing crosses. The `tenant_id` on child
> tables like `order_line` is deliberate denormalisation so the Hibernate filter
> applies uniformly — see [README.md](./README.md).

> **These arrows are database foreign keys, not Java object references.**
> Peer-review Phase 2 removed every `@ManyToOne`/`@OneToMany` from `com.pos.pojo`
> — a full retroactive sweep across all 9 mapped entities, not just new code. Every
> relationship below is still a real, database-enforced FK, same name and
> `ON DELETE` behavior as always (see
> [constraints-and-indexes.md](./constraints-and-indexes.md#foreign-keys) for the
> shadow-association mechanism that keeps it that way); reading the "far side" of
> one now goes through an explicit DAO call, never a lazy-loaded getter.

```mermaid
erDiagram
    TENANT ||--o{ APP_USER : "employs"
    TENANT ||--o{ PRODUCT : "owns"
    TENANT ||--o{ VARIANT : "owns"
    TENANT ||--o{ POS_ORDER : "owns"
    TENANT ||--o{ SALES_RETURN : "owns"
    TENANT ||--o{ TENANT_SEQUENCE : "numbers"

    PRODUCT ||--o{ VARIANT : "has"
    VARIANT ||--o{ ORDER_LINE : "sold as"
    VARIANT ||--o{ RETURN_LINE : "returned as"

    APP_USER ||--o{ POS_ORDER : "rings up"
    APP_USER ||--o{ SALES_RETURN : "processes"

    POS_ORDER ||--|{ ORDER_LINE : "contains"
    POS_ORDER ||--o{ SALES_RETURN : "refunded by"
    SALES_RETURN ||--|{ RETURN_LINE : "contains"

    TENANT {
        bigint id PK
        varchar name
        varchar code UK "globally unique - the login discriminator"
        varchar status "ACTIVE | SUSPENDED | PENDING_VERIFICATION"
        boolean is_platform "true for the one reserved row"
        datetime created_at
        varchar verification_token UK "C9, nullable, self-registration only"
        datetime verification_expires_at "C9, paired with the token"
    }

    APP_USER {
        bigint id PK
        bigint tenant_id FK "UK with username"
        varchar username "unique per tenant"
        varchar password_hash "BCrypt"
        varchar display_name
        varchar email "C9, nullable, never used for login"
        varchar role "SUPER_ADMIN | ADMIN | CASHIER"
        boolean is_active
    }

    PRODUCT {
        bigint id PK
        bigint tenant_id FK
        varchar name
        varchar brand
        varchar category
        varchar hsn_code
        decimal tax_rate_percent "GST slab"
        boolean is_active "soft delete"
    }

    VARIANT {
        bigint id PK
        bigint tenant_id FK "UK with sku, and with qr_code"
        bigint product_id FK
        varchar variant_label
        json attributes
        varchar sku "unique per tenant"
        varchar qr_code "POS-QR-{tenantId}-{seq}, unique per tenant"
        decimal mrp
        decimal selling_price "CHECK <= mrp"
        int stock_quantity
        varchar unit_of_measure
        boolean is_active
    }

    POS_ORDER {
        bigint id PK
        bigint tenant_id FK "UK with order_number"
        varchar order_number "ORD-YYYY-NNNN, per-tenant sequence"
        varchar status "DRAFT | HELD | COMPLETED | CANCELLED"
        decimal subtotal
        decimal total_tax
        decimal order_discount
        decimal round_off
        decimal grand_total
        varchar payment_method "embedded payment, single tender"
        decimal payment_amount
        decimal amount_tendered
        decimal change_due
        varchar payment_reference
        bigint cashier_id FK
        datetime created_at
    }

    ORDER_LINE {
        bigint id PK
        bigint tenant_id FK
        bigint order_id FK
        bigint variant_id FK
        varchar name "SNAPSHOT at sale time"
        varchar qr_code "SNAPSHOT"
        int quantity
        decimal unit_price "SNAPSHOT"
        decimal tax_rate_percent "SNAPSHOT"
        decimal line_discount
        decimal line_total
    }

    SALES_RETURN {
        bigint id PK
        bigint tenant_id FK "UK with return_number"
        varchar return_number "RET-YYYY-NNNN, per-tenant sequence"
        bigint original_order_id FK
        varchar original_order_number "SNAPSHOT"
        decimal refund_subtotal
        decimal refund_tax
        decimal round_off
        decimal refund_total
        varchar refund_method
        varchar reason
        bigint processed_by FK
        datetime created_at
    }

    RETURN_LINE {
        bigint id PK
        bigint tenant_id FK
        bigint return_id FK
        bigint variant_id FK
        varchar name "SNAPSHOT"
        int quantity
        decimal unit_price "SNAPSHOT from the original sale"
        decimal tax_rate_percent "SNAPSHOT"
        decimal line_refund
    }

    TENANT_SEQUENCE {
        bigint tenant_id PK "composite with kind"
        varchar kind PK "ORDER | RETURN | QR"
        bigint next_value
    }
```

## Reading the diagram

**`TENANT_SEQUENCE` is the one table with no business meaning.** It exists because
going per-tenant meant giving up `AUTO_INCREMENT` — which was a concurrency-safe
number generator the database gave us for free. One row per `(tenant, kind)`, taken
under a row lock in the same transaction as the insert it numbers.

**`ORDER_LINE` and `RETURN_LINE` carry snapshots, not just foreign keys.** The
`variant_id` says *what was sold*; the `name` / `unit_price` / `tax_rate_percent`
say *on what terms*. A later price change must not rewrite history, and a refund
reads these back verbatim (`requirements.md` §3). Don't "normalise" them away.

**`SALES_RETURN` hangs off `POS_ORDER`, and inherits its tenant.** A return always
belongs to its original order's tenant — that's the rule, and the frontend
expresses it by inheriting rather than re-reading the session.

**Payment is embedded in `POS_ORDER`, not a separate table.** v1 is a single
payment covering the full amount, with no split tender (`requirements.md` §3/§12).
If split tender ever arrives, this is the join to extract.
