package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unwrapped {@code GET /sqlflow/version} response payload ({@code 00 §3.3 #4}, {@code 02 §5.6}).
 *
 * <p>Wire invariant: {@code /version} is NOT wrapped in {@code {code, data}} — its root JSON object contains
 * ONLY {@code {"version": { ... }}}, including {@code support["regular.job"]} and dotted license/customer keys.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"version"})
public class VersionEnvelope {

    private VersionDetails version;

    public VersionEnvelope() {
    }

    public VersionEnvelope(VersionDetails version) {
        this.version = version;
    }

    public static VersionEnvelope defaultCleanRoom(String engineVersion, String backendVersion, String releaseDate) {
        VersionDetails details = new VersionDetails();
        details.setGsp(new ComponentVersion(engineVersion, releaseDate));
        details.setBackend(new ComponentVersion(backendVersion, releaseDate));
        Map<String, Object> support = new LinkedHashMap<>();
        support.put("password.security.validate", Boolean.FALSE);
        support.put("regular.job", Boolean.TRUE);
        details.setSupport(support);
        return new VersionEnvelope(details);
    }

    public VersionDetails getVersion() {
        return version;
    }

    public void setVersion(VersionDetails version) {
        this.version = version;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({
        "gsp",
        "backend",
        "ui",
        "support",
        "machine.code",
        "customer.company.name",
        "customer.contact.name",
        "customer.contact.email",
        "license.expired",
        "license.max.users"
    })
    public static class VersionDetails {
        private ComponentVersion gsp;
        private ComponentVersion backend;
        private ComponentVersion ui;
        private Map<String, Object> support;

        @JsonProperty("machine.code")
        private String machineCode;

        @JsonProperty("customer.company.name")
        private String customerCompanyName;

        @JsonProperty("customer.contact.name")
        private String customerContactName;

        @JsonProperty("customer.contact.email")
        private String customerContactEmail;

        @JsonProperty("license.expired")
        private String licenseExpired;

        @JsonProperty("license.max.users")
        private Object licenseMaxUsers;

        public VersionDetails() {
        }

        public ComponentVersion getGsp() {
            return gsp;
        }

        public void setGsp(ComponentVersion gsp) {
            this.gsp = gsp;
        }

        public ComponentVersion getBackend() {
            return backend;
        }

        public void setBackend(ComponentVersion backend) {
            this.backend = backend;
        }

        public ComponentVersion getUi() {
            return ui;
        }

        public void setUi(ComponentVersion ui) {
            this.ui = ui;
        }

        public Map<String, Object> getSupport() {
            return support;
        }

        public void setSupport(Map<String, Object> support) {
            this.support = support;
        }

        @JsonProperty("machine.code")
        public String getMachineCode() {
            return machineCode;
        }

        @JsonProperty("machine.code")
        public void setMachineCode(String machineCode) {
            this.machineCode = machineCode;
        }

        @JsonProperty("customer.company.name")
        public String getCustomerCompanyName() {
            return customerCompanyName;
        }

        @JsonProperty("customer.company.name")
        public void setCustomerCompanyName(String customerCompanyName) {
            this.customerCompanyName = customerCompanyName;
        }

        @JsonProperty("customer.contact.name")
        public String getCustomerContactName() {
            return customerContactName;
        }

        @JsonProperty("customer.contact.name")
        public void setCustomerContactName(String customerContactName) {
            this.customerContactName = customerContactName;
        }

        @JsonProperty("customer.contact.email")
        public String getCustomerContactEmail() {
            return customerContactEmail;
        }

        @JsonProperty("customer.contact.email")
        public void setCustomerContactEmail(String customerContactEmail) {
            this.customerContactEmail = customerContactEmail;
        }

        @JsonProperty("license.expired")
        public String getLicenseExpired() {
            return licenseExpired;
        }

        @JsonProperty("license.expired")
        public void setLicenseExpired(String licenseExpired) {
            this.licenseExpired = licenseExpired;
        }

        @JsonProperty("license.max.users")
        public Object getLicenseMaxUsers() {
            return licenseMaxUsers;
        }

        @JsonProperty("license.max.users")
        public void setLicenseMaxUsers(Object licenseMaxUsers) {
            this.licenseMaxUsers = licenseMaxUsers;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"version", "release.date"})
    public static class ComponentVersion {
        private String version;

        @JsonProperty("release.date")
        private String releaseDate;

        public ComponentVersion() {
        }

        public ComponentVersion(String version, String releaseDate) {
            this.version = version;
            this.releaseDate = releaseDate;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        @JsonProperty("release.date")
        public String getReleaseDate() {
            return releaseDate;
        }

        @JsonProperty("release.date")
        public void setReleaseDate(String releaseDate) {
            this.releaseDate = releaseDate;
        }
    }
}
