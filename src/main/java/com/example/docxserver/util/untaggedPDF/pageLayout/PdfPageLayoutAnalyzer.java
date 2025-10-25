package com.example.docxserver.util.untaggedPDF.pageLayout;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * PDF 页面布局分析器 - 表格和段落的统一处理中心
 *
 * 核心思路（Lattice 算法）：
 * 1. 继承 PDFGraphicsStreamEngine，一次遍历同时收集文字和线段
 * 2. 在 showGlyph 中收集文本块的坐标
 * 3. 在图形操作中收集水平/垂直线段
 * 4. 页面结束时用线段构建网格（lattice）
 * 5. 将文本投放到网格单元格中
 */
public class PdfPageLayoutAnalyzer extends PDFGraphicsStreamEngine {

    // ========== 数据结构 ==========

    /** 文本块 */
    public static class TextBlock {
        public Rectangle2D.Double bbox;
        public String text;
        public float fontSize;

        public TextBlock(Rectangle2D.Double bbox, String text, float fontSize) {
            this.bbox = bbox;
            this.text = text;
            this.fontSize = fontSize;
        }
    }

    /** 线段 */
    public static class Line {
        public Point2D.Double start;
        public Point2D.Double end;
        public boolean isHorizontal;
        public boolean isVertical;

        public Line(Point2D.Double start, Point2D.Double end) {
            this.start = start;
            this.end = end;
            double dx = Math.abs(end.x - start.x);
            double dy = Math.abs(end.y - start.y);
            this.isHorizontal = dy < 2.0 && dx > 5.0;  // 容差2点，最小长度5点
            this.isVertical = dx < 2.0 && dy > 5.0;
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

    // ========== 收集的数据 ==========

    private List<TextBlock> textBlocks = new ArrayList<>();
    private List<Line> lines = new ArrayList<>();
    private List<Table> tables = new ArrayList<>();

    // 当前路径追踪（用于收集线段）
    private Point2D.Double currentPoint = null;
    private List<Point2D.Double> currentPath = new ArrayList<>();

    // 当前文本状态（用于合并字符为词）
    private StringBuilder currentWord = new StringBuilder();
    private Rectangle2D.Double currentWordBbox = null;
    private float currentFontSize = 0;

    // ========== 构造函数 ==========

    public PdfPageLayoutAnalyzer(PDPage page) {
        super(page);
    }

    /**
     * 提取页面中的表格
     * 这是主入口方法，会触发内容流的解析
     */
    public void extract() throws IOException {
        // 调用父类的 processPage 方法来解析页面内容流
        // 这会触发 showGlyph、moveTo、lineTo 等回调方法
        processPage(getPage());

        // 解析完成后，构建表格
        buildTables();
    }

    // ========== 文本收集 ==========

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
        super.showGlyph(textRenderingMatrix, font, code, displacement);

        // 获取字形的包围框
        try {
            String unicode = font.toUnicode(code);
            if (unicode == null || unicode.trim().isEmpty()) {
                return;
            }

            // 计算字形的基线坐标
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float x = textRenderingMatrix.getTranslateX();
            float y = textRenderingMatrix.getTranslateY();
            float height = textRenderingMatrix.getScaleY();
            float width = displacement.getX() * textRenderingMatrix.getScaleX();

            Point2D.Double point = new Point2D.Double(x, y);
            ctm.transform(point);

            Rectangle2D.Double glyphBbox = new Rectangle2D.Double(
                point.x,
                point.y,
                width,
                height
            );

            // 合并到当前词
            if (currentWordBbox == null) {
                currentWordBbox = glyphBbox;
                currentFontSize = height;
                currentWord.append(unicode);
            } else {
                // 判断是否属于同一个词（横向距离小于字体大小）
                double gap = glyphBbox.x - (currentWordBbox.x + currentWordBbox.width);
                if (gap < currentFontSize * 0.3) {
                    // 扩展当前词
                    currentWordBbox.add(glyphBbox);
                    currentWord.append(unicode);
                } else {
                    // 保存当前词，开始新词
                    flushCurrentWord();
                    currentWordBbox = glyphBbox;
                    currentFontSize = height;
                    currentWord.append(unicode);
                }
            }
        } catch (Exception e) {
            // 忽略字形处理错误
        }
    }

    private void flushCurrentWord() {
        if (currentWord.length() > 0 && currentWordBbox != null) {
            textBlocks.add(new TextBlock(currentWordBbox, currentWord.toString(), currentFontSize));
            currentWord.setLength(0);
            currentWordBbox = null;
        }
    }

    // ========== 图形收集（线段） ==========

    @Override
    public void moveTo(float x, float y) throws IOException {
        currentPoint = transformPoint(x, y);
        currentPath.clear();
        currentPath.add(currentPoint);
    }

    @Override
    public void lineTo(float x, float y) throws IOException {
        Point2D.Double endPoint = transformPoint(x, y);
        currentPath.add(endPoint);
        currentPoint = endPoint;
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
        // 表格通常不使用曲线，简化处理：只记录终点
        currentPoint = transformPoint(x3, y3);
        currentPath.add(currentPoint);
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {
        // 矩形 → 4条线段
        currentPath.clear();
        currentPath.add(new Point2D.Double(p0.getX(), p0.getY()));
        currentPath.add(new Point2D.Double(p1.getX(), p1.getY()));
        currentPath.add(new Point2D.Double(p2.getX(), p2.getY()));
        currentPath.add(new Point2D.Double(p3.getX(), p3.getY()));
        currentPath.add(new Point2D.Double(p0.getX(), p0.getY())); // 闭合
    }

    @Override
    public void strokePath() throws IOException {
        extractLinesFromPath();
        currentPath.clear();
    }

    @Override
    public void fillPath(int windingRule) throws IOException {
        // 填充路径也可能是表格背景，提取线段
        extractLinesFromPath();
        currentPath.clear();
    }

    @Override
    public void fillAndStrokePath(int windingRule) throws IOException {
        extractLinesFromPath();
        currentPath.clear();
    }

    @Override
    public void closePath() throws IOException {
        if (!currentPath.isEmpty()) {
            Point2D.Double firstPoint = currentPath.get(0);
            currentPath.add(new Point2D.Double(firstPoint.x, firstPoint.y));
        }
    }

    @Override
    public void endPath() throws IOException {
        currentPath.clear();
    }

    private void extractLinesFromPath() {
        for (int i = 0; i < currentPath.size() - 1; i++) {
            Point2D.Double p1 = currentPath.get(i);
            Point2D.Double p2 = currentPath.get(i + 1);
            Line line = new Line(p1, p2);
            if (line.isHorizontal || line.isVertical) {
                lines.add(line);
            }
        }
    }

    private Point2D.Double transformPoint(float x, float y) {
        try {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            Point2D.Double point = new Point2D.Double(x, y);
            ctm.transform(point);
            return point;
        } catch (Exception e) {
            return new Point2D.Double(x, y);
        }
    }

    // ========== 未使用的图形操作 ==========

    @Override
    public void clip(int windingRule) throws IOException {
        // 忽略
    }

    @Override
    public void shadingFill(COSName shadingName) throws IOException {
        // 忽略
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        // 忽略
    }

    @Override
    public Point2D getCurrentPoint() throws IOException {
        return currentPoint;
    }

    // ========== 网格构建与文字落格 ==========

    /**
     * 处理收集到的数据，构建表格
     */
    private void buildTables() {
        flushCurrentWord(); // 确保最后一个词也被保存

        if (lines.isEmpty()) {
            return; // 无线段，无法构建表格
        }

        // 1. 提取水平线和垂直线
        List<Line> hLines = new ArrayList<>();
        List<Line> vLines = new ArrayList<>();
        for (Line line : lines) {
            if (line.isHorizontal) hLines.add(line);
            if (line.isVertical) vLines.add(line);
        }

        if (hLines.isEmpty() || vLines.isEmpty()) {
            return; // 需要同时有水平和垂直线
        }

        // 2. 提取唯一的 Y 坐标（行边界）和 X 坐标（列边界）
        Set<Double> yCoords = new TreeSet<>();
        Set<Double> xCoords = new TreeSet<>();

        for (Line line : hLines) {
            yCoords.add(round(line.start.y, 1.0));
        }
        for (Line line : vLines) {
            xCoords.add(round(line.start.x, 1.0));
        }

        List<Double> yList = new ArrayList<>(yCoords);
        List<Double> xList = new ArrayList<>(xCoords);

        if (yList.size() < 2 || xList.size() < 2) {
            return; // 至少需要2条线才能形成单元格
        }

        // 3. 构建网格单元格
        Table table = new Table(new Rectangle2D.Double(
            xList.get(0),
            yList.get(0),
            xList.get(xList.size() - 1) - xList.get(0),
            yList.get(yList.size() - 1) - yList.get(0)
        ));

        for (int r = 0; r < yList.size() - 1; r++) {
            List<Cell> row = new ArrayList<>();
            for (int c = 0; c < xList.size() - 1; c++) {
                double x1 = xList.get(c);
                double y1 = yList.get(r);
                double x2 = xList.get(c + 1);
                double y2 = yList.get(r + 1);

                Rectangle2D.Double cellBbox = new Rectangle2D.Double(x1, y1, x2 - x1, y2 - y1);
                Cell cell = new Cell(cellBbox, r, c);
                row.add(cell);
            }
            table.rows.add(row);
        }

        // 4. 将文本块投放到单元格
        for (TextBlock block : textBlocks) {
            for (List<Cell> row : table.rows) {
                for (Cell cell : row) {
                    if (cell.bbox.intersects(block.bbox)) {
                        if (cell.text.length() > 0) {
                            cell.text += " ";
                        }
                        cell.text += block.text;
                    }
                }
            }
        }

        tables.add(table);
    }

    private double round(double value, double precision) {
        return Math.round(value / precision) * precision;
    }

    // ========== 获取结果 ==========

    public List<Table> getTables() {
        return tables;
    }

    public List<TextBlock> getTextBlocks() {
        return textBlocks;
    }

    public List<Line> getLines() {
        return lines;
    }

    /**
     * 输出为 JSON 格式（简化版，可用 Gson/Jackson 替换）
     */
    public String toJson() {
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
                      .append(cell.bbox.y + cell.bbox.height).append("]}");
                }

                sb.append("]}");
            }

            sb.append("\n      ]\n    }");
        }

        sb.append("\n  ]\n}");
        return sb.toString();
    }

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    // ========== 静态工具方法（用于测试和使用） ==========

    /**
     * 从 PDF 文件中提取表格
     *
     * @param pdfPath PDF 文件路径
     * @return JSON 格式的表格数据
     */
    public static String extractTablesFromPdf(String pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            StringBuilder allTablesJson = new StringBuilder();
            allTablesJson.append("{\n  \"pages\": [\n");

            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                PDPage page = document.getPage(pageIndex);

                PdfPageLayoutAnalyzer analyzer = new PdfPageLayoutAnalyzer(page);
                analyzer.extract();

                List<Table> tables = analyzer.getTables();

                if (pageIndex > 0) {
                    allTablesJson.append(",\n");
                }

                allTablesJson.append("    {\n");
                allTablesJson.append("      \"pageIndex\": ").append(pageIndex).append(",\n");
                allTablesJson.append("      \"tableCount\": ").append(tables.size()).append(",\n");
                allTablesJson.append("      \"tables\": ").append(analyzer.toJson()).append("\n");
                allTablesJson.append("    }");

                System.out.println("Page " + pageIndex + ": found " + tables.size() + " tables, " +
                        analyzer.getTextBlocks().size() + " text blocks, " +
                        analyzer.getLines().size() + " lines");
            }

            allTablesJson.append("\n  ]\n}");
            return allTablesJson.toString();
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
                PDPage page = document.getPage(pageIndex);
                PdfPageLayoutAnalyzer analyzer = new PdfPageLayoutAnalyzer(page);
                analyzer.extract();

                List<Table> tables = analyzer.getTables();
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
        String outputPath = pdfDir + java.io.File.separator + pdfName + "_tables.json";

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