package com.example.docxserver.util.taggedPDF.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * COCO 格式标注信息
 */
public class CocoAnnotation {
    private int id;
    private int image_id;
    private int category_id;
    private double[] bbox; // [x, y, width, height] 像素坐标
    private String text;   // 额外字段：段落文本内容
    private List<List<Integer>> linking; // 预留字段
    private String label;  // 预留字段

    // === 调试字段（用于追踪坐标转换） ===
    private double[] bbox_pdf_raw;      // PDF原始坐标 [x0, y0, x1, y1]（从TextPosition获取）
    private double[] bbox_pdf_user;     // PDF用户空间坐标 [x0, y0, x1, y1]（左下角为原点）
    private double yDirAdj_raw;         // 原始YDirAdj值（可能为负数）
    private double yDirAdj_abs;         // 取绝对值后的YDirAdj
    private double yBase;               // 基线位置（从底部算起）
    private double yTop_pdf;            // PDF顶部坐标（用户空间）
    private double yBottom_pdf;         // PDF底部坐标（用户空间）
    private double pdfHeight;           // 页面高度
    private double scaleX;              // X方向缩放比例
    private double scaleY;              // Y方向缩放比例
    private List<Integer> bbox_norm;    // 归一化坐标 [x0,y0,x1,y1]，范围0~1000，左上角为原点
    private List<String> words;         // 逐字形的文本列表（不包含零宽字符）
    private List<List<Integer>> bbox_glyph_norm; // 逐字形的归一化坐标 [[x0,y0,x1,y1], ...]，LayoutLMv3格式（不包含零宽字符）

    public CocoAnnotation() {
        this.linking = new ArrayList<>();
        this.label = "";
    }

    public CocoAnnotation(int id, int imageId, int categoryId, double[] bbox, String text) {
        this();
        this.id = id;
        this.image_id = imageId;
        this.category_id = categoryId;
        this.bbox = bbox;
        this.text = text;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getImage_id() {
        return image_id;
    }

    public void setImage_id(int image_id) {
        this.image_id = image_id;
    }

    public int getCategory_id() {
        return category_id;
    }

    public void setCategory_id(int category_id) {
        this.category_id = category_id;
    }

    public double[] getBbox() {
        return bbox;
    }

    public void setBbox(double[] bbox) {
        this.bbox = bbox;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<List<Integer>> getLinking() {
        return linking;
    }

    public void setLinking(List<List<Integer>> linking) {
        this.linking = linking;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    // === 调试字段的 Getter/Setter ===

    public double[] getBbox_pdf_raw() {
        return bbox_pdf_raw;
    }

    public void setBbox_pdf_raw(double[] bbox_pdf_raw) {
        this.bbox_pdf_raw = bbox_pdf_raw;
    }

    public double[] getBbox_pdf_user() {
        return bbox_pdf_user;
    }

    public void setBbox_pdf_user(double[] bbox_pdf_user) {
        this.bbox_pdf_user = bbox_pdf_user;
    }

    public double getyDirAdj_raw() {
        return yDirAdj_raw;
    }

    public void setyDirAdj_raw(double yDirAdj_raw) {
        this.yDirAdj_raw = yDirAdj_raw;
    }

    public double getyDirAdj_abs() {
        return yDirAdj_abs;
    }

    public void setyDirAdj_abs(double yDirAdj_abs) {
        this.yDirAdj_abs = yDirAdj_abs;
    }

    public double getyBase() {
        return yBase;
    }

    public void setyBase(double yBase) {
        this.yBase = yBase;
    }

    public double getyTop_pdf() {
        return yTop_pdf;
    }

    public void setyTop_pdf(double yTop_pdf) {
        this.yTop_pdf = yTop_pdf;
    }

    public double getyBottom_pdf() {
        return yBottom_pdf;
    }

    public void setyBottom_pdf(double yBottom_pdf) {
        this.yBottom_pdf = yBottom_pdf;
    }

    public double getPdfHeight() {
        return pdfHeight;
    }

    public void setPdfHeight(double pdfHeight) {
        this.pdfHeight = pdfHeight;
    }

    public double getScaleX() {
        return scaleX;
    }

    public void setScaleX(double scaleX) {
        this.scaleX = scaleX;
    }

    public double getScaleY() {
        return scaleY;
    }

    public void setScaleY(double scaleY) {
        this.scaleY = scaleY;
    }

    public List<Integer> getBbox_norm() {
        return bbox_norm;
    }

    public void setBbox_norm(List<Integer> bbox_norm) {
        this.bbox_norm = bbox_norm;
    }

    public List<String> getWords() {
        return words;
    }

    public void setWords(List<String> words) {
        this.words = words;
    }

    public List<List<Integer>> getBbox_glyph_norm() {
        return bbox_glyph_norm;
    }

    public void setBbox_glyph_norm(List<List<Integer>> bbox_glyph_norm) {
        this.bbox_glyph_norm = bbox_glyph_norm;
    }
}