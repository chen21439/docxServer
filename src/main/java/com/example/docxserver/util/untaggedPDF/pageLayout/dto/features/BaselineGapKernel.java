package com.example.docxserver.util.untaggedPDF.pageLayout.dto.features;

import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Cell;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.Feature;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.PageContext;

/**
 * 特征：基线间距（em 标准化）
 *
 * 计算逻辑：
 * - 计算上下单元格基线之间的距离（以 em 为单位）
 * - 使用高斯核函数：峰值在 1.4em 附近
 * - 如果在 [min, max] 范围内返回 1.0，否则使用高斯衰减
 */
public class BaselineGapKernel implements Feature {

    @Override
    public String name() {
        return "baseline_gap_em";
    }

    @Override
    public double value(Cell upper, Cell lower, PageContext context) {
        // 使用 upper 的字体大小（至少 9pt）
        double em = Math.max(9.0, upper.fontSize);

        // 计算基线间距（假设基线在 bbox.y）
        double gap = Math.abs(lower.bbox.y - upper.bbox.y) / em;

        double min = context.getConfig().BASELINE_GAP_EM_MIN;
        double max = context.getConfig().BASELINE_GAP_EM_MAX;

        // 在合理范围内返回 1.0
        if (gap >= min && gap <= max) {
            return 1.0;
        }

        // 否则使用高斯核函数（峰值在 1.4em）
        double peak = 1.4;
        double sigma = 0.5;
        return Math.exp(-Math.pow(gap - peak, 2) / (2 * sigma * sigma));
    }
}