package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.MCIDTextExtractor;
import com.example.docxserver.util.taggedPDF.dto.TextWithPositions;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.*;

/**
 * PDF文本提取器
 * 负责从PDF结构元素中提取文本内容
 */
public class PdfTextExtractor {

    /**
     * 从结构元素中提取文本（使用MCID按页分桶的方法）
     *
     * 核心思路：
     * 1. 优先使用 /ActualText 属性
     * 2. 递归收集该元素后代的所有MCID，**按页分桶**存储
     * 3. 对每一页，用MCIDTextExtractor只提取该页该元素的MCID对应的文本
     * 4. 拼接所有页的文本
     *
     * 关键点：
     * - MCID的收集范围**严格限制在该元素的后代**，不包含整表/整页
     * - 按页分桶，避免跨页混用MCID
     * - 每页单独提取，避免把其他页的同号MCID内容也吸进来
     *
     * @param element 结构元素
     * @param doc PDF文档
     * @return 提取的文本
     * @throws IOException IO异常
     */
    public static String extractTextFromElement(
            PDStructureElement element,
            PDDocument doc) throws IOException {
        return extractTextFromElement(element, doc, "", null);
    }

    /**
     * 从结构元素中提取文本（带调试ID和表格MCID过滤）
     *
     * @param element 结构元素
     * @param doc PDF文档
     * @param cellId 单元格ID（用于调试输出）
     * @param tableMCIDsByPage 表格MCID按页分桶的映射（用于过滤）
     * @return 提取的文本
     * @throws IOException IO异常
     */
    public static String extractTextFromElement(
            PDStructureElement element,
            PDDocument doc,
            String cellId,
            Map<PDPage, Set<Integer>> tableMCIDsByPage) throws IOException {

        // 1. 优先使用 /ActualText
        String actualText = element.getActualText();
        if (actualText != null && !actualText.isEmpty()) {
            return actualText;
        }

        // 2. 收集该元素后代的MCID，按页分桶（不排除表格，因为该方法有tableMCIDsByPage参数用于后过滤）
        Map<PDPage, Set<Integer>> mcidsByPage = McidCollector.collectMcidsByPage(element, doc, false);

        // 3. 过滤掉表格的MCID（如果tableMCIDsByPage不为null）
        if (tableMCIDsByPage != null && !tableMCIDsByPage.isEmpty()) {
            int beforeFilterCount = 0;
            int afterFilterCount = 0;

            // 统计过滤前的总MCID数
            for (Set<Integer> mcids : mcidsByPage.values()) {
                beforeFilterCount += mcids.size();
            }

            // 逐页过滤
            for (Map.Entry<PDPage, Set<Integer>> entry : mcidsByPage.entrySet()) {
                PDPage page = entry.getKey();
                Set<Integer> mcids = entry.getValue();
                Set<Integer> tableMcids = tableMCIDsByPage.get(page);

                if (tableMcids != null && !tableMcids.isEmpty()) {
                    // 移除该页的表格MCID
                    mcids.removeAll(tableMcids);
                }
            }

            // 统计过滤后的总MCID数
            for (Set<Integer> mcids : mcidsByPage.values()) {
                afterFilterCount += mcids.size();
            }

            if (beforeFilterCount != afterFilterCount) {
                System.out.println("      [MCID过滤] " + cellId + " 过滤前:" + beforeFilterCount +
                                   " -> 过滤后:" + afterFilterCount +
                                   " (移除了" + (beforeFilterCount - afterFilterCount) + "个表格MCID)");
            }
        }

        if (mcidsByPage.isEmpty()) {
            // 没有MCID，尝试递归提取子元素的ActualText
            return extractTextFromChildrenActualText(element);
        }

        // 4. 按页提取文本
        StringBuilder result = new StringBuilder();

        try {
            // 按文档页序遍历（确保文本顺序正确）
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                Set<Integer> mcids = mcidsByPage.get(page);

                if (mcids != null && !mcids.isEmpty()) {
                    // 使用MCIDTextExtractor提取该页该元素的文本
                    MCIDTextExtractor extractor = new MCIDTextExtractor(mcids);
                    extractor.setDebugPrefix(cellId);  // 设置调试前缀
                    extractor.processPage(page);
                    String pageText = extractor.getText().trim();

                    if (!pageText.isEmpty()) {
                        if (result.length() > 0) {
                            result.append(" ");
                        }
                        result.append(pageText);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("      [错误] MCID文本提取失败: " + e.getMessage());
            e.printStackTrace();
            return "";
        }

        return result.toString().trim();
    }

    /**
     * 从子元素递归提取ActualText（fallback方法）
     *
     * @param element 结构元素
     * @return 提取的文本
     * @throws IOException IO异常
     */
    private static String extractTextFromChildrenActualText(PDStructureElement element) throws IOException {
        StringBuilder text = new StringBuilder();

        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement) {
                PDStructureElement childElement = (PDStructureElement) kid;

                String childActualText = childElement.getActualText();
                if (childActualText != null && !childActualText.isEmpty()) {
                    if (text.length() > 0) {
                        text.append(" ");
                    }
                    text.append(childActualText);
                } else {
                    // 递归提取
                    String childText = extractTextFromChildrenActualText(childElement);
                    if (!childText.isEmpty()) {
                        if (text.length() > 0) {
                            text.append(" ");
                        }
                        text.append(childText);
                    }
                }
            }
        }

        return text.toString().trim();
    }

    /**
     * 收集该结构元素后代的所有MCID（不分页，返回Set）
     *
     * @param element 结构元素
     * @return MCID集合
     * @throws IOException IO异常
     */
    public static Set<Integer> collectAllMcids(PDStructureElement element) throws IOException {
        Set<Integer> result = new HashSet<>();
        McidCollector.collectAllMcidsRecursive(element, result);
        return result;
    }

    /**
     * 从结构元素中提取文本及其TextPosition列表（包含坐标信息）
     *
     * 用于需要同时获取文本和边界框坐标的场景
     *
     * @param element 结构元素
     * @param doc PDF文档
     * @return TextWithPositions对象（包含文本和TextPosition列表）
     * @throws IOException IO异常
     */
    public static TextWithPositions extractTextWithPositions(
            PDStructureElement element,
            PDDocument doc) throws IOException {

        // 1. 优先使用 /ActualText
        String actualText = element.getActualText();
        if (actualText != null && !actualText.isEmpty()) {
            // ActualText没有位置信息，返回空列表
            return new TextWithPositions(actualText, new ArrayList<TextPosition>());
        }

        // 2. 收集该元素后代的MCID，按页分桶
        Map<PDPage, Set<Integer>> mcidsByPage = McidCollector.collectMcidsByPage(element, doc, false);

        if (mcidsByPage.isEmpty()) {
            // 没有MCID，尝试递归提取子元素的ActualText
            String text = extractTextFromChildrenActualText(element);
            return new TextWithPositions(text, new ArrayList<TextPosition>());
        }

        // 3. 按页提取文本和位置
        StringBuilder resultText = new StringBuilder();
        List<TextPosition> allPositions = new ArrayList<>();
        Map<PDPage, List<TextPosition>> positionsByPage = new HashMap<>();

        try {
            // 按文档页序遍历（确保文本顺序正确）
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                Set<Integer> mcids = mcidsByPage.get(page);

                if (mcids != null && !mcids.isEmpty()) {
                    // 使用MCIDTextExtractor提取该页该元素的文本和位置
                    MCIDTextExtractor extractor = new MCIDTextExtractor(mcids);
                    extractor.processPage(page);

                    String pageText = extractor.getText().trim();
                    List<TextPosition> pagePositions = extractor.getTextPositions();

                    if (!pageText.isEmpty()) {
                        if (resultText.length() > 0) {
                            resultText.append(" ");
                        }
                        resultText.append(pageText);
                    }

                    // 收集所有TextPosition（兼容旧逻辑）
                    allPositions.addAll(pagePositions);

                    // 按页分组收集TextPosition（用于跨页bbox计算）
                    if (!pagePositions.isEmpty()) {
                        positionsByPage.put(page, new ArrayList<>(pagePositions));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("      [错误] MCID文本提取失败: " + e.getMessage());
            e.printStackTrace();
            return new TextWithPositions("", new ArrayList<TextPosition>());
        }

        return new TextWithPositions(resultText.toString().trim(), allPositions, positionsByPage);
    }
}