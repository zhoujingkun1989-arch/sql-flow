package io.sqlflow.contracts.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Job and lineage summary counts ({@code SummaryInfo}) returned by {@code /sqlflow/generation/sqlflow/summary}
 * and {@code /sqlflow/job/queryJobSummaryInfo} ({@code 02 §5.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SummaryInfo {

    private int process;
    private int database;
    private int schema;
    private int table;
    private int view;
    private int column;
    private int relationship;
    private int dblink;
    private Integer relationCount;
    private List<MostRelationTable> mostRelationTables;

    public SummaryInfo() {
    }

    public int getProcess() {
        return process;
    }

    public void setProcess(int process) {
        this.process = process;
    }

    public int getDatabase() {
        return database;
    }

    public void setDatabase(int database) {
        this.database = database;
    }

    public int getSchema() {
        return schema;
    }

    public void setSchema(int schema) {
        this.schema = schema;
    }

    public int getTable() {
        return table;
    }

    public void setTable(int table) {
        this.table = table;
    }

    public int getView() {
        return view;
    }

    public void setView(int view) {
        this.view = view;
    }

    public int getColumn() {
        return column;
    }

    public void setColumn(int column) {
        this.column = column;
    }

    public int getRelationship() {
        return relationship;
    }

    public void setRelationship(int relationship) {
        this.relationship = relationship;
    }

    public int getDblink() {
        return dblink;
    }

    public void setDblink(int dblink) {
        this.dblink = dblink;
    }

    public Integer getRelationCount() {
        return relationCount;
    }

    public void setRelationCount(Integer relationCount) {
        this.relationCount = relationCount;
    }

    public List<MostRelationTable> getMostRelationTables() {
        return mostRelationTables;
    }

    public void setMostRelationTables(List<MostRelationTable> mostRelationTables) {
        this.mostRelationTables = mostRelationTables;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MostRelationTable {
        private String database;
        private String schema;
        private String table;
        private int relationCount;

        public MostRelationTable() {
        }

        public MostRelationTable(String database, String schema, String table, int relationCount) {
            this.database = database;
            this.schema = schema;
            this.table = table;
            this.relationCount = relationCount;
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

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public int getRelationCount() {
            return relationCount;
        }

        public void setRelationCount(int relationCount) {
            this.relationCount = relationCount;
        }
    }
}
