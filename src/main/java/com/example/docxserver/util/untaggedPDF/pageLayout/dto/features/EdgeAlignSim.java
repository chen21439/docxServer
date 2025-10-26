package com.example.docxserver.util.untaggedPDF.pageLayout.dto.features;

import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Cell;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.Feature;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.PageContext;

/**
 * 特征：左右边界对齐相似度
 *
 * 计算逻辑：
 * - 比较上下单元格的左边界和右边界的对齐程度
 * - 使用指数衰减函数：exp(-(dl+dr)/(2*tolerance))
 * - 对齐越好，值越接近 1.0
 */
public class EdgeAlignSim implements Feature {

    @Override
    public String name() {
        return "edge_align_sim";
    }

    @Override
    public double value(Cell upper, Cell lower, PageContext context) {
        double tolerance = context.getConfig().EDGE_ALIGN_TOL_PT;

        // 计算左右边界差异
        double dl = Math.abs(upper.bbox.x - lower.bbox.x);
        double dr = Math.abs(upper.bbox.getMaxX() - lower.bbox.getMaxX());

        // 指数衰减：对齐越好值越大
        return Math.exp(-(dl + dr) / (2.0 * tolerance));
    }
}