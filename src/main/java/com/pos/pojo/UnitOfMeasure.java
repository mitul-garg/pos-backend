package com.pos.pojo;

/**
 * How a variant is sold. {@code EACH} is the only one exercised in v1; weight-based
 * selling is future scope, and the values exist now so that adding it later is not a
 * schema change (requirements.md section 3).
 */
public enum UnitOfMeasure {
    EACH,
    KG,
    LITRE
}
