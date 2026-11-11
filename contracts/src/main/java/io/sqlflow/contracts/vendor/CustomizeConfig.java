package io.sqlflow.contracts.vendor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Operator customization schema corresponding to {@code conf/customize.yaml} ({@code CustomizeConfig}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomizeConfig {

    @JsonProperty("file_path_database")
    private String filePathDatabase;

    @JsonProperty("file_path_schema")
    private String filePathSchema;

    @JsonProperty("decrypt_replace_password")
    private DecryptReplacePassword decryptReplacePassword;

    @JsonProperty("case_sensitive")
    private CaseSensitiveRules caseSensitive;

    public CustomizeConfig() {
    }

    public String getFilePathDatabase() {
        return filePathDatabase;
    }

    public void setFilePathDatabase(String filePathDatabase) {
        this.filePathDatabase = filePathDatabase;
    }

    public String getFilePathSchema() {
        return filePathSchema;
    }

    public void setFilePathSchema(String filePathSchema) {
        this.filePathSchema = filePathSchema;
    }

    public DecryptReplacePassword getDecryptReplacePassword() {
        return decryptReplacePassword;
    }

    public void setDecryptReplacePassword(DecryptReplacePassword decryptReplacePassword) {
        this.decryptReplacePassword = decryptReplacePassword;
    }

    public CaseSensitiveRules getCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(CaseSensitiveRules caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DecryptReplacePassword {
        private Boolean enable;
        private List<FunctionArgRule> function = new ArrayList<>();

        public DecryptReplacePassword() {
        }

        public Boolean getEnable() {
            return enable;
        }

        public void setEnable(Boolean enable) {
            this.enable = enable;
        }

        public List<FunctionArgRule> getFunction() {
            return function;
        }

        public void setFunction(List<FunctionArgRule> function) {
            this.function = function;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionArgRule {
        private String name;
        private Integer index;

        public FunctionArgRule() {
        }

        public FunctionArgRule(String name, Integer index) {
            this.name = name;
            this.index = index;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getIndex() {
            return index;
        }

        public void setIndex(Integer index) {
            this.index = index;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CaseSensitiveRules {
        private List<VendorCaseSensitive> defaults = new ArrayList<>();
        private List<VendorCaseSensitive> catalogs = new ArrayList<>();
        private List<VendorCaseSensitive> tables = new ArrayList<>();
        private List<VendorCaseSensitive> functions = new ArrayList<>();
        private List<VendorCaseSensitive> columns = new ArrayList<>();

        public CaseSensitiveRules() {
        }

        public List<VendorCaseSensitive> getDefaults() {
            return defaults;
        }

        public void setDefaults(List<VendorCaseSensitive> defaults) {
            this.defaults = defaults;
        }

        public List<VendorCaseSensitive> getCatalogs() {
            return catalogs;
        }

        public void setCatalogs(List<VendorCaseSensitive> catalogs) {
            this.catalogs = catalogs;
        }

        public List<VendorCaseSensitive> getTables() {
            return tables;
        }

        public void setTables(List<VendorCaseSensitive> tables) {
            this.tables = tables;
        }

        public List<VendorCaseSensitive> getFunctions() {
            return functions;
        }

        public void setFunctions(List<VendorCaseSensitive> functions) {
            this.functions = functions;
        }

        public List<VendorCaseSensitive> getColumns() {
            return columns;
        }

        public void setColumns(List<VendorCaseSensitive> columns) {
            this.columns = columns;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VendorCaseSensitive {
        private String vendor;
        private Boolean value;

        public VendorCaseSensitive() {
        }

        public VendorCaseSensitive(String vendor, Boolean value) {
            this.vendor = vendor;
            this.value = value;
        }

        public String getVendor() {
            return vendor;
        }

        public void setVendor(String vendor) {
            this.vendor = vendor;
        }

        public Boolean getValue() {
            return value;
        }

        public void setValue(Boolean value) {
            this.value = value;
        }
    }
}
