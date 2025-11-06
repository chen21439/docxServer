package com.example.docxserver.util.taggedPDF.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 带边界框的段落数据结构
 * 用于输出给外部系统（如模型训练）
 */
public class ParagraphWithBox {
    /**
     * 段落文本内容
     */
    private String text;

    /**
     * 边界框坐标 [x0, y0, x1, y1]
     * 坐标系：图像坐标系（左上角为原点，y向下增）
     * 浮点数，完整坐标
     */
    private double[] box;

    /**
     * 关联关系（预留字段，当前为空数组）
     */
    private List<List<Integer>> linking;

    /**
     * 标签类型（预留字段，当前为空字符串）
     */
    private String label;

    /**
     * 单词级别信息（预留字段，当前为空数组）
     */
    private List<Object> words;

    public ParagraphWithBox() {
        this.linking = new ArrayList<>();
        this.label = "";
        this.words = new ArrayList<>();
    }

    public ParagraphWithBox(String text, double[] box) {
        this();
        this.text = text;
        this.box = box;
    }

    // Getters and Setters
    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public double[] getBox() {
        return box;
    }

    public void setBox(double[] box) {
        this.box = box;
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

    public List<Object> getWords() {
        return words;
    }

    public void setWords(List<Object> words) {
        this.words = words;
    }
}