package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Non-standard response envelope for {@code GET /sqlflow/user/product/list} ({@code 00 §3.3 #17}, {@code 02 §5.6}).
 *
 * <p>Wire invariant: exposes top-level {@code products} array alongside {@code code = 200} (NO {@code data} key).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"code", "products", "error"})
public class ProductListEnvelope {

    private int code = 200;
    private List<ProductInfo> products;
    private String error;

    public ProductListEnvelope() {
    }

    public ProductListEnvelope(List<ProductInfo> products) {
        this.code = 200;
        this.products = products;
    }

    public static ProductListEnvelope ok(List<ProductInfo> products) {
        return new ProductListEnvelope(products);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public List<ProductInfo> getProducts() {
        return products;
    }

    public void setProducts(List<ProductInfo> products) {
        this.products = products;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
