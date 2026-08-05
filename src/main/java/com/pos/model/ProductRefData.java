package com.pos.model;

/**
 * The parent product, as much of it as a variant needs to carry — {@code {id, name,
 * brand}}, exactly the object {@code variantService.withProduct()} nests in the mock.
 *
 * <p>A separate DTO rather than a nested class so {@code JsonIdTest.everyIdFieldIsAnnotated}
 * treats it like any other: its {@code id} is a database id and has to reach the wire as a
 * string, or the frontend's {@code ===} against a {@code useParams} value silently never
 * matches.
 */
public class ProductRefData {

    @JsonId
    private final Long id;

    private final String name;
    private final String brand;

    public ProductRefData(Long id, String name, String brand) {
        this.id = id;
        this.name = name;
        this.brand = brand;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }
}
