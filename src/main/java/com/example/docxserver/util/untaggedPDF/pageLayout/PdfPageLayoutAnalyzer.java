package com.example.docxserver.util.untaggedPDF.pageLayout;

import com.example.docxserver.util.untaggedPDF.pageLayout.dto.*;
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
        public int possibleRowspan = 0;  // 合并单元格提示（纵向）
        public int possibleColspan = 0;  // 合并单元格提示（横向）
        public String rowspanHintReason = null;
        public String colspanHintReason = null;
        public float fontSize = 10.0f;   // 字体大小（用于特征计算）
        public float rotation = 0.0f;    // 旋转角度（用于特征计算）
        public List<TextBlock> words = new ArrayList<>();  // V2: 存储文本块明细（用于软合并）

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

    /** 行带（用于V2管线） */
    public static class RowBand {
        public double top;
        public double bottom;
        public double centerY;
        public List<TextBlock> words = new ArrayList<>();

        public RowBand(double top, double bottom) {
            this.top = top;
            this.bottom = bottom;
            this.centerY = (top + bottom) / 2.0;
        }

        public static RowBand from(TextBlock word) {
            RowBand band = new RowBand(word.bbox.y, word.bbox.y + word.bbox.height);
            band.words.add(word);
            band.centerY = word.bbox.getCenterY();
            return band;
        }

        public void absorb(TextBlock word) {
            this.top = Math.min(this.top, word.bbox.y);
            this.bottom = Math.max(this.bottom, word.bbox.y + word.bbox.height);
            this.centerY = (this.top + this.bottom) / 2.0;
            this.words.add(word);
        }

        public RowBand close() {
            return this;
        }

        public double height() {
            return bottom - top;
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
    private Double currentBaselineY = null;
    private Double lastGlyphRightX = null;
    private Float currentRotation = null; // 0/90/180/270 的近似值

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

    /**
     * 计算文本旋转角度（归一化到 0/90/180/270）
     */
    private static float approxRotation(Matrix m) {
        // 取 textRenderingMatrix 的旋转角；只分象限，鲁棒即可
        double angle = Math.atan2(m.getShearY(), m.getScaleY());
        double deg = Math.toDegrees(angle);
        // 归一化到 [0,360)
        deg = (deg % 360 + 360) % 360;
        // 就近到 0/90/180/270
        double[] opts = {0, 90, 180, 270};
        double best = 0;
        double dmin = 1e9;
        for (double o : opts) {
            double d = Math.abs(deg - o);
            if (d < dmin) {
                dmin = d;
                best = o;
            }
        }
        return (float) best;
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
        super.showGlyph(textRenderingMatrix, font, code, displacement);

        try {
            String unicode = font.toUnicode(code);
            if (unicode == null || unicode.trim().isEmpty()) {
                return;
            }

            // 设备空间下的基线 & glyph bbox（近似）
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float x = textRenderingMatrix.getTranslateX();
            float y = textRenderingMatrix.getTranslateY();
            float h = Math.abs(textRenderingMatrix.getScaleY());
            float w = Math.abs(displacement.getX() * textRenderingMatrix.getScaleX());

            Point2D.Double p = new Point2D.Double(x, y);
            ctm.transform(p);

            Rectangle2D.Double glyphBbox = new Rectangle2D.Double(p.x, p.y, Math.max(0.5f, w), Math.max(0.5f, h));

            // 基于旋转象限做"同一行"判断（一般中文是 0°）
            float rot = approxRotation(textRenderingMatrix);
            double baselineY = (rot == 0 || rot == 180) ? p.y : p.x; // 竖排时改用 x 判断"同一行"
            double glyphLeftX  = p.x;
            double glyphRightX = p.x + glyphBbox.width;

            boolean newLine = false;
            if (currentBaselineY != null) {
                // 行距阈值：0.5 * 字体大小（经验值）
                double lineGap = Math.abs(baselineY - currentBaselineY);
                if (lineGap > Math.max(1.0, currentFontSize * 0.5f)) {
                    newLine = true;
                }
                // X 大幅回退（换行常见）：本 glyph 的左边 < 上个 glyph 的右边 - 0.4em
                if (!newLine && lastGlyphRightX != null) {
                    if (glyphLeftX + Math.max(1.0, currentFontSize * 0.4f) < lastGlyphRightX) {
                        newLine = true;
                    }
                }
                // 旋转象限或字体变化也触发换行
                if (!newLine && (currentRotation != null && Math.abs(rot - currentRotation) > 1e-3)) {
                    newLine = true;
                }
            }

            if (newLine) {
                flushCurrentWord();
            }

            if (currentWordBbox == null) {
                currentWordBbox = glyphBbox;
                currentFontSize = h;
                currentBaselineY = baselineY;
                currentRotation = rot;
                currentWord.append(unicode);
            } else {
                // 是否同一"词"（横向空隙阈值：0.3em）
                double gap = glyphBbox.x - (currentWordBbox.x + currentWordBbox.width);
                if (gap < Math.max(1.0, currentFontSize * 0.3f)) {
                    currentWordBbox.add(glyphBbox);
                    currentWord.append(unicode);
                } else {
                    flushCurrentWord();
                    currentWordBbox = glyphBbox;
                    currentFontSize = h;
                    currentBaselineY = baselineY;
                    currentRotation = rot;
                    currentWord.append(unicode);
                }
            }
            lastGlyphRightX = glyphRightX;
        } catch (Exception ignore) {}
    }

    private void flushCurrentWord() {
        if (currentWord.length() > 0 && currentWordBbox != null) {
            String text = currentWord.toString();

            // ========== DEBUG: 打印包含目标关键词的文本块 ==========
            boolean isDebugTarget = text.contains("通用条") || text.contains("款序号")
                                 || text.contains("通") || text.contains("用") || text.contains("条")
                                 || text.contains("款") || text.contains("序") || text.contains("号");

            if (isDebugTarget) {
                System.out.println("\n========== TEXT BLOCK DEBUG INFO ==========");
                System.out.println("Text Content: '" + text + "'");
                System.out.println("Text Length: " + text.length() + " characters");

                // Unicode 编码
                System.out.print("Unicode Codes: ");
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    System.out.print("U+" + Integer.toHexString(c).toUpperCase() + " ");
                }
                System.out.println();

                // 边界框信息
                System.out.println("\n--- Text Block BBox (Device Space) ---");
                System.out.println("bbox.x (left): " + currentWordBbox.x);
                System.out.println("bbox.y: " + currentWordBbox.y);
                System.out.println("bbox.width: " + currentWordBbox.width);
                System.out.println("bbox.height: " + currentWordBbox.height);
                System.out.println("Font Size: " + currentFontSize);

                System.out.println("\n--- Coordinate System Analysis ---");
                System.out.println("bbox.y (起点): " + currentWordBbox.y);
                System.out.println("bbox.y + height (终点): " + (currentWordBbox.y + currentWordBbox.height));
                System.out.println("Center Y: " + currentWordBbox.getCenterY());
                System.out.println("Baseline Y: " + currentBaselineY);

                System.out.println("\n--- 假设Y向下 ---");
                System.out.println("Top (视觉顶部): " + currentWordBbox.y);
                System.out.println("Bottom (视觉底部): " + (currentWordBbox.y + currentWordBbox.height));

                System.out.println("\n--- 假设Y向上 ---");
                System.out.println("Bottom (视觉底部): " + currentWordBbox.y);
                System.out.println("Top (视觉顶部): " + (currentWordBbox.y + currentWordBbox.height));

                // 位置信息
                System.out.println("\n--- Position Info ---");
                System.out.println("Right: " + (currentWordBbox.x + currentWordBbox.width));
                System.out.println("Center: (" + currentWordBbox.getCenterX() + ", " + currentWordBbox.getCenterY() + ")");
                System.out.println("Rotation: " + (currentRotation != null ? currentRotation + "°" : "0°"));

                // 查找周围的表格线
                System.out.println("\n--- Surrounding Table Lines ---");
                debugPrintSurroundingLines(currentWordBbox);

                System.out.println("==========================================\n");
            }
            // ========== END DEBUG ==========

            textBlocks.add(new TextBlock(currentWordBbox, text, currentFontSize));
        }
        currentWord.setLength(0);
        currentWordBbox = null;
        currentFontSize = 0;
        currentBaselineY = null;
        lastGlyphRightX = null;
    }

    /**
     * 调试方法：打印文本块周围的表格线
     */
    private void debugPrintSurroundingLines(Rectangle2D.Double bbox) {
        double tolerance = 5.0; // 5pt 容差

        // 查找上边线（水平线，Y坐标在文本块顶部附近）
        double topY = bbox.y + bbox.height;
        List<Line> topLines = new ArrayList<>();
        for (Line line : lines) {
            if (line.isHorizontal && Math.abs(line.start.y - topY) < tolerance) {
                // 检查X坐标是否重叠
                double lineMinX = Math.min(line.start.x, line.end.x);
                double lineMaxX = Math.max(line.start.x, line.end.x);
                double boxMinX = bbox.x;
                double boxMaxX = bbox.x + bbox.width;
                if (lineMaxX >= boxMinX && lineMinX <= boxMaxX) {
                    topLines.add(line);
                }
            }
        }

        // 查找下边线（水平线，Y坐标在文本块底部附近）
        double bottomY = bbox.y;
        List<Line> bottomLines = new ArrayList<>();
        for (Line line : lines) {
            if (line.isHorizontal && Math.abs(line.start.y - bottomY) < tolerance) {
                double lineMinX = Math.min(line.start.x, line.end.x);
                double lineMaxX = Math.max(line.start.x, line.end.x);
                double boxMinX = bbox.x;
                double boxMaxX = bbox.x + bbox.width;
                if (lineMaxX >= boxMinX && lineMinX <= boxMaxX) {
                    bottomLines.add(line);
                }
            }
        }

        // 查找左边线（垂直线，X坐标在文本块左侧附近）
        double leftX = bbox.x;
        List<Line> leftLines = new ArrayList<>();
        for (Line line : lines) {
            if (line.isVertical && Math.abs(line.start.x - leftX) < tolerance) {
                double lineMinY = Math.min(line.start.y, line.end.y);
                double lineMaxY = Math.max(line.start.y, line.end.y);
                double boxMinY = bbox.y;
                double boxMaxY = bbox.y + bbox.height;
                if (lineMaxY >= boxMinY && lineMinY <= boxMaxY) {
                    leftLines.add(line);
                }
            }
        }

        // 查找右边线（垂直线，X坐标在文本块右侧附近）
        double rightX = bbox.x + bbox.width;
        List<Line> rightLines = new ArrayList<>();
        for (Line line : lines) {
            if (line.isVertical && Math.abs(line.start.x - rightX) < tolerance) {
                double lineMinY = Math.min(line.start.y, line.end.y);
                double lineMaxY = Math.max(line.start.y, line.end.y);
                double boxMinY = bbox.y;
                double boxMaxY = bbox.y + bbox.height;
                if (lineMaxY >= boxMinY && lineMinY <= boxMaxY) {
                    rightLines.add(line);
                }
            }
        }

        // 打印找到的线
        System.out.println("Top Lines (near Y=" + String.format("%.2f", topY) + "):");
        if (topLines.isEmpty()) {
            System.out.println("  NONE found within " + tolerance + "pt");
        } else {
            for (Line line : topLines) {
                System.out.println("  H-Line: Y=" + String.format("%.2f", line.start.y) + ", X=[" +
                    String.format("%.2f", Math.min(line.start.x, line.end.x)) + " to " +
                    String.format("%.2f", Math.max(line.start.x, line.end.x)) + "], length=" +
                    String.format("%.2f", Math.abs(line.end.x - line.start.x)));
            }
        }

        System.out.println("Bottom Lines (near Y=" + String.format("%.2f", bottomY) + "):");
        if (bottomLines.isEmpty()) {
            System.out.println("  NONE found within " + tolerance + "pt");
        } else {
            for (Line line : bottomLines) {
                System.out.println("  H-Line: Y=" + String.format("%.2f", line.start.y) + ", X=[" +
                    String.format("%.2f", Math.min(line.start.x, line.end.x)) + " to " +
                    String.format("%.2f", Math.max(line.start.x, line.end.x)) + "], length=" +
                    String.format("%.2f", Math.abs(line.end.x - line.start.x)));
            }
        }

        System.out.println("Left Lines (near X=" + String.format("%.2f", leftX) + "):");
        if (leftLines.isEmpty()) {
            System.out.println("  NONE found within " + tolerance + "pt");
        } else {
            for (Line line : leftLines) {
                System.out.println("  V-Line: X=" + String.format("%.2f", line.start.x) + ", Y=[" +
                    String.format("%.2f", Math.min(line.start.y, line.end.y)) + " to " +
                    String.format("%.2f", Math.max(line.start.y, line.end.y)) + "], length=" +
                    String.format("%.2f", Math.abs(line.end.y - line.start.y)));
            }
        }

        System.out.println("Right Lines (near X=" + String.format("%.2f", rightX) + "):");
        if (rightLines.isEmpty()) {
            System.out.println("  NONE found within " + tolerance + "pt");
        } else {
            for (Line line : rightLines) {
                System.out.println("  V-Line: X=" + String.format("%.2f", line.start.x) + ", Y=[" +
                    String.format("%.2f", Math.min(line.start.y, line.end.y)) + " to " +
                    String.format("%.2f", Math.max(line.start.y, line.end.y)) + "], length=" +
                    String.format("%.2f", Math.abs(line.end.y - line.start.y)));
            }
        }

        System.out.println("Total lines in document: " + lines.size() +
            " (H: " + lines.stream().filter(l -> l.isHorizontal).count() +
            ", V: " + lines.stream().filter(l -> l.isVertical).count() + ")");
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
        if (currentPath.size() >= 2) {
            System.out.println("[extractLinesFromPath] 路径点数 = " + currentPath.size());
        }

        for (int i = 0; i < currentPath.size() - 1; i++) {
            Point2D.Double p1 = currentPath.get(i);
            Point2D.Double p2 = currentPath.get(i + 1);

            double dx = Math.abs(p2.x - p1.x);
            double dy = Math.abs(p2.y - p1.y);

            boolean isH = (dy < 2.0 && dx > 5.0);
            boolean isV = (dx < 2.0 && dy > 5.0);

            // 特别标记Y坐标在72-121范围内的横线（表头区域）
            double avgY = (p1.y + p2.y) / 2.0;
            boolean isHeaderArea = isH && avgY >= 70 && avgY <= 125;

            System.out.printf("[LINE] p1=(%.1f,%.1f) → p2=(%.1f,%.1f), dx=%.2f, dy=%.2f → 水平=%b, 垂直=%b",
                p1.x, p1.y, p2.x, p2.y, dx, dy, isH, isV);

            if (isHeaderArea) {
                System.out.printf(" ⚠ 表头区域横线 Y=%.2f", avgY);
            }
            System.out.println();

            Line line = new Line(p1, p2);
            if (line.isHorizontal || line.isVertical) {
                lines.add(line);
                System.out.println("  ✓ 添加成功");
            } else {
                System.out.println("  ✗ 被过滤掉（原因：" +
                    (dx <= 5.0 && dy <= 5.0 ? "线段太短" : "倾斜角度过大") + "）");
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

    // ========== 网格构建与文字落格（V2 管线） ==========

    /**
     * 处理收集到的数据，构建表格（只使用V2管线）
     */
    private void buildTables() {
        flushCurrentWord(); // 确保最后一个词也被保存

        if (lines.isEmpty()) {
            return; // 无线段，无法构建表格
        }

        System.out.println("\n========== V2 表格提取管线 ==========");
        System.out.println("原则：竖优先切列 | 文本主导行聚类 | 横线仅做证据 | 同列同行软合并");
        System.out.println("=====================================\n");

        buildTablesV2();
    }


    /**
     * V2 管线（新逻辑，按列优先）
     *
     * 核心改进：
     * 1. 竖优先切列（硬约束）- 从垂直线确定列边界
     * 2. 文本主导行聚类 - 每列内按基线聚类
     * 3. 横线仅做证据/补强 - 不是行边界的主要依据
     * 4. 软合并仅同列同行 - 禁止跨列/跨行合并
     */
    private void buildTablesV2() {
        System.out.println("[V2] 开始表格提取...");

        // Step 1: 分离横线和竖线
        List<Line> hLines = new ArrayList<>();
        List<Line> vLines = new ArrayList<>();
        for (Line line : lines) {
            if (line.isHorizontal) hLines.add(line);
            if (line.isVertical) vLines.add(line);
        }

        if (vLines.isEmpty()) {
            System.out.println("[V2] ⚠ 没有竖线，无法确定列边界");
            return;
        }

        // Step 2: ① 竖优先切列 - 从垂直线提取 X 坐标
        List<Double> xBreaks = computeVerticalBreaks(vLines);
        System.out.println("[V2] ① 竖线列边界: " + xBreaks);

        // Step 3: ② 文本按列分桶
        Map<Integer, List<TextBlock>> buckets = bucketWordsByColumn(textBlocks, xBreaks);
        System.out.println("[V2] ② 文本分桶完成: " + buckets.size() + " 列");

        // Step 4: ③ 列内行聚类
        Map<Integer, List<RowBand>> colRowBands = clusterRowsPerColumn(buckets);
        System.out.println("[V2] ③ 列内行聚类完成");

        // Step 5: ④ 多列对齐 - 暂时简化：直接合并所有行带
        List<Double> yBreaks = reconcileRowsAcrossColumns(colRowBands, hLines);
        System.out.println("[V2] ④ 行边界对齐: " + yBreaks.size() + " 个边界");

        // Step 6: ⑤ 生成单元格
        Table table = emitCells(xBreaks, yBreaks, buckets);
        System.out.println("[V2] ⑤ 生成单元格: " + table.rows.size() + " 行");

        // Step 7: ⑥ 单元格内段落合并
        softMergeWithinCell(table);
        System.out.println("[V2] ⑥ 单元格内段落合并完成");

        // Step 8: ⑦ 跨行续行合并（处理右列被拆分的情况）
        mergeInterRowContinuation(table);
        System.out.println("[V2] ⑦ 跨行续行合并完成");

        tables.add(table);
    }

    // ========== V2 辅助方法 ==========

    /**
     * ① 竖优先切列：从垂直线提取 X 断点
     *
     * 原理：
     * - 垂直线的X坐标代表列边界
     * - 将相近的X坐标聚合（容差EPS_X）
     * - 返回排序后的列边界列表
     */
    private List<Double> computeVerticalBreaks(List<Line> vLines) {
        if (vLines.isEmpty()) {
            return new ArrayList<>();
        }

        // 收集所有垂直线的X坐标
        Set<Double> xCoords = new TreeSet<>();
        for (Line line : vLines) {
            xCoords.add(line.start.x);
            xCoords.add(line.end.x);
        }

        // 聚合：将相邻且距离< EPS_X的坐标合并
        List<Double> xList = new ArrayList<>(xCoords);
        List<Double> xBreaks = new ArrayList<>();

        double current = xList.get(0);
        int count = 1;
        double sum = current;

        for (int i = 1; i < xList.size(); i++) {
            double x = xList.get(i);
            if (Math.abs(x - current) < TableExtractionConfig.EPS_X) {
                // 属于同一簇，累加求均值
                sum += x;
                count++;
            } else {
                // 新簇，保存当前簇的均值
                xBreaks.add(sum / count);
                current = x;
                sum = x;
                count = 1;
            }
        }
        // 最后一簇
        xBreaks.add(sum / count);

        System.out.println("[computeVerticalBreaks] 聚类前: " + xList.size() + " 个坐标");
        System.out.println("[computeVerticalBreaks] 聚类后: " + xBreaks.size() + " 个列边界");

        return xBreaks;
    }

    /**
     * ② 文本按列分桶：禁止跨列
     *
     * 原理：
     * - 根据文本块中心X坐标判断所属列
     * - 每个文本块必须且只能属于一列
     */
    private Map<Integer, List<TextBlock>> bucketWordsByColumn(List<TextBlock> words, List<Double> xBreaks) {
        Map<Integer, List<TextBlock>> buckets = new HashMap<>();

        // 初始化桶
        for (int i = 0; i < xBreaks.size() - 1; i++) {
            buckets.put(i, new ArrayList<>());
        }

        // 分配文本到列
        for (TextBlock word : words) {
            double cx = word.bbox.getCenterX();

            // 定位所属列
            int col = -1;
            for (int i = 0; i < xBreaks.size() - 1; i++) {
                if (cx >= xBreaks.get(i) && cx < xBreaks.get(i + 1)) {
                    col = i;
                    break;
                }
            }

            if (col >= 0) {
                buckets.get(col).add(word);
            }
        }

        // 按Y坐标排序每列内的文本（为后续行聚类做准备）
        for (List<TextBlock> bucket : buckets.values()) {
            bucket.sort(Comparator.comparingDouble(a -> a.bbox.getCenterY()));
        }

        return buckets;
    }

    /**
     * ③ 列内行聚类：文本主导
     *
     * 原理：
     * - 每列内的文本按基线Y坐标聚类成行带
     * - 使用简单的距离阈值（INTRA_COL_LINE_GAP_EM * fontSize）
     */
    private Map<Integer, List<RowBand>> clusterRowsPerColumn(Map<Integer, List<TextBlock>> buckets) {
        Map<Integer, List<RowBand>> result = new HashMap<>();

        for (Map.Entry<Integer, List<TextBlock>> entry : buckets.entrySet()) {
            int col = entry.getKey();
            List<TextBlock> words = entry.getValue();
            List<RowBand> bands = new ArrayList<>();

            if (words.isEmpty()) {
                result.put(col, bands);
                continue;
            }

            // 聚类：基于Y坐标距离
            RowBand currentBand = RowBand.from(words.get(0));

            for (int i = 1; i < words.size(); i++) {
                TextBlock word = words.get(i);
                double gap = Math.abs(word.bbox.getCenterY() - currentBand.centerY);
                double threshold = TableExtractionConfig.INTRA_COL_LINE_GAP_EM * word.fontSize;

                if (gap <= threshold) {
                    // 同一行带
                    currentBand.absorb(word);
                } else {
                    // 新行带
                    bands.add(currentBand.close());
                    currentBand = RowBand.from(word);
                }
            }
            bands.add(currentBand.close());

            result.put(col, bands);
        }

        return result;
    }

    /**
     * ④ 多列对齐：行边界支持度
     *
     * 暂时简化：收集所有列的行带边界，聚合后形成全局行边界
     * TODO: 实现完整的支持度计算
     */
    private List<Double> reconcileRowsAcrossColumns(Map<Integer, List<RowBand>> colRowBands, List<Line> hLines) {
        Set<Double> yCoords = new TreeSet<>();

        // 收集所有列的行带边界
        for (List<RowBand> bands : colRowBands.values()) {
            for (RowBand band : bands) {
                yCoords.add(band.top);
                yCoords.add(band.bottom);
            }
        }

        // 聚合相近的Y坐标
        List<Double> yList = new ArrayList<>(yCoords);
        List<Double> yBreaks = new ArrayList<>();

        if (yList.isEmpty()) {
            return yBreaks;
        }

        double current = yList.get(0);
        double sum = current;
        int count = 1;

        for (int i = 1; i < yList.size(); i++) {
            double y = yList.get(i);
            if (Math.abs(y - current) < TableExtractionConfig.EPS_Y) {
                sum += y;
                count++;
            } else {
                yBreaks.add(sum / count);
                current = y;
                sum = y;
                count = 1;
            }
        }
        yBreaks.add(sum / count);

        return yBreaks;
    }

    /**
     * ⑤ 生成单元格：按(row, col)笛卡尔生成
     *
     * 原理：
     * - 根据X/Y边界生成网格
     * - 将每列的文本分配到对应的单元格
     */
    private Table emitCells(List<Double> xBreaks, List<Double> yBreaks, Map<Integer, List<TextBlock>> buckets) {
        // 创建表格
        Table table = new Table(new Rectangle2D.Double(
            xBreaks.get(0),
            yBreaks.get(0),
            xBreaks.get(xBreaks.size() - 1) - xBreaks.get(0),
            yBreaks.get(yBreaks.size() - 1) - yBreaks.get(0)
        ));

        // 生成网格单元格
        for (int r = 0; r < yBreaks.size() - 1; r++) {
            List<Cell> row = new ArrayList<>();
            for (int c = 0; c < xBreaks.size() - 1; c++) {
                Rectangle2D.Double cellBbox = new Rectangle2D.Double(
                    xBreaks.get(c),
                    yBreaks.get(r),
                    xBreaks.get(c + 1) - xBreaks.get(c),
                    yBreaks.get(r + 1) - yBreaks.get(r)
                );
                row.add(new Cell(cellBbox, r, c));
            }
            table.rows.add(row);
        }

        // 文本投放：只从对应列的文本桶中分配，先保存到 words 列表
        for (int c = 0; c < xBreaks.size() - 1; c++) {
            List<TextBlock> colWords = buckets.get(c);
            if (colWords == null) continue;

            for (TextBlock word : colWords) {
                double cy = word.bbox.getCenterY();

                // 找到所属行
                int targetRow = -1;
                for (int r = 0; r < yBreaks.size() - 1; r++) {
                    if (cy >= yBreaks.get(r) && cy < yBreaks.get(r + 1)) {
                        targetRow = r;
                        break;
                    }
                }

                if (targetRow >= 0 && targetRow < table.rows.size()) {
                    Cell cell = table.rows.get(targetRow).get(c);
                    cell.words.add(word);  // V2: 保存 TextBlock，后续做软合并
                    if (cell.fontSize == 0) {
                        cell.fontSize = word.fontSize;
                    }
                }
            }
        }

        return table;
    }

    /**
     * ⑥ 单元格内段落合并（intra-cell soft merge）
     *
     * 规则：
     * - 按Y坐标排序单元格内的TextBlock
     * - 根据垂直间距分组为段落
     * - 用换行符连接段落
     */
    private void softMergeWithinCell(Table table) {
        for (List<Cell> row : table.rows) {
            for (Cell cell : row) {
                if (cell.words == null || cell.words.isEmpty()) {
                    cell.text = "";
                    continue;
                }

                // 1. 按Y坐标排序（top → bottom）
                cell.words.sort(Comparator.comparingDouble(w -> w.bbox.y));

                // 2. 按垂直间距分组为段落
                List<String> paragraphs = new ArrayList<>();
                StringBuilder currentPara = new StringBuilder();
                double lastBottom = cell.words.get(0).bbox.y;

                for (int i = 0; i < cell.words.size(); i++) {
                    TextBlock word = cell.words.get(i);
                    double gap = word.bbox.y - lastBottom;
                    double gapThreshold = TableExtractionConfig.SOFT_MERGE_GAP_EM * word.fontSize;

                    if (i > 0 && gap > gapThreshold) {
                        // 间隙超过阈值 → 新段落
                        paragraphs.add(currentPara.toString().trim());
                        currentPara.setLength(0);
                    }

                    // 拼接当前词
                    if (currentPara.length() > 0) {
                        currentPara.append(" ");
                    }
                    currentPara.append(word.text);

                    lastBottom = word.bbox.y + word.bbox.height;
                }

                // 最后一个段落
                if (currentPara.length() > 0) {
                    paragraphs.add(currentPara.toString().trim());
                }

                // 3. 用换行符连接段落
                cell.text = String.join("\n", paragraphs);

                // 可选：释放明细（节省内存）
                // cell.words = null;
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
     * - 合并到最近的锚行（优先上方，垂直距离 < 1.5 × medianRowHeight）
     * - 用换行符连接续行内容
     */
    private void mergeInterRowContinuation(Table table) {
        if (table.rows.isEmpty()) return;

        // 计算表格中位行高（用于距离阈值）
        List<Double> rowHeights = new ArrayList<>();
        for (List<Cell> row : table.rows) {
            if (!row.isEmpty()) {
                rowHeights.add(row.get(0).bbox.height);
            }
        }
        rowHeights.sort(Double::compare);
        double medianRowHeight = rowHeights.isEmpty() ? 20.0 :
            rowHeights.get(rowHeights.size() / 2);
        double distanceThreshold = 1.5 * medianRowHeight;

        System.out.println("[V2] ⑦ 续行合并: medianRowHeight=" + medianRowHeight +
                          ", threshold=" + distanceThreshold);

        // 从上到下遍历，查找续行并合并
        int numCols = table.rows.get(0).size();
        if (numCols < 3) {
            System.out.println("[V2] ⚠ 列数 < 3，跳过续行合并");
            return; // 至少要3列
        }

        for (int r = 0; r < table.rows.size(); r++) {
            List<Cell> row = table.rows.get(r);
            if (row.size() < 3) continue;

            // 检查是否为续行：左两列空，右列非空
            boolean isLeftEmpty = isEmpty(row.get(0).text) && isEmpty(row.get(1).text);
            boolean isRightNonEmpty = !isEmpty(row.get(2).text);

            if (isLeftEmpty && isRightNonEmpty) {
                System.out.println("[V2] 发现续行: row=" + r + ", 内容=" + row.get(2).text.substring(0, Math.min(30, row.get(2).text.length())));

                // 找最近的锚行（优先上方）
                int anchorRow = findNearestAnchorRow(table, r, distanceThreshold);

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
    }

    /**
     * 查找最近的锚行（左两列非空的行）
     *
     * @param table 表格
     * @param currentRow 当前续行的行号
     * @param distanceThreshold 距离阈值
     * @return 锚行行号，-1表示未找到
     */
    private int findNearestAnchorRow(Table table, int currentRow, double distanceThreshold) {
        double currentY = table.rows.get(currentRow).get(0).bbox.y;

        // 优先向上查找
        for (int r = currentRow - 1; r >= 0; r--) {
            List<Cell> row = table.rows.get(r);
            if (row.size() < 3) continue;

            // 检查是否为锚行：左两列至少一列非空
            boolean isAnchor = !isEmpty(row.get(0).text) || !isEmpty(row.get(1).text);

            if (isAnchor) {
                double anchorY = row.get(0).bbox.y;
                double distance = Math.abs(currentY - anchorY);

                if (distance <= distanceThreshold) {
                    return r;
                }
            }
        }

        // 若上方无锚行，向下查找
        for (int r = currentRow + 1; r < table.rows.size(); r++) {
            List<Cell> row = table.rows.get(r);
            if (row.size() < 3) continue;

            boolean isAnchor = !isEmpty(row.get(0).text) || !isEmpty(row.get(1).text);

            if (isAnchor) {
                double anchorY = row.get(0).bbox.y;
                double distance = Math.abs(currentY - anchorY);

                if (distance <= distanceThreshold) {
                    return r;
                }
            }
        }

        return -1; // 未找到
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
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
        String pdfPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\task\\1979102567573037058\\table.pdf";

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