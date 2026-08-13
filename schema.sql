
    create table app_user (
        id bigint not null auto_increment,
        is_active tinyint default true not null,
        created_at datetime(6) not null,
        display_name varchar(120) not null,
        email varchar(254),
        password_hash varchar(100) not null,
        role varchar(16) not null check (role in ('SUPER_ADMIN','ADMIN','CASHIER')),
        username varchar(64) not null,
        tenant_id bigint not null,
        primary key (id)
    ) engine=InnoDB;

    create table order_line (
        id bigint not null auto_increment,
        line_discount decimal(12,2) default 0 not null,
        line_total decimal(12,2) not null,
        name varchar(255) not null,
        qr_code varchar(64),
        quantity integer not null,
        tax_rate_percent decimal(5,2) not null,
        unit_price decimal(12,2) not null,
        order_id bigint not null,
        tenant_id bigint not null,
        variant_id bigint not null,
        primary key (id),
        constraint ck_order_line_quantity_positive check (quantity > 0)
    ) engine=InnoDB;

    create table pos_order (
        id bigint not null auto_increment,
        amount_tendered decimal(12,2),
        change_due decimal(12,2),
        created_at datetime(6) not null,
        grand_total decimal(12,2) not null,
        order_discount decimal(12,2) default 0 not null,
        order_number varchar(32) not null,
        payment_amount decimal(12,2),
        payment_method varchar(16) check (payment_method in ('CASH','CARD','UPI')),
        payment_reference varchar(64),
        round_off decimal(12,2) not null,
        status varchar(16) not null check (status in ('DRAFT','HELD','COMPLETED','CANCELLED')),
        subtotal decimal(12,2) not null,
        total_tax decimal(12,2) not null,
        cashier_id bigint not null,
        tenant_id bigint not null,
        primary key (id)
    ) engine=InnoDB;

    create table product (
        id bigint not null auto_increment,
        is_active tinyint default true not null,
        brand varchar(120),
        category varchar(80),
        created_at datetime(6) not null,
        description varchar(500),
        hsn_code varchar(16),
        name varchar(200) not null,
        tax_rate_percent decimal(5,2) not null,
        tenant_id bigint not null,
        primary key (id)
    ) engine=InnoDB;

    create table return_line (
        id bigint not null auto_increment,
        line_refund decimal(12,2) not null,
        name varchar(255) not null,
        quantity integer not null,
        tax_rate_percent decimal(5,2) not null,
        unit_price decimal(12,2) not null,
        return_id bigint not null,
        tenant_id bigint not null,
        variant_id bigint not null,
        primary key (id)
    ) engine=InnoDB;

    create table sales_return (
        id bigint not null auto_increment,
        created_at datetime(6) not null,
        original_order_number varchar(32) not null,
        reason varchar(500),
        refund_method varchar(16) not null check (refund_method in ('CASH','CARD','UPI')),
        refund_subtotal decimal(12,2) not null,
        refund_tax decimal(12,2) not null,
        refund_total decimal(12,2) not null,
        return_number varchar(32) not null,
        round_off decimal(12,2) not null,
        original_order_id bigint not null,
        processed_by bigint not null,
        tenant_id bigint not null,
        primary key (id)
    ) engine=InnoDB;

    create table tenant (
        id bigint not null auto_increment,
        code varchar(64) not null,
        created_at datetime(6) not null,
        name varchar(120) not null,
        is_platform tinyint default false not null,
        status varchar(32) not null check (status in ('ACTIVE','SUSPENDED','PENDING_VERIFICATION')),
        verification_expires_at datetime(6),
        verification_token varchar(64),
        primary key (id)
    ) engine=InnoDB;

    create table tenant_sequence (
        kind varchar(16) not null check (kind in ('ORDER','RETURN','QR')),
        next_value bigint default 1 not null,
        tenant_id bigint not null,
        primary key (kind, tenant_id)
    ) engine=InnoDB;

    create table variant (
        id bigint not null auto_increment,
        is_active tinyint default true not null,
        attributes json,
        mrp decimal(12,2) not null,
        qr_code varchar(64) not null,
        selling_price decimal(12,2) not null,
        sku varchar(64) not null,
        stock_quantity integer default 0 not null,
        unit_of_measure varchar(8) default 'EACH' not null check (unit_of_measure in ('EACH','KG','LITRE')),
        variant_label varchar(120) not null,
        product_id bigint not null,
        tenant_id bigint not null,
        primary key (id),
        constraint ck_variant_price_within_mrp check (selling_price <= mrp),
        constraint ck_variant_stock_not_negative check (stock_quantity >= 0)
    ) engine=InnoDB;

    create index idx_user_tenant 
       on app_user (tenant_id);

    create index idx_user_email 
       on app_user (email);

    alter table app_user 
       add constraint uk_user_tenant_username unique (tenant_id, username);

    create index idx_orderline_order 
       on order_line (order_id);

    create index idx_order_tenant_status 
       on pos_order (tenant_id, status);

    create index idx_order_tenant_cashier 
       on pos_order (tenant_id, cashier_id);

    create index idx_order_tenant_created 
       on pos_order (tenant_id, created_at);

    alter table pos_order 
       add constraint uk_order_tenant_number unique (tenant_id, order_number);

    create index idx_product_tenant 
       on product (tenant_id);

    create index idx_product_tenant_category 
       on product (tenant_id, category);

    create index idx_returnline_return 
       on return_line (return_id);

    create index idx_return_tenant_processor 
       on sales_return (tenant_id, processed_by);

    create index idx_return_order 
       on sales_return (original_order_id);

    create index idx_return_tenant_created 
       on sales_return (tenant_id, created_at);

    alter table sales_return 
       add constraint uk_return_tenant_number unique (tenant_id, return_number);

    alter table tenant 
       add constraint uk_tenant_code unique (code);

    alter table tenant 
       add constraint uk_tenant_verification_token unique (verification_token);

    create index idx_variant_tenant 
       on variant (tenant_id);

    create index idx_variant_product 
       on variant (product_id);

    alter table variant 
       add constraint uk_variant_tenant_sku unique (tenant_id, sku);

    alter table variant 
       add constraint uk_variant_tenant_qrcode unique (tenant_id, qr_code);

    alter table app_user 
       add constraint fk_app_user_tenant 
       foreign key (tenant_id) 
       references tenant (id);

    alter table order_line 
       add constraint fk_order_line_pos_order 
       foreign key (order_id) 
       references pos_order (id) 
       on delete cascade;

    alter table order_line 
       add constraint fk_order_line_tenant 
       foreign key (tenant_id) 
       references tenant (id);

    alter table order_line 
       add constraint fk_order_line_variant 
       foreign key (variant_id) 
       references variant (id);

    alter table pos_order 
       add constraint fk_pos_order_cashier 
       foreign key (cashier_id) 
       references app_user (id);

    alter table pos_order 
       add constraint fk_pos_order_tenant 
       foreign key (tenant_id) 
       references tenant (id);

    alter table product 
       add constraint fk_product_tenant 
       foreign key (tenant_id) 
       references tenant (id);

    alter table return_line 
       add constraint fk_return_line_sales_return 
       foreign key (return_id) 
       references sales_return (id) 
       on delete cascade;

    alter table return_line 
       add constraint fk_return_line_tenant 
       foreign key (tenant_id) 
       references tenant (id);

    alter table return_line 
       add constraint fk_return_line_variant 
       foreign key (variant_id) 
       references variant (id);

    alter table sales_return 
       add constraint fk_sales_return_pos_order 
       foreign key (original_order_id) 
       references pos_order (id);

    alter table sales_return 
       add constraint fk_sales_return_processed_by 
       foreign key (processed_by) 
       references app_user (id);

    alter table sales_return 
       add constraint fk_sales_return_tenant 
       foreign key (tenant_id) 
       references tenant (id);

    alter table tenant_sequence 
       add constraint fk_tenant_sequence_tenant 
       foreign key (tenant_id) 
       references tenant (id);

    alter table variant 
       add constraint fk_variant_product 
       foreign key (product_id) 
       references product (id);

    alter table variant 
       add constraint fk_variant_tenant 
       foreign key (tenant_id) 
       references tenant (id);
