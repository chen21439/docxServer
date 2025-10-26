package com.example.docxserver.util.untaggedPDF.pageLayout.dto.features;

import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Cell;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.Feature;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.PageContext;

/**
 * 特征：样式匹配（字体大小、旋转）
 *
 * 计算逻辑：
 * - 比较字体大小相似度（0.5 权重）
 * - 比较旋转角度一致性（0.5 权重）
 * - 返回值：综合相似度 [0, 1]
 */
public class StyleMatch implements Feature {

    @Override
    public String name() {
        return "style_match";
    }

    @Override
    public double value(Cell upper, Cell lower, PageContext context) {
        // 字体大小相似度（差异越小越好）
        double fontSizeSim = 1.0 - Math.min(1.0, Math.abs(upper.fontSize - lower.fontSize) / 2.0);

        // 旋转一致性（完全一致为 1.0）
        double rotationSim = (upper.rotation == lower.rotation) ? 1.0 : 0.0;

        // 综合（各占 50%）
        return 0.5 * fontSizeSim + 0.5 * rotationSim;
    }
}