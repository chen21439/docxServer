package com.example.docxserver.util.untaggedPDF.pageLayout.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * PDF 表格解析全局配置类
 *
 * 设计原则：
 * 1. 硬编码默认值（开箱即用）
 * 2. 支持从 JSON 文件部分覆盖
 * 3. 容错回退（JSON 解析失败时使用默认值）
 */
public class PdfTableConfig {

    // ========== 文本采集相关 ==========

    /** 词内字符间距阈值（em 单位） */
    public double WORD_GAP_EM = 0.3;

    /** 行距阈值（em 单位） */
    public double LINE_GAP_EM = 0.5;

    /** X 坐标回退触发换行阈值（em 单位） */
    public double X_BACKTRACK_EM = 0.4;

    // ========== 线段处理相关 ==========

    /** 坐标聚合容差（pt 单位） */
    public double COORD_MERGE_EPS = 2.0;

    /** 最小线段长度（pt 单位） */
    public double MIN_LINE_LENGTH = 5.0;

    // ========== 软合并相关 ==========

    /** 合并总分阈值 */
    public double MERGE_SCORE_THRESHOLD = 0.80;

    /** 左右边界对齐容差（pt 单位） */
    public double EDGE_ALIGN_TOL_PT = 3.0;

    /** 基线间距最小值（em 单位） */
    public double BASELINE_GAP_EM_MIN = 1.0;

    /** 基线间距最大值（em 单位） */
    public double BASELINE_GAP_EM_MAX = 2.0;

    /** 下一行"空"判定比例 */
    public double ROW_EMPTY_RATIO = 0.80;

    // ========== 特征权重 ==========

    /** 特征权重映射表 */
    private Map<String, Double> weights = new HashMap<>();

    /**
     * 私有构造函数（使用工厂方法创建）
     */
    private PdfTableConfig() {
        initDefaultWeights();
    }

    /**
     * 初始化默认权重
     */
    private void initDefaultWeights() {
        weights.put("no_hline_between", 1.00);
        weights.put("edge_align_sim", 0.80);
        weights.put("baseline_gap_em", 0.60);
        weights.put("row_rplus1_empty_ratio", 0.50);
        weights.put("style_match", 0.30);
    }

    /**
     * 获取特征权重
     */
    public double getWeight(String featureName) {
        return weights.getOrDefault(featureName, 0.0);
    }

    /**
     * 获取所有权重
     */
    public Map<String, Double> getWeights() {
        return new HashMap<>(weights);
    }

    /**
     * 加载默认配置
     */
    public static PdfTableConfig loadDefault() {
        return new PdfTableConfig();
    }

    /**
     * 从 JSON 文件加载配置（部分覆盖）
     *
     * @param jsonPath JSON 配置文件路径
     * @return 配置对象（失败时返回默认配置）
     */
    public static PdfTableConfig loadFromJson(String jsonPath) {
        PdfTableConfig config = new PdfTableConfig();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(new File(jsonPath));

            // 覆盖阈值参数
            if (json.has("WORD_GAP_EM")) {
                config.WORD_GAP_EM = json.get("WORD_GAP_EM").asDouble();
            }
            if (json.has("LINE_GAP_EM")) {
                config.LINE_GAP_EM = json.get("LINE_GAP_EM").asDouble();
            }
            if (json.has("X_BACKTRACK_EM")) {
                config.X_BACKTRACK_EM = json.get("X_BACKTRACK_EM").asDouble();
            }
            if (json.has("COORD_MERGE_EPS")) {
                config.COORD_MERGE_EPS = json.get("COORD_MERGE_EPS").asDouble();
            }
            if (json.has("MIN_LINE_LENGTH")) {
                config.MIN_LINE_LENGTH = json.get("MIN_LINE_LENGTH").asDouble();
            }
            if (json.has("MERGE_SCORE_THRESHOLD")) {
                config.MERGE_SCORE_THRESHOLD = json.get("MERGE_SCORE_THRESHOLD").asDouble();
            }
            if (json.has("EDGE_ALIGN_TOL_PT")) {
                config.EDGE_ALIGN_TOL_PT = json.get("EDGE_ALIGN_TOL_PT").asDouble();
            }
            if (json.has("BASELINE_GAP_EM_MIN")) {
                config.BASELINE_GAP_EM_MIN = json.get("BASELINE_GAP_EM_MIN").asDouble();
            }
            if (json.has("BASELINE_GAP_EM_MAX")) {
                config.BASELINE_GAP_EM_MAX = json.get("BASELINE_GAP_EM_MAX").asDouble();
            }
            if (json.has("ROW_EMPTY_RATIO")) {
                config.ROW_EMPTY_RATIO = json.get("ROW_EMPTY_RATIO").asDouble();
            }

            // 覆盖特征权重
            if (json.has("weights")) {
                JsonNode weightsJson = json.get("weights");
                Iterator<String> fieldNames = weightsJson.fieldNames();
                while (fieldNames.hasNext()) {
                    String key = fieldNames.next();
                    config.weights.put(key, weightsJson.get(key).asDouble());
                }
            }

            System.out.println("[PdfTableConfig] Loaded config from: " + jsonPath);

        } catch (Exception e) {
            System.err.println("[PdfTableConfig] Failed to load JSON, using default config: " + e.getMessage());
        }

        return config;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("PdfTableConfig{\n");
        sb.append("  WORD_GAP_EM=").append(WORD_GAP_EM).append(",\n");
        sb.append("  LINE_GAP_EM=").append(LINE_GAP_EM).append(",\n");
        sb.append("  X_BACKTRACK_EM=").append(X_BACKTRACK_EM).append(",\n");
        sb.append("  MERGE_SCORE_THRESHOLD=").append(MERGE_SCORE_THRESHOLD).append(",\n");
        sb.append("  weights=").append(weights).append("\n");
        sb.append("}");
        return sb.toString();
    }
}