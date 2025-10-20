package com.example.docxserver.util.pdf.highter;

import com.example.docxserver.util.MCIDTextExtractor;
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
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
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
     * 生成QuadPoints数组（用于高亮注释）- 必过版
     *
     * 修复内容：
     * 1. Y坐标转换到页面用户空间（左下原点）
     * 2. 处理宽度为0的情况（使用兜底值）
     * 3. ✅ **修复点序为 TL,TR,BL,BR**（PDF标准要求）
     * 4. ✅ **同行合并**（避免零宽度问题，提高可见性）
     *
     * QuadPoints格式（PDF标准）：
     * - 每个高亮区域用8个浮点数表示（4个点的x,y坐标）
     * - **正确顺序：TL(左上), TR(右上), BL(左下), BR(右下)**
     *
     * @param positions TextPosition列表
     * @param pageHeight 页面高度（用于Y坐标转换）
     * @return QuadPoints数组
     */
    private static float[] generateQuadPoints(List<TextPosition> positions, float pageHeight) {
        if (positions.isEmpty()) {
            return new float[0];
        }

        // 步骤1：将TextPosition转换为统一坐标系，并按行聚合
        List<List<TextPosition>> lines = groupIntoLines(positions);

        List<Float> quadsList = new ArrayList<>();

        // 步骤2：为每一行生成一个QuadPoints（同行合并）
        for (List<TextPosition> line : lines) {
            if (line.isEmpty()) continue;

            float xMin = Float.MAX_VALUE;
            float xMax = -Float.MAX_VALUE;
            float yLL = Float.MAX_VALUE;  // 左下角Y（行内最低点）
            float hMax = 0f;              // 行内最大高度

            // 遍历行内所有字符，计算外接矩形
            for (TextPosition tp : line) {
                float w = Math.max(0.01f, tp.getWidthDirAdj());  // 宽度兜底
                float h = Math.max(0.01f, tp.getHeightDir());    // 高度兜底
                float x = tp.getXDirAdj();

                // Y坐标转换：YDirAdj为负数时，取绝对值即为从底部算起的Y坐标
                // 例如：YDirAdj=-755.889 表示距离底部755.889点（即在页面顶部）
                float y = Math.abs(tp.getYDirAdj()) - h;

                xMin = Math.min(xMin, x);
                xMax = Math.max(xMax, x + w);
                yLL = Math.min(yLL, y);   // 取行内最低的下边缘
                hMax = Math.max(hMax, h);
            }

            // 生成QuadPoints - 正确顺序：TL, TR, BL, BR
            float yTop = yLL + hMax;  // 上边缘Y

            quadsList.add(xMin);  quadsList.add(yTop);  // TL (左上)
            quadsList.add(xMax);  quadsList.add(yTop);  // TR (右上)
            quadsList.add(xMin);  quadsList.add(yLL);   // BL (左下)
            quadsList.add(xMax);  quadsList.add(yLL);   // BR (右下)
        }

        // 转换为数组
        float[] quads = new float[quadsList.size()];
        for (int i = 0; i < quadsList.size(); i++) {
            quads[i] = quadsList.get(i);
        }
        return quads;
    }

    /**
     * 将TextPosition按行聚合
     * 简单策略：按Y坐标聚类（同一行的Y坐标接近）
     */
    private static List<List<TextPosition>> groupIntoLines(List<TextPosition> positions) {
        if (positions.isEmpty()) {
            return new ArrayList<>();
        }

        List<List<TextPosition>> lines = new ArrayList<>();
        List<TextPosition> currentLine = new ArrayList<>();
        currentLine.add(positions.get(0));

        float threshold = 2.0f;  // Y坐标差异阈值（点）

        for (int i = 1; i < positions.size(); i++) {
            TextPosition prev = positions.get(i - 1);
            TextPosition curr = positions.get(i);

            // 如果Y坐标接近，认为是同一行
            float yDiff = Math.abs(curr.getYDirAdj() - prev.getYDirAdj());

            if (yDiff <= threshold) {
                currentLine.add(curr);
            } else {
                // 新行
                lines.add(currentLine);
                currentLine = new ArrayList<>();
                currentLine.add(curr);
            }
        }

        // 添加最后一行
        if (!currentLine.isEmpty()) {
            lines.add(currentLine);
        }

        return lines;
    }

    /**
     * 从QuadPoints计算边界框（Rect）
     * 找出所有点中的最小X,Y和最大X,Y
     *
     * @param quadPoints QuadPoints数组（格式：TL,TR,BL,BR × N个四边形）
     * @return 边界框矩形
     */
    private static PDRectangle calculateBoundingBoxFromQuadPoints(float[] quadPoints) {
        if (quadPoints.length == 0) {
            return new PDRectangle(0, 0, 0, 0);
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        // 遍历所有点（每个点有x,y两个值）
        for (int i = 0; i < quadPoints.length; i += 2) {
            float x = quadPoints[i];
            float y = quadPoints[i + 1];

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        return new PDRectangle(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * 计算边界框（Rect）- 从TextPosition计算（备用）
     * PDF注释必须有Rect属性，表示注释的边界框
     *
     * @param positions TextPosition列表
     * @return 边界框矩形
     */
    private static PDRectangle calculateBoundingBox(List<TextPosition> positions) {
        if (positions.isEmpty()) {
            return new PDRectangle(0, 0, 0, 0);
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (TextPosition tp : positions) {
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

        // 1. 使用MCIDTextExtractor提取TextPosition（复用已验证的代码）
        MCIDTextExtractor extractor = new MCIDTextExtractor(mcids);

        // 2. 处理页面
        PDPage page = doc.getPage(pageIndex);
        System.out.println("[调试] 开始处理页面，目标MCID: " + mcids);
        extractor.processPage(page);

        // 3. 获取TextPosition列表
        List<TextPosition> positions = extractor.getTextPositions();
        System.out.println("[调试] 页面处理完成，收集到 " + positions.size() + " 个字形");

        if (positions.isEmpty()) {
            System.out.println("[警告] 页面 " + (pageIndex + 1) + " 未找到MCID " + mcids + " 的内容");
            return;
        }

        // 调试：打印前3个TextPosition的原始数据
        System.out.println("[调试] 前3个TextPosition原始数据:");
        PDRectangle cropBox = page.getCropBox();
        float pageHeight = cropBox.getHeight();
        float pageWidth = cropBox.getWidth();
        System.out.println("[调试] 页面尺寸: " + pageWidth + " x " + pageHeight);
        for (int i = 0; i < Math.min(3, positions.size()); i++) {
            TextPosition tp = positions.get(i);
            System.out.println("  TextPosition[" + i + "]: unicode='" + tp.getUnicode() +
                             "' X=" + tp.getX() + " Y=" + tp.getY() +
                             " XDirAdj=" + tp.getXDirAdj() + " YDirAdj=" + tp.getYDirAdj() +
                             " Width=" + tp.getWidth() + " WidthDirAdj=" + tp.getWidthDirAdj() +
                             " Height=" + tp.getHeight() + " HeightDir=" + tp.getHeightDir());
        }

        // 4. 生成QuadPoints（带页面高度进行Y坐标转换）
        float[] quadPoints = generateQuadPoints(positions, pageHeight);

        // 调试：输出QuadPoints信息
        System.out.println("[调试] QuadPoints数量: " + (quadPoints.length / 8) + " 个四边形");
        System.out.println("[调试] 前3个QuadPoints坐标:");
        for (int i = 0; i < Math.min(3, quadPoints.length / 8); i++) {
            int offset = i * 8;
            System.out.println("  QuadPoint[" + i + "]: LL(" + quadPoints[offset] + "," + quadPoints[offset+1] +
                             ") LR(" + quadPoints[offset+2] + "," + quadPoints[offset+3] +
                             ") UR(" + quadPoints[offset+4] + "," + quadPoints[offset+5] +
                             ") UL(" + quadPoints[offset+6] + "," + quadPoints[offset+7] + ")");
        }

        // 5. 计算边界框（从QuadPoints推导）
        PDRectangle rect = calculateBoundingBoxFromQuadPoints(quadPoints);
        System.out.println("[调试] 边界框 Rect: (" + rect.getLowerLeftX() + "," + rect.getLowerLeftY() + ") 宽=" + rect.getWidth() + " 高=" + rect.getHeight());

        // 6. 🔴 添加红色边框验证坐标（用于调试）
        System.out.println("[调试] 添加红色方框验证坐标...");
        // PDFBox 3.0中PDAnnotationSquareCircle是抽象类，需要通过COSDictionary创建
        COSDictionary squareDict = new COSDictionary();
        squareDict.setName(COSName.TYPE, "Annot");
        squareDict.setName(COSName.SUBTYPE, "Square");
        PDAnnotationSquareCircle box = (PDAnnotationSquareCircle) PDAnnotation.createAnnotation(squareDict);
        box.setRectangle(rect);
        PDBorderStyleDictionary borderStyle = new PDBorderStyleDictionary();
        borderStyle.setWidth(1.0f);  // 1pt边框
        box.setBorderStyle(borderStyle);
        PDColor redColor = new PDColor(new float[]{1.0f, 0f, 0f}, PDDeviceRGB.INSTANCE);
        box.setColor(redColor);
        box.setPrinted(true);
        page.getAnnotations().add(box);
        System.out.println("[成功] 红色方框已添加");

        // 7. 创建高亮注释（PDFBox 3.0方式）
        // PDFBox 3.0中PDAnnotationTextMarkup构造函数是protected，需要通过COSDictionary创建
        COSDictionary highlightDict = new COSDictionary();
        highlightDict.setName(COSName.TYPE, "Annot");
        highlightDict.setName(COSName.SUBTYPE, "Highlight");
        PDAnnotationTextMarkup highlight = (PDAnnotationTextMarkup) PDAnnotation.createAnnotation(highlightDict);

        // 8. 设置QuadPoints和边界框
        highlight.setQuadPoints(quadPoints);
        highlight.setRectangle(rect);

        // 9. 设置颜色和不透明度
        PDColor pdColor = new PDColor(color, PDDeviceRGB.INSTANCE);
        highlight.setColor(pdColor);
        highlight.setConstantOpacity(1.0f);  // 改为完全不透明，更容易看到

        // 设置CA（外观不透明度）
        highlight.getCOSObject().setFloat(COSName.CA, 0.5f);

        // 10. 设置标志位
        highlight.setPrinted(true);  // 可打印
        // 确保不设置隐藏标志

        // 11. 添加到页面
        page.getAnnotations().add(highlight);
        System.out.println("[成功] 黄色高亮已添加");

        // 10. 获取文本内容（用于调试）
        String extractedText = extractor.getText();
        System.out.println("[成功] 页面 " + (pageIndex + 1) + " 高亮了 " +
                         (quadPoints.length / 8) + " 个字形，MCID: " + mcids);
        System.out.println("       文本内容: " + extractedText);
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

        // 使用时间戳生成唯一的输出文件名，避免覆盖
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        String outputPdf = "E:\\programFile\\AIProgram\\docxServer\\pdf\\1978018096320905217_mcid_highlighted_" + timestamp + ".pdf";

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