package com.example.docxserver.util.docx;

import org.apache.poi.xwpf.usermodel.*;

import java.util.*;

/**
 * 合成嵌套表检测器（Step 3 实现）
 *
 * 功能：
 * - 检测单张表内部的逻辑子表（伪嵌套表）
 * - 通过列名近似匹配、列数突变、缩进风格等信号融合识别子表
 * - 输出合成子表片段（synthetic nested tables）
 *
 * 算法：
 * 1. 列名近似匹配：检测重复的表头模式
 * 2. 列数突变检测：3列→4列等变化
 * 3. 缩进/编号风格检测：识别分组标记
 * 4. 多信号融合，阈值 0.75 起算
 */
public class SyntheticTableDetector {

    /**
     * 合成子表检测阈值
     */
    private static final double SYNTHETIC_TABLE_THRESHOLD = 0.75;

    /**
     * 列名相似度阈值
     */
    private static final double COLUMN_SIMILARITY_THRESHOLD = 0.7;

    /**
     * 常见的评分表列头关键词
     */
    private static final Set<String> EVAL_TABLE_KEYWORDS = new HashSet<>(Arrays.asList(
        "序号", "评分因素", "评分项", "权重", "分值", "评分准则", "评分标准", "评审内容",
        "指标", "考核内容", "考核标准", "分数", "得分", "评分细则"
    ));

    /**
     * 检测表格中的合成嵌套表
     *
     * @param xwpfTable POI 表格对象
     * @param tableId 表格ID
     * @param originalColumns 原始列定义（来自第一行表头）
     * @return 检测到的合成子表列表
     */
    public static List<SyntheticSubTable> detectSyntheticTables(
            XWPFTable xwpfTable, String tableId, List<DocxAnalysisResult.TableColumn> originalColumns) {

        List<SyntheticSubTable> syntheticTables = new ArrayList<>();
        List<XWPFTableRow> rows = xwpfTable.getRows();

        if (rows.size() <= 1) {
            return syntheticTables;  // 表格太小，无法检测子表
        }

        // 使用滑动窗口扫描表格，检测子表头和子表范围
        int windowStart = 1;  // 跳过第一行（主表头）
        int syntheticTableIndex = 0;

        while (windowStart < rows.size()) {
            // 检测从 windowStart 开始的潜在子表
            SubTableDetectionResult result = detectSubTableAt(rows, windowStart, originalColumns);

            if (result != null && result.confidence >= SYNTHETIC_TABLE_THRESHOLD) {
                syntheticTableIndex++;

                SyntheticSubTable subTable = new SyntheticSubTable();
                subTable.id = String.format("%s.syn%03d", tableId, syntheticTableIndex);
                subTable.headerRowIndex = result.headerRowIndex;
                subTable.startRowIndex = result.startRowIndex;
                subTable.endRowIndex = result.endRowIndex;
                subTable.sourceRows = new ArrayList<>();
                for (int i = result.startRowIndex; i <= result.endRowIndex; i++) {
                    subTable.sourceRows.add(i);
                }
                subTable.columns = result.columns;
                subTable.confidence = result.confidence;
                subTable.signals = result.signals;

                syntheticTables.add(subTable);

                // 跳过已检测的子表范围
                windowStart = result.endRowIndex + 1;
            } else {
                windowStart++;
            }
        }

        return syntheticTables;
    }

    /**
     * 在指定位置检测潜在的子表
     *
     * @param rows 所有行
     * @param startIndex 开始检测的行索引
     * @param originalColumns 原始列定义
     * @return 检测结果（如果检测到子表）
     */
    private static SubTableDetectionResult detectSubTableAt(
            List<XWPFTableRow> rows, int startIndex, List<DocxAnalysisResult.TableColumn> originalColumns) {

        if (startIndex >= rows.size()) {
            return null;
        }

        XWPFTableRow candidateHeaderRow = rows.get(startIndex);
        List<XWPFTableCell> headerCells = candidateHeaderRow.getTableCells();

        // 提取候选表头的文本
        List<String> candidateHeaders = new ArrayList<>();
        for (XWPFTableCell cell : headerCells) {
            candidateHeaders.add(cell.getText().trim());
        }

        // 计算检测分数
        DetectionScore score = calculateDetectionScore(candidateHeaders, originalColumns, rows, startIndex);

        if (score.totalScore < SYNTHETIC_TABLE_THRESHOLD) {
            return null;  // 分数太低，不是子表头
        }

        // 确定子表的结束行
        int endRowIndex = findSubTableEnd(rows, startIndex + 1, candidateHeaders.size());

        // 构建结果
        SubTableDetectionResult result = new SubTableDetectionResult();
        result.headerRowIndex = startIndex;
        result.startRowIndex = startIndex;
        result.endRowIndex = endRowIndex;
        result.confidence = score.totalScore;
        result.signals = score.signals;

        // 构建列定义
        result.columns = new ArrayList<>();
        for (int i = 0; i < candidateHeaders.size(); i++) {
            DocxAnalysisResult.TableColumn column = new DocxAnalysisResult.TableColumn();
            column.setId(String.format("c%d", i + 1));
            column.setLabel(candidateHeaders.get(i));
            result.columns.add(column);
        }

        return result;
    }

    /**
     * 计算子表检测分数
     *
     * @param candidateHeaders 候选表头文本
     * @param originalColumns 原始列定义
     * @param rows 所有行
     * @param rowIndex 当前行索引
     * @return 检测分数
     */
    private static DetectionScore calculateDetectionScore(
            List<String> candidateHeaders,
            List<DocxAnalysisResult.TableColumn> originalColumns,
            List<XWPFTableRow> rows,
            int rowIndex) {

        DetectionScore score = new DetectionScore();
        score.signals = new ArrayList<>();

        // 信号1：列数突变（权重 0.30）
        int originalColCount = originalColumns.size();
        int candidateColCount = candidateHeaders.size();

        if (candidateColCount != originalColCount && candidateColCount >= 3) {
            score.columnCountChange = 0.30;
            score.totalScore += 0.30;
            score.signals.add(String.format("columnCountChange:%d→%d", originalColCount, candidateColCount));
        }

        // 信号2：列名包含评分表关键词（权重 0.40）
        double keywordScore = calculateKeywordScore(candidateHeaders);
        if (keywordScore > 0) {
            score.keywordMatch = keywordScore * 0.40;
            score.totalScore += score.keywordMatch;
            score.signals.add(String.format("keywordMatch:%.2f", keywordScore));
        }

        // 信号3：前一行是分组标记（如"技术部分"）（权重 0.30）
        if (rowIndex > 0) {
            XWPFTableRow prevRow = rows.get(rowIndex - 1);
            String prevText = prevRow.getTableCells().get(0).getText().trim();

            if (isGroupMarker(prevText)) {
                score.groupMarker = 0.30;
                score.totalScore += 0.30;
                score.signals.add(String.format("groupMarker:%s", prevText));
            }
        }

        return score;
    }

    /**
     * 计算列名关键词得分
     *
     * @param headers 表头文本列表
     * @return 关键词匹配得分（0.0-1.0）
     */
    private static double calculateKeywordScore(List<String> headers) {
        int matchCount = 0;
        for (String header : headers) {
            for (String keyword : EVAL_TABLE_KEYWORDS) {
                if (header.contains(keyword)) {
                    matchCount++;
                    break;
                }
            }
        }

        if (headers.isEmpty()) {
            return 0.0;
        }

        return (double) matchCount / headers.size();
    }

    /**
     * 判断文本是否为分组标记
     *
     * @param text 文本内容
     * @return true 如果是分组标记
     */
    private static boolean isGroupMarker(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // 检测常见的分组标记模式
        return text.matches(".*部分.*\\(\\d+\\)") ||  // "技术部分(58)"
               text.matches(".*部分") ||                // "技术部分"
               text.matches(".*项") ||                  // "评审项"
               text.matches("第[一二三四五六七八九十]+部分") ||
               text.matches("[一二三四五六七八九十]+、.*");
    }

    /**
     * 查找子表的结束行
     *
     * @param rows 所有行
     * @param startRowIndex 子表数据开始行（表头的下一行）
     * @param expectedColCount 期望的列数
     * @return 子表结束行索引（inclusive）
     */
    private static int findSubTableEnd(List<XWPFTableRow> rows, int startRowIndex, int expectedColCount) {
        int endRowIndex = startRowIndex;

        // 向前扫描，直到遇到列数不匹配或新的分组标记
        for (int i = startRowIndex; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<XWPFTableCell> cells = row.getTableCells();

            // 检查列数是否匹配
            if (cells.size() != expectedColCount) {
                break;
            }

            // 检查是否遇到新的分组标记
            String firstCellText = cells.get(0).getText().trim();
            if (isGroupMarker(firstCellText) && i > startRowIndex) {
                break;
            }

            endRowIndex = i;
        }

        return endRowIndex;
    }

    /**
     * 合成子表
     */
    public static class SyntheticSubTable {
        public String id;
        public int headerRowIndex;  // 子表头行索引
        public int startRowIndex;   // 子表起始行索引（含表头）
        public int endRowIndex;     // 子表结束行索引
        public List<Integer> sourceRows;  // 源行范围
        public List<DocxAnalysisResult.TableColumn> columns;
        public double confidence;
        public List<String> signals;
    }

    /**
     * 子表检测结果
     */
    private static class SubTableDetectionResult {
        int headerRowIndex;
        int startRowIndex;
        int endRowIndex;
        List<DocxAnalysisResult.TableColumn> columns;
        double confidence;
        List<String> signals;
    }

    /**
     * 检测分数
     */
    private static class DetectionScore {
        double columnCountChange = 0.0;  // 列数突变得分
        double keywordMatch = 0.0;       // 关键词匹配得分
        double groupMarker = 0.0;        // 分组标记得分
        double totalScore = 0.0;         // 总分
        List<String> signals = new ArrayList<>();
    }
}