package com.example.docxserver.util.untaggedPDF.pageLayout.dto;

import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Cell;

/**
 * 表格合并特征接口
 *
 * 每个特征计算上下相邻单元格之间的相似度/兼容性，返回值范围 [0, 1]
 * - 0: 完全不匹配（不应合并）
 * - 1: 完全匹配（强烈建议合并）
 */
public interface Feature {

    /**
     * 特征名称（用于权重配置和日志）
     */
    String name();

    /**
     * 计算特征值
     *
     * @param upper 上方单元格
     * @param lower 下方单元格（必须是 upper 的下一行同一列）
     * @param context 页面上下文（提供全局信息，如表格线、其他单元格等）
     * @return 特征值 [0, 1]
     */
    double value(Cell upper, Cell lower, PageContext context);
}