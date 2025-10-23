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
    /**
     * 【阶段3：QuadPoints生成】从字符坐标到高亮框
     *
     * <h3>核心原理</h3>
     * PDF高亮注释（Highlight Annotation）需要QuadPoints数组来定义高亮区域的形状。
     * QuadPoints格式：[x1,y1, x2,y2, x3,y3, x4,y4, ...]
     *                  ↑TL   ↑TR   ↑BL   ↑BR
     * 每8个浮点数定义一个四边形（TL=左上, TR=右上, BL=左下, BR=右下）
     *
     * <h3>生成过程</h3>
     * <ol>
     *   <li><b>按行分组</b>：将TextPosition按Y坐标聚类（同一行的字符Y坐标接近）</li>
     *   <li><b>计算边界框</b>：
     *     <ul>
     *       <li>X坐标：直接使用 XDirAdj（字符左边缘）</li>
     *       <li>Y坐标：
     *         <ul>
     *           <li>基线位置 = Math.abs(YDirAdj)</li>
     *           <li>顶部 = 基线 + HeightDir + padTop（向上扩展）</li>
     *           <li>底部 = 基线 - padBottom（向下扩展）</li>
     *         </ul>
     *       </li>
     *     </ul>
     *   </li>
     *   <li><b>动态padding</b>：基于字体中位数高度
     *     <ul>
     *       <li>顶部：max(0.6pt, 6% × 中位高度)</li>
     *       <li>底部：max(0.6pt, 8% × 中位高度)</li>
     *       <li>横向：max(0.4pt, 4% × 宽度)</li>
     *     </ul>
     *   </li>
     *   <li><b>同行合并</b>：将同一行的所有字符合并为一个四边形，避免每个字符一个框</li>
     *   <li><b>生成QuadPoints</b>：按TL, TR, BL, BR顺序添加四个顶点坐标</li>
     * </ol>
     *
     * <h3>为什么需要padding</h3>
     * - HeightDir只是字体度量值，不包含行距、上下留白
     * - 添加padding确保高亮框完整覆盖文字（包括上下边缘、字母上标/下标等）
     * - 动态padding根据字体大小自适应，小字体小padding，大字体大padding
     *
     * <h3>坐标系说明</h3>
     * - PDF用户空间：原点在左下角，Y轴向上
     * - YDirAdj为负数时，Math.abs()取绝对值即为从底部算起的Y坐标
     * - 示例：YDirAdj=-755.889 表示距离页面底部755.889点（即在页面顶部）
     *
     * @param positions TextPosition列表（已通过MCID筛选的目标文本）
     * @param pageHeight 页面高度（未使用，保留用于未来扩展）
     * @return QuadPoints数组，格式为[TL, TR, BL, BR, TL, TR, BL, BR, ...]（每行一个四边形）
     */
    private static float[] generateQuadPoints(List<TextPosition> positions, float pageHeight) {
        if (positions.isEmpty()) {
            return new float[0];
        }

        // 步骤1：按行分组
        List<List<TextPosition>> lines = groupIntoLines(positions);
        List<Float> quadsList = new ArrayList<>();

        // 步骤2：为每一行生成QuadPoints（带padding）
        for (List<TextPosition> line : lines) {
            if (line.isEmpty()) continue;

            // 收集高度用于计算中位数（用于动态padding）
            List<Float> heights = new ArrayList<>();
            for (TextPosition tp : line) {
                heights.add(tp.getHeightDir());
            }
            heights.sort(Float::compareTo);
            float hMed = heights.get(heights.size() / 2);

            // 动态padding（参考TextHighlighter：顶部6%，底部8%，最小0.6pt）
            float padTop = Math.max(0.6f, 0.06f * hMed);
            float padBottom = Math.max(0.6f, 0.08f * hMed);

            // 计算边界
            float xMin = Float.MAX_VALUE;
            float xMax = -Float.MAX_VALUE;
            float yMin = Float.MAX_VALUE;  // 最小Y（底部）
            float yMax = -Float.MAX_VALUE; // 最大Y（顶部）

            for (TextPosition tp : line) {
                float w = Math.max(0.01f, tp.getWidthDirAdj());
                float h = tp.getHeightDir();
                float x = tp.getXDirAdj();

                // Y坐标转换：Math.abs(YDirAdj)是基线位置
                float yBase = Math.abs(tp.getYDirAdj());

                // 文字顶部：基线向上 + 字体高度 + padding
                // 文字底部：基线向下 - padding（基线通常在字符底部附近）
                float yTop = yBase + h + padTop;
                float yBottom = yBase - padBottom;

                xMin = Math.min(xMin, x);
                xMax = Math.max(xMax, x + w);
                yMin = Math.min(yMin, yBottom);
                yMax = Math.max(yMax, yTop);
            }

            // 横向padding（4%或最小0.4pt）
            float padX = Math.max(0.4f, 0.04f * (xMax - xMin));
            xMin -= padX;
            xMax += padX;

            System.out.printf("[调试] 行高亮框: left=%.2f right=%.2f top=%.2f bottom=%.2f height=%.2f (中位高度=%.2f, padTop=%.2f, padBottom=%.2f)%n",
                xMin, xMax, yMax, yMin, yMax - yMin, hMed, padTop, padBottom);

            // 生成QuadPoints - 顺序：TL, TR, BL, BR
            quadsList.add(xMin);  quadsList.add(yMax);  // TL (左上)
            quadsList.add(xMax);  quadsList.add(yMax);  // TR (右上)
            quadsList.add(xMin);  quadsList.add(yMin);  // BL (左下)
            quadsList.add(xMax);  quadsList.add(yMin);  // BR (右下)
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
     * 【完整流程】为指定MCID添加高亮注释
     *
     * <h3>核心思路：分层架构 - 不修改ContentStream，而是添加Annotation层</h3>
     *
     * <h3>阶段2：TextPosition收集 - 获取精确坐标</h3>
     * <b>TextPosition包含的信息：</b>
     * <ul>
     *   <li>unicode: 字符内容（如"文"）</li>
     *   <li>XDirAdj: X坐标（已考虑文本矩阵变换）</li>
     *   <li>YDirAdj: Y坐标（基线位置）</li>
     *   <li>WidthDirAdj: 字符宽度</li>
     *   <li>HeightDir: 字符高度（字体大小）</li>
     *   <li>font: 字体信息</li>
     *   <li>textMatrix: 文本变换矩阵</li>
     * </ul>
     *
     * <b>关键：PDFBox已经完成所有坐标变换计算</b>
     * <ul>
     *   <li>CTM（Current Transformation Matrix）</li>
     *   <li>文本矩阵（Text Matrix）</li>
     *   <li>字体矩阵（Font Matrix）</li>
     *   <li>页面旋转、缩放等</li>
     * </ul>
     * 所以XDirAdj、YDirAdj是<b>最终的用户空间坐标</b>，可以直接使用！
     *
     * <h3>阶段4：注释插入 - 添加高亮层</h3>
     * <b>核心：不修改contentStream，而是添加Annotation</b>
     * <pre>
     * PDF结构示意：
     * Page
     * ├── ContentStream (内容流)  ← 原始文字、图形（不修改！）
     * │   └── /P &lt;&lt;/MCID 5&gt;&gt; BDC
     * │       BT ... (文字) Tj ET
     * │       EMC
     * │
     * └── Annotations (注释层)    ← 我们添加的高亮（独立层）
     *     └── Highlight Annotation
     *         ├── QuadPoints: [x1,y1, x2,y2, ...]  ← 高亮框位置
     *         ├── Color: [0, 1, 0]                  ← 绿色
     *         └── Opacity: 0.5                      ← 半透明
     * </pre>
     *
     * <b>优势：</b>
     * <ul>
     *   <li>非破坏性：不修改原始内容流，可随时删除注释</li>
     *   <li>跨阅读器兼容：Annotation是PDF标准，所有阅读器都支持</li>
     *   <li>精确定位：利用PDFBox的坐标计算，考虑所有变换</li>
     *   <li>灵活性：可设置颜色、透明度、注释文本等</li>
     * </ul>
     *
     * @param doc PDF文档对象
     * @param pageIndex 页码（从0开始）
     * @param mcids 目标MCID集合
     * @param color RGB颜色数组（如 {0f, 1f, 0f} 表示绿色）
     * @param opacity 透明度（0.0-1.0，未使用，保留用于扩展）
     * @throws IOException 文件操作异常
     */
    public static void highlightByMcid(
            PDDocument doc,
            int pageIndex,
            Set<Integer> mcids,
            float[] color,
            float opacity) throws IOException {

        // ========== 阶段2：TextPosition收集 ==========
        // 1. 使用MCIDTextExtractor提取TextPosition（复用已验证的代码）
        // MCIDTextExtractor通过解析ContentStream中的BDC/EMC操作符，
        // 识别MCID区域并收集该区域内所有字符的TextPosition
        MCIDTextExtractor extractor = new MCIDTextExtractor(mcids);

        // 2. 处理页面（PDFStreamEngine会解析contentStream）
        PDPage page = doc.getPage(pageIndex);
        System.out.println("[调试] 开始处理页面，目标MCID: " + mcids);
        extractor.processPage(page);

        // 3. 获取TextPosition列表（包含精确的用户空间坐标）
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

        // 6. 创建高亮注释（PDFBox 3.0方式）
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
        System.out.println("[成功] 高亮已添加");

        // 10. 获取文本内容（用于调试）
        String extractedText = extractor.getText();
        System.out.println("[成功] 页面 " + (pageIndex + 1) + " 高亮了 " +
                         (quadPoints.length / 8) + " 个字形，MCID: " + mcids);
        System.out.println("       文本内容: " + extractedText);
    }

    /**
     * 【字符级别高亮】在指定MCID范围内查找文本并高亮
     *
     * 核心思路：
     * 1. 从指定的 page + mcids 中提取所有 TextPosition
     * 2. 在这些 TextPosition 中查找匹配的文本
     * 3. 只高亮匹配的 TextPosition
     *
     * 使用场景：
     * - 高亮表格单元格中的特定文本（而非整个单元格）
     * - 精确高亮搜索结果
     * - 避免高亮不相关的内容
     *
     * @param doc PDF文档对象
     * @param pageIndex 页码（从0开始）
     * @param mcids 目标MCID集合（搜索范围）
     * @param targetText 要高亮的文本内容
     * @param color RGB颜色数组
     * @param opacity 透明度（0.0-1.0）
     * @throws IOException 文件操作异常
     */
    public static void highlightByTextInMcids(
            PDDocument doc,
            int pageIndex,
            Set<Integer> mcids,
            String targetText,
            float[] color,
            float opacity) throws IOException {

        if (targetText == null || targetText.trim().isEmpty()) {
            System.out.println("[警告] 目标文本为空，跳过高亮");
            return;
        }

        // ========== 阶段1：提取MCID范围内的所有TextPosition ==========
        MCIDTextExtractor extractor = new MCIDTextExtractor(mcids);
        PDPage page = doc.getPage(pageIndex);
        extractor.processPage(page);

        List<TextPosition> allPositions = extractor.getTextPositions();
        if (allPositions.isEmpty()) {
            System.out.println("[警告] 页面 " + (pageIndex + 1) + " 在MCID " + mcids + " 中未找到任何内容");
            return;
        }

        String fullText = extractor.getText();

        // ========== 阶段2：查找匹配的TextPosition ==========
        List<TextPosition> matchedPositions = findTextPositions(allPositions, fullText, targetText);

        if (matchedPositions.isEmpty()) {
            System.out.println("[警告] 未找到匹配的文本");
            return;
        }

        // ========== 阶段3：生成QuadPoints并高亮 ==========
        PDRectangle cropBox = page.getCropBox();
        float pageHeight = cropBox.getHeight();

        float[] quadPoints = generateQuadPoints(matchedPositions, pageHeight);
        PDRectangle rect = calculateBoundingBoxFromQuadPoints(quadPoints);

        // 创建高亮注释
        COSDictionary highlightDict = new COSDictionary();
        highlightDict.setName(COSName.TYPE, "Annot");
        highlightDict.setName(COSName.SUBTYPE, "Highlight");
        PDAnnotationTextMarkup highlight = (PDAnnotationTextMarkup) PDAnnotation.createAnnotation(highlightDict);

        highlight.setQuadPoints(quadPoints);
        highlight.setRectangle(rect);

        PDColor pdColor = new PDColor(color, PDDeviceRGB.INSTANCE);
        highlight.setColor(pdColor);
        highlight.setConstantOpacity(1.0f);
        highlight.getCOSObject().setFloat(COSName.CA, 0.5f);
        highlight.setPrinted(true);

        // 设置批注内容为要高亮的文本
        highlight.setContents(targetText);

        page.getAnnotations().add(highlight);

        System.out.println("[成功] 页面 " + (pageIndex + 1) + " 高亮了 " +
                         matchedPositions.size() + " 个字符，文本: " + targetText);
    }

    /**
     * 在TextPosition列表中查找匹配的文本
     *
     * 算法：
     * 1. 构建完整文本字符串（用于匹配）
     * 2. 查找目标文本的起始位置
     * 3. 根据匹配位置提取对应的TextPosition
     *
     * 注意：
     * - 忽略文本中的空白符差异（多个空格/换行视为一个空格）
     * - 支持部分匹配
     *
     * @param positions 所有TextPosition
     * @param fullText 完整文本（与positions对应）
     * @param targetText 要查找的文本
     * @return 匹配的TextPosition列表
     */
    private static List<TextPosition> findTextPositions(
            List<TextPosition> positions,
            String fullText,
            String targetText) {

        List<TextPosition> result = new ArrayList<>();

        // 归一化文本（去除多余空白符）
        String normalizedFull = normalizeWhitespace(fullText);
        String normalizedTarget = normalizeWhitespace(targetText);

        // 查找匹配位置（在归一化文本中）
        int matchStart = normalizedFull.indexOf(normalizedTarget);
        if (matchStart < 0) {
            System.out.println("[警告] 未找到匹配的文本");
            return result;
        }

        int matchEnd = matchStart + normalizedTarget.length();

        // 映射归一化位置到原始TextPosition
        // 需要建立归一化文本位置 -> 原始TextPosition的映射
        result = mapNormalizedPositionsToTextPositions(
            positions, fullText, normalizedFull, matchStart, matchEnd);

        return result;
    }

    /**
     * 归一化文本（直接调用TextUtils.normalizeText()保持一致性）
     *
     * 重要：必须使用与ParagraphMapperRefactored相同的归一化逻辑，否则会导致匹配失败！
     * 该方法会：
     * - 去除所有换行符和空白符
     * - 去除零宽字符
     * - 去除标点符号
     * - 转换为小写
     */
    private static String normalizeWhitespace(String text) {
        if (text == null) return "";

        // 直接调用TextUtils.normalizeText()，保持与ParagraphMapperRefactored一致
        String normalized = com.example.docxserver.util.taggedPDF.TextUtils.normalizeText(text);
        System.out.println("[调试-归一化] normalizeText处理后: " + normalized);

        return normalized;
    }

    /**
     * 将归一化文本中的位置映射回原始TextPosition
     *
     * TODO: ⚠️ 当前实现存在问题！
     * 问题描述：
     * - 当前逻辑假设归一化后保留空格（压缩为单个空格）
     * - 但实际normalizeText()会完全去除所有空白符、标点符号、并转小写
     * - 例如："ISO/IEC 15693" → "isoiec15693"（无空格、无斜杠、全小写）
     * - 当前的空白符处理逻辑（第811-817行）会失效
     *
     * 影响：
     * - 可能导致高亮位置偏移或不准确
     * - 对于包含标点和空格的文本，映射会出错
     *
     * 修复方案：
     * - 需要重新设计映射算法，跳过所有会被normalizeText()去除的字符
     * - 包括：所有空白符(\s)、所有标点符号(\\p{Punct})
     * - 同时处理大小写转换
     *
     * 算法：
     * 1. 遍历原始文本，同时追踪归一化文本的位置
     * 2. 当归一化位置在匹配范围内时，记录对应的TextPosition
     *
     * @param positions 原始TextPosition列表
     * @param originalText 原始文本
     * @param normalizedText 归一化文本
     * @param normalizedStart 归一化文本中的起始位置
     * @param normalizedEnd 归一化文本中的结束位置
     * @return 匹配的TextPosition列表
     */
    private static List<TextPosition> mapNormalizedPositionsToTextPositions(
            List<TextPosition> positions,
            String originalText,
            String normalizedText,
            int normalizedStart,
            int normalizedEnd) {

        List<TextPosition> result = new ArrayList<>();

        int normalizedIndex = 0;  // 归一化文本中的当前位置
        int originalIndex = 0;    // 原始文本中的当前位置
        boolean inWhitespace = false;  // 是否在连续空白符中

        for (int i = 0; i < positions.size(); i++) {
            if (originalIndex >= originalText.length()) break;

            char originalChar = originalText.charAt(originalIndex);
            boolean isWhitespace = Character.isWhitespace(originalChar);

            if (isWhitespace) {
                // 空白符：在归一化文本中只占1个位置（如果不在连续空白符中）
                if (!inWhitespace) {
                    normalizedIndex++;
                    inWhitespace = true;
                }
            } else {
                // 非空白符：在归一化文本中占1个位置
                inWhitespace = false;

                // 检查是否在匹配范围内
                if (normalizedIndex >= normalizedStart && normalizedIndex < normalizedEnd) {
                    result.add(positions.get(i));
                }

                normalizedIndex++;
            }

            originalIndex++;
        }

        return result;
    }

    /**
     * 【批量高亮】一次调用高亮多个位置
     *
     * 使用场景：
     * - 高亮JSON文件中所有匹配的段落
     * - 高亮搜索结果（可能跨多个页面）
     * - 从PdfTextFinder.FindResult批量转换并高亮
     *
     * 示例：
     * <pre>
     * List&lt;HighlightTarget&gt; targets = new ArrayList&lt;&gt;();
     * targets.add(new HighlightTarget(0, Arrays.asList("4", "5"), "t001-r001-c001-p001"));
     * targets.add(new HighlightTarget(1, Arrays.asList("10"), "t001-r002-c001-p001"));
     *
     * highlightMultipleTargets(doc, targets, new float[]{1f, 1f, 0f}, 0.3f);
     * </pre>
     *
     * @param doc PDF文档对象
     * @param targets 高亮目标列表
     * @param color RGB颜色数组（如 {1f, 1f, 0f} 表示黄色）
     * @param opacity 透明度（0.0-1.0）
     * @throws IOException 文件操作异常
     */
    public static void highlightMultipleTargets(
            PDDocument doc,
            List<HighlightTarget> targets,
            float[] color,
            float opacity) throws IOException {

        if (targets == null || targets.isEmpty()) {
            System.out.println("[警告] 高亮目标列表为空，跳过");
            return;
        }

        System.out.println("\n=== 批量高亮开始 ===");
        System.out.println("总目标数: " + targets.size());

        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        for (int i = 0; i < targets.size(); i++) {
            HighlightTarget target = targets.get(i);

            // 跳过无效目标
            if (!target.isValid()) {
                System.out.println("[跳过 #" + (i + 1) + "] " + target.getId() + " - MCID列表为空");
                skipCount++;
                continue;
            }

            try {
                System.out.println("\n[处理 #" + (i + 1) + "/" + targets.size() + "] " + target);

                // 转换MCID字符串为整数集合
                java.util.Set<Integer> mcidInts = target.getMcidIntegers();

                if (mcidInts.isEmpty()) {
                    System.out.println("  -> 跳过：MCID解析失败");
                    skipCount++;
                    continue;
                }

                // 判断是否使用字符级别高亮
                if (target.hasText()) {
                    // 字符级别高亮：在MCID范围内查找文本并高亮
                    System.out.println("  -> 使用字符级别高亮，目标文本: " + target.getText());
                    highlightByTextInMcids(doc, target.getPage(), mcidInts, target.getText(), color, opacity);
                } else {
                    // MCID区域高亮：高亮整个MCID区域
                    System.out.println("  -> 使用MCID区域高亮");
                    highlightByMcid(doc, target.getPage(), mcidInts, color, opacity);
                }

                successCount++;
                System.out.println("  -> 成功");

            } catch (Exception e) {
                errorCount++;
                System.err.println("  -> 失败：" + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n=== 批量高亮完成 ===");
        System.out.println("成功: " + successCount);
        System.out.println("跳过: " + skipCount);
        System.out.println("失败: " + errorCount);
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

        // 绿色高亮，30%透明度
        float[] color = {0.0f, 1.0f, 0.0f};  // RGB: 绿色
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