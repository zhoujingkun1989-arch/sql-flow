package io.sqlflow.contracts.lineage.xml;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * {@code <column>} element in {@code <dlineage>} XML ({@code 03 §1.3}).
 *
 * <p>Wire invariant ({@code 00 §3.3 #1}): XML attribute MUST be serialized as {@code unqiueKey}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class XmlColumn {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "name")
    private String name;

    @JacksonXmlProperty(isAttribute = true, localName = "displayName")
    private String displayName;

    @JacksonXmlProperty(isAttribute = true, localName = "coordinate")
    private String coordinate;

    @JacksonXmlProperty(isAttribute = true, localName = "source")
    private String source;

    @JacksonXmlProperty(isAttribute = true, localName = "qualifiedTable")
    private String qualifiedTable;

    @JacksonXmlProperty(isAttribute = true, localName = "isFunction")
    private Boolean isFunction;

    @JacksonXmlProperty(isAttribute = true, localName = "dataType")
    private String dataType;

    @JacksonXmlProperty(isAttribute = true, localName = "primaryKey")
    private Boolean primaryKey;

    @JacksonXmlProperty(isAttribute = true, localName = "unqiueKey")
    @JsonProperty("unqiueKey")
    @JsonAlias({"uniqueKey", "unqiueKey"})
    private Boolean unqiueKey;

    @JacksonXmlProperty(isAttribute = true, localName = "indexKey")
    private Boolean indexKey;

    @JacksonXmlProperty(isAttribute = true, localName = "foreignKey")
    private Boolean foreignKey;

    @JacksonXmlProperty(isAttribute = true, localName = "nullable")
    private Boolean nullable;

    @JacksonXmlProperty(isAttribute = true, localName = "identity")
    private Boolean identity;

    @JacksonXmlProperty(isAttribute = true, localName = "computed")
    private Boolean computed;

    @JacksonXmlProperty(isAttribute = true, localName = "persisted")
    private Boolean persisted;

    @JacksonXmlProperty(isAttribute = true, localName = "computedDefinition")
    private String computedDefinition;

    @JacksonXmlProperty(isAttribute = true, localName = "defaultValue")
    private String defaultValue;

    @JacksonXmlProperty(isAttribute = true, localName = "defaultName")
    private String defaultName;

    @JacksonXmlProperty(isAttribute = true, localName = "star")
    private Boolean star;

    public XmlColumn() {
    }

    public XmlColumn(String id, String name) {
        this.id = id;
        this.name = name;
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

    public String getCoordinate() {
        return coordinate;
    }

    public void setCoordinate(String coordinate) {
        this.coordinate = coordinate;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public Boolean getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(Boolean primaryKey) {
        this.primaryKey = primaryKey;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "unqiueKey")
    @JsonProperty("unqiueKey")
    public Boolean getUnqiueKey() {
        return unqiueKey;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "unqiueKey")
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

    public Boolean getStar() {
        return star;
    }

    public void setStar(Boolean star) {
        this.star = star;
    }
}
