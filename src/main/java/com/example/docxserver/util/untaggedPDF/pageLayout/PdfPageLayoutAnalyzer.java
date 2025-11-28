package com.example.docxserver.util.untaggedPDF.pageLayout;

import com.example.docxserver.util.untaggedPDF.pageLayout.dto.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

// Tabula imports
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.PageIterator;
import technology.tabula.RectangularTextContainer;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * PDF 页面布局分析器 - 基于 Tabula 的表格提取
 *
 * 重构说明：
 * 1. 使用 Tabula 库进行表格检测和切分（替换原有的启发式算法）
 * 2. 保留业务后处理逻辑：
 *    - softMergeWithinCell：单元格内段落合并
 *    - mergeInterRowContinuation：跨行续行合并
 * 3. 保持原有的 JSON 输出格式
 */
public class PdfPageLayoutAnalyzer {

    // ========== 数据结构 ==========

    /** 线段（保留用于兼容性，Tabula 版本不再使用） */
    public static class Line {
        public Point2D start;
        public Point2D end;
        public boolean isHorizontal;
        public boolean isVertical;
        public double widthPt;

        public Line(Point2D start, Point2D end, boolean isHorizontal, boolean isVertical, double widthPt) {
            this.start = start;
            this.end = end;
            this.isHorizontal = isHorizontal;
            this.isVertical = isVertical;
            this.widthPt = widthPt;
        }

        public static class Point2D {
            public double x;
            public double y;

            public Point2D(double x, double y) {
                this.x = x;
                this.y = y;
            }
        }
    }

    /** 表格单元格 */
    public static class Cell {
        public Rectangle2D.Double bbox;
        public String text;
        public int rowIndex;
        public int colIndex;
        public int rowspan = 1;
        public int colspan = 1;
        public int possibleRowspan = 0;  // 合并单元格提示（纵向）
        public int possibleColspan = 0;  // 合并单元格提示（横向）
        public String rowspanHintReason = null;
        public String colspanHintReason = null;
        public float fontSize = 10.0f;
        public int rotation = 0;  // 旋转角度（保留用于兼容性，Tabula 版本默认为 0）

        public Cell() {
        }

        public Cell(Rectangle2D.Double bbox, int row, int col) {
            this.bbox = bbox;
            this.rowIndex = row;
            this.colIndex = col;
            this.text = "";
        }
    }

    /** 表格 */
    public static class Table {
        public Rectangle2D.Double bbox;
        public List<List<Cell>> rows = new ArrayList<>();

        public Table(Rectangle2D.Double bbox) {
            this.bbox = bbox;
        }
    }

    // ========== 核心提取逻辑（基于 Tabula） ==========

    /**
     * 使用 Tabula 从单个页面提取表格
     *
     * @param doc PDF 文档
     * @param pageIndex 页面索引
     * @return 提取的表格列表
     */
    private static List<Table> extractTablesWithTabula(PDDocument doc, int pageIndex) {
        List<Table> tables = new ArrayList<>();

        try {
            ObjectExtractor oe = new ObjectExtractor(doc);
            PageIterator it = oe.extract();

            // 定位到目标页面
            Page tabulaPage = null;
            int i = 0;
            while (it.hasNext()) {
                Page p = it.next();
                if (i == pageIndex) {
                    tabulaPage = p;
                    break;
                }
                i++;
            }

            if (tabulaPage == null) {
                System.out.println("[Tabula] 页面 " + pageIndex + " 未找到");
                return tables;
            }

            // 策略：先 lattice（有线框），失败则 stream（无线框兜底）
            SpreadsheetExtractionAlgorithm lattice = new SpreadsheetExtractionAlgorithm();
            BasicExtractionAlgorithm stream = new BasicExtractionAlgorithm();

            // 1. 尝试 lattice
            List<technology.tabula.Table> tabulaTables = lattice.extract(tabulaPage);
            System.out.println("[Tabula] Lattice 提取到 " + tabulaTables.size() + " 个表格");

            // 2. 若无表格，尝试 stream
            if (tabulaTables.isEmpty()) {
                tabulaTables = stream.extract(tabulaPage);
                System.out.println("[Tabula] Stream 提取到 " + tabulaTables.size() + " 个表格");
            }

            // 3. 转换为内部数据结构
            for (technology.tabula.Table tabulaTable : tabulaTables) {
                Table table = convertTabulaTable(tabulaTable);
                tables.add(table);
            }

        } catch (Exception e) {
            System.err.println("[Tabula] 提取失败: " + e.getMessage());
            e.printStackTrace();
        }

        return tables;
    }

    /**
     * 将 Tabula 的 Table 转换为内部 Table 结构
     */
    private static Table convertTabulaTable(technology.tabula.Table tabulaTable) {
        // 计算表格边界框
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;

        for (List<RectangularTextContainer> row : tabulaTable.getRows()) {
            for (RectangularTextContainer cell : row) {
                minX = Math.min(minX, cell.getLeft());
                minY = Math.min(minY, cell.getTop());
                maxX = Math.max(maxX, cell.getRight());
                maxY = Math.max(maxY, cell.getBottom());
            }
        }

        Rectangle2D.Double tableBbox = new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
        Table table = new Table(tableBbox);

        // 转换行和单元格
        int rowIndex = 0;
        for (List<RectangularTextContainer> tabulaRow : tabulaTable.getRows()) {
            List<Cell> row = new ArrayList<>();
            int colIndex = 0;

            for (RectangularTextContainer tabulaCell : tabulaRow) {
                Rectangle2D.Double cellBbox = new Rectangle2D.Double(
                    tabulaCell.getLeft(),
                    tabulaCell.getTop(),
                    tabulaCell.getWidth(),
                    tabulaCell.getHeight()
                );

                Cell cell = new Cell(cellBbox, rowIndex, colIndex);
                cell.text = tabulaCell.getText().trim();

                // 保留原始文本（用于后续后处理）
                // 注意：Tabula 已经做了基本的文本拼接，这里直接使用

                row.add(cell);
                colIndex++;
            }

            table.rows.add(row);
            rowIndex++;
        }

        System.out.println("[Tabula] 转换表格: " + table.rows.size() + " 行 × " +
                          (table.rows.isEmpty() ? 0 : table.rows.get(0).size()) + " 列");

        return table;
    }

    // ========== 后处理逻辑（保留原有业务规则） ==========

    /**
     * ⑥ 单元格内段落合并（intra-cell soft merge）
     *
     * 规则：
     * - 识别单元格内的多段落（基于换行符）
     * - 保持段落结构
     */
    private static void softMergeWithinCell(Table table) {
        for (List<Cell> row : table.rows) {
            for (Cell cell : row) {
                if (cell.text == null || cell.text.isEmpty()) {
                    cell.text = "";
                    continue;
                }

                // Tabula 已经处理了基本的文本拼接
                // 这里做额外的清理和段落化
                String[] lines = cell.text.split("\\r?\\n");
                List<String> paragraphs = new ArrayList<>();

                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        paragraphs.add(trimmed);
                    }
                }

                // 用换行符连接段落
                cell.text = String.join("\n", paragraphs);
            }
        }
    }

    /**
     * ⑦ 跨行续行合并（inter-row continuation merge）
     *
     * 专为招标文件三列表设计：
     * - 左两列为"锚列"（序号+事项）
     * - 右列为"内容列"（常被拆成多行）
     * - 若某行仅右列非空，判定为续行，并入最近的锚行
     *
     * 规则：
     * - 续行条件：左两列都空，右列非空
     * - 合并到最近的锚行（优先上方）
     */
    private static void mergeInterRowContinuation(Table table) {
        if (table.rows.isEmpty()) return;

        int numCols = table.rows.get(0).size();
        if (numCols < 3) {
            System.out.println("[V2] ⚠ 列数 < 3，跳过续行合并");
            return;
        }

        System.out.println("[V2] ⑦ 续行合并开始...");

        // 从上到下遍历，查找续行并合并
        for (int r = 0; r < table.rows.size(); r++) {
            List<Cell> row = table.rows.get(r);
            if (row.size() < 3) continue;

            // 检查是否为续行：左两列空，右列非空
            boolean isLeftEmpty = isEmpty(row.get(0).text) && isEmpty(row.get(1).text);
            boolean isRightNonEmpty = !isEmpty(row.get(2).text);

            if (isLeftEmpty && isRightNonEmpty) {
                System.out.println("[V2] 发现续行: row=" + r + ", 内容=" +
                    row.get(2).text.substring(0, Math.min(30, row.get(2).text.length())));

                // 找最近的锚行（优先上方）
                int anchorRow = findNearestAnchorRow(table, r);

                if (anchorRow >= 0) {
                    // 合并到锚行的右列
                    Cell anchorCell = table.rows.get(anchorRow).get(2);
                    Cell continuationCell = row.get(2);

                    System.out.println("[V2] 合并到锚行: anchorRow=" + anchorRow);

                    // 用换行符追加
                    if (!isEmpty(anchorCell.text)) {
                        anchorCell.text += "\n" + continuationCell.text;
                    } else {
                        anchorCell.text = continuationCell.text;
                    }

                    // 清空续行（标记为已合并）
                    continuationCell.text = "";
                } else {
                    System.out.println("[V2] ⚠ 未找到锚行，保留续行");
                }
            }
        }

        System.out.println("[V2] ⑦ 续行合并完成");
    }

    /**
     * 查找最近的锚行（左两列非空的行）
     */
    private static int findNearestAnchorRow(Table table, int currentRow) {
        // 优先向上查找（最近的非空锚行）
        for (int r = currentRow - 1; r >= 0; r--) {
            List<Cell> row = table.rows.get(r);
            if (row.size() < 3) continue;

            // 检查是否为锚行：左两列至少一列非空
            boolean isAnchor = !isEmpty(row.get(0).text) || !isEmpty(row.get(1).text);

            if (isAnchor) {
                return r;
            }
        }

        // 若上方无锚行，向下查找
        for (int r = currentRow + 1; r < table.rows.size(); r++) {
            List<Cell> row = table.rows.get(r);
            if (row.size() < 3) continue;

            boolean isAnchor = !isEmpty(row.get(0).text) || !isEmpty(row.get(1).text);

            if (isAnchor) {
                return r;
            }
        }

        return -1; // 未找到
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ========== JSON 输出（保持原有格式） ==========

    /**
     * 输出为 JSON 格式
     */
    public static String tablesToJson(List<Table> tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"tables\": [\n");

        for (int t = 0; t < tables.size(); t++) {
            Table table = tables.get(t);
            if (t > 0) sb.append(",\n");
            sb.append("    {\n");
            sb.append("      \"bbox\": [").append(table.bbox.x).append(", ")
              .append(table.bbox.y).append(", ")
              .append(table.bbox.x + table.bbox.width).append(", ")
              .append(table.bbox.y + table.bbox.height).append("],\n");
            sb.append("      \"rows\": [\n");

            for (int r = 0; r < table.rows.size(); r++) {
                List<Cell> row = table.rows.get(r);
                if (r > 0) sb.append(",\n");
                sb.append("        {\"cells\": [");

                for (int c = 0; c < row.size(); c++) {
                    Cell cell = row.get(c);
                    if (c > 0) sb.append(", ");
                    sb.append("{\"text\": \"").append(escapeJson(cell.text))
                      .append("\", \"bbox\": [")
                      .append(cell.bbox.x).append(", ")
                      .append(cell.bbox.y).append(", ")
                      .append(cell.bbox.x + cell.bbox.width).append(", ")
                      .append(cell.bbox.y + cell.bbox.height).append("]");

                    // 仅当 possibleRowspan > 1 时输出
                    if (cell.possibleRowspan > 1) {
                        sb.append(", \"rowspan_hint\": ").append(cell.possibleRowspan);
                        if (cell.rowspanHintReason != null) {
                            sb.append(", \"rowspan_hint_reason\": \"").append(cell.rowspanHintReason).append("\"");
                        }
                    }

                    // 仅当 possibleColspan > 1 时输出
                    if (cell.possibleColspan > 1) {
                        sb.append(", \"colspan_hint\": ").append(cell.possibleColspan);
                        if (cell.colspanHintReason != null) {
                            sb.append(", \"colspan_hint_reason\": \"").append(cell.colspanHintReason).append("\"");
                        }
                    }

                    sb.append("}");
                }

                sb.append("]}");
            }

            sb.append("\n      ]\n    }");
        }

        sb.append("\n  ]\n}");
        return sb.toString();
    }

    /**
     * 输出为扁平化的 JSON 格式（每个 table 对象包含 pageIndex 和 tableCount）
     */
    private static String tablesToJsonFlat(List<TableWithMeta> tablesWithMeta) {
        // 统计每页的表格数量
        Map<Integer, Integer> pageTableCounts = new HashMap<>();
        for (TableWithMeta tm : tablesWithMeta) {
            pageTableCounts.put(tm.pageIndex,
                pageTableCounts.getOrDefault(tm.pageIndex, 0) + 1);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"tables\": [\n");

        for (int i = 0; i < tablesWithMeta.size(); i++) {
            TableWithMeta tm = tablesWithMeta.get(i);
            Table table = tm.table;
            int tableCount = pageTableCounts.get(tm.pageIndex);

            if (i > 0) sb.append(",\n");
            sb.append("    {\n");

            // 添加页面信息
            sb.append("      \"pageIndex\": ").append(tm.pageIndex).append(",\n");
            sb.append("      \"tableCount\": ").append(tableCount).append(",\n");

            // 表格边界框
            sb.append("      \"bbox\": [").append(table.bbox.x).append(", ")
              .append(table.bbox.y).append(", ")
              .append(table.bbox.x + table.bbox.width).append(", ")
              .append(table.bbox.y + table.bbox.height).append("],\n");

            // 表格行
            sb.append("      \"rows\": [\n");

            for (int r = 0; r < table.rows.size(); r++) {
                List<Cell> row = table.rows.get(r);
                if (r > 0) sb.append(",\n");
                sb.append("        {\"cells\": [");

                for (int c = 0; c < row.size(); c++) {
                    Cell cell = row.get(c);
                    if (c > 0) sb.append(", ");
                    sb.append("{\"text\": \"").append(escapeJson(cell.text))
                      .append("\", \"bbox\": [")
                      .append(cell.bbox.x).append(", ")
                      .append(cell.bbox.y).append(", ")
                      .append(cell.bbox.x + cell.bbox.width).append(", ")
                      .append(cell.bbox.y + cell.bbox.height).append("]");

                    // 仅当 possibleRowspan > 1 时输出
                    if (cell.possibleRowspan > 1) {
                        sb.append(", \"rowspan_hint\": ").append(cell.possibleRowspan);
                        if (cell.rowspanHintReason != null) {
                            sb.append(", \"rowspan_hint_reason\": \"").append(escapeJson(cell.rowspanHintReason)).append("\"");
                        }
                    }

                    // 仅当 possibleColspan > 1 时输出
                    if (cell.possibleColspan > 1) {
                        sb.append(", \"colspan_hint\": ").append(cell.possibleColspan);
                        if (cell.colspanHintReason != null) {
                            sb.append(", \"colspan_hint_reason\": \"").append(escapeJson(cell.colspanHintReason)).append("\"");
                        }
                    }

                    sb.append("}");
                }

                sb.append("]}");
            }

            sb.append("\n      ]\n    }");
        }

        sb.append("\n  ]\n}");
        return sb.toString();
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    // ========== 静态工具方法（公开接口） ==========

    /**
     * 从 PDF 文件中提取表格
     *
     * @param pdfPath PDF 文件路径
     * @return JSON 格式的表格数据
     */
    public static String extractTablesFromPdf(String pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            List<TableWithMeta> allTables = new ArrayList<>();

            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                System.out.println("\n========== 处理页面 " + pageIndex + " ==========");

                // 使用 Tabula 提取表格
                List<Table> tables = extractTablesWithTabula(document, pageIndex);

                if (tables.isEmpty()) {
                    System.out.println("Page " + pageIndex + ": 未检测到表格，跳过");
                    continue;
                }

                // 后处理：单元格内段落合并
                for (Table table : tables) {
                    softMergeWithinCell(table);
                    System.out.println("[V2] ⑥ 单元格内段落合并完成");
                }

                // 后处理：跨行续行合并
                for (Table table : tables) {
                    mergeInterRowContinuation(table);
                }

                // 将表格添加到结果列表，附带页面信息
                for (int i = 0; i < tables.size(); i++) {
                    allTables.add(new TableWithMeta(tables.get(i), pageIndex, i));
                }

                System.out.println("Page " + pageIndex + ": found " + tables.size() + " tables");
            }

            return tablesToJsonFlat(allTables);
        }
    }

    /**
     * 带元信息的表格（包含 pageIndex 和 tableIndex）
     */
    private static class TableWithMeta {
        Table table;
        int pageIndex;
        int tableIndexInPage;

        TableWithMeta(Table table, int pageIndex, int tableIndexInPage) {
            this.table = table;
            this.pageIndex = pageIndex;
            this.tableIndexInPage = tableIndexInPage;
        }
    }

    /**
     * 提取表格并保存为 JSON 文件
     */
    public static void extractAndSave(String pdfPath, String outputJsonPath) throws IOException {
        String json = extractTablesFromPdf(pdfPath);

        try (FileWriter writer = new FileWriter(outputJsonPath)) {
            writer.write(json);
        }

        System.out.println("Table extraction completed. Output saved to: " + outputJsonPath);
    }

    /**
     * 提取表格并打印文本内容（用于调试）
     */
    public static void printTableContents(String pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                List<Table> tables = extractTablesWithTabula(document, pageIndex);

                // 后处理
                for (Table table : tables) {
                    softMergeWithinCell(table);
                    mergeInterRowContinuation(table);
                }

                System.out.println("\n=== Page " + pageIndex + " ===");

                for (int t = 0; t < tables.size(); t++) {
                    Table table = tables.get(t);
                    System.out.println("\nTable " + (t + 1) + ":");

                    for (int r = 0; r < table.rows.size(); r++) {
                        List<Cell> row = table.rows.get(r);
                        System.out.print("  Row " + r + ": ");
                        for (int c = 0; c < row.size(); c++) {
                            Cell cell = row.get(c);
                            System.out.print("[" + cell.text + "] ");
                        }
                        System.out.println();
                    }
                }
            }
        }
    }

    /**
     * 测试入口
     */
    public static void main(String[] args) {
        String pdfPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\task\\1979102567573037058\\1979102567573037058.pdf";

        // 生成与PDF相同目录的输出路径
        java.io.File pdfFile = new java.io.File(pdfPath);
        String pdfDir = pdfFile.getParent();
        String pdfName = pdfFile.getName().replaceFirst("[.][^.]+$", "");  // 去除扩展名
        String outputPath = pdfDir + java.io.File.separator + pdfName + "_tables_tabula.json";

        try {
            // 方式1：打印到控制台（调试用）
            printTableContents(pdfPath);

            // 方式2：保存为 JSON
            extractAndSave(pdfPath, outputPath);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}