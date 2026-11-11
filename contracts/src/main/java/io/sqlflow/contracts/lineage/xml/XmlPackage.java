package io.sqlflow.contracts.lineage.xml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.util.List;

/**
 * {@code <package>} element in {@code <dlineage>} XML ({@code 03 §1.3}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class XmlPackage {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "server")
    private String server;

    @JacksonXmlProperty(isAttribute = true, localName = "database")
    private String database;

    @JacksonXmlProperty(isAttribute = true, localName = "schema")
    private String schema;

    @JacksonXmlProperty(isAttribute = true, localName = "name")
    private String name;

    @JacksonXmlProperty(isAttribute = true, localName = "type")
    private String type;

    @JacksonXmlProperty(isAttribute = true, localName = "coordinate")
    private String coordinate;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "procedure")
    private List<XmlProcedure> procedures;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "variable")
    private List<XmlTable> variables;

    public XmlPackage() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCoordinate() {
        return coordinate;
    }

    public void setCoordinate(String coordinate) {
        this.coordinate = coordinate;
    }

    public List<XmlProcedure> getProcedures() {
        return procedures;
    }

    public void setProcedures(List<XmlProcedure> procedures) {
        this.procedures = procedures;
    }

    public List<XmlTable> getVariables() {
        return variables;
    }

    public void setVariables(List<XmlTable> variables) {
        this.variables = variables;
    }
}
