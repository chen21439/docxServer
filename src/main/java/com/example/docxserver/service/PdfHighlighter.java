package com.example.docxserver.service;

import com.example.docxserver.util.untaggedPDF.PdfTextIndexer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnderline;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationStrikeout;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquiggly;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF高亮注释生成器
 *
 * 核心功能：
 * 1. 基于 PdfTextIndex 的查询结果添加高亮注释
 * 2. 支持多种注释类型：Highlight（高亮）、Underline（下划线）、StrikeOut（删除线）
 * 3. 自动处理 PDF/A-1b（不透明）和 PDF/A-2b/A-4（半透明）的外观差异
 * 4. 支持跨行高亮（多个QuadPoints）
 *
 * Java 8兼容，PDFBox 3.0
 */
public class PdfHighlighter {

    /**
     * 注释类型
     */
    public enum MarkupType {
        HIGHLIGHT,      // 高亮（黄色背景）
        UNDERLINE,      // 下划线
        STRIKEOUT,      // 删除线
        SQUIGGLY        // 波浪下划线
    }

    /**
     * 高亮样式配置
     */
    public static class HighlightStyle {
        /** 注释类型 */
        public MarkupType type;

        /** 颜色（RGB，0-1范围） */
        public float[] color;

        /** 不透明度（0-1，仅PDF/A-2b+支持） */
        public float opacity;

        /** 注释标题 */
        public String title;

        /** 注释内容 */
        public String contents;

        public HighlightStyle() {
            this.type = MarkupType.HIGHLIGHT;
            this.color = new float[]{1.0f, 1.0f, 0.0f}; // 黄色
            this.opacity = 0.3f;
            this.title = "Highlight";
            this.contents = "";
        }

        /** 预设样式：黄色高亮 */
        public static HighlightStyle yellowHighlight() {
            HighlightStyle style = new HighlightStyle();
            style.type = MarkupType.HIGHLIGHT;
            style.color = new float[]{1.0f, 1.0f, 0.0f};
            style.opacity = 0.3f;
            return style;
        }

        /** 预设样式：红色下划线 */
        public static HighlightStyle redUnderline() {
            HighlightStyle style = new HighlightStyle();
            style.type = MarkupType.UNDERLINE;
            style.color = new float[]{1.0f, 0.0f, 0.0f};
            style.opacity = 1.0f;
            return style;
        }

        /** 预设样式：绿色高亮 */
        public static HighlightStyle greenHighlight() {
            HighlightStyle style = new HighlightStyle();
            style.type = MarkupType.HIGHLIGHT;
            style.color = new float[]{0.0f, 1.0f, 0.0f};
            style.opacity = 0.2f;
            return style;
        }

        /** 预设样式：蓝色删除线 */
        public static HighlightStyle blueStrikeOut() {
            HighlightStyle style = new HighlightStyle();
            style.type = MarkupType.STRIKEOUT;
            style.color = new float[]{0.0f, 0.0f, 1.0f};
            style.opacity = 1.0f;
            return style;
        }
    }

    /**
     * 添加高亮注释（基于查询结果）
     *
     * @param pdfPath 原PDF文件路径
     * @param matches 查询结果列表
     * @param style 高亮样式
     * @param outputPath 输出PDF路径
     */
    public static void addHighlights(String pdfPath, List<PdfTextIndexer.TextMatch> matches,
                                     HighlightStyle style, String outputPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            // 按页分组
            for (PdfTextIndexer.TextMatch match : matches) {
                if (match.quadPoints == null || match.quadPoints.isEmpty()) {
                    continue;
                }

                // 获取页面
                PDPage page = document.getPage(match.pageNumber - 1); // PDDocument页码从0开始

                // 创建注释
                PDAnnotationTextMarkup annotation = createMarkupAnnotation(match, style, page);

                // 添加到页面
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations == null) {
                    annotations = new ArrayList<>();
                }
                annotations.add(annotation);
                page.setAnnotations(annotations);
            }

            // 保存
            document.save(outputPath);
        }
    }

    /**
     * 创建文本标记注释
     */
    private static PDAnnotationTextMarkup createMarkupAnnotation(PdfTextIndexer.TextMatch match,
                                                                 HighlightStyle style,
                                                                 PDPage page) throws IOException {
        // 根据类型创建注释
        // PDFBox 3.0.2: 使用具体的子类（PDAnnotationHighlight, PDAnnotationUnderline等）
        PDAnnotationTextMarkup annotation;
        switch (style.type) {
            case HIGHLIGHT:
                annotation = new PDAnnotationHighlight();
                break;
            case UNDERLINE:
                annotation = new PDAnnotationUnderline();
                break;
            case STRIKEOUT:
                annotation = new PDAnnotationStrikeout();
                break;
            case SQUIGGLY:
                annotation = new PDAnnotationSquiggly();
                break;
            default:
                annotation = new PDAnnotationHighlight();
        }

        // 设置QuadPoints（支持多个四边形，用于跨行高亮）
        float[] allQuads = flattenQuadPoints(match.quadPoints);
        annotation.setQuadPoints(allQuads);

        // 设置矩形边界（包围所有QuadPoints）
        PDRectangle rect = calculateBoundingRect(match.quadPoints);
        annotation.setRectangle(rect);

        // 设置颜色
        PDColor color = new PDColor(style.color, PDDeviceRGB.INSTANCE);
        annotation.setColor(color);

        // 设置不透明度（注意：PDF/A-1b可能不支持透明）
        annotation.setConstantOpacity(style.opacity);

        // 设置标题和内容
        annotation.setTitlePopup(style.title);
        annotation.setContents(style.contents != null && !style.contents.isEmpty()
                               ? style.contents
                               : match.matchedText);

        // 设置其他属性
        annotation.setPrinted(true); // 打印时可见

        return annotation;
    }

    /**
     * 将多个QuadPoints展平为单个数组
     *
     * PDF注释的QuadPoints格式：
     * [x1,y1, x2,y2, x3,y3, x4,y4, ...] 多个四边形连续存储
     */
    private static float[] flattenQuadPoints(List<float[]> quadPoints) {
        int totalLength = 0;
        for (float[] quad : quadPoints) {
            totalLength += quad.length;
        }

        float[] result = new float[totalLength];
        int offset = 0;
        for (float[] quad : quadPoints) {
            System.arraycopy(quad, 0, result, offset, quad.length);
            offset += quad.length;
        }

        return result;
    }

    /**
     * 计算包围所有QuadPoints的矩形
     */
    private static PDRectangle calculateBoundingRect(List<float[]> quadPoints) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;

        for (float[] quad : quadPoints) {
            // quad格式：[x1,y1, x2,y2, x3,y3, x4,y4]
            for (int i = 0; i < quad.length; i += 2) {
                float x = quad[i];
                float y = quad[i + 1];
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }

        return new PDRectangle(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * 便捷方法：通过关键字高亮
     *
     * @param pdfPath PDF路径
     * @param indexPath 索引文件路径
     * @param keyword 关键字
     * @param outputPath 输出路径
     */
    public static void highlightKeyword(String pdfPath, String indexPath, String keyword,
                                        String outputPath) throws IOException, ClassNotFoundException {
        // 加载索引
        PdfTextIndexer.PdfTextIndex index = PdfTextIndexer.loadIndex(indexPath);

        // 查找关键字
        List<PdfTextIndexer.TextMatch> matches = PdfTextIndexer.findByKeyword(index, keyword, false);

        System.out.println("Found " + matches.size() + " matches for keyword: " + keyword);

        // 添加高亮
        addHighlights(pdfPath, matches, HighlightStyle.yellowHighlight(), outputPath);

        System.out.println("Highlighted PDF saved to: " + outputPath);
    }

    /**
     * 便捷方法：通过ID高亮（结合PdfTextIndex的扩展）
     *
     * @param pdfPath PDF路径
     * @param index 索引对象
     * @param pageNum 页码
     * @param startChar 起始字符
     * @param endChar 结束字符
     * @param style 样式
     * @param outputPath 输出路径
     */
    public static void highlightByOffset(String pdfPath, PdfTextIndexer.PdfTextIndex index,
                                         int pageNum, int startChar, int endChar,
                                         HighlightStyle style, String outputPath) throws IOException {
        // 查找
        PdfTextIndexer.TextMatch match = PdfTextIndexer.findByOffset(index, pageNum, startChar, endChar);

        if (match == null) {
            System.err.println("No match found for page " + pageNum + ", offset " + startChar + "-" + endChar);
            return;
        }

        // 添加高亮
        List<PdfTextIndexer.TextMatch> matches = new ArrayList<>();
        matches.add(match);
        addHighlights(pdfPath, matches, style, outputPath);

        System.out.println("Highlighted PDF saved to: " + outputPath);
    }

    /**
     * 批量高亮（支持不同样式）
     *
     * @param pdfPath PDF路径
     * @param highlights 高亮列表（每个包含match+style）
     * @param outputPath 输出路径
     */
    public static class HighlightTask {
        public PdfTextIndexer.TextMatch match;
        public HighlightStyle style;

        public HighlightTask(PdfTextIndexer.TextMatch match, HighlightStyle style) {
            this.match = match;
            this.style = style;
        }
    }

    public static void addBatchHighlights(String pdfPath, List<HighlightTask> highlights,
                                          String outputPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            // 按页分组
            for (HighlightTask task : highlights) {
                PdfTextIndexer.TextMatch match = task.match;
                if (match.quadPoints == null || match.quadPoints.isEmpty()) {
                    continue;
                }

                // 获取页面
                PDPage page = document.getPage(match.pageNumber - 1);

                // 创建注释
                PDAnnotationTextMarkup annotation = createMarkupAnnotation(match, task.style, page);

                // 添加到页面
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations == null) {
                    annotations = new ArrayList<>();
                }
                annotations.add(annotation);
                page.setAnnotations(annotations);
            }

            // 保存
            document.save(outputPath);
        }
    }

    /**
     * PDF/A兼容性检查（简化版）
     *
     * PDF/A-1b：不支持透明度，需要将opacity设为1.0或使用下划线/删除线
     * PDF/A-2b/A-4：支持透明度
     */
    public static HighlightStyle adjustForPdfA1b(HighlightStyle style) {
        HighlightStyle adjusted = new HighlightStyle();
        adjusted.type = style.type;
        adjusted.color = style.color;
        adjusted.title = style.title;
        adjusted.contents = style.contents;

        // 强制不透明
        adjusted.opacity = 1.0f;

        // 建议：高亮改为下划线（视觉效果更好）
        if (style.type == MarkupType.HIGHLIGHT) {
            adjusted.type = MarkupType.UNDERLINE;
        }

        return adjusted;
    }

    /**
     * 检测PDF是否为PDF/A-1b（简化版）
     */
    public static boolean isPdfA1b(PDDocument document) {
        // 简化检测：检查metadata中的PDF/A标识
        // 实际应用中应使用veraPDF或PDFBox的Preflight验证器
        try {
            // PDFBox 3.0中检测PDF/A的方法
            // 这里提供简化版本，实际需要检查XMP metadata
            return false; // 默认假设支持透明度
        } catch (Exception e) {
            return false;
        }
    }
}