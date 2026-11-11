package io.sqlflow.graph.export;

import io.sqlflow.contracts.graph.SqlflowJSON;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphColumn;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphEdge;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphElements;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphPackage;
import io.sqlflow.contracts.graph.SqlflowJSON.GraphTable;
import io.sqlflow.contracts.graph.SqlflowJSON.PortPoint;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Exports a laid-out {@link SqlflowJSON} graph to valid yFiles / yEd {@code .graphml} XML
 * matching the 4 SQLFlow GraphML template structures ({@code graph.template}, {@code table.template},
 * {@code column.template}, {@code link.template}, plus compound {@code package} group nodes)
 * ({@code OBL-059}, {@code 02 §6.5}, {@code 03 §4.1.2}).
 */
public final class GraphMlExporter {

    private GraphMlExporter() {
    }

    public static String export(SqlflowJSON graph) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
        sb.append("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\"\n");
        sb.append("         xmlns:java=\"http://www.yworks.com/xml/yfiles-common/1.0/java\"\n");
        sb.append("         xmlns:sys=\"http://www.yworks.com/xml/yfiles-common/markup/primitives/2.0\"\n");
        sb.append("         xmlns:x=\"http://www.yworks.com/xml/yfiles-common/markup/2.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xmlns:y=\"http://www.yworks.com/xml/graphml\"\n");
        sb.append("         xmlns:yed=\"http://www.yworks.com/xml/yed/3\"\n");
        sb.append("         xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns ");
        sb.append("http://www.yworks.com/xml/schema/graphml/1.1/ygraphml.xsd\">\n");
        sb.append("  <key for=\"port\" id=\"d0\" yfiles.type=\"portgraphics\"/>\n");
        sb.append("  <key for=\"port\" id=\"d1\" yfiles.type=\"portgeometry\"/>\n");
        sb.append("  <key for=\"port\" id=\"d2\" yfiles.type=\"portuserdata\"/>\n");
        sb.append("  <key attr.name=\"url\" attr.type=\"string\" for=\"node\" id=\"d3\"/>\n");
        sb.append("  <key attr.name=\"description\" attr.type=\"string\" for=\"node\" id=\"d4\"/>\n");
        sb.append("  <key for=\"node\" id=\"d5\" yfiles.type=\"nodegraphics\"/>\n");
        sb.append("  <key for=\"graphml\" id=\"d6\" yfiles.type=\"resources\"/>\n");
        sb.append("  <key attr.name=\"url\" attr.type=\"string\" for=\"edge\" id=\"d7\"/>\n");
        sb.append("  <key attr.name=\"description\" attr.type=\"string\" for=\"edge\" id=\"d8\"/>\n");
        sb.append("  <key for=\"edge\" id=\"d9\" yfiles.type=\"edgegraphics\"/>\n");
        sb.append("  <graph edgedefault=\"directed\" id=\"G\">\n");

        GraphElements elements = graph != null ? graph.resolveElements() : null;
        Set<String> packagedTableIds = new HashSet<>();

        if (elements != null && elements.getPackages() != null) {
            for (GraphPackage pkg : elements.getPackages()) {
                if (pkg == null) {
                    continue;
                }
                if (pkg.getTables() != null) {
                    for (GraphTable child : pkg.getTables()) {
                        if (child != null && child.getId() != null) {
                            packagedTableIds.add(child.getId());
                        }
                    }
                }
                appendPackageNode(sb, pkg, elements.getTables(), packagedTableIds);
            }
        }

        if (elements != null && elements.getTables() != null) {
            for (GraphTable tbl : elements.getTables()) {
                if (tbl != null && !packagedTableIds.contains(tbl.getId())) {
                    appendTableNode(sb, tbl, "    ");
                }
            }
        }

        if (elements != null && elements.getEdges() != null) {
            for (GraphEdge edge : elements.getEdges()) {
                if (edge != null) {
                    appendEdge(sb, edge);
                }
            }
        }

        sb.append("  </graph>\n");
        sb.append("  <data key=\"d6\">\n");
        sb.append("    <y:Resources/>\n");
        sb.append("  </data>\n");
        sb.append("</graphml>\n");
        return sb.toString();
    }

    private static void appendPackageNode(
            StringBuilder sb,
            GraphPackage pkg,
            List<GraphTable> allTables,
            Set<String> packagedTableIds) {
        String pkgId = pkg.getId() != null ? pkg.getId() : "p0";
        String title = pkg.getLabel() != null && pkg.getLabel().getContent() != null
                ? pkg.getLabel().getContent()
                : (pkg.getName() != null ? pkg.getName() : pkgId);
        double w = pkg.getWidth() > 0 ? pkg.getWidth() : 200.0;
        double h = pkg.getHeight() > 0 ? pkg.getHeight() : 120.0;

        sb.append("    <node id=\"").append(escapeXml(pkgId)).append("\" yfiles.foldertype=\"group\">\n");
        sb.append("      <data key=\"d4\">").append(escapeXml("package")).append("</data>\n");
        sb.append("      <data key=\"d5\">\n");
        sb.append("        <y:ProxyAutoBoundsNode>\n");
        sb.append("          <y:Realizers active=\"0\">\n");
        sb.append("            <y:GroupNode>\n");
        sb.append("              <y:Geometry height=\"").append(fmt(h))
                .append("\" width=\"").append(fmt(w))
                .append("\" x=\"").append(fmt(pkg.getX()))
                .append("\" y=\"").append(fmt(pkg.getY())).append("\"/>\n");
        sb.append("              <y:Fill color=\"#F7F7F7\" transparent=\"false\"/>\n");
        sb.append("              <y:BorderStyle color=\"#888888\" type=\"dashed\" width=\"1.0\"/>\n");
        sb.append("              <y:NodeLabel alignment=\"center\" autoSizePolicy=\"node_width\" ")
                .append("backgroundColor=\"#DBDADA\" borderDistance=\"0.0\" ")
                .append("configuration=\"demo.view.dlineage.table.label.name\" ")
                .append("fontFamily=\"Segoe UI Symbol\" fontSize=\"12\" fontStyle=\"bold\" ")
                .append("hasLineColor=\"false\" height=\"22.0\" modelName=\"internal\" ")
                .append("modelPosition=\"t\" textColor=\"#000000\" visible=\"true\" width=\"")
                .append(fmt(w)).append("\" x=\"0.0\" y=\"0.0\">")
                .append(escapeXml(title)).append("</y:NodeLabel>\n");
        sb.append("              <y:Shape type=\"roundrectangle\"/>\n");
        sb.append("              <y:State closed=\"false\" innerGraphDisplayEnabled=\"true\"/>\n");
        sb.append("              <y:Insets bottom=\"12\" bottomF=\"12.0\" left=\"14\" leftF=\"14.0\" ")
                .append("right=\"14\" rightF=\"14.0\" top=\"8\" topF=\"8.0\"/>\n");
        sb.append("              <y:BorderInsets bottom=\"0\" bottomF=\"0.0\" left=\"0\" leftF=\"0.0\" ")
                .append("right=\"0\" rightF=\"0.0\" top=\"0\" topF=\"0.0\"/>\n");
        sb.append("            </y:GroupNode>\n");
        sb.append("          </y:Realizers>\n");
        sb.append("        </y:ProxyAutoBoundsNode>\n");
        sb.append("      </data>\n");
        sb.append("      <graph edgedefault=\"directed\" id=\"graph_").append(escapeXml(pkgId)).append("\">\n");

        if (pkg.getTables() != null && !pkg.getTables().isEmpty()) {
            for (GraphTable child : pkg.getTables()) {
                if (child != null) {
                    appendTableNode(sb, child, "        ");
                }
            }
        } else if (allTables != null) {
            for (GraphTable cand : allTables) {
                if (cand != null && pkgId.equals(cand.getParent())) {
                    packagedTableIds.add(cand.getId());
                    appendTableNode(sb, cand, "        ");
                }
            }
        }

        sb.append("      </graph>\n");
        sb.append("    </node>\n");
    }

    private static void appendTableNode(StringBuilder sb, GraphTable tbl, String indent) {
        String tblId = tbl.getId() != null ? tbl.getId() : "n0";
        String title = tbl.getLabel() != null && tbl.getLabel().getContent() != null
                ? tbl.getLabel().getContent()
                : (tbl.getDisplayName() != null ? tbl.getDisplayName() : (tbl.getName() != null ? tbl.getName() : tblId));
        double w = tbl.getWidth() > 0 ? tbl.getWidth() : 162.0;
        double h = tbl.getHeight() > 0 ? tbl.getHeight() : 42.0;
        String labelColor = resolveGraphMlHeaderColor(tbl.getType());

        sb.append(indent).append("<node id=\"").append(escapeXml(tblId)).append("\" yfiles.foldertype=\"group\">\n");
        if (tbl.getType() != null) {
            sb.append(indent).append("  <data key=\"d4\">").append(escapeXml(tbl.getType())).append("</data>\n");
        }
        sb.append(indent).append("  <data key=\"d5\">\n");
        sb.append(indent).append("    <y:ProxyAutoBoundsNode>\n");
        sb.append(indent).append("      <y:Realizers active=\"0\">\n");
        sb.append(indent).append("        <y:GroupNode>\n");
        sb.append(indent).append("          <y:Geometry height=\"").append(fmt(h))
                .append("\" width=\"").append(fmt(w))
                .append("\" x=\"").append(fmt(tbl.getX()))
                .append("\" y=\"").append(fmt(tbl.getY())).append("\"/>\n");
        sb.append(indent).append("          <y:Fill color=\"#FFFFFF\" transparent=\"false\"/>\n");
        sb.append(indent).append("          <y:BorderStyle color=\"#000000\" type=\"line\" width=\"1.0\"/>\n");
        sb.append(indent).append("          <y:NodeLabel alignment=\"center\" autoSizePolicy=\"node_width\" ")
                .append("backgroundColor=\"").append(labelColor).append("\" borderDistance=\"0.0\" ")
                .append("configuration=\"demo.view.dlineage.table.label.name\" ")
                .append("fontFamily=\"Segoe UI Symbol\" fontSize=\"12\" fontStyle=\"plain\" ")
                .append("hasLineColor=\"false\" height=\"18.0\" modelName=\"internal\" ")
                .append("modelPosition=\"t\" textColor=\"#000000\" visible=\"true\" width=\"")
                .append(fmt(w)).append("\" x=\"0.0\" y=\"0.0\">")
                .append(escapeXml(title)).append("</y:NodeLabel>\n");
        sb.append(indent).append("          <y:Shape type=\"roundrectangle\"/>\n");
        sb.append(indent).append("          <y:State closed=\"false\" innerGraphDisplayEnabled=\"true\"/>\n");
        sb.append(indent).append("          <y:Insets bottom=\"4\" bottomF=\"4.0\" left=\"1\" leftF=\"1.0\" ")
                .append("right=\"1\" rightF=\"1.0\" top=\"4\" topF=\"4.0\"/>\n");
        sb.append(indent).append("          <y:BorderInsets bottom=\"0\" bottomF=\"0.0\" left=\"0\" leftF=\"0.0\" ")
                .append("right=\"0\" rightF=\"0.0\" top=\"0\" topF=\"0.0\"/>\n");
        sb.append(indent).append("          <y:NodeBounds considerNodeLabelSize=\"true\"/>\n");
        sb.append(indent).append("        </y:GroupNode>\n");
        sb.append(indent).append("      </y:Realizers>\n");
        sb.append(indent).append("    </y:ProxyAutoBoundsNode>\n");
        sb.append(indent).append("  </data>\n");

        sb.append(indent).append("  <graph edgedefault=\"directed\" id=\"graph_")
                .append(escapeXml(tblId)).append("\">\n");
        if (tbl.getColumns() != null) {
            for (int j = 0; j < tbl.getColumns().size(); j++) {
                GraphColumn col = tbl.getColumns().get(j);
                if (col != null) {
                    appendColumnNode(sb, tbl, col, j, indent + "    ");
                }
            }
        }
        sb.append(indent).append("  </graph>\n");
        sb.append(indent).append("</node>\n");
    }

    private static void appendColumnNode(
            StringBuilder sb,
            GraphTable parent,
            GraphColumn col,
            int rowIndex,
            String indent) {
        String colId = col.getId() != null ? col.getId() : (parent.getId() + "::n" + rowIndex);
        String colTitle = col.getLabel() != null && col.getLabel().getContent() != null
                ? col.getLabel().getContent()
                : (col.getDisplayName() != null ? col.getDisplayName() : (col.getName() != null ? col.getName() : colId));
        double colW = col.getWidth() > 0 ? col.getWidth() : Math.max(160.0, parent.getWidth() - 2.0);
        double colH = col.getHeight() > 0 ? col.getHeight() : 16.0;
        double absX = parent.getX() + (col.getX() > 0 ? col.getX() : 1.0);
        double absY = parent.getY() + (col.getY() > 0 ? col.getY() : (22.0 + rowIndex * 16.0));

        sb.append(indent).append("<node id=\"").append(escapeXml(colId)).append("\">\n");
        sb.append(indent).append("  <data key=\"d5\">\n");
        sb.append(indent).append("    <y:ShapeNode>\n");
        sb.append(indent).append("      <y:Geometry height=\"").append(fmt(colH))
                .append("\" width=\"").append(fmt(colW))
                .append("\" x=\"").append(fmt(absX))
                .append("\" y=\"").append(fmt(absY)).append("\"/>\n");
        sb.append(indent).append("      <y:Fill color=\"#FFFFFF\" transparent=\"false\"/>\n");
        sb.append(indent).append("      <y:BorderStyle color=\"#FFFFFF\" type=\"line\" width=\"0.0\"/>\n");
        sb.append(indent).append("      <y:NodeLabel alignment=\"left\" autoSizePolicy=\"content\" ")
                .append("fontFamily=\"Segoe UI Symbol\" fontSize=\"12\" fontStyle=\"plain\" ")
                .append("hasBackgroundColor=\"false\" hasLineColor=\"false\" height=\"16.0\" ")
                .append("leftInset=\"16\" modelName=\"internal\" modelPosition=\"tl\" ")
                .append("textColor=\"#000000\" visible=\"true\" width=\"").append(fmt(colW))
                .append("\" x=\"0.0\" y=\"0.0\">")
                .append(escapeXml(colTitle)).append("</y:NodeLabel>\n");
        sb.append(indent).append("      <y:Shape type=\"rectangle\"/>\n");
        sb.append(indent).append("    </y:ShapeNode>\n");
        sb.append(indent).append("  </data>\n");
        sb.append(indent).append("</node>\n");
    }

    private static void appendEdge(StringBuilder sb, GraphEdge edge) {
        if (edge.getSourceId() == null || edge.getTargetId() == null) {
            return;
        }
        String edgeId = edge.getId() != null ? edge.getId() : "e0";
        boolean direct = edge.getType() == null || "fdd".equalsIgnoreCase(edge.getType());
        String lineType = direct ? "line" : "dashed";

        sb.append("    <edge id=\"").append(escapeXml(edgeId))
                .append("\" source=\"").append(escapeXml(edge.getSourceId()))
                .append("\" target=\"").append(escapeXml(edge.getTargetId())).append("\">\n");
        if (edge.getType() != null) {
            sb.append("      <data key=\"d8\">").append(escapeXml(edge.getType())).append("</data>\n");
        }
        sb.append("      <data key=\"d9\">\n");
        sb.append("        <y:PolyLineEdge>\n");
        List<PortPoint> pts = edge.getPoints();
        if (pts != null && pts.size() > 2) {
            sb.append("          <y:Path sx=\"0.0\" sy=\"0.0\" tx=\"0.0\" ty=\"0.0\">\n");
            for (int i = 1; i < pts.size() - 1; i++) {
                PortPoint p = pts.get(i);
                sb.append("            <y:Point x=\"").append(fmt(p.getX()))
                        .append("\" y=\"").append(fmt(p.getY())).append("\"/>\n");
            }
            sb.append("          </y:Path>\n");
        } else {
            sb.append("          <y:Path sx=\"0.0\" sy=\"0.0\" tx=\"0.0\" ty=\"0.0\"/>\n");
        }
        sb.append("          <y:LineStyle color=\"#555555\" type=\"").append(lineType).append("\" width=\"1.0\"/>\n");
        sb.append("          <y:Arrows source=\"none\" target=\"standard\"/>\n");
        sb.append("          <y:BendStyle smoothed=\"false\"/>\n");
        sb.append("        </y:PolyLineEdge>\n");
        sb.append("      </data>\n");
        sb.append("    </edge>\n");
    }

    /**
     * Exact yEd GraphML header color mapping ({@code 02 §6.5}, {@code 03 §4.1.2}).
     */
    private static String resolveGraphMlHeaderColor(String type) {
        if (type == null || type.isBlank()) {
            return "#9999ff";
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "table" -> "#9999ff";
            case "view", "materialized view" -> "#ff99cc";
            case "procedure", "function", "trigger", "macro" -> "#ffccff";
            case "process" -> "#87CEFA";
            default -> "#DBDADA";
        };
    }

    private static String fmt(double val) {
        return String.format(Locale.ROOT, "%.2f", val);
    }

    public static String escapeXml(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
