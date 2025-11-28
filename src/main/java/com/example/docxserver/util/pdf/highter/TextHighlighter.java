package com.example.docxserver.util.pdf.highter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文本高亮工具（基于字符范围的精确高亮）
 *
 * 功能：
 * - 根据字符索引范围高亮文本
 * - 支持跨行高亮
 * - 自动行分组和片段合并
 * - 处理文本方向和坐标转换
 */
public class TextHighlighter {

    private static final float INF = Float.MAX_VALUE;
    private static final float GAP_THRESHOLD = 5.0f;  // 字符间隙阈值（用于判断是否合并）
    private static final float LINE_HEIGHT_THRESHOLD = 2.0f;  // 行高阈值（用于判断是否同一行）

    /**
     * 页面索引信息
     * 存储页面文本、字符到字形的映射、字形列表
     */
    public static class PageIndex {
        public String pageText;                // 页面完整文本
        public int[] charToGlyphIndex;        // 字符索引 -> 字形索引
        public List<TextPosition> glyphs;     // 字形列表（TextPosition）

        public PageIndex(String pageText, int[] charToGlyphIndex, List<TextPosition> glyphs) {
            this.pageText = pageText;
            this.charToGlyphIndex = charToGlyphIndex;
            this.glyphs = glyphs;
        }
    }

    /**
     * 根据字符范围构建 QuadPoints（行业最佳实践：全 DirAdj 坐标系 + HeightDir）
     *
     * <h3>核心原理</h3>
     * <ol>
     *   <li><b>完全使用 DirAdj 坐标系</b>：避免混用 fontDescriptor（不含缩放）导致高度失真
     *     <ul>
     *       <li>XDirAdj/YDirAdj/WidthDirAdj/HeightDir 已包含文本矩阵缩放</li>
     *       <li>DirAdj 坐标系：Y 轴向下递增（小 Y 是顶部，大 Y 是底部）</li>
     *     </ul>
     *   </li>
     *   <li><b>高度计算</b>：直接使用 getHeightDir()，不再用 ascent/descent
     *     <ul>
     *       <li>yTop = YDirAdj - HeightDir（顶部：向上偏移）</li>
     *       <li>yBottom = YDirAdj（底部：基线位置）</li>
     *     </ul>
     *   </li>
     *   <li><b>动态 padding</b>（基于中位高度）：
     *     <ul>
     *       <li>顶部：max(6% × 中位高度, 0.6pt)</li>
     *       <li>底部：max(8% × 中位高度, 0.6pt)</li>
     *       <li>横向：max(4% × 宽度, 0.4pt)</li>
     *     </ul>
     *   </li>
     *   <li><b>坐标转换</b>：最后统一转换到 PDF 用户空间（Y 轴向上递增）
     *     <ul>
     *       <li>top = pageHeight - topDir（期望 top &gt; bottom）</li>
     *       <li>bottom = pageHeight - botDir</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <h3>关键约束</h3>
     * <ul>
     *   <li>DirAdj 坐标系：topDir &lt; botDir（Y 向下增）</li>
     *   <li>用户空间：top &gt; bottom（Y 向上增）</li>
     *   <li>最终高度 ≈ 中位高度 × 1.14（1 + 0.06 + 0.08）</li>
     * </ul>
     *
     * @param pageIndex 页面索引
     * @param pdPage PDF页面对象（用于获取页面高度）
     * @param startChar 起始字符索引（包含）
     * @param endChar 结束字符索引（不包含）
     * @return QuadPoints 列表，每个 float[8] 表示一个四边形（TL, TR, BL, BR）
     */
    public static List<float[]> buildQuadsForRange(PageIndex pageIndex, PDPage pdPage, int startChar, int endChar) {
        // 获取页面高度（用于 Y 轴转换）
        float pageHeight = pdPage.getCropBox().getHeight();

        // 1) 取出覆盖的 glyph 序列
        List<TextPosition> hits = new ArrayList<>();
        for (int i = startChar; i < endChar && i < pageIndex.charToGlyphIndex.length; i++) {
            int gi = pageIndex.charToGlyphIndex[i];
            if (gi >= 0 && gi < pageIndex.glyphs.size()) {
                hits.add(pageIndex.glyphs.get(gi));
            }
        }
        if (hits.isEmpty()) {
            return new ArrayList<>();
        }

        // 2) 行分组（y 阈值/基线角度）
        List<List<TextPosition>> lines = groupByLine(hits);

        // 3) 行内合并片段（全程 DirAdj 坐标系，使用 HeightDir，避免混用导致失真）
        List<float[]> quads = new ArrayList<>();
        for (List<TextPosition> line : lines) {
            List<List<TextPosition>> segments = mergeByGap(line); // 按 gapX 合并
            for (List<TextPosition> seg : segments) {
                // 在 DirAdj 坐标系中计算边界框（Y 向下增）
                float leftDir = INF, rightDir = -INF;
                float topDir = INF, botDir = -INF;
                TextPosition firstTp = seg.get(0);

                // 收集高度用于计算中位数（用于 padding）
                List<Float> heights = new ArrayList<>();

                for (TextPosition tp : seg) {
                    // DirAdj 坐标系（已含文本矩阵缩放）
                    float xL_dir = tp.getXDirAdj();
                    float xR_dir = tp.getXDirAdj() + tp.getWidthDirAdj();

                    // DirAdj 坐标中，Y 是"自上向下增加"
                    float yBottom_dir = tp.getYDirAdj();                        // 底部
                    float yTop_dir = tp.getYDirAdj() - tp.getHeightDir();       // 顶部（y - height）

                    heights.add(tp.getHeightDir());

                    leftDir = Math.min(leftDir, xL_dir);
                    rightDir = Math.max(rightDir, xR_dir);
                    topDir = Math.min(topDir, yTop_dir);      // 顶：取更小的 y
                    botDir = Math.max(botDir, yBottom_dir);   // 底：取更大的 y

                    // 调试：打印第一个字符的详细信息
                    if (tp == firstTp) {
                        System.out.printf("[DEBUG] 第一个字符详细信息:%n");
                        System.out.printf("  字符: '%s'%n", tp.getUnicode());
                        System.out.printf("  XDirAdj=%.2f WDirAdj=%.2f  YDirAdj=%.2f HDir=%.2f%n",
                            xL_dir, tp.getWidthDirAdj(), tp.getYDirAdj(), tp.getHeightDir());
                        System.out.printf("  yTopDir=%.2f (YDirAdj-HDir)  yBotDir=%.2f (YDirAdj)%n",
                            yTop_dir, yBottom_dir);
                        System.out.printf("  高度(DirAdj): %.2f%n", yBottom_dir - yTop_dir);
                    }
                }

                // 计算中位数高度
                heights.sort(Float::compareTo);
                float hMed = heights.get(heights.size() / 2);

                // 动态 padding（行业最佳实践：顶部 6%，底部 8%，最小 0.6pt）
                float padTopDir = Math.max(0.6f, 0.06f * hMed);
                float padBottomDir = Math.max(0.6f, 0.08f * hMed);

                // DirAdj 里：往"上"是减小数值，往"下"是增大数值
                topDir -= padTopDir;
                botDir += padBottomDir;

                // X 向轻微外扩，取 1% 或 0.2pt（减小左右 padding，更贴近实际文字）
                float padX = Math.max(0.2f, 0.01f * (rightDir - leftDir));
                leftDir -= padX;
                rightDir += padX;

                // 转换到 PDF 用户空间（Y 轴翻转：用户空间 Y 从下向上增）
                float top = pageHeight - topDir;       // 顶部：pageHeight - 小Y
                float bottom = pageHeight - botDir;    // 底部：pageHeight - 大Y

                // 自检：用户空间里 top 必须 > bottom
                if (top <= bottom) {
                    System.err.printf("[WARN] top<=bottom 异常: top=%.2f bottom=%.2f (pageH=%.2f)%n",
                        top, bottom, pageHeight);
                    // 交换避免负高
                    float t = top;
                    top = bottom;
                    bottom = t;
                }

                // 调试输出
                System.out.printf("[DEBUG] 片段聚合(DirAdj): topDir=%.2f botDir=%.2f leftDir=%.2f rightDir=%.2f%n",
                    topDir + padTopDir, botDir - padBottomDir, leftDir + padX, rightDir - padX);
                System.out.printf("[DEBUG] Padding: top=%.2f bottom=%.2f horizontal=%.2f (基于中位高度 %.2f)%n",
                    padTopDir, padBottomDir, padX, hMed);
                System.out.printf("[DEBUG] DirAdj坐标(加padding后): topDir=%.2f botDir=%.2f (期望 topDir<botDir)%n",
                    topDir, botDir);
                System.out.printf("[DEBUG] 用户空间坐标: top=%.2f bottom=%.2f left=%.2f right=%.2f%n",
                    top, bottom, leftDir, rightDir);
                System.out.printf("[DEBUG] 最终高亮框高度: %.2f (期望为正，≈中位高度×1.14)%n", top - bottom);

                // QuadPoints 顺序：TL, TR, BL, BR
                quads.add(new float[]{
                    leftDir, top,        // Top-Left
                    rightDir, top,       // Top-Right
                    leftDir, bottom,     // Bottom-Left
                    rightDir, bottom     // Bottom-Right
                });
            }
        }
        return quads;
    }

    /**
     * 按行分组（根据 y 坐标和行高）
     *
     * @param glyphs 字形列表
     * @return 分组后的行列表
     */
    private static List<List<TextPosition>> groupByLine(List<TextPosition> glyphs) {
        List<List<TextPosition>> lines = new ArrayList<>();
        if (glyphs.isEmpty()) return lines;

        List<TextPosition> currentLine = new ArrayList<>();
        currentLine.add(glyphs.get(0));
        float lastY = glyphs.get(0).getYDirAdj();
        float lastHeight = glyphs.get(0).getHeightDir();

        for (int i = 1; i < glyphs.size(); i++) {
            TextPosition tp = glyphs.get(i);
            float y = tp.getYDirAdj();
            float height = tp.getHeightDir();

            // 判断是否同一行（y 坐标差异小于行高阈值）
            if (Math.abs(y - lastY) <= LINE_HEIGHT_THRESHOLD) {
                currentLine.add(tp);
            } else {
                // 新行
                lines.add(currentLine);
                currentLine = new ArrayList<>();
                currentLine.add(tp);
                lastY = y;
                lastHeight = height;
            }
        }

        if (!currentLine.isEmpty()) {
            lines.add(currentLine);
        }

        return lines;
    }

    /**
     * 按间隙合并片段（同一行内，如果字符间距过大则分段）
     *
     * @param line 同一行的字形列表
     * @return 分段后的片段列表
     */
    private static List<List<TextPosition>> mergeByGap(List<TextPosition> line) {
        List<List<TextPosition>> segments = new ArrayList<>();
        if (line.isEmpty()) return segments;

        List<TextPosition> currentSegment = new ArrayList<>();
        currentSegment.add(line.get(0));
        float lastEndX = line.get(0).getXDirAdj() + line.get(0).getWidthDirAdj();

        for (int i = 1; i < line.size(); i++) {
            TextPosition tp = line.get(i);
            float x = tp.getXDirAdj();

            // 计算间隙
            float gap = x - lastEndX;

            // 如果间隙小于阈值，合并到当前片段
            if (gap <= GAP_THRESHOLD) {
                currentSegment.add(tp);
            } else {
                // 间隙过大，开始新片段
                segments.add(currentSegment);
                currentSegment = new ArrayList<>();
                currentSegment.add(tp);
            }

            lastEndX = x + tp.getWidthDirAdj();
        }

        if (!currentSegment.isEmpty()) {
            segments.add(currentSegment);
        }

        return segments;
    }

    /**
     * 添加高亮注释到 PDF 页面
     *
     * @param doc PDF 文档
     * @param page PDF 页面
     * @param quads QuadPoints 列表
     * @param rgb RGB 颜色数组 [r, g, b]（0.0-1.0）
     * @param opacity 透明度（0.0-1.0）
     * @param note 注释文本（可选）
     * @throws IOException IO 异常
     */
    public static void addHighlight(PDDocument doc, PDPage page, List<float[]> quads,
                                     float[] rgb, float opacity, String note) throws IOException {
        // 创建高亮注释（PDFBox 3.0 API）
        org.apache.pdfbox.cos.COSDictionary dict = new org.apache.pdfbox.cos.COSDictionary();
        dict.setName(org.apache.pdfbox.cos.COSName.TYPE, "Annot");
        dict.setName(org.apache.pdfbox.cos.COSName.SUBTYPE, "Highlight");

        org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation annotation =
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation.createAnnotation(dict);

        PDAnnotationTextMarkup hl = (PDAnnotationTextMarkup) annotation;

        // QuadPoints
        float[] qp = new float[quads.size() * 8];
        float minX = INF, minY = INF, maxX = -INF, maxY = -INF;
        int o = 0;
        for (float[] q : quads) {
            System.arraycopy(q, 0, qp, o, 8);
            o += 8;
            for (int i = 0; i < 8; i += 2) {
                minX = Math.min(minX, q[i]);
                minY = Math.min(minY, q[i + 1]);
                maxX = Math.max(maxX, q[i]);
                maxY = Math.max(maxY, q[i + 1]);
            }
        }

        hl.setQuadPoints(qp);
        hl.setRectangle(new PDRectangle(minX, minY, maxX - minX, maxY - minY));
        hl.setColor(new PDColor(rgb, PDDeviceRGB.INSTANCE));
        hl.setConstantOpacity(opacity);
        if (note != null) {
            hl.setContents(note);
        }
        hl.setPrinted(true);

        page.getAnnotations().add(hl);

        // 尝试构建外观流
        try {
            hl.constructAppearances();
            System.out.println("[DEBUG] Successfully constructed appearance for annotation");
        } catch (Exception e) {
            System.err.println("[WARNING] Failed to construct appearance: " + e.getMessage());
            e.printStackTrace();
            // 不中断流程，继续添加注释（某些PDF查看器会自动渲染）
        }
    }

    /**
     * 便捷方法：根据字符范围直接高亮
     *
     * @param doc PDF 文档
     * @param page PDF 页面
     * @param pageIndex 页面索引
     * @param startChar 起始字符索引
     * @param endChar 结束字符索引
     * @param rgb RGB 颜色数组
     * @param opacity 透明度
     * @param note 注释文本
     * @throws IOException IO 异常
     */
    public static void highlightCharRange(PDDocument doc, PDPage page, PageIndex pageIndex,
                                           int startChar, int endChar,
                                           float[] rgb, float opacity, String note) throws IOException {
        List<float[]> quads = buildQuadsForRange(pageIndex, page, startChar, endChar);
        if (!quads.isEmpty()) {
            addHighlight(doc, page, quads, rgb, opacity, note);
        }
    }

    /**
     * 便捷方法：使用默认红色高亮
     *
     * @param doc PDF 文档
     * @param page PDF 页面
     * @param pageIndex 页面索引
     * @param startChar 起始字符索引
     * @param endChar 结束字符索引
     * @throws IOException IO 异常
     */
    public static void highlightCharRange(PDDocument doc, PDPage page, PageIndex pageIndex,
                                           int startChar, int endChar) throws IOException {
        float[] red = {1.0f, 0.0f, 0.0f};
        highlightCharRange(doc, page, pageIndex, startChar, endChar, red, 0.3f, null);
    }
}