package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Column contract across {@code DataFlow}, ER diagrams, and metadata catalogs ({@code 02 §5.1}, {@code 03 §1.3.3, §1.5.2}).
 *
 * <p><strong>Wire Invariant #4 ({@code 00 §3.3})</strong>: The unique-key boolean MUST serialize on the wire
 * as {@code "unqiueKey"} (transposed {@code i} and {@code u}), while accepting both {@code "unqiueKey"}
 * and {@code "uniqueKey"} when deserializing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "id", "name", "displayName", "dataType", "comment", "coordinates", "source",
    "uiVisible", "primaryKey", "unqiueKey", "indexKey", "foreignKey",
    "nullable", "identity", "computed", "persisted", "computedDefinition",
    "defaultValue", "defaultName", "qualifiedTable", "isFunction", "star"
})
public class Column {

    private String id;
    private String name;
    private String displayName;
    private String dataType;
    private String comment;
    private List<Coordinate> coordinates;
    private String source;
    private Boolean uiVisible;
    private Boolean primaryKey;

    @JsonProperty("unqiueKey")
    @JsonAlias({"uniqueKey", "unqiueKey"})
    private Boolean unqiueKey;

    private Boolean indexKey;
    private Boolean foreignKey;
    private Boolean nullable;
    private Boolean identity;
    private Boolean computed;
    private Boolean persisted;
    private String computedDefinition;
    private String defaultValue;
    private String defaultName;
    private String qualifiedTable;
    private Boolean isFunction;
    private Boolean star;

    public Column() {
    }

    public Column(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public Column(String id, String name, String dataType) {
        this.id = id;
        this.name = name;
        this.dataType = dataType;
    }

    /**
     * Creates the synthetic {@code RelationRows} pseudo-column ({@code source = "system"}) ({@code 03 §3.1}).
     */
    public static Column relationRows(String id) {
        Column col = new Column(id, "RelationRows");
        col.setSource("system");
        return col;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public List<Coordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Coordinate> coordinates) {
        this.coordinates = coordinates;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Boolean getUiVisible() {
        return uiVisible;
    }

    public void setUiVisible(Boolean uiVisible) {
        this.uiVisible = uiVisible;
    }

    public Boolean getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(Boolean primaryKey) {
        this.primaryKey = primaryKey;
    }

    @JsonProperty("unqiueKey")
    public Boolean getUnqiueKey() {
        return unqiueKey;
    }

    @JsonProperty("unqiueKey")
    @JsonAlias({"uniqueKey", "unqiueKey"})
    public void setUnqiueKey(Boolean unqiueKey) {
        this.unqiueKey = unqiueKey;
    }

    @JsonIgnore
    public Boolean getUniqueKey() {
        return unqiueKey;
    }

    @JsonIgnore
    public void setUniqueKey(Boolean uniqueKey) {
        this.unqiueKey = uniqueKey;
    }

    public Boolean getIndexKey() {
        return indexKey;
    }

    public void setIndexKey(Boolean indexKey) {
        this.indexKey = indexKey;
    }

    public Boolean getForeignKey() {
        return foreignKey;
    }

    public void setForeignKey(Boolean foreignKey) {
        this.foreignKey = foreignKey;
    }

    public Boolean getNullable() {
        return nullable;
    }

    public void setNullable(Boolean nullable) {
        this.nullable = nullable;
    }

    public Boolean getIdentity() {
        return identity;
    }

    public void setIdentity(Boolean identity) {
        this.identity = identity;
    }

    public Boolean getComputed() {
        return computed;
    }

    public void setComputed(Boolean computed) {
        this.computed = computed;
    }

    public Boolean getPersisted() {
        return persisted;
    }

    public void setPersisted(Boolean persisted) {
        this.persisted = persisted;
    }

    public String getComputedDefinition() {
        return computedDefinition;
    }

    public void setComputedDefinition(String computedDefinition) {
        this.computedDefinition = computedDefinition;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getDefaultName() {
        return defaultName;
    }

    public void setDefaultName(String defaultName) {
        this.defaultName = defaultName;
    }

    public String getQualifiedTable() {
        return qualifiedTable;
    }

    public void setQualifiedTable(String qualifiedTable) {
        this.qualifiedTable = qualifiedTable;
    }

    public Boolean getIsFunction() {
        return isFunction;
    }

    public void setIsFunction(Boolean isFunction) {
        this.isFunction = isFunction;
    }

    public Boolean getStar() {
        return star;
    }

    public void setStar(Boolean star) {
        this.star = star;
    }
}
