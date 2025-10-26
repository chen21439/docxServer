package com.example.docxserver.util.untaggedPDF.pageLayout;

/**
 * 表格提取配置类（简化版）
 *
 * 设计原则：
 * 1. 竖优先切列（硬约束）- 从垂直线确定列边界，后续不得跨列
 * 2. 文本主导行聚类 - 每列内按基线聚类，避免右列长段落切碎整表
 * 3. 横线仅做证据/补强 - 不是行边界的主要依据
 * 4. 软合并仅同列同行 - 禁止跨列/跨行合并
 */
public class TableExtractionConfig {

    // ========== 核心阈值（静态常量） ==========

    /** X/Y 坐标量化精度（pt） */
    public static final double EPS_X = 1.0;
    public static final double EPS_Y = 1.0;

    /** 线段缝合/文本拼接的间隙容忍（pt） */
    public static final double GAP_TOL = 1.5;

    /** 水平/竖直判定角度（度） */
    public static final double ANGLE_TOL_DEG = 1.5;

    /** 候选线段最短长度（pt） */
    public static final double MIN_LEN_PT = 10.0;

    /** 过细线阈值（判装饰，pt） */
    public static final double HAIRLINE_PT = 0.35;

    /** 横线与文本 Y 重叠比例上限 */
    public static final double TEXT_OVERLAP_MAX = 0.30;

    /** 横线端点吸附到列断点的容忍（pt） */
    public static final double END_SNAP_SLACK = 1.5;

    /** 行边界支持度阈值（至少两列同意） */
    public static final int ROW_SUPPORT_TH = 2;

    /** 同列内文本行距阈值（em单位，用于行聚类） */
    public static final double INTRA_COL_LINE_GAP_EM = 1.5;

    /** 软合并垂直间隙容忍度（em单位） */
    public static final double SOFT_MERGE_GAP_EM = 0.8;

    // ========== 私有构造（防止实例化） ==========
    private TableExtractionConfig() {
    }
}