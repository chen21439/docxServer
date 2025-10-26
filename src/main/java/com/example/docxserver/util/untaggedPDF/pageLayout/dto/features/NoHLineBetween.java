package com.example.docxserver.util.untaggedPDF.pageLayout.dto.features;

import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Cell;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.Feature;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.PageContext;

/**
 * 特征：两行之间无水平线
 *
 * 计算逻辑：
 * - 检查 upper 的底边和 lower 的顶边之间是否存在完整的水平线
 * - 返回值：1.0 - 线存在比例（1.0 表示完全无线，0.0 表示有完整线）
 */
public class NoHLineBetween implements Feature {

    @Override
    public String name() {
        return "no_hline_between";
    }

    @Override
    public double value(Cell upper, Cell lower, PageContext context) {
        // 检查两个单元格之间的水平线存在情况
        double presence = context.hRulePresenceBetween(upper, lower);
        return 1.0 - presence;
    }
}