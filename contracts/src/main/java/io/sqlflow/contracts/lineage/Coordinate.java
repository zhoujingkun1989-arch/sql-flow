package io.sqlflow.contracts.lineage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 1-based SQL token coordinate ({@code x} = 1-based line, {@code y} = 1-based column, optional {@code hashCode})
 * supporting JSON object format ({@code {"x": 12, "y": 8, "hashCode": "0"}}), 2-tuple / 3-tuple array format
 * ({@code [12, 8]} / {@code [12, 8, "0"]}), {@code [[startLine, startCol], [endLine, endCol]]} spans, and
 * {@code <dlineage>} XML bracketed strings ({@code [12,8,0],[12,19,0]}) ({@code 00 §3.3 #5}, {@code 03 §1.2}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"x", "y", "x1", "y1", "hashCode"})
@JsonDeserialize(using = Coordinate.CoordinateDeserializer.class)
public class Coordinate {

    private long x;
    private long y;
    private Long x1;
    private Long y1;
    private String hashCode;

    public Coordinate() {
    }

    public Coordinate(long x, long y) {
        this(x, y, null);
    }

    public Coordinate(long x, long y, String hashCode) {
        this.x = x;
        this.y = y;
        this.hashCode = hashCode;
    }

    public Coordinate(long x, long y, Long x1, Long y1, String hashCode) {
        this.x = x;
        this.y = y;
        this.x1 = x1;
        this.y1 = y1;
        this.hashCode = hashCode;
    }

    public static Coordinate of(long line, long column) {
        return new Coordinate(line, column, null);
    }

    public static Coordinate of(long line, long column, String hashCode) {
        return new Coordinate(line, column, hashCode);
    }

    /**
     * Synthetic sentinel coordinate ({@code x = -1, y = -1, hashCode = "0"}) used for metadata-only objects.
     */
    public static Coordinate synthetic() {
        return new Coordinate(-1L, -1L, "0");
    }

    /**
     * Builds a 2-element {@code [start, end]} coordinate pair for a single 1-based SQL token span.
     */
    public static List<Coordinate> span(long startLine, long startCol, long endLine, long endCol, String hashCode) {
        return List.of(
                new Coordinate(startLine, startCol, hashCode),
                new Coordinate(endLine, endCol, hashCode));
    }

    /**
     * Converts a flat coordinate list {@code [start_0, end_0, ...]} into {@code [[line, col], [line, col], ...]} 2-tuples.
     */
    public static List<List<Long>> toLineColTuples(List<Coordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return List.of();
        }
        List<List<Long>> tuples = new ArrayList<>(coordinates.size());
        for (Coordinate c : coordinates) {
            if (c != null) {
                tuples.add(List.of(c.getX(), c.getY()));
            }
        }
        return tuples;
    }

    /**
     * Converts a coordinate list into 3-tuples {@code [line, col, hashCode]} (or 2-tuples when {@code hashCode == null}).
     */
    public static List<List<Object>> toTuplesWithHash(List<Coordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return List.of();
        }
        List<List<Object>> tuples = new ArrayList<>(coordinates.size());
        for (Coordinate c : coordinates) {
            if (c != null) {
                if (c.getHashCode() != null) {
                    tuples.add(List.of(c.getX(), c.getY(), c.getHashCode()));
                } else {
                    tuples.add(List.of(c.getX(), c.getY()));
                }
            }
        }
        return tuples;
    }

    /**
     * Parses XML {@code coordinate} attribute strings ({@code PositionUtil} rules in {@code 03 §1.2.1}),
     * e.g. {@code "[12,8,0],[12,19,0]"} or {@code "[12,8],[12,19]"}.
     */
    public static List<Coordinate> parseXmlCoordinate(String xmlCoordinate) {
        if (xmlCoordinate == null || xmlCoordinate.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = xmlCoordinate.trim();
        int firstOpen = trimmed.indexOf('[');
        if (firstOpen < 0) {
            firstOpen = trimmed.indexOf('(');
        }
        int firstClose = trimmed.indexOf(']');
        if (firstClose < 0) {
            firstClose = trimmed.indexOf(')');
        }
        if (firstOpen < 0 || firstClose <= firstOpen) {
            return Collections.emptyList();
        }
        String firstTuple = trimmed.substring(firstOpen + 1, firstClose);
        int arity = firstTuple.split(",", -1).length;
        if (arity != 2 && arity != 3) {
            return Collections.emptyList();
        }
        String stripped = trimmed
                .replace("[", "")
                .replace("]", "")
                .replace("(", "")
                .replace(")", "");
        String[] tokens = stripped.split(",", -1);
        if (tokens.length % (2 * arity) != 0) {
            return Collections.emptyList();
        }
        int pointCount = tokens.length / arity;
        List<Coordinate> result = new ArrayList<>(pointCount);
        for (int p = 0; p < pointCount; p++) {
            int base = p * arity;
            long line = Long.parseLong(tokens[base].trim());
            long col = Long.parseLong(tokens[base + 1].trim());
            String hash = arity == 3 ? tokens[base + 2].trim() : null;
            result.add(new Coordinate(line, col, hash));
        }
        return result;
    }

    /**
     * Formats a list of {@link Coordinate} points into the canonical {@code <dlineage>} XML attribute format.
     */
    public static String formatXmlCoordinate(List<Coordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return null;
        }
        boolean includeHash = coordinates.stream().anyMatch(c -> c != null && c.getHashCode() != null);
        StringBuilder sb = new StringBuilder(coordinates.size() * 14);
        for (Coordinate c : coordinates) {
            if (c == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append('[').append(c.getX()).append(',').append(c.getY());
            if (includeHash) {
                sb.append(',').append(c.getHashCode() != null ? c.getHashCode() : "0");
            }
            sb.append(']');
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public long getX() {
        return x;
    }

    public void setX(long x) {
        this.x = x;
    }

    public long getY() {
        return y;
    }

    public void setY(long y) {
        this.y = y;
    }

    public Long getX1() {
        return x1;
    }

    public void setX1(Long x1) {
        this.x1 = x1;
    }

    public Long getY1() {
        return y1;
    }

    public void setY1(Long y1) {
        this.y1 = y1;
    }

    public String getHashCode() {
        return hashCode;
    }

    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }

    @JsonIgnore
    public long getLine() {
        return x;
    }

    @JsonIgnore
    public long getColumn() {
        return y;
    }

    @JsonIgnore
    public boolean isOneBasedValid() {
        return x >= 1 && y >= 1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Coordinate that)) {
            return false;
        }
        return x == that.x
                && y == that.y
                && Objects.equals(x1, that.x1)
                && Objects.equals(y1, that.y1)
                && Objects.equals(hashCode, that.hashCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, x1, y1, hashCode);
    }

    @Override
    public String toString() {
        return hashCode != null
                ? "[" + x + "," + y + "," + hashCode + "]"
                : "[" + x + "," + y + "]";
    }

    /**
     * Polymorphic deserializer accepting both {@code {"x": 12, "y": 8, "hashCode": "0"}} objects
     * (with optional {@code x1}/{@code y1}) and {@code [12, 8]} / {@code [12, 8, "0"]} array tuples.
     */
    public static class CoordinateDeserializer extends JsonDeserializer<Coordinate> {
        @Override
        public Coordinate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.START_ARRAY) {
                JsonNode arrayNode = p.getCodec().readTree(p);
                if (arrayNode.size() < 2) {
                    throw new IOException("Coordinate tuple array must contain at least [line, col]");
                }
                long line = arrayNode.get(0).asLong();
                long col = arrayNode.get(1).asLong();
                String hash = arrayNode.size() >= 3 && !arrayNode.get(2).isNull()
                        ? arrayNode.get(2).asText()
                        : null;
                return new Coordinate(line, col, hash);
            }
            if (token == JsonToken.START_OBJECT) {
                JsonNode obj = p.getCodec().readTree(p);
                long line = obj.has("x") ? obj.get("x").asLong() : obj.path("line").asLong(0L);
                long col = obj.has("y") ? obj.get("y").asLong() : obj.path("column").asLong(0L);
                Long endLine = obj.hasNonNull("x1") ? obj.get("x1").asLong() : null;
                Long endCol = obj.hasNonNull("y1") ? obj.get("y1").asLong() : null;
                String hash = obj.hasNonNull("hashCode") ? obj.get("hashCode").asText() : null;
                return new Coordinate(line, col, endLine, endCol, hash);
            }
            if (token == JsonToken.VALUE_STRING) {
                List<Coordinate> parsed = parseXmlCoordinate(p.getText());
                return parsed.isEmpty() ? null : parsed.get(0);
            }
            return null;
        }
    }
}
