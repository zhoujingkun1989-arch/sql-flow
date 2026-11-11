package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Product catalog item inside {@link ProductListEnvelope} matching {@code POST /product/products}
 * ({@code 02 §4.10.6 & §5.6}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "productId",
    "productName",
    "productPeriod",
    "recommend",
    "selected",
    "productPrice",
    "description"
})
public class ProductInfo {

    private Integer productId;
    private String productName;
    private String productPeriod;
    private Boolean recommend;
    private Boolean selected;
    private String productPrice;
    private String description;

    public ProductInfo() {
    }

    public ProductInfo(
            Integer productId,
            String productName,
            String productPeriod,
            Boolean recommend,
            Boolean selected,
            String productPrice,
            String description) {
        this.productId = productId;
        this.productName = productName;
        this.productPeriod = productPeriod;
        this.recommend = recommend;
        this.selected = selected;
        this.productPrice = productPrice;
        this.description = description;
    }

    public Integer getProductId() {
        return productId;
    }

    public void setProductId(Integer productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductPeriod() {
        return productPeriod;
    }

    public void setProductPeriod(String productPeriod) {
        this.productPeriod = productPeriod;
    }

    public Boolean getRecommend() {
        return recommend;
    }

    public void setRecommend(Boolean recommend) {
        this.recommend = recommend;
    }

    public Boolean getSelected() {
        return selected;
    }

    public void setSelected(Boolean selected) {
        this.selected = selected;
    }

    public String getProductPrice() {
        return productPrice;
    }

    public void setProductPrice(String productPrice) {
        this.productPrice = productPrice;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
