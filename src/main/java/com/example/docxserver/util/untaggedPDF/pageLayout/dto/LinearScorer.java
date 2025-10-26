package com.example.docxserver.util.untaggedPDF.pageLayout.dto;

import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Cell;
import com.example.docxserver.util.untaggedPDF.pageLayout.dto.features.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 线性打分器
 *
 * 使用加权线性组合计算合并得分：
 * score = Σ(weight_i * feature_i)
 */
public class LinearScorer {

    private final PdfTableConfig config;
    private final List<Feature> features;

    public LinearScorer(PdfTableConfig config) {
        this.config = config;
        this.features = new ArrayList<>();

        // 注册所有特征
        features.add(new NoHLineBetween());
        features.add(new EdgeAlignSim());
        features.add(new BaselineGapKernel());
        features.add(new RowRplus1Empty());
        features.add(new StyleMatch());
    }

    /**
     * 计算合并得分
     *
     * @param upper 上方单元格
     * @param lower 下方单元格
     * @param context 页面上下文
     * @return 得分（范围通常在 [0, 5] 左右，取决于权重总和）
     */
    public double score(Cell upper, Cell lower, PageContext context) {
        double totalScore = 0.0;

        for (Feature feature : features) {
            double featureValue = feature.value(upper, lower, context);
            double weight = config.getWeight(feature.name());
            totalScore += weight * featureValue;
        }

        return totalScore;
    }

    /**
     * 获取所有特征
     */
    public List<Feature> getFeatures() {
        return features;
    }
}