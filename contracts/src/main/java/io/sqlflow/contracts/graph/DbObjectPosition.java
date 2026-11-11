package io.sqlflow.contracts.graph;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 1-based SQL text highlight span item returned by {@code /sqlflow/generation/sqlflow/getSelectedDbObjectInfo}
 * ({@code 02 §5.2}, {@code 00 §3.3 #5}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DbObjectPosition {

    private String file;
    private String sql;
    private int index;
    private List<PositionPoint> positions;

    public DbObjectPosition() {
    }

    public DbObjectPosition(String file, String sql, int index, List<PositionPoint> positions) {
        this.file = file;
        this.sql = sql;
        this.index = index;
        this.positions = positions;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public List<PositionPoint> getPositions() {
        return positions;
    }

    public void setPositions(List<PositionPoint> positions) {
        this.positions = positions;
    }

    /**
     * Converts even-sized {@code [start_0, end_0, ...]} points into {@code [[[startLine, startCol], [endLine, endCol]], ...]}.
     */
    @JsonIgnore
    public List<List<List<Integer>>> toSpanPairs() {
        if (positions == null || positions.size() < 2) {
            return List.of();
        }
        List<List<List<Integer>>> spans = new ArrayList<>(positions.size() / 2);
        for (int i = 0; i + 1 < positions.size(); i += 2) {
            PositionPoint start = positions.get(i);
            PositionPoint end = positions.get(i + 1);
            spans.add(List.of(
                    List.of(start.getX(), start.getY()),
                    List.of(end.getX(), end.getY())));
        }
        return spans;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonDeserialize(using = PositionPointDeserializer.class)
    public static class PositionPoint {
        private int x;
        private int y;

        public PositionPoint() {
        }

        public PositionPoint(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PositionPoint that)) {
                return false;
            }
            return x == that.x && y == that.y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }

    public static class PositionPointDeserializer extends JsonDeserializer<PositionPoint> {
        @Override
        public PositionPoint deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.START_ARRAY) {
                JsonNode arr = p.getCodec().readTree(p);
                if (arr.size() < 2) {
                    throw new IOException("PositionPoint array must have at least [line, col]");
                }
                return new PositionPoint(arr.get(0).asInt(), arr.get(1).asInt());
            }
            if (token == JsonToken.START_OBJECT) {
                JsonNode obj = p.getCodec().readTree(p);
                int line = obj.has("x") ? obj.get("x").asInt() : obj.path("line").asInt(0);
                int col = obj.has("y") ? obj.get("y").asInt() : obj.path("column").asInt(0);
                return new PositionPoint(line, col);
            }
            return null;
        }
    }
}
