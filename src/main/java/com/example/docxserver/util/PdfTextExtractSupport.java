package com.example.docxserver.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PDF文本提取支持类
 * 提供PDF表格遍历和文本提取的公共方法，复用toXML()方法中的核心逻辑
 *
 * 核心功能：
 * 1. 遍历PDF结构树中的表格、行、单元格
 * 2. 为每个单元格生成ID（格式：t001-r007-c001-p001）
 * 3. 从单元格中提取文本内容
 * 4. 支持按顺序读取表格数据（与toXML()方法保持一致）
 *
 * 核心实现思路（与ParagraphMapper.extractTablesFromElement保持完全一致）：
 *
 * 一、PDF结构树遍历策略
 * 1. 前提条件：
 *    - PDF必须是Tagged PDF（PDF/A-4或带结构标签）
 *    - 包含完整的结构树：Table -> TR -> TD -> P/Span
 *    - 从DOCX转换而来，结构完整无丢失
 *
 * 2. 遍历顺序（深度优先）：
 *    - 从StructureTreeRoot开始
 *    - 递归查找所有Table元素
 *    - 对每个Table，按顺序遍历TR（行）
 *    - 对每个TR，按顺序遍历TD（单元格）
 *    - 保证遍历顺序与文档显示顺序一致
 *
 * 二、ID生成规则（严格按顺序生成，确保与toXML()完全一致）
 * 1. 表格ID：t001, t002, ... (全局递增，按遍历顺序)
 * 2. 行ID：t001-r001, t001-r002, ... (表格内递增)
 * 3. 单元格ID：t001-r007-c001-p001 (行内递增，固定-p001后缀)
 * 4. 索引从1开始，使用%03d格式化为3位数字
 *
 * 三、文本提取策略（MCID按页分桶方法）
 * 1. 优先级顺序：
 *    a. 优先使用/ActualText属性（如果存在）
 *    b. 否则收集MCID，使用MCIDTextExtractor提取
 *    c. 最后fallback到递归提取子元素的ActualText
 *
 * 2. MCID收集与提取（关键技术）：
 *    - 问题：同一个MCID可能在多页出现，直接提取会混淆
 *    - 解决方案：按页分桶
 *      a. 递归收集该TD后代的所有MCID（深度优先）
 *      b. 将MCID按所属页面分组：Map<PDPage, Set<Integer>>
 *      c. 对每一页单独提取：MCIDTextExtractor(mcids).processPage(page)
 *      d. 拼接所有页的文本，保持文档顺序
 *
 * 3. 关键点：
 *    - MCID收集范围严格限制在该TD的后代（不包含兄弟/父节点）
 *    - 按文档页序遍历（for i=0 to doc.getNumberOfPages()-1）
 *    - 每页只提取该TD在该页的MCID对应的文本
 *
 * 四、与toXML()方法的对应关系
 * 1. extractTextByIds() ≈ toXML()的批量优化版本
 * 2. extractTablesWithIds() ≈ extractTablesFromElement()的目标ID过滤版本
 * 3. extractTextFromCell() ≈ extractTextFromElement()的独立版本
 * 4. ID生成逻辑完全相同，确保一致性
 *
 * 五、使用场景
 * 1. testFindTextByIdInPdf()：批量测试ID定位和文本提取
 * 2. 未来功能：根据ID修改PDF文字格式
 * 3. 验证功能：对比_pdf.txt文件中的文本
 *
 * @author Claude
 */
public class PdfTextExtractSupport {

    /**
     * 从PDF中按ID提取文本
     * 参考toXML()方法的实现逻辑，确保顺序和ID生成规则一致
     *
     * @param pdfPath PDF文件路径
     * @param targetIds 目标ID列表
     * @return Map<ID, 文本内容>
     * @throws IOException 文件读取异常
     */
    public static Map<String, String> extractTextByIds(String pdfPath, List<String> targetIds) throws IOException {
        Map<String, String> results = new LinkedHashMap<>();

        // 将目标ID转换为Set，便于快速查找
        Set<String> targetIdSet = new HashSet<>(targetIds);

        // 打开PDF文档
        File pdfFile = new File(pdfPath);
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {

            // 获取结构树根节点
            if (doc.getDocumentCatalog() == null || doc.getDocumentCatalog().getStructureTreeRoot() == null) {
                System.err.println("该PDF没有结构树（不是Tagged PDF）");
                return results;
            }

            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot structTreeRoot =
                doc.getDocumentCatalog().getStructureTreeRoot();

            System.out.println("开始从PDF提取指定ID的文本...");

            // 遍历结构树，提取目标ID的文本
            TableCounter tableCounter = new TableCounter();
            for (Object kid : structTreeRoot.getKids()) {
                if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element =
                        (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;
                    extractTablesWithIds(element, doc, targetIdSet, results, tableCounter);
                }
            }

            System.out.println("提取完成，成功提取 " + results.size() + " / " + targetIds.size() + " 个ID");
        }

        return results;
    }

    /**
     * 从结构元素中递归提取表格（只提取目标ID的文本）
     * 参考ParagraphMapper.toXML()的实现逻辑
     *
     * 增强策略：先在TR级别收集所有单元格的文本
     * 1. 优先：如果cellID完全匹配，直接使用该TD的文本
     * 2. 降级：如果cellID没有匹配，尝试用目标文本与TR的所有TD文本进行归一化匹配
     */
    private static void extractTablesWithIds(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element,
            PDDocument doc,
            Set<String> targetIdSet,
            Map<String, String> results,
            TableCounter tableCounter) throws IOException {

        String structType = element.getStructureType();

        // 如果是Table元素
        if ("Table".equalsIgnoreCase(structType)) {
            int tableIndex = tableCounter.tableIndex++;
            String tableId = "t" + String.format("%03d", tableIndex + 1);

            // 提取表格内的行
            int rowIndex = 0;
            for (Object kid : element.getKids()) {
                if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement rowElement =
                        (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                    if ("TR".equalsIgnoreCase(rowElement.getStructureType())) {
                        String rowId = tableId + "-r" + String.format("%03d", rowIndex + 1);

                        // 先收集该行所有单元格的ID和文本（用于后续降级匹配）
                        Map<String, String> rowCellTexts = new LinkedHashMap<>();
                        int colIndex = 0;
                        for (Object cellKid : rowElement.getKids()) {
                            if (cellKid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement cellElement =
                                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) cellKid;

                                if ("TD".equalsIgnoreCase(cellElement.getStructureType())) {
                                    String cellId = rowId + "-c" + String.format("%03d", colIndex + 1) + "-p001";
                                    String cellText = extractTextFromCell(cellElement, doc);
                                    rowCellTexts.put(cellId, cellText);
                                    colIndex++;
                                }
                            }
                        }

                        // 遍历目标ID，优先精确匹配，否则降级到TR级别匹配
                        for (String targetId : targetIdSet) {
                            if (results.containsKey(targetId)) {
                                // 该ID已经找到，跳过
                                continue;
                            }

                            // 检查该targetId是否属于当前行（通过rowId前缀判断）
                            if (!targetId.startsWith(rowId + "-c")) {
                                continue;
                            }

                            // 策略1: 精确匹配cellID
                            if (rowCellTexts.containsKey(targetId)) {
                                results.put(targetId, rowCellTexts.get(targetId));
                            } else {
                                // 策略2: 降级到TR级别匹配（使用整行所有单元格的文本）
                                StringBuilder rowText = new StringBuilder();
                                for (String cellText : rowCellTexts.values()) {
                                    if (cellText != null && !cellText.trim().isEmpty()) {
                                        if (rowText.length() > 0) {
                                            rowText.append(" ");
                                        }
                                        rowText.append(cellText);
                                    }
                                }
                                results.put(targetId, rowText.toString());
                            }
                        }

                        rowIndex++;
                    }
                }
            }
        }

        // 递归处理子元素（继续查找更多表格）
        for (Object kid : element.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;
                extractTablesWithIds(childElement, doc, targetIdSet, results, tableCounter);
            }
        }
    }

    /**
     * 从单元格中提取文本
     * 参考ParagraphMapper.extractTextFromElement()的实现逻辑
     * 使用MCID方式提取文本（与toXML()保持一致）
     */
    private static String extractTextFromCell(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement cellElement,
            PDDocument doc) throws IOException {

        // 1. 优先使用 /ActualText
        String actualText = cellElement.getActualText();
        if (actualText != null && !actualText.isEmpty()) {
            return actualText;
        }

        // 2. 收集该TD后代的MCID，按页分桶
        Map<org.apache.pdfbox.pdmodel.PDPage, Set<Integer>> mcidsByPage = collectMcidsByPage(cellElement);

        if (mcidsByPage.isEmpty()) {
            // 没有MCID，尝试递归提取子元素的ActualText
            return extractTextFromChildrenActualText(cellElement);
        }

        // 3. 按页提取文本
        StringBuilder result = new StringBuilder();

        try {
            // 按文档页序遍历（确保文本顺序正确）
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(i);
                Set<Integer> mcids = mcidsByPage.get(page);

                if (mcids != null && !mcids.isEmpty()) {
                    // 使用MCIDTextExtractor提取该页该TD的文本
                    MCIDTextExtractor extractor = new MCIDTextExtractor(mcids);
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
            return "";
        }

        return result.toString().trim();
    }

    /**
     * 收集该结构元素后代的所有MCID，按页分桶
     */
    private static Map<org.apache.pdfbox.pdmodel.PDPage, Set<Integer>> collectMcidsByPage(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element) throws IOException {

        Map<org.apache.pdfbox.pdmodel.PDPage, Set<Integer>> result = new HashMap<>();
        collectMcidsRecursive(element, result);
        return result;
    }

    /**
     * 递归收集MCID（深度优先遍历）
     */
    private static void collectMcidsRecursive(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element,
            Map<org.apache.pdfbox.pdmodel.PDPage, Set<Integer>> mcidsByPage) throws IOException {

        for (Object kid : element.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                // 递归处理子结构元素
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;
                collectMcidsRecursive(childElement, mcidsByPage);

            } else if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent) {
                // PDMarkedContent包含MCID信息
                org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent mc =
                    (org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent) kid;

                Integer mcid = mc.getMCID();
                org.apache.pdfbox.pdmodel.PDPage page = element.getPage();

                if (mcid != null && page != null) {
                    mcidsByPage.computeIfAbsent(page, k -> new HashSet<>()).add(mcid);
                }

            } else if (kid instanceof Integer) {
                // 直接的MCID整数
                Integer mcid = (Integer) kid;
                org.apache.pdfbox.pdmodel.PDPage page = element.getPage();

                if (page != null) {
                    mcidsByPage.computeIfAbsent(page, k -> new HashSet<>()).add(mcid);
                }
            }
        }
    }

    /**
     * 从子元素递归提取ActualText（fallback方法）
     */
    private static String extractTextFromChildrenActualText(
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement element) throws IOException {

        StringBuilder text = new StringBuilder();

        for (Object kid : element.getKids()) {
            if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) {
                org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement childElement =
                    (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement) kid;

                String childActualText = childElement.getActualText();
                if (childActualText != null && !childActualText.isEmpty()) {
                    if (text.length() > 0) {
                        text.append(" ");
                    }
                    text.append(childActualText);
                } else {
                    // 递归
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
     * 表格计数器类
     */
    public static class TableCounter {
        public int tableIndex = 0;
    }
}