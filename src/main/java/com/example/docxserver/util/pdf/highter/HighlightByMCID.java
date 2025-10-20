package com.example.docxserver.util.pdf.highter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

/**
 * 基于MCID的PDF文本高亮工具
 *
 * 核心原理：
 * 1. 解析PDF页面内容流中的Marked Content标记（BDC/EMC操作符）
 * 2. 追踪MCID栈，识别目标MCID区域
 * 3. 收集目标MCID内的TextPosition（包含精确坐标）
 * 4. 将TextPosition转换为QuadPoints（四边形坐标）
 * 5. 创建PDAnnotationTextMarkup高亮注释
 *
 * PDF内容流示例：
 * /P <</MCID 5>> BDC          ← 开始标记，MCID=5
 *   BT                         ← Begin Text（文本块开始）
 *     /F1 12 Tf                ← 设置字体
 *     100 650 Td               ← 移动文本位置
 *     (这是文本内容) Tj          ← 显示文本
 *   ET                         ← End Text
 * EMC                          ← 结束标记
 *
 * 优势：
 * - 非破坏性：不修改原内容流，仅添加注释
 * - 精确定位：使用PDFBox计算的精确坐标（考虑了CTM、字体矩阵、旋转等）
 * - 跨阅读器兼容：符合PDF标准的Text Markup注释
 * - PDF/A友好：不影响文档结构
 */
public class HighlightByMCID {

    /**
     * MCID高亮器（继承PDFStreamEngine）
     *
     * 工作原理（与MCIDTextExtractor完全相同的实现）：
     * 1. 使用addOperator手动拦截BDC/EMC操作符
     * 2. 追踪currentMCID（单变量，与MCIDTextExtractor一致）
     * 3. 在showGlyph中收集目标MCID内的TextPosition
     * 4. 生成QuadPoints用于高亮注释
     *
     * 重要：此实现与MCIDTextExtractor保持完全一致，确保能成功提取MCID文本
     */
    static class McidHighlighter extends PDFStreamEngine {
        private final Set<Integer> targetMcids;
        private Integer currentMCID = null;  // 使用单变量（与MCIDTextExtractor一致）
        private final List<TextPosition> collectedPositions = new ArrayList<>();
        private boolean debugMode = false;

        /**
         * 构造函数
         * @param targetMcids 目标MCID集合
         */
        public McidHighlighter(Set<Integer> targetMcids) throws IOException {
            this.targetMcids = targetMcids;

            // 添加文本显示操作符（与MCIDTextExtractor完全相同）
            addOperator(new org.apache.pdfbox.contentstream.operator.text.BeginText(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.text.EndText(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.text.SetFontAndSize(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowText(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowTextAdjusted(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowTextLine(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowTextLineAndSpace(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.text.MoveText(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.text.MoveTextSetLeading(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.text.NextLine(this));

            // 添加图形状态操作符（与MCIDTextExtractor完全相同）
            addOperator(new org.apache.pdfbox.contentstream.operator.state.Concatenate(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.state.Restore(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.state.Save(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.state.SetMatrix(this));

            // 添加标记内容操作符 - 关键！（与MCIDTextExtractor完全相同）
            addOperator(new org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence(this) {
                @Override
                public void process(Operator operator, List<COSBase> arguments) throws IOException {
                    super.process(operator, arguments);
                    // BMC 操作符，可能没有MCID
                }
            });

            addOperator(new org.apache.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties(this) {
                @Override
                public void process(Operator operator, List<COSBase> arguments) throws IOException {
                    super.process(operator, arguments);

                    // BDC 操作符，从属性字典中提取MCID（与MCIDTextExtractor完全相同）
                    if (arguments.size() >= 2) {
                        COSBase properties = arguments.get(1);
                        if (properties instanceof COSName) {
                            // 间接引用，需要查找资源字典
                            // 简化处理：跳过（与MCIDTextExtractor一致）
                        } else if (properties instanceof COSDictionary) {
                            COSDictionary dict = (COSDictionary) properties;
                            if (dict.containsKey(COSName.MCID)) {
                                currentMCID = dict.getInt(COSName.MCID);

                                if (debugMode) {
                                    System.out.println("[BDC] MCID=" + currentMCID +
                                                     ", inTarget=" + targetMcids.contains(currentMCID));
                                }
                            }
                        }
                    }
                }
            });

            addOperator(new org.apache.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence(this) {
                @Override
                public void process(Operator operator, List<COSBase> arguments) throws IOException {
                    super.process(operator, arguments);
                    currentMCID = null; // 退出标记内容
                }
            });
        }

        /**
         * 设置调试模式
         */
        public void setDebugMode(boolean debugMode) {
            this.debugMode = debugMode;
        }

        /**
         * 判断是否应该收集文本（与MCIDTextExtractor完全相同）
         */
        private boolean shouldCollectText() {
            return currentMCID != null && targetMcids.contains(currentMCID);
        }

        /**
         * 显示字符 - 由父类调用（与MCIDTextExtractor类似，但收集TextPosition而非手动创建）
         */
        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
            if (shouldCollectText()) {
                // 获取Unicode字符
                String unicode = font.toUnicode(code);
                if (unicode != null) {
                    // 计算文本位置（与MCIDTextExtractor完全相同）
                    Matrix matrix = textRenderingMatrix.clone();
                    float fontSize = getGraphicsState().getTextState().getFontSize();
                    float horizontalScaling = getGraphicsState().getTextState().getHorizontalScaling() / 100f;

                    float x = matrix.getTranslateX();
                    float y = matrix.getTranslateY();
                    float width = displacement.getX() * fontSize * horizontalScaling;
                    float height = fontSize;

                    TextPosition textPosition = new TextPosition(
                        0, // rotation - 简化处理
                        0, // page width
                        0, // page height
                        matrix,
                        x,
                        y,
                        height,
                        width,
                        width,
                        unicode,
                        new int[]{code},
                        font,
                        fontSize,
                        (int) (fontSize * matrix.getScalingFactorY())
                    );

                    collectedPositions.add(textPosition);
                }
            }
        }

        /**
         * 生成QuadPoints数组（用于高亮注释）
         *
         * QuadPoints格式（PDF标准）：
         * - 每个字形/片段用8个浮点数表示（4个点的x,y坐标）
         * - 顺序：左下、右下、右上、左上（逆时针）
         * - 第1、2个点定义文本基线
         *
         * @return QuadPoints数组
         */
        public float[] generateQuadPoints() {
            List<Float> quadsList = new ArrayList<>();

            for (TextPosition tp : collectedPositions) {
                float x = tp.getXDirAdj();
                float y = tp.getYDirAdj();
                float width = tp.getWidthDirAdj();
                float height = tp.getHeightDir();

                // 计算四个角的坐标（逆时针：左下、右下、右上、左上）
                // 注意：PDFBox坐标系是左下角为原点，Y轴向上
                float x1 = x;           // 左下 X
                float y1 = y;           // 左下 Y
                float x2 = x + width;   // 右下 X
                float y2 = y;           // 右下 Y
                float x3 = x + width;   // 右上 X
                float y3 = y + height;  // 右上 Y
                float x4 = x;           // 左上 X
                float y4 = y + height;  // 左上 Y

                // 添加到QuadPoints（8个值）
                quadsList.add(x1); quadsList.add(y1);  // 左下
                quadsList.add(x2); quadsList.add(y2);  // 右下
                quadsList.add(x3); quadsList.add(y3);  // 右上
                quadsList.add(x4); quadsList.add(y4);  // 左上
            }

            // 转换为数组
            float[] quads = new float[quadsList.size()];
            for (int i = 0; i < quadsList.size(); i++) {
                quads[i] = quadsList.get(i);
            }
            return quads;
        }

        /**
         * 计算边界框（Rect）
         * PDF注释必须有Rect属性，表示注释的边界框
         *
         * @return 边界框矩形
         */
        public PDRectangle calculateBoundingBox() {
            if (collectedPositions.isEmpty()) {
                return new PDRectangle(0, 0, 0, 0);
            }

            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            for (TextPosition tp : collectedPositions) {
                float x = tp.getXDirAdj();
                float y = tp.getYDirAdj();
                float width = tp.getWidthDirAdj();
                float height = tp.getHeightDir();

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + width);
                maxY = Math.max(maxY, y + height);
            }

            return new PDRectangle(minX, minY, maxX - minX, maxY - minY);
        }

        /**
         * 获取收集到的文本内容（用于调试）
         */
        public String getCollectedText() {
            StringBuilder sb = new StringBuilder();
            for (TextPosition tp : collectedPositions) {
                sb.append(tp.getUnicode());
            }
            return sb.toString();
        }

        /**
         * 获取收集到的字形数量（用于调试）
         */
        public int getCollectedPositionsCount() {
            return collectedPositions.size();
        }
    }

    /**
     * MCID扫描器（用于发现页面上的所有MCID）
     * 增强版：打印调试信息 + 支持名字引用
     */
    static class McidScanner extends PDFTextStripper {
        private final Set<Integer> foundMcids = new LinkedHashSet<>();
        private boolean debugMode = false;

        public McidScanner() throws IOException {
            super();
        }

        public McidScanner(boolean debugMode) throws IOException {
            super();
            this.debugMode = debugMode;
        }

        @Override
        public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
            super.beginMarkedContentSequence(tag, properties);

            if (debugMode) {
                System.out.println("[Scanner-BDC] tag=" + tag + ", properties=" + properties);
            }

            // 提取MCID
            if (properties != null && properties.containsKey(COSName.MCID)) {
                int mcid = properties.getInt(COSName.MCID);
                foundMcids.add(mcid);
                if (debugMode) {
                    System.out.println("  -> 发现MCID: " + mcid);
                }
            }
        }

        public Set<Integer> getFoundMcids() {
            return foundMcids;
        }
    }

    /**
     * 从结构树中扫描页面上的所有MCID
     *
     * @param doc PDF文档对象
     * @param pageIndex 页码（从0开始）
     * @return 该页面上所有MCID的集合
     * @throws IOException 文件操作异常
     */
    public static Set<Integer> scanMcidsFromStructureTree(PDDocument doc, int pageIndex) throws IOException {
        Set<Integer> mcids = new LinkedHashSet<>();

        // 获取结构树根节点
        if (doc.getDocumentCatalog() == null ||
            doc.getDocumentCatalog().getStructureTreeRoot() == null) {
            return mcids;
        }

        org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot structTreeRoot =
            doc.getDocumentCatalog().getStructureTreeRoot();

        PDPage targetPage = doc.getPage(pageIndex);

        // 递归遍历结构树，收集该页面的MCID
        for (Object kid : structTreeRoot.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;
                collectMcidsFromElement(element, targetPage, mcids);
            }
        }

        return mcids;
    }

    /**
     * 递归收集指定页面的MCID
     */
    private static void collectMcidsFromElement(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element,
            PDPage targetPage,
            Set<Integer> mcids) throws IOException {

        for (Object kid : element.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                // 递归处理子结构元素
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;
                collectMcidsFromElement(childElement, targetPage, mcids);

            } else if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent) {
                // PDMarkedContent 包含MCID信息
                org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent mc =
                    (org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent) kid;

                Integer mcid = mc.getMCID();
                PDPage page = element.getPage();

                if (mcid != null && page != null && page.equals(targetPage)) {
                    mcids.add(mcid);
                }

            } else if (kid instanceof Integer) {
                // 直接的MCID整数
                Integer mcid = (Integer) kid;
                PDPage page = element.getPage();

                if (page != null && page.equals(targetPage)) {
                    mcids.add(mcid);
                }
            }
        }
    }

    /**
     * 扫描页面上的所有MCID（兼容方法，优先使用结构树）
     *
     * @param doc PDF文档对象
     * @param pageIndex 页码（从0开始）
     * @return 该页面上所有MCID的集合（按出现顺序）
     * @throws IOException 文件操作异常
     */
    public static Set<Integer> scanMcidsOnPage(PDDocument doc, int pageIndex) throws IOException {
        // 方法1：从结构树提取（推荐）
        Set<Integer> mcids = scanMcidsFromStructureTree(doc, pageIndex);

        if (!mcids.isEmpty()) {
            return mcids;
        }

        // 方法2：从内容流扫描（fallback）
        McidScanner scanner = new McidScanner();
        PDPage page = doc.getPage(pageIndex);
        scanner.processPage(page);
        return scanner.getFoundMcids();
    }

    /**
     * 获取页面上前N个MCID
     *
     * @param doc PDF文档对象
     * @param pageIndex 页码（从0开始）
     * @param count 要获取的MCID数量
     * @return MCID集合
     * @throws IOException 文件操作异常
     */
    public static Set<Integer> getFirstNMcids(PDDocument doc, int pageIndex, int count) throws IOException {
        Set<Integer> allMcids = scanMcidsOnPage(doc, pageIndex);
        Set<Integer> result = new LinkedHashSet<>();
        int i = 0;
        for (Integer mcid : allMcids) {
            if (i >= count) break;
            result.add(mcid);
            i++;
        }
        return result;
    }

    /**
     * 为指定MCID添加高亮注释
     *
     * @param doc PDF文档对象
     * @param pageIndex 页码（从0开始）
     * @param mcids 目标MCID集合
     * @param color RGB颜色数组（如 {1f, 1f, 0f} 表示黄色）
     * @param opacity 透明度（0.0-1.0）
     * @throws IOException 文件操作异常
     */
    public static void highlightByMcid(
            PDDocument doc,
            int pageIndex,
            Set<Integer> mcids,
            float[] color,
            float opacity) throws IOException {

        // 1. 创建MCID高亮器
        McidHighlighter highlighter = new McidHighlighter(mcids);
        highlighter.setDebugMode(true);  // 启用调试模式

        // 2. 处理页面（PDFBox会自动解析内容流，调用beginMarkedContentSequence等方法）
        PDPage page = doc.getPage(pageIndex);
        System.out.println("[调试] 开始处理页面，目标MCID: " + mcids);
        highlighter.processPage(page);
        System.out.println("[调试] 页面处理完成，收集到 " + highlighter.getCollectedPositionsCount() + " 个字形");

        // 3. 生成QuadPoints
        float[] quadPoints = highlighter.generateQuadPoints();

        if (quadPoints.length == 0) {
            System.out.println("[警告] 页面 " + (pageIndex + 1) + " 未找到MCID " + mcids + " 的内容");
            return;
        }

        // 4. 创建高亮注释（PDFBox 3.0方式）
        COSDictionary dict = new COSDictionary();
        dict.setName(COSName.TYPE, "Annot");
        dict.setName(COSName.SUBTYPE, "Highlight");

        PDAnnotation annotation = PDAnnotation.createAnnotation(dict);
        PDAnnotationTextMarkup highlight = (PDAnnotationTextMarkup) annotation;

        // 5. 设置QuadPoints和边界框
        highlight.setQuadPoints(quadPoints);
        highlight.setRectangle(highlighter.calculateBoundingBox());

        // 6. 设置颜色和透明度
        PDColor pdColor = new PDColor(color, PDDeviceRGB.INSTANCE);
        highlight.setColor(pdColor);
        highlight.setConstantOpacity(opacity);

        // 7. 设置为可打印（在打印时显示）
        highlight.setPrinted(true);

        // 8. 添加到页面
        page.getAnnotations().add(highlight);

        System.out.println("[成功] 页面 " + (pageIndex + 1) + " 高亮了 " +
                         (quadPoints.length / 8) + " 个字形，MCID: " + mcids);
        System.out.println("       文本内容: " + highlighter.getCollectedText());
    }

    /**
     * 测试方法：高亮指定PDF中的MCID
     *
     * 使用示例：
     * - 输入PDF必须是Tagged PDF（包含MCID标记）
     * - 通过结构树或其他方式确定要高亮的MCID
     */
    public static void main(String[] args) throws IOException {
        // 测试参数
        String inputPdf = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217_A2b.pdf";
        String outputPdf = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217_mcid_highlighted.pdf";

        int pageIndex = 19;  // 第20页（从0开始）
        Set<Integer> targetMcids = new HashSet<>(Arrays.asList(5, 7, 10));  // 测试MCID

        // 黄色高亮，30%透明度
        float[] color = {1.0f, 1.0f, 0.0f};  // RGB: 黄色
        float opacity = 0.3f;

        // 打开PDF
        File inputFile = new File(inputPdf);
        if (!inputFile.exists()) {
            System.err.println("输入文件不存在: " + inputPdf);
            return;
        }

        try (PDDocument doc = Loader.loadPDF(inputFile)) {
            System.out.println("=== MCID高亮测试 ===");
            System.out.println("输入文件: " + inputPdf);
            System.out.println("目标页面: " + (pageIndex + 1));
            System.out.println();

            // 步骤1：扫描页面，发现所有MCID
            System.out.println("步骤1：扫描页面上的所有MCID...");
            Set<Integer> allMcids = scanMcidsOnPage(doc, pageIndex);
            System.out.println("  发现 " + allMcids.size() + " 个MCID: " + allMcids);
            System.out.println();

            // 步骤1.5：调试 - 查看内容流中的BDC操作符
            System.out.println("步骤1.5：调试 - 扫描内容流中的MCID（前10个BDC）...");
            McidScanner debugScanner = new McidScanner(true);
            debugScanner.setStartPage(pageIndex + 1);
            debugScanner.setEndPage(pageIndex + 1);
            StringWriter writer = new StringWriter();
            debugScanner.writeText(doc, writer);
            System.out.println("  内容流扫描完成，发现MCID: " + debugScanner.getFoundMcids());
            System.out.println();

            // 步骤2：取前3个MCID进行测试
            System.out.println("步骤2：选择前3个MCID进行高亮测试...");
            targetMcids = getFirstNMcids(doc, pageIndex, 3);
            System.out.println("  选中的MCID: " + targetMcids);
            System.out.println();

            if (targetMcids.isEmpty()) {
                System.out.println("[警告] 页面上没有找到任何MCID，无法测试");
                return;
            }

            // 步骤3：执行高亮
            System.out.println("步骤3：执行高亮...");
            highlightByMcid(doc, pageIndex, targetMcids, color, opacity);

            // 步骤4：保存结果
            System.out.println();
            System.out.println("步骤4：保存文件...");
            doc.save(outputPdf);
            System.out.println("  输出文件: " + outputPdf);
            System.out.println();
            System.out.println("=== 测试完成 ===");

        } catch (Exception e) {
            System.err.println("高亮失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}