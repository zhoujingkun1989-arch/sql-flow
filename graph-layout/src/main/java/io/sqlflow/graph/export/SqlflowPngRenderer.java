package io.sqlflow.graph.export;

import io.sqlflow.contracts.graph.DboColorPalette;
import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphColumn;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphEdge;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphElements;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphPackage;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphTable;
import io.sqlflow.contracts.graph.SqlflowJSON.PortPoint;
import io.sqlflow.contracts.lineage.Orientation;
import io.sqlflow.contracts.spi.GraphLayoutSpi.PngRenderOptions;
import io.sqlflow.graph.layout.BezierEdgeRouter;
import io.sqlflow.graph.layout.BezierEdgeRouter.AnchorBox;
import io.sqlflow.graph.layout.TextMetricsHelper;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Server-side headless Java2D PNG rasterizer and SVG renderer matching {@code SqlflowImageService}
 * ({@code OBL-060}, {@code OBL-088}, {@code OBL-090}, {@code 03 §4.4}).
 *
 * <p>Uses the exact 17-entry {@link DboColorPalette} header palette, crisp rounded node cards,
 * PK/FK/{@code unqiueKey}/IX key badges, package containers, and horizontal-tangent / double-cubic
 * Bézier curves with {@code 8px} arrowheads.
 */
public final class SqlflowPngRenderer {

    private static final Color CANVAS_BG = parseHexColor(DboColorPalette.CANVAS_BACKGROUND);
    private static final Color CARD_BG = parseHexColor(DboColorPalette.COLUMN_BACKGROUND);
    private static final Color ROW_DIVIDER = parseHexColor(DboColorPalette.COLUMN_DIVIDER);
    private static final Color CONTAINER_BORDER = parseHexColor(DboColorPalette.CONTAINER_BORDER);
    private static final Color EDGE_COLOR = parseHexColor(DboColorPalette.EDGE_STROKE);
    private static final Color SHADOW_COLOR = new Color(0, 0, 0, 28);
    private static final Color TEXT_DARK = new Color(34, 34, 34);
    private static final Color TEXT_MUTED = new Color(119, 119, 119);
    private static final Color PK_BADGE_COLOR = new Color(217, 119, 6);
    private static final Color FK_BADGE_COLOR = new Color(37, 99, 235);
    private static final Color UK_BADGE_COLOR = new Color(124, 58, 237);
    private static final Color IX_BADGE_COLOR = new Color(5, 150, 105);

    private static final Stroke SOLID_EDGE_STROKE = new BasicStroke(
            1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke DASHED_EDGE_STROKE = new BasicStroke(
            1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10.0f, new float[] {4.0f, 4.0f}, 0.0f);
    private static final Stroke PACKAGE_BORDER_STROKE = new BasicStroke(
            1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10.0f, new float[] {6.0f, 4.0f}, 0.0f);
    private static final Stroke CARD_BORDER_STROKE = new BasicStroke(1.0f);

    private static final Font HEADER_FONT = new Font("Verdana", Font.BOLD, 12);
    private static final Font COLUMN_FONT = new Font("Verdana", Font.PLAIN, 11);
    private static final Font TYPE_FONT = new Font("Verdana", Font.PLAIN, 9);
    private static final Font BADGE_FONT = new Font("Verdana", Font.BOLD, 8);

    private SqlflowPngRenderer() {
    }

    public static byte[] renderPng(SqlflowJSON graph, PngRenderOptions options) {
        System.setProperty("java.awt.headless", "true");
        PngRenderOptions opts = options != null ? options : new PngRenderOptions();
        double scale = opts.getScale() > 0.05 ? Math.min(opts.getScale(), 4.0) : 1.0;
        int extraPad = Math.max(0, opts.getPadding());
        boolean showShadow = opts.isShowShadow();

        GraphElements elements = graph != null ? graph.resolveElements() : null;
        Map<String, String> tooltipMap = resolveTooltips(graph);
        Orientation orientation = graph != null && graph.getOrientation() != null
                ? graph.getOrientation()
                : (graph != null && graph.getGraph() != null && graph.getGraph().getOrientation() != null
                        ? graph.getGraph().getOrientation()
                        : Orientation.LEFT_TO_RIGHT);

        // Compute normalized world bounds (handling negative Y if raw yFiles coordinates were supplied)
        Bounds bounds = computeBounds(graph, elements);
        double shiftX = (bounds.minX < 0 ? -bounds.minX : 0.0) + extraPad;
        double shiftY = (bounds.minY < 0 ? -bounds.minY : 0.0) + extraPad;

        double logicalWidth = Math.max(240.0, bounds.maxRight + shiftX + extraPad);
        double logicalHeight = Math.max(160.0, bounds.maxBottom + shiftY + extraPad);

        int imgWidth = Math.max(64, (int) Math.ceil(logicalWidth * scale));
        int imgHeight = Math.max(64, (int) Math.ceil(logicalHeight * scale));

        BufferedImage image = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            g2.setColor(CANVAS_BG);
            g2.fillRect(0, 0, imgWidth, imgHeight);

            AffineTransform baseTx = g2.getTransform();
            g2.scale(scale, scale);
            g2.translate(shiftX, shiftY);

            if (elements != null) {
                Map<String, AnchorBox> anchors = buildAnchorLookup(elements);

                // 1. Draw Package compound containers
                if (elements.getPackages() != null) {
                    for (GraphPackage pkg : elements.getPackages()) {
                        if (pkg != null) {
                            drawPackage(g2, pkg, tooltipMap, showShadow);
                        }
                    }
                }

                // 2. Draw Table / View / Resultset / Procedure cards
                if (elements.getTables() != null) {
                    for (GraphTable tbl : elements.getTables()) {
                        if (tbl != null) {
                            drawTableCard(g2, tbl, tooltipMap, showShadow);
                        }
                    }
                }

                // 3. Draw Cubic Bézier edges & arrowheads
                if (elements.getEdges() != null) {
                    for (GraphEdge edge : elements.getEdges()) {
                        if (edge != null) {
                            drawEdge(g2, edge, anchors, orientation);
                        }
                    }
                }
            }

            g2.setTransform(baseTx);
        } finally {
            g2.dispose();
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(8192)) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to encode SQLFlow PNG image", ex);
        }
    }

    public static String renderSvg(SqlflowJSON graph) {
        GraphElements elements = graph != null ? graph.resolveElements() : null;
        Map<String, String> tooltipMap = resolveTooltips(graph);
        Orientation orientation = graph != null && graph.getOrientation() != null
                ? graph.getOrientation()
                : Orientation.LEFT_TO_RIGHT;
        Bounds b = computeBounds(graph, elements);
        double shiftX = b.minX < 0 ? -b.minX + 24.0 : 0.0;
        double shiftY = b.minY < 0 ? -b.minY + 24.0 : 0.0;
        double w = Math.max(320.0, b.maxRight + shiftX + 24.0);
        double h = Math.max(200.0, b.maxBottom + shiftY + 24.0);

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(fmt(w)).append("\" height=\"").append(fmt(h))
                .append("\" viewBox=\"0 0 ").append(fmt(w)).append(" ").append(fmt(h)).append("\">\n");
        sb.append("  <defs>\n");
        sb.append("    <marker id=\"sqlflow-arrow\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"4\" orient=\"auto\">\n");
        sb.append("      <path d=\"M0,1 L8,4 L0,7 Z\" fill=\"").append(DboColorPalette.EDGE_STROKE).append("\"/>\n");
        sb.append("    </marker>\n");
        sb.append("  </defs>\n");
        sb.append("  <rect width=\"100%\" height=\"100%\" fill=\"").append(DboColorPalette.CANVAS_BACKGROUND).append("\"/>\n");
        sb.append("  <g transform=\"translate(").append(fmt(shiftX)).append(",").append(fmt(shiftY)).append(")\">\n");

        if (elements != null) {
            Map<String, AnchorBox> anchors = buildAnchorLookup(elements);
            if (elements.getPackages() != null) {
                for (GraphPackage pkg : elements.getPackages()) {
                    if (pkg == null) {
                        continue;
                    }
                    String pkgTitle = resolveDisplayTitle(
                            pkg.getId(),
                            pkg.getLabel() != null ? pkg.getLabel().getContent() : pkg.getName(),
                            tooltipMap);
                    sb.append("    <rect x=\"").append(fmt(pkg.getX())).append("\" y=\"").append(fmt(pkg.getY()))
                            .append("\" width=\"").append(fmt(pkg.getWidth())).append("\" height=\"").append(fmt(pkg.getHeight()))
                            .append("\" rx=\"4\" fill=\"#f7f7f7\" stroke=\"").append(DboColorPalette.CONTAINER_BORDER)
                            .append("\" stroke-dasharray=\"6,4\"/>\n");
                    sb.append("    <rect x=\"").append(fmt(pkg.getX())).append("\" y=\"").append(fmt(pkg.getY()))
                            .append("\" width=\"").append(fmt(pkg.getWidth())).append("\" height=\"22\" rx=\"4\" fill=\"")
                            .append(DboColorPalette.PROCEDURE_OR_FALLBACK).append("\"/>\n");
                    sb.append("    <text x=\"").append(fmt(pkg.getX() + pkg.getWidth() / 2.0))
                            .append("\" y=\"").append(fmt(pkg.getY() + 15.0))
                            .append("\" text-anchor=\"middle\" font-family=\"Verdana\" font-size=\"12\" font-weight=\"bold\" fill=\"#222222\">")
                            .append(GraphMlExporter.escapeXml(pkgTitle)).append("</text>\n");
                }
            }

            if (elements.getTables() != null) {
                for (GraphTable tbl : elements.getTables()) {
                    if (tbl == null) {
                        continue;
                    }
                    String hdrHex = DboColorPalette.resolveHeaderColor(tbl.getType(), tbl.getSubType());
                    String title = resolveDisplayTitle(
                            tbl.getId(),
                            tbl.getLabel() != null ? tbl.getLabel().getContent() : tbl.getName(),
                            tooltipMap);
                    double tw = tbl.getWidth() > 0 ? tbl.getWidth() : 162.0;
                    double th = tbl.getHeight() > 0 ? tbl.getHeight() : 42.0;
                    sb.append("    <rect x=\"").append(fmt(tbl.getX())).append("\" y=\"").append(fmt(tbl.getY()))
                            .append("\" width=\"").append(fmt(tw)).append("\" height=\"").append(fmt(th))
                            .append("\" rx=\"3\" fill=\"#ffffff\" stroke=\"").append(hdrHex).append("\"/>\n");
                    sb.append("    <rect x=\"").append(fmt(tbl.getX())).append("\" y=\"").append(fmt(tbl.getY()))
                            .append("\" width=\"").append(fmt(tw)).append("\" height=\"22\" rx=\"3\" fill=\"")
                            .append(hdrHex).append("\"/>\n");
                    sb.append("    <text x=\"").append(fmt(tbl.getX() + tw / 2.0))
                            .append("\" y=\"").append(fmt(tbl.getY() + 15.0))
                            .append("\" text-anchor=\"middle\" font-family=\"Verdana\" font-size=\"12\" font-weight=\"bold\" fill=\"#ffffff\">")
                            .append(GraphMlExporter.escapeXml(title)).append("</text>\n");

                    if (tbl.getColumns() != null) {
                        for (int j = 0; j < tbl.getColumns().size(); j++) {
                            GraphColumn col = tbl.getColumns().get(j);
                            if (col == null) {
                                continue;
                            }
                            double cy = tbl.getY() + (col.getY() > 0 ? col.getY() : (22.0 + j * 16.0));
                            String colText = resolveDisplayTitle(
                                    col.getId(),
                                    col.getLabel() != null ? col.getLabel().getContent() : col.getName(),
                                    tooltipMap);
                            sb.append("    <text x=\"").append(fmt(tbl.getX() + 10.0))
                                    .append("\" y=\"").append(fmt(cy + 12.0))
                                    .append("\" font-family=\"Verdana\" font-size=\"11\" fill=\"#222222\">")
                                    .append(GraphMlExporter.escapeXml(colText)).append("</text>\n");
                        }
                    }
                }
            }

            if (elements.getEdges() != null) {
                for (GraphEdge edge : elements.getEdges()) {
                    if (edge == null) {
                        continue;
                    }
                    List<PortPoint> pts = resolveEdgePoints(edge, anchors, orientation);
                    if (pts.size() < 2) {
                        continue;
                    }
                    String d = buildSvgPathD(pts);
                    boolean direct = edge.getType() == null || "fdd".equalsIgnoreCase(edge.getType());
                    sb.append("    <path d=\"").append(d).append("\" fill=\"none\" stroke=\"")
                            .append(DboColorPalette.EDGE_STROKE).append("\" stroke-width=\"1.2\"");
                    if (!direct) {
                        sb.append(" stroke-dasharray=\"4,4\"");
                    }
                    sb.append(" marker-end=\"url(#sqlflow-arrow)\"/>\n");
                }
            }
        }

        sb.append("  </g>\n</svg>");
        return sb.toString();
    }

    private static void drawPackage(
            Graphics2D g2,
            GraphPackage pkg,
            Map<String, String> tooltipMap,
            boolean showShadow) {
        double x = pkg.getX();
        double y = pkg.getY();
        double w = pkg.getWidth() > 0 ? pkg.getWidth() : 200.0;
        double h = pkg.getHeight() > 0 ? pkg.getHeight() : 120.0;

        if (showShadow) {
            g2.setColor(SHADOW_COLOR);
            g2.fill(new RoundRectangle2D.Double(x + 3.0, y + 3.0, w, h, 8.0, 8.0));
        }

        RoundRectangle2D.Double box = new RoundRectangle2D.Double(x, y, w, h, 8.0, 8.0);
        g2.setColor(new Color(247, 247, 247));
        g2.fill(box);

        Shape oldClip = g2.getClip();
        g2.clip(box);
        Color pkgHdr = parseHexColor(DboColorPalette.PROCEDURE_OR_FALLBACK);
        g2.setColor(pkgHdr);
        g2.fill(new Rectangle2D.Double(x, y, w, 22.0));
        g2.setClip(oldClip);

        g2.setColor(CONTAINER_BORDER);
        g2.setStroke(PACKAGE_BORDER_STROKE);
        g2.draw(box);

        String rawTitle = pkg.getLabel() != null && pkg.getLabel().getContent() != null
                ? pkg.getLabel().getContent()
                : pkg.getName();
        String displayTitle = resolveDisplayTitle(pkg.getId(), rawTitle, tooltipMap);
        g2.setFont(HEADER_FONT);
        FontMetrics fm = g2.getFontMetrics();
        double textX = x + Math.max(6.0, (w - fm.stringWidth(displayTitle)) / 2.0);
        double textY = y + (22.0 - fm.getHeight()) / 2.0 + fm.getAscent();
        g2.setColor(TEXT_DARK);
        g2.drawString(displayTitle, (float) textX, (float) textY);
    }

    private static void drawTableCard(
            Graphics2D g2,
            GraphTable tbl,
            Map<String, String> tooltipMap,
            boolean showShadow) {
        double x = tbl.getX();
        double y = tbl.getY();
        double w = tbl.getWidth() > 0 ? tbl.getWidth() : 162.0;
        double h = tbl.getHeight() > 0 ? tbl.getHeight() : 42.0;
        double headerH = Math.min(22.0, h);

        if (showShadow) {
            g2.setColor(SHADOW_COLOR);
            g2.fill(new RoundRectangle2D.Double(x + 3.0, y + 3.0, w, h, 6.0, 6.0));
        }

        String headerHex = DboColorPalette.resolveHeaderColor(tbl.getType(), tbl.getSubType());
        Color headerColor = parseHexColor(headerHex);

        RoundRectangle2D.Double cardRect = new RoundRectangle2D.Double(x, y, w, h, 6.0, 6.0);
        g2.setColor(CARD_BG);
        g2.fill(cardRect);

        // Clip to rounded card so header bar has crisp top rounded corners and flat bottom
        Shape savedClip = g2.getClip();
        g2.clip(cardRect);

        g2.setColor(headerColor);
        g2.fill(new Rectangle2D.Double(x, y, w, headerH));

        String rawHeader = tbl.getLabel() != null && tbl.getLabel().getContent() != null
                ? tbl.getLabel().getContent()
                : (tbl.getDisplayName() != null ? tbl.getDisplayName() : tbl.getName());
        String headerTitle = resolveDisplayTitle(tbl.getId(), rawHeader, tooltipMap);

        g2.setFont(HEADER_FONT);
        FontMetrics hdrFm = g2.getFontMetrics();
        boolean lightBg = isLightColor(headerColor);
        g2.setColor(lightBg ? TEXT_DARK : Color.WHITE);
        double titleX = x + Math.max(6.0, (w - hdrFm.stringWidth(headerTitle)) / 2.0);
        double titleY = y + (headerH - hdrFm.getHeight()) / 2.0 + hdrFm.getAscent();
        g2.drawString(headerTitle, (float) titleX, (float) titleY);

        // Draw column rows & PK / FK / unqiueKey / IX badges
        if (tbl.getColumns() != null) {
            for (int j = 0; j < tbl.getColumns().size(); j++) {
                GraphColumn col = tbl.getColumns().get(j);
                if (col == null) {
                    continue;
                }
                double rowY = y + (col.getY() > 0 ? col.getY() : (22.0 + j * 16.0));
                double rowH = col.getHeight() > 0 ? col.getHeight() : 16.0;

                if (j > 0) {
                    g2.setColor(ROW_DIVIDER);
                    g2.setStroke(CARD_BORDER_STROKE);
                    g2.draw(new Line2D.Double(x + 1.0, rowY, x + w - 1.0, rowY));
                }

                double cursorX = x + 6.0;
                if (Boolean.TRUE.equals(col.getPrimaryKey())) {
                    cursorX = drawKeyBadge(g2, "PK", PK_BADGE_COLOR, cursorX, rowY, rowH);
                }
                if (Boolean.TRUE.equals(col.getUnqiueKey())) {
                    cursorX = drawKeyBadge(g2, "UK", UK_BADGE_COLOR, cursorX, rowY, rowH);
                }
                if (Boolean.TRUE.equals(col.getForeignKey())) {
                    cursorX = drawKeyBadge(g2, "FK", FK_BADGE_COLOR, cursorX, rowY, rowH);
                }
                if (Boolean.TRUE.equals(col.getIndexKey())) {
                    cursorX = drawKeyBadge(g2, "IX", IX_BADGE_COLOR, cursorX, rowY, rowH);
                }

                String rawCol = col.getLabel() != null && col.getLabel().getContent() != null
                        ? col.getLabel().getContent()
                        : (col.getDisplayName() != null ? col.getDisplayName() : col.getName());
                String colTitle = resolveDisplayTitle(col.getId(), rawCol, tooltipMap);

                g2.setFont(COLUMN_FONT);
                FontMetrics colFm = g2.getFontMetrics();
                double colBaseline = rowY + (rowH - colFm.getHeight()) / 2.0 + colFm.getAscent();
                g2.setColor(TEXT_DARK);
                g2.drawString(colTitle, (float) cursorX, (float) colBaseline);

                if (col.getDataType() != null && !col.getDataType().isBlank()) {
                    String dt = TextMetricsHelper.getShortLabel(col.getDataType(), 14);
                    g2.setFont(TYPE_FONT);
                    FontMetrics dtFm = g2.getFontMetrics();
                    double dtWidth = dtFm.stringWidth(dt);
                    double dtX = x + w - dtWidth - 6.0;
                    if (dtX > cursorX + colFm.stringWidth(colTitle) + 8.0) {
                        g2.setColor(TEXT_MUTED);
                        g2.drawString(dt, (float) dtX, (float) colBaseline);
                    }
                }
            }
        }

        g2.setClip(savedClip);

        // Crisp 1px card border in headerColor (03 §4.4.1 #3)
        g2.setColor(headerColor);
        g2.setStroke(CARD_BORDER_STROKE);
        g2.draw(cardRect);
    }

    private static double drawKeyBadge(
            Graphics2D g2,
            String text,
            Color badgeColor,
            double x,
            double rowY,
            double rowH) {
        g2.setFont(BADGE_FONT);
        FontMetrics fm = g2.getFontMetrics();
        double bw = fm.stringWidth(text) + 6.0;
        double bh = Math.min(12.0, rowH - 3.0);
        double by = rowY + (rowH - bh) / 2.0;

        RoundRectangle2D.Double pill = new RoundRectangle2D.Double(x, by, bw, bh, 4.0, 4.0);
        g2.setColor(badgeColor);
        g2.fill(pill);
        g2.setColor(Color.WHITE);
        double ty = by + (bh - fm.getHeight()) / 2.0 + fm.getAscent();
        g2.drawString(text, (float) (x + 3.0), (float) ty);
        return x + bw + 3.0;
    }

    private static void drawEdge(
            Graphics2D g2,
            GraphEdge edge,
            Map<String, AnchorBox> anchors,
            Orientation orientation) {
        if (edge.getSourceId() != null && edge.getSourceId().equals(edge.getTargetId())) {
            // Suppress degenerate identical-field self clutter unless explicit loop points provided
            if (edge.getPoints() == null || edge.getPoints().size() < 4) {
                return;
            }
        }

        List<PortPoint> pts = resolveEdgePoints(edge, anchors, orientation);
        if (pts.size() < 2) {
            return;
        }

        Path2D.Double path = new Path2D.Double();
        PortPoint first = pts.get(0);
        path.moveTo(first.getX(), first.getY());

        double tangentFromX;
        double tangentFromY;
        PortPoint last = pts.get(pts.size() - 1);

        if (pts.size() == 4) {
            PortPoint p1 = pts.get(1);
            PortPoint p2 = pts.get(2);
            path.curveTo(p1.getX(), p1.getY(), p2.getX(), p2.getY(), last.getX(), last.getY());
            tangentFromX = p2.getX();
            tangentFromY = p2.getY();
        } else if (pts.size() == 7) {
            PortPoint p1 = pts.get(1);
            PortPoint p2 = pts.get(2);
            PortPoint p3 = pts.get(3);
            PortPoint p4 = pts.get(4);
            PortPoint p5 = pts.get(5);
            path.curveTo(p1.getX(), p1.getY(), p2.getX(), p2.getY(), p3.getX(), p3.getY());
            path.curveTo(p4.getX(), p4.getY(), p5.getX(), p5.getY(), last.getX(), last.getY());
            tangentFromX = p5.getX();
            tangentFromY = p5.getY();
        } else if (pts.size() == 2) {
            double midX = (first.getX() + last.getX()) / 2.0;
            path.curveTo(midX, first.getY(), midX, last.getY(), last.getX(), last.getY());
            tangentFromX = midX;
            tangentFromY = last.getY();
        } else {
            for (int i = 1; i < pts.size(); i++) {
                PortPoint p = pts.get(i);
                path.lineTo(p.getX(), p.getY());
            }
            PortPoint prev = pts.get(pts.size() - 2);
            tangentFromX = prev.getX();
            tangentFromY = prev.getY();
        }

        boolean direct = (edge.getType() == null && edge.getEffectType() == null)
                || "fdd".equalsIgnoreCase(edge.getType())
                || ("fdd".equalsIgnoreCase(edge.getEffectType()) && edge.getType() == null);

        g2.setColor(EDGE_COLOR);
        g2.setStroke(direct ? SOLID_EDGE_STROKE : DASHED_EDGE_STROKE);
        g2.draw(path);

        drawArrowhead(g2, tangentFromX, tangentFromY, last.getX(), last.getY());
    }

    private static void drawArrowhead(Graphics2D g2, double fromX, double fromY, double tipX, double tipY) {
        double dx = tipX - fromX;
        double dy = tipY - fromY;
        double len = Math.hypot(dx, dy);
        double ux = len > 1e-4 ? dx / len : 1.0;
        double uy = len > 1e-4 ? dy / len : 0.0;

        double arrowLen = 8.0;
        double halfWidth = 3.6;
        double baseX = tipX - ux * arrowLen;
        double baseY = tipY - uy * arrowLen;

        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(baseX - uy * halfWidth, baseY + ux * halfWidth);
        arrow.lineTo(baseX + uy * halfWidth, baseY - ux * halfWidth);
        arrow.closePath();

        g2.setColor(EDGE_COLOR);
        g2.fill(arrow);
    }

    private static List<PortPoint> resolveEdgePoints(
            GraphEdge edge,
            Map<String, AnchorBox> anchors,
            Orientation orientation) {
        if (edge.getPoints() != null && edge.getPoints().size() >= 2) {
            return edge.getPoints();
        }
        AnchorBox src = edge.getSourceId() != null ? anchors.get(edge.getSourceId()) : null;
        AnchorBox tgt = edge.getTargetId() != null ? anchors.get(edge.getTargetId()) : null;
        if (src != null && tgt != null) {
            return BezierEdgeRouter.computeControlPoints(src, tgt, orientation);
        }
        return List.of();
    }

    private static String buildSvgPathD(List<PortPoint> pts) {
        PortPoint p0 = pts.get(0);
        if (pts.size() == 4) {
            return "M " + fmt(p0.getX()) + " " + fmt(p0.getY())
                    + " C " + fmt(pts.get(1).getX()) + " " + fmt(pts.get(1).getY())
                    + ", " + fmt(pts.get(2).getX()) + " " + fmt(pts.get(2).getY())
                    + ", " + fmt(pts.get(3).getX()) + " " + fmt(pts.get(3).getY());
        }
        if (pts.size() == 7) {
            return "M " + fmt(p0.getX()) + " " + fmt(p0.getY())
                    + " C " + fmt(pts.get(1).getX()) + " " + fmt(pts.get(1).getY())
                    + ", " + fmt(pts.get(2).getX()) + " " + fmt(pts.get(2).getY())
                    + ", " + fmt(pts.get(3).getX()) + " " + fmt(pts.get(3).getY())
                    + " C " + fmt(pts.get(4).getX()) + " " + fmt(pts.get(4).getY())
                    + ", " + fmt(pts.get(5).getX()) + " " + fmt(pts.get(5).getY())
                    + ", " + fmt(pts.get(6).getX()) + " " + fmt(pts.get(6).getY());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("M ").append(fmt(p0.getX())).append(" ").append(fmt(p0.getY()));
        for (int i = 1; i < pts.size(); i++) {
            sb.append(" L ").append(fmt(pts.get(i).getX())).append(" ").append(fmt(pts.get(i).getY()));
        }
        return sb.toString();
    }

    private static Map<String, AnchorBox> buildAnchorLookup(GraphElements elements) {
        Map<String, AnchorBox> anchors = new HashMap<>();
        if (elements.getPackages() != null) {
            for (GraphPackage gp : elements.getPackages()) {
                if (gp != null && gp.getId() != null) {
                    double pw = gp.getWidth() > 0 ? gp.getWidth() : 200.0;
                    double ph = gp.getHeight() > 0 ? gp.getHeight() : 120.0;
                    anchors.put(gp.getId(), new AnchorBox(
                            gp.getX(), gp.getY(), pw, ph,
                            gp.getX(), gp.getX() + pw, gp.getX() + pw / 2.0,
                            gp.getY(), gp.getY() + 22.0, gp.getY() + 11.0));
                }
            }
        }
        if (elements.getTables() != null) {
            for (GraphTable gt : elements.getTables()) {
                if (gt == null || gt.getId() == null) {
                    continue;
                }
                double tw = gt.getWidth() > 0 ? gt.getWidth() : 162.0;
                double th = gt.getHeight() > 0 ? gt.getHeight() : 42.0;
                anchors.put(gt.getId(), new AnchorBox(
                        gt.getX(), gt.getY(), tw, th,
                        gt.getX(), gt.getX() + tw, gt.getX() + tw / 2.0,
                        gt.getY(), gt.getY() + 22.0, gt.getY() + 11.0));
                if (gt.getColumns() != null) {
                    for (int j = 0; j < gt.getColumns().size(); j++) {
                        GraphColumn gc = gt.getColumns().get(j);
                        if (gc == null || gc.getId() == null) {
                            continue;
                        }
                        double cy = gt.getY() + (gc.getY() > 0 ? gc.getY() : (22.0 + j * 16.0));
                        double ch = gc.getHeight() > 0 ? gc.getHeight() : 16.0;
                        anchors.put(gc.getId(), new AnchorBox(
                                gt.getX(), gt.getY(), tw, th,
                                gt.getX(), gt.getX() + tw, gt.getX() + tw / 2.0,
                                cy, cy + ch, cy + ch / 2.0));
                    }
                }
            }
        }
        return anchors;
    }

    private record Bounds(double minX, double minY, double maxRight, double maxBottom) {
    }

    private static Bounds computeBounds(SqlflowJSON graph, GraphElements elements) {
        double minX = 0.0;
        double minY = 0.0;
        double maxRight = graph != null && graph.getWidth() != null ? graph.getWidth() : 320.0;
        double maxBottom = graph != null && graph.getHeight() != null ? graph.getHeight() : 200.0;

        if (elements != null) {
            if (elements.getPackages() != null) {
                for (GraphPackage gp : elements.getPackages()) {
                    if (gp != null) {
                        minX = Math.min(minX, gp.getX());
                        minY = Math.min(minY, gp.getY());
                        maxRight = Math.max(maxRight, gp.getX() + Math.max(180.0, gp.getWidth()));
                        maxBottom = Math.max(maxBottom, gp.getY() + Math.max(80.0, gp.getHeight()));
                    }
                }
            }
            if (elements.getTables() != null) {
                for (GraphTable gt : elements.getTables()) {
                    if (gt != null) {
                        minX = Math.min(minX, gt.getX());
                        minY = Math.min(minY, gt.getY());
                        maxRight = Math.max(maxRight, gt.getX() + Math.max(162.0, gt.getWidth()));
                        maxBottom = Math.max(maxBottom, gt.getY() + Math.max(28.0, gt.getHeight()));
                    }
                }
            }
            if (elements.getEdges() != null) {
                for (GraphEdge ge : elements.getEdges()) {
                    if (ge != null && ge.getPoints() != null) {
                        for (PortPoint pt : ge.getPoints()) {
                            minX = Math.min(minX, pt.getX());
                            minY = Math.min(minY, pt.getY());
                            maxRight = Math.max(maxRight, pt.getX());
                            maxBottom = Math.max(maxBottom, pt.getY());
                        }
                    }
                }
            }
        }
        return new Bounds(minX, minY, maxRight, maxBottom);
    }

    private static Map<String, String> resolveTooltips(SqlflowJSON graph) {
        if (graph == null) {
            return Map.of();
        }
        if (graph.getTooltip() != null && !graph.getTooltip().isEmpty()) {
            return graph.getTooltip();
        }
        if (graph.getGraph() != null && graph.getGraph().getTooltip() != null) {
            return graph.getGraph().getTooltip();
        }
        return Map.of();
    }

    private static String resolveDisplayTitle(String id, String fallbackLabel, Map<String, String> tooltips) {
        String full = (id != null && tooltips != null && tooltips.containsKey(id))
                ? tooltips.get(id)
                : (fallbackLabel != null ? fallbackLabel : (id != null ? id : ""));
        return TextMetricsHelper.get50Char(full);
    }

    public static Color parseHexColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return new Color(145, 192, 81);
        }
        String clean = hex.trim();
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (clean.length() == 6) {
            int rgb = Integer.parseInt(clean, 16);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }
        return new Color(145, 192, 81);
    }

    private static boolean isLightColor(Color c) {
        double luminance = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
        return luminance > 0.72;
    }

    private static String fmt(double val) {
        return String.format(Locale.ROOT, "%.2f", val);
    }
}
