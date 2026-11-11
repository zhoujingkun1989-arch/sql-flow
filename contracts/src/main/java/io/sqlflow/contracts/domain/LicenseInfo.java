package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Open config-backed license and edition metadata DTO ({@code 00 §6.1}, {@code 01 §3.3.1},
 * {@code 04 §12.4} clean-room replacement for legacy hardware-locked license verification while
 * preserving exact {@code GET /license} UI admin display keys).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LicenseInfo {

    public static final String KEY_SUPPORTED_DATABASES = "Supported databases";
    public static final String KEY_CUSTOMER_COMPANY_NAME = "Customer company name";
    public static final String KEY_CUSTOMER_CONTACT_NAME = "Customer contact name";
    public static final String KEY_CUSTOMER_CONTACT_EMAIL = "Customer contact email";
    public static final String KEY_PRODUCT_NAME = "Product name";
    public static final String KEY_PRODUCT_VERSION = "Product version";
    public static final String KEY_PURCHASE_DATE = "Purchase date";
    public static final String KEY_PRODUCT_LICENSE = "Product license";

    private String edition;

    @JsonAlias({KEY_PRODUCT_LICENSE, "productLicense"})
    private String productLicense;

    @JsonAlias({KEY_PRODUCT_NAME, "productName"})
    private String productName;

    @JsonAlias({KEY_PRODUCT_VERSION, "productVersion"})
    private String productVersion;

    @JsonAlias({KEY_PURCHASE_DATE, "purchaseDate"})
    private String purchaseDate;

    @JsonAlias({KEY_SUPPORTED_DATABASES, "supportedDatabases"})
    private String supportedDatabases;

    @JsonAlias({KEY_CUSTOMER_COMPANY_NAME, "companyName"})
    private String companyName;

    @JsonAlias({KEY_CUSTOMER_CONTACT_NAME, "contactName"})
    private String contactName;

    @JsonAlias({KEY_CUSTOMER_CONTACT_EMAIL, "contactEmail"})
    private String contactEmail;

    private String expiredDate;
    private Object maxUsers;
    private Boolean trialVersion;
    private Boolean valid;
    private String machineCode;
    private List<String> vendors;
    private Map<String, Boolean> features;

    public LicenseInfo() {
    }

    public static LicenseInfo openCleanRoomDefault() {
        LicenseInfo info = new LicenseInfo();
        info.setEdition("enterprise");
        info.setProductName("Gudu SQLFlow");
        info.setProductVersion("8.2.2.15");
        info.setPurchaseDate("2026-01-01");
        info.setSupportedDatabases("All 48 supported SQL dialects");
        info.setCompanyName("Open Clean-Room Deployment");
        info.setContactName("Administrator");
        info.setContactEmail("admin@localhost");
        info.setProductLicense("SQLFlow Clean-Room Open Edition");
        info.setExpiredDate("2099-12-31");
        info.setMaxUsers("unlimited");
        info.setTrialVersion(Boolean.FALSE);
        info.setValid(Boolean.TRUE);
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("regular.job", Boolean.TRUE);
        features.put("password.security.validate", Boolean.FALSE);
        info.setFeatures(features);
        return info;
    }

    /**
     * Converts this {@link LicenseInfo} into the exact spaced-key map payload returned inside
     * {@code ApiResponse<Map<String, Object>>} by {@code GET /license} ({@code 01 §3.3.1}).
     */
    public Map<String, Object> toDisplayMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        String resolvedDatabases = supportedDatabases != null
                ? supportedDatabases
                : (vendors != null && !vendors.isEmpty() ? String.join(", ", vendors) : "");
        map.put(KEY_SUPPORTED_DATABASES, resolvedDatabases);
        map.put(KEY_CUSTOMER_COMPANY_NAME, companyName != null ? companyName : "");
        map.put(KEY_CUSTOMER_CONTACT_NAME, contactName != null ? contactName : "");
        map.put(KEY_CUSTOMER_CONTACT_EMAIL, contactEmail != null ? contactEmail : "");
        map.put(KEY_PRODUCT_NAME, productName != null ? productName : "Gudu SQLFlow");
        map.put(KEY_PRODUCT_VERSION, productVersion != null ? productVersion : "8.2.2.15");
        map.put(KEY_PURCHASE_DATE, purchaseDate != null ? purchaseDate : "");
        map.put(KEY_PRODUCT_LICENSE, productLicense != null ? productLicense : "");
        return map;
    }

    public String getEdition() {
        return edition;
    }

    public void setEdition(String edition) {
        this.edition = edition;
    }

    public String getProductLicense() {
        return productLicense;
    }

    @JsonAlias({KEY_PRODUCT_LICENSE, "productLicense"})
    public void setProductLicense(String productLicense) {
        this.productLicense = productLicense;
    }

    public String getProductName() {
        return productName;
    }

    @JsonAlias({KEY_PRODUCT_NAME, "productName"})
    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductVersion() {
        return productVersion;
    }

    @JsonAlias({KEY_PRODUCT_VERSION, "productVersion"})
    public void setProductVersion(String productVersion) {
        this.productVersion = productVersion;
    }

    public String getPurchaseDate() {
        return purchaseDate;
    }

    @JsonAlias({KEY_PURCHASE_DATE, "purchaseDate"})
    public void setPurchaseDate(String purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    public String getSupportedDatabases() {
        return supportedDatabases;
    }

    @JsonAlias({KEY_SUPPORTED_DATABASES, "supportedDatabases"})
    public void setSupportedDatabases(String supportedDatabases) {
        this.supportedDatabases = supportedDatabases;
    }

    public String getCompanyName() {
        return companyName;
    }

    @JsonAlias({KEY_CUSTOMER_COMPANY_NAME, "companyName"})
    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getContactName() {
        return contactName;
    }

    @JsonAlias({KEY_CUSTOMER_CONTACT_NAME, "contactName"})
    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    @JsonAlias({KEY_CUSTOMER_CONTACT_EMAIL, "contactEmail"})
    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getExpiredDate() {
        return expiredDate;
    }

    public void setExpiredDate(String expiredDate) {
        this.expiredDate = expiredDate;
    }

    public Object getMaxUsers() {
        return maxUsers;
    }

    public void setMaxUsers(Object maxUsers) {
        this.maxUsers = maxUsers;
    }

    public Boolean getTrialVersion() {
        return trialVersion;
    }

    public void setTrialVersion(Boolean trialVersion) {
        this.trialVersion = trialVersion;
    }

    public Boolean getValid() {
        return valid;
    }

    public void setValid(Boolean valid) {
        this.valid = valid;
    }

    public String getMachineCode() {
        return machineCode;
    }

    public void setMachineCode(String machineCode) {
        this.machineCode = machineCode;
    }

    public List<String> getVendors() {
        return vendors;
    }

    public void setVendors(List<String> vendors) {
        this.vendors = vendors;
    }

    public Map<String, Boolean> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, Boolean> features) {
        this.features = features;
    }
}
