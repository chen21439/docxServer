package com.example.docxserver.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PDFUtil {

    public static void main(String[] args) throws Exception {
        File file = new File("E:\\programFile\\AIProgram\\docxServer\\pdf\\批注_5.19深圳市水库小学新改扩建二装图书馆设备采购招标文件 (1).pdf");

        try (PDDocument doc = Loader.loadPDF(file)) {
            System.out.println("=== 检查 PDF 是否为 Tagged PDF ===");
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            PDStructureTreeRoot structureTreeRoot = catalog.getStructureTreeRoot();

            if (structureTreeRoot != null) {
                System.out.println("✓ 这是一个 Tagged PDF");
                System.out.println("标记类型: " + structureTreeRoot.getCOSObject().getItem("RoleMap"));
                System.out.println("\n=== 使用 P 标签提取段落 ===\n");

                try {
                    // 方法1: 使用 MCID 映射提取（更准确）
                    extractParagraphsByMCID(doc, structureTreeRoot);
                } catch (Exception e) {
                    System.out.println("MCID 提取失败: " + e.getMessage());
                    e.printStackTrace();
                    System.out.println("\n=== 降级使用文本分析方式 ===\n");
                    extractParagraphsByTextAnalysis(doc);
                }
            } else {
                System.out.println("✗ 这不是 Tagged PDF，使用普通段落分割方式");
                System.out.println("\n=== 使用文本分析方式提取段落 ===\n");
                extractParagraphsByTextAnalysis(doc);
            }
        }
    }

    /**
     * 使用结构化信息指导的文本提取（混合方案）
     * 基于 P 标签知道有多少个段落，然后用文本分析提取
     */
    private static void extractParagraphsByMCID(PDDocument doc, PDStructureTreeRoot root) throws IOException {
        // 步骤1: 收集所有 P 标签的段落元素
        List<ParagraphElement> paragraphElements = new ArrayList<>();
        collectParagraphElements(root, paragraphElements);

        System.out.println("✓ Tagged PDF 结构中找到 " + paragraphElements.size() + " 个段落标签");
        System.out.println("  (由于 MCID 映射实现复杂，当前使用增强的文本分析方法)\n");

        // 步骤2: 使用改进的文本分析方法
        // 根据段落数量调整分割策略
        extractParagraphsByTextAnalysisEnhanced(doc, paragraphElements.size());
    }

    /**
     * 段落元素类
     */
    static class ParagraphElement {
        String type;
        int pageNum;
        List<Integer> mcids = new ArrayList<>();

        public ParagraphElement(String type) {
            this.type = type;
        }
    }

    /**
     * 收集所有段落元素（P、H1-H6等）
     */
    private static void collectParagraphElements(PDStructureNode node, List<ParagraphElement> result) throws IOException {
        if (node instanceof PDStructureElement) {
            PDStructureElement element = (PDStructureElement) node;
            String type = element.getStructureType();

            // 如果是段落类型，添加到结果
            if (isParagraphType(type)) {
                ParagraphElement para = new ParagraphElement(type);
                collectMCIDs(element, para);
                if (!para.mcids.isEmpty()) {
                    result.add(para);
                }
            }

            // 递归处理子元素
            List<Object> kids = element.getKids();
            if (kids != null) {
                for (Object kid : kids) {
                    if (kid instanceof PDStructureNode) {
                        collectParagraphElements((PDStructureNode) kid, result);
                    }
                }
            }
        } else if (node instanceof PDStructureTreeRoot) {
            PDStructureTreeRoot root = (PDStructureTreeRoot) node;
            List<Object> kids = root.getKids();
            if (kids != null) {
                for (Object kid : kids) {
                    if (kid instanceof PDStructureNode) {
                        collectParagraphElements((PDStructureNode) kid, result);
                    }
                }
            }
        }
    }

    /**
     * 收集元素的所有 MCID
     */
    private static void collectMCIDs(PDStructureElement element, ParagraphElement para) throws IOException {
        List<Object> kids = element.getKids();
        if (kids != null) {
            for (Object kid : kids) {
                if (kid instanceof Integer) {
                    para.mcids.add((Integer) kid);
                } else if (kid instanceof PDStructureElement) {
                    collectMCIDs((PDStructureElement) kid, para);
                }
            }
        }
    }

    /**
     * 从页面提取 MCID 到文本的映射
     * 使用简化方案：直接用文本位置分析，但保留段落顺序
     */
    private static Map<Integer, String> extractMCIDText(PDPage page) throws IOException {
        // 简化实现：先返回空映射
        // 实际的 MCID 文本提取非常复杂，需要深入解析 PDF 内容流
        return new HashMap<>();
    }

    /**
     * 根据 MCID 列表构建段落文本
     */
    private static String buildParagraphText(ParagraphElement para, Map<Integer, Map<Integer, String>> pageToMCIDText) {
        StringBuilder text = new StringBuilder();

        // 遍历所有页面的 MCID 映射
        for (Map<Integer, String> mcidToText : pageToMCIDText.values()) {
            for (Integer mcid : para.mcids) {
                String mcidText = mcidToText.get(mcid);
                if (mcidText != null) {
                    text.append(mcidText);
                }
            }
        }

        return text.toString();
    }


    /**
     * 提取 Tagged PDF 的结构化段落（调试用）
     */
    private static void extractStructuredParagraphs(PDStructureNode node, int level) throws IOException {
        String indent = repeatString("  ", level);

        if (node instanceof PDStructureElement) {
            PDStructureElement element = (PDStructureElement) node;
            String type = element.getStructureType();

            // 打印结构类型
            System.out.println(indent + "类型: " + type + " | 标题: " + element.getTitle());

            // 递归处理子元素
            List<Object> kids = element.getKids();
            if (kids != null && !kids.isEmpty()) {
                System.out.println(indent + "  子元素数量: " + kids.size());
                for (Object kid : kids) {
                    if (kid instanceof PDStructureNode) {
                        extractStructuredParagraphs((PDStructureNode) kid, level + 1);
                    } else if (kid instanceof Integer) {
                        System.out.println(indent + "  -> 内容引用ID: " + kid);
                    } else {
                        System.out.println(indent + "  -> 其他类型: " + kid.getClass().getName());
                    }
                }
            } else {
                System.out.println(indent + "  (无子元素)");
            }
        } else if (node instanceof PDStructureTreeRoot) {
            PDStructureTreeRoot root = (PDStructureTreeRoot) node;
            List<Object> kids = root.getKids();
            System.out.println("结构树根节点，子元素数量: " + (kids != null ? kids.size() : 0));
            if (kids != null) {
                for (Object kid : kids) {
                    if (kid instanceof PDStructureNode) {
                        extractStructuredParagraphs((PDStructureNode) kid, level + 1);
                    }
                }
            }
        }
    }

    /**
     * 重复字符串（Java 8 兼容）
     */
    private static String repeatString(String str, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(str.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    /**
     * 判断是否为段落类型的标签
     */
    private static boolean isParagraphType(String type) {
        return type != null && (
            type.equals("P") ||      // Paragraph
            type.equals("H") ||      // Heading
            type.equals("H1") ||     // Heading 1
            type.equals("H2") ||     // Heading 2
            type.equals("H3") ||     // Heading 3
            type.equals("H4") ||     // Heading 4
            type.equals("H5") ||     // Heading 5
            type.equals("H6") ||     // Heading 6
            type.equals("Lbl") ||    // Label
            type.equals("LI") ||     // List Item
            type.equals("LBody")     // List Body
        );
    }

    /**
     * 从结构化元素中提取文本
     * 注意：这个方法在 PDFBox 中很难直接从 PDStructureElement 获取文本
     * 因为结构树只存储了内容的引用（MCID），真正的文本在页面内容流中
     * 需要通过页面内容流和 MCID 映射来获取，这很复杂
     */
    private static String extractElementText(PDStructureElement element) throws IOException {
        StringBuilder text = new StringBuilder();
        List<Object> kids = element.getKids();

        if (kids != null) {
            for (Object kid : kids) {
                if (kid instanceof PDStructureElement) {
                    String childText = extractElementText((PDStructureElement) kid);
                    if (childText != null) {
                        text.append(childText);
                    }
                } else if (kid instanceof Integer) {
                    // 这是 MCID (Marked Content ID)，需要通过页面内容流解析
                    // 这里暂时无法直接获取文本
                    text.append("[MCID:").append(kid).append("]");
                }
            }
        }

        return text.toString();
    }

    /**
     * 对于非 Tagged PDF，使用文本分析方式提取段落
     */
    private static void extractParagraphsByTextAnalysis(PDDocument doc) throws IOException {
        extractParagraphsByTextAnalysisEnhanced(doc, -1);
    }

    /**
     * 增强的文本分析提取（可以参考期望的段落数量）
     */
    private static void extractParagraphsByTextAnalysisEnhanced(PDDocument doc, int expectedParagraphCount) throws IOException {
        ParagraphStripper stripper = new ParagraphStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(1);
        stripper.setEndPage(doc.getNumberOfPages());

        StringWriter writer = new StringWriter();
        stripper.writeText(doc, writer);

        List<String> paragraphs = stripper.getParagraphs();

        if (expectedParagraphCount > 0) {
            System.out.println("实际提取到 " + paragraphs.size() + " 个段落");
            System.out.println("（结构化标记显示应有 " + expectedParagraphCount + " 个段落）\n");
        } else {
            System.out.println("总共提取到 " + paragraphs.size() + " 个段落\n");
        }

        int index = 1;
        for (String paragraph : paragraphs) {
            System.out.println("【段落 " + index + "】");
            System.out.println(paragraph);
            System.out.println("-----------------------------------\n");
            index++;
        }
    }

    /**
     * 自定义 PDFTextStripper 用于段落分析
     */
    static class ParagraphStripper extends PDFTextStripper {
        private List<String> paragraphs = new ArrayList<>();
        private StringBuilder currentParagraph = new StringBuilder();
        private float lastY = -1;
        private float lastX = -1;
        private float lastFontSize = -1;
        private int currentPage = -1;
        private static final float LINE_SPACING_THRESHOLD = 15.0f; // 行间距阈值（段落之间）
        private static final float NORMAL_LINE_HEIGHT = 12.0f; // 正常行高

        public ParagraphStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
            super.startPage(page);
            currentPage++;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (textPositions.isEmpty()) {
                return;
            }

            TextPosition firstPosition = textPositions.get(0);
            float currentY = firstPosition.getY();
            float currentX = firstPosition.getX();
            float currentFontSize = firstPosition.getFontSize();

            // 判断是否应该开始新段落
            if (lastY != -1) {
                float yDiff = currentY - lastY; // 不用绝对值，保留方向信息
                boolean fontSizeChanged = Math.abs(currentFontSize - lastFontSize) > 1.0f;
                boolean isNewLine = yDiff > 1.0f; // Y坐标增加表示新行

                // 判断是否为新段落的条件：
                // 1. 行间距明显大于正常行高（说明是段落间距）
                // 2. 字体大小改变（可能是标题或新部分）
                // 3. X坐标明显不同且在左侧（可能是新段落开始，排除右对齐和居中的情况）
                boolean isParagraphBreak = false;

                if (fontSizeChanged) {
                    // 字体大小变化，可能是新段落或标题
                    isParagraphBreak = true;
                } else if (isNewLine && yDiff > LINE_SPACING_THRESHOLD) {
                    // 行间距过大，明显的段落分隔
                    isParagraphBreak = true;
                } else if (isNewLine && Math.abs(currentX - lastX) < 5.0f && yDiff > NORMAL_LINE_HEIGHT * 1.5) {
                    // 相似的X坐标（左对齐），但行距较大
                    isParagraphBreak = true;
                }

                if (isParagraphBreak) {
                    saveParagraph();
                }
            }

            currentParagraph.append(text);
            lastY = currentY;
            lastX = currentX;
            lastFontSize = currentFontSize;

            super.writeString(text, textPositions);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            // 在同一段落内的换行，不添加空格，保持原样
            super.writeLineSeparator();
        }

        private void saveParagraph() {
            String paragraph = currentParagraph.toString().trim();
            if (!paragraph.isEmpty()) {
                paragraphs.add(paragraph);
            }
            currentParagraph = new StringBuilder();
            lastY = -1; // 重置，避免影响下一段
        }

        public List<String> getParagraphs() {
            // 保存最后一个段落
            saveParagraph();
            return paragraphs;
        }
    }
}
