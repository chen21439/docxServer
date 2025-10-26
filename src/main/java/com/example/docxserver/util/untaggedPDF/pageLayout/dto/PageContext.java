package com.example.docxserver.util.untaggedPDF.pageLayout.dto;

import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Cell;
import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Line;
import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Table;

import java.util.List;

/**
 * 页面上下文
 *
 * 提供特征计算所需的全局信息：
 * - 表格结构
 * - 表格线数据
 * - 配置参数
 */
public class PageContext {

    private final Table table;
    private final List<Line> lines;
    private final PdfTableConfig config;

    public PageContext(Table table, List<Line> lines, PdfTableConfig config) {
        this.table = table;
        this.lines = lines;
        this.config = config;
    }

    public PdfTableConfig getConfig() {
        return config;
    }

    public Table getTable() {
        return table;
    }

    public List<Line> getLines() {
        return lines;
    }

    /**
     * 检查两个单元格之间水平线的存在情况
     *
     * @param upper 上方单元格
     * @param lower 下方单元格
     * @return 水平线存在比例 [0, 1]（0 = 完全无线，1 = 有完整线）
     */
    public double hRulePresenceBetween(Cell upper, Cell lower) {
        // 计算两个单元格之间的 Y 坐标（取平均值）
        double betweenY = (upper.bbox.getMaxY() + lower.bbox.y) / 2.0;
        double tolerance = 3.0; // 3pt 容差

        // 计算单元格的 X 范围
        double minX = Math.max(upper.bbox.x, lower.bbox.x);
        double maxX = Math.min(upper.bbox.getMaxX(), lower.bbox.getMaxX());
        double rangeX = maxX - minX;

        if (rangeX <= 0) {
            return 0.0; // 无重叠区域
        }

        // 查找在该 Y 坐标附近的水平线
        double coveredLength = 0.0;

        for (Line line : lines) {
            if (!line.isHorizontal) continue;

            // 检查 Y 坐标是否在容差范围内
            if (Math.abs(line.start.y - betweenY) > tolerance) continue;

            // 计算线段与单元格 X 范围的重叠部分
            double lineMinX = Math.min(line.start.x, line.end.x);
            double lineMaxX = Math.max(line.start.x, line.end.x);

            double overlapMin = Math.max(minX, lineMinX);
            double overlapMax = Math.min(maxX, lineMaxX);

            if (overlapMax > overlapMin) {
                coveredLength += (overlapMax - overlapMin);
            }
        }

        // 返回覆盖比例
        return Math.min(1.0, coveredLength / rangeX);
    }

    /**
     * 计算指定行（除了指定列外）的空单元格比例
     *
     * @param rowIndex 行索引
     * @param excludeCol 排除的列索引
     * @return 空比例 [0, 1]
     */
    public double rowEmptyRatio(int rowIndex, int excludeCol) {
        if (rowIndex < 0 || rowIndex >= table.rows.size()) {
            return 0.0;
        }

        List<Cell> row = table.rows.get(rowIndex);
        int totalCells = row.size();
        int emptyCells = 0;

        for (int c = 0; c < row.size(); c++) {
            if (c == excludeCol) {
                totalCells--; // 不计入该列
                continue;
            }

            Cell cell = row.get(c);
            if (cell.text == null || cell.text.trim().isEmpty()) {
                emptyCells++;
            }
        }

        if (totalCells <= 0) {
            return 0.0;
        }

        return (double) emptyCells / totalCells;
    }
}