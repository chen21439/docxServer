package com.example.docxserver.util.untaggedPDF.pageLayout.dto.features;

import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Cell;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.Feature;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.PageContext;

/**
 * 特征：下一行空比例
 *
 * 计算逻辑：
 * - 统计 lower 所在行（除了当前列外）的空单元格比例
 * - 空比例越高，说明 lower 可能是 upper 的延续行
 * - 返回值：空比例 [0, 1]
 */
public class RowRplus1Empty implements Feature {

    @Override
    public String name() {
        return "row_rplus1_empty_ratio";
    }

    @Override
    public double value(Cell upper, Cell lower, PageContext context) {
        return context.rowEmptyRatio(lower.rowIndex, upper.colIndex);
    }
}