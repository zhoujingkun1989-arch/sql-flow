package io.sqlflow.graph.layout;

import io.sqlflow.contracts.graph.SqlflowJSON.PortPoint;
import io.sqlflow.contracts.lineage.Orientation;
import java.util.List;

/**
 * Computes exact cubic Bézier port anchors and horizontal/vertical tangent control points
 * ({@code 03 §4.4.3}) for forward edges (4 control points: {@code M p0 C p1, p2, p3}) and
 * backward/same-rank/self-loop edges (7 control points: {@code M p0 C p1, p2, p3 C p4, p5, p6}).
 */
public final class BezierEdgeRouter {

    public record AnchorBox(
            double boxX,
            double boxY,
            double boxWidth,
            double boxHeight,
            double portMinX,
            double portMaxX,
            double portMidX,
            double portMinY,
            double portMaxY,
            double portMidY) {
    }

    private BezierEdgeRouter() {
    }

    public static List<PortPoint> computeControlPoints(
            AnchorBox source,
            AnchorBox target,
            Orientation orientation) {
        Orientation dir = orientation != null ? orientation : Orientation.LEFT_TO_RIGHT;
        return switch (dir) {
            case LEFT_TO_RIGHT -> routeLeftToRight(source, target);
            case RIGHT_TO_LEFT -> routeRightToLeft(source, target);
            case TOP_TO_BOTTOM -> routeTopToBottom(source, target);
            case BOTTOM_TO_TOP -> routeBottomToTop(source, target);
        };
    }

    private static List<PortPoint> routeLeftToRight(AnchorBox s, AnchorBox t) {
        double x1 = round2(s.portMaxX());
        double y1 = round2(s.portMidY());
        double xEnd = round2(t.portMinX());
        double yEnd = round2(t.portMidY());

        if (x1 < xEnd - 1.0) {
            // Forward 1-segment cubic Bézier with horizontal mid-span tangents (03 §4.4.3 Case 1)
            double midX = round2((x1 + xEnd) / 2.0);
            return List.of(
                    new PortPoint(x1, y1),
                    new PortPoint(midX, y1),
                    new PortPoint(midX, yEnd),
                    new PortPoint(xEnd, yEnd));
        }

        // Backward / Same-Rank / Self-Loop smooth C¹ double cubic Bézier (03 §4.4.3 Case 2)
        double x2 = round2(x1 + 50.0);
        double y2 = y1;
        double topClearance = Math.min(s.boxY(), t.boxY()) - 14.0;
        double bottomClearance = Math.max(s.boxY() + s.boxHeight(), t.boxY() + t.boxHeight()) + 25.0;
        boolean routeAbove = topClearance >= 12.0
                && Math.abs(y1 - topClearance) <= Math.abs(bottomClearance - y1);
        double y3 = round2(routeAbove ? topClearance : bottomClearance);
        double x3 = x2;
        double x4 = round2((x1 + xEnd) / 2.0);
        double y4 = y3;
        double c2x1 = round2(2.0 * x4 - x3);
        double c2y1 = round2(2.0 * y4 - y3);
        double x5 = round2(xEnd - 30.0);
        double y5 = round2(routeAbove ? yEnd - 10.0 : yEnd + 10.0);

        return List.of(
                new PortPoint(x1, y1),
                new PortPoint(x2, y2),
                new PortPoint(x3, y3),
                new PortPoint(x4, y4),
                new PortPoint(c2x1, c2y1),
                new PortPoint(x5, y5),
                new PortPoint(xEnd, yEnd));
    }

    private static List<PortPoint> routeRightToLeft(AnchorBox s, AnchorBox t) {
        double x1 = round2(s.portMinX());
        double y1 = round2(s.portMidY());
        double xEnd = round2(t.portMaxX());
        double yEnd = round2(t.portMidY());

        if (xEnd < x1 - 1.0) {
            double midX = round2((x1 + xEnd) / 2.0);
            return List.of(
                    new PortPoint(x1, y1),
                    new PortPoint(midX, y1),
                    new PortPoint(midX, yEnd),
                    new PortPoint(xEnd, yEnd));
        }

        double x2 = round2(x1 - 50.0);
        double y2 = y1;
        double bottomClearance = Math.max(s.boxY() + s.boxHeight(), t.boxY() + t.boxHeight()) + 25.0;
        double x3 = x2;
        double y3 = round2(bottomClearance);
        double x4 = round2((x1 + xEnd) / 2.0);
        double y4 = y3;
        double c2x1 = round2(2.0 * x4 - x3);
        double c2y1 = round2(2.0 * y4 - y3);
        double x5 = round2(xEnd + 30.0);
        double y5 = round2(yEnd + 10.0);

        return List.of(
                new PortPoint(x1, y1),
                new PortPoint(x2, y2),
                new PortPoint(x3, y3),
                new PortPoint(x4, y4),
                new PortPoint(c2x1, c2y1),
                new PortPoint(x5, y5),
                new PortPoint(xEnd, yEnd));
    }

    private static List<PortPoint> routeTopToBottom(AnchorBox s, AnchorBox t) {
        double x1 = round2(s.portMidX());
        double y1 = round2(s.boxY() + s.boxHeight());
        double xEnd = round2(t.portMidX());
        double yEnd = round2(t.boxY());

        if (y1 < yEnd - 1.0) {
            double midY = round2((y1 + yEnd) / 2.0);
            return List.of(
                    new PortPoint(x1, y1),
                    new PortPoint(x1, midY),
                    new PortPoint(xEnd, midY),
                    new PortPoint(xEnd, yEnd));
        }

        double y2 = round2(y1 + 40.0);
        double rightClearance = Math.max(s.boxX() + s.boxWidth(), t.boxX() + t.boxWidth()) + 30.0;
        double x3 = round2(rightClearance);
        double y3 = y2;
        double x4 = x3;
        double y4 = round2((y1 + yEnd) / 2.0);
        double c2x1 = round2(2.0 * x4 - x3);
        double c2y1 = round2(2.0 * y4 - y3);
        double x5 = round2(xEnd + 10.0);
        double y5 = round2(yEnd - 30.0);

        return List.of(
                new PortPoint(x1, y1),
                new PortPoint(x1, y2),
                new PortPoint(x3, y3),
                new PortPoint(x4, y4),
                new PortPoint(c2x1, c2y1),
                new PortPoint(x5, y5),
                new PortPoint(xEnd, yEnd));
    }

    private static List<PortPoint> routeBottomToTop(AnchorBox s, AnchorBox t) {
        double x1 = round2(s.portMidX());
        double y1 = round2(s.boxY());
        double xEnd = round2(t.portMidX());
        double yEnd = round2(t.boxY() + t.boxHeight());

        if (yEnd < y1 - 1.0) {
            double midY = round2((y1 + yEnd) / 2.0);
            return List.of(
                    new PortPoint(x1, y1),
                    new PortPoint(x1, midY),
                    new PortPoint(xEnd, midY),
                    new PortPoint(xEnd, yEnd));
        }

        double y2 = round2(y1 - 40.0);
        double rightClearance = Math.max(s.boxX() + s.boxWidth(), t.boxX() + t.boxWidth()) + 30.0;
        double x3 = round2(rightClearance);
        double y3 = y2;
        double x4 = x3;
        double y4 = round2((y1 + yEnd) / 2.0);
        double c2x1 = round2(2.0 * x4 - x3);
        double c2y1 = round2(2.0 * y4 - y3);
        double x5 = round2(xEnd + 10.0);
        double y5 = round2(yEnd + 30.0);

        return List.of(
                new PortPoint(x1, y1),
                new PortPoint(x1, y2),
                new PortPoint(x3, y3),
                new PortPoint(x4, y4),
                new PortPoint(c2x1, c2y1),
                new PortPoint(x5, y5),
                new PortPoint(xEnd, yEnd));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
