package io.sqlflow.lineage.lexer;

import io.sqlflow.contracts.lineage.Coordinate;
import java.util.List;

/**
 * Immutable 1-based source coordinate span ({@code [startLine, startCol]} to {@code [endLine, endCol]})
 * paired with 0-based character offsets into the source SQL unit.
 */
public record SourceSpan(
        long startLine,
        long startCol,
        long endLine,
        long endCol,
        int startOffset,
        int endOffset
) {
    public static final SourceSpan SYNTHETIC = new SourceSpan(-1L, -1L, -1L, -1L, 0, 0);

    public static SourceSpan between(SourceSpan start, SourceSpan end) {
        if (start == null) {
            return end != null ? end : SYNTHETIC;
        }
        if (end == null || end == SYNTHETIC) {
            return start;
        }
        if (start == SYNTHETIC) {
            return end;
        }
        return new SourceSpan(
                start.startLine,
                start.startCol,
                end.endLine,
                end.endCol,
                start.startOffset,
                Math.max(start.endOffset, end.endOffset));
    }

    public boolean isValid() {
        return startLine >= 1 && startCol >= 1;
    }

    public List<Coordinate> toCoordinates(String hashCode) {
        if (!isValid()) {
            return List.of(Coordinate.synthetic(), Coordinate.synthetic());
        }
        return Coordinate.span(startLine, startCol, endLine, endCol, hashCode);
    }

    public Coordinate toSingleCoordinate(String hashCode) {
        if (!isValid()) {
            return Coordinate.synthetic();
        }
        return new Coordinate(startLine, startCol, endLine, endCol, hashCode);
    }

    public String extractText(String sourceSql) {
        if (sourceSql == null || startOffset < 0 || endOffset > sourceSql.length() || startOffset >= endOffset) {
            return "";
        }
        return sourceSql.substring(startOffset, endOffset);
    }
}
