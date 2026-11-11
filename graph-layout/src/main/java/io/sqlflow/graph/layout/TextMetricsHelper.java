package io.sqlflow.graph.layout;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Headless Java2D font metrics and SQLFlow label truncation helper ({@code 03 §4.1.1, §4.4.1}).
 */
public final class TextMetricsHelper {

    public static final String GRAPHML_FONT_FAMILY = "Segoe UI Symbol";
    public static final String RENDER_FONT_FAMILY = "Verdana";
    public static final int HEADER_FONT_SIZE = 12;
    public static final int COLUMN_FONT_SIZE = 11;
    public static final int SHORT_LABEL_LIMIT = 25;
    public static final int MIDDLE_ELLIPSIS_LIMIT = 50;

    private static final Font HEADER_FONT = new Font(RENDER_FONT_FAMILY, Font.BOLD, HEADER_FONT_SIZE);
    private static final Font COLUMN_FONT = new Font(RENDER_FONT_FAMILY, Font.PLAIN, COLUMN_FONT_SIZE);
    private static final Font BADGE_FONT = new Font(RENDER_FONT_FAMILY, Font.BOLD, 9);

    private static final FontMetrics HEADER_METRICS;
    private static final FontMetrics COLUMN_METRICS;
    private static final FontMetrics BADGE_METRICS;

    static {
        System.setProperty("java.awt.headless", "true");
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scratch.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        HEADER_METRICS = g2.getFontMetrics(HEADER_FONT);
        COLUMN_METRICS = g2.getFontMetrics(COLUMN_FONT);
        BADGE_METRICS = g2.getFontMetrics(BADGE_FONT);
        g2.dispose();
    }

    private TextMetricsHelper() {
    }

    public static Font headerFont() {
        return HEADER_FONT;
    }

    public static Font columnFont() {
        return COLUMN_FONT;
    }

    public static Font badgeFont() {
        return BADGE_FONT;
    }

    /**
     * Truncates labels exceeding 25 characters to {@code raw.substring(0, 25) + "..."} ({@code 03 §4.1.1 #4}).
     */
    public static String getShortLabel(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= limit) {
            return raw;
        }
        return raw.substring(0, limit) + "...";
    }

    /**
     * Applies 50-character middle ellipsis truncation ({@code 03 §4.4.1 #2}):
     * {@code s.substring(0, 25) + "..." + s.substring(len - 25)}.
     */
    public static String get50Char(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= MIDDLE_ELLIPSIS_LIMIT) {
            return raw;
        }
        return raw.substring(0, 25) + "..." + raw.substring(raw.length() - 25);
    }

    public static double measureHeaderWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }
        return Math.max(HEADER_METRICS.stringWidth(text), text.length() * 6.8);
    }

    public static double measureColumnWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }
        return Math.max(COLUMN_METRICS.stringWidth(text), text.length() * 6.2);
    }

    public static double measureBadgeWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }
        return BADGE_METRICS.stringWidth(text) + 8.0;
    }
}
