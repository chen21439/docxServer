package com.example.docxserver.util.taggedPDF.dto;

import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本及其位置信息的封装类
 * 用于同时返回提取的文本和TextPosition列表
 */
public class TextWithPositions {
    /**
     * 提取的文本内容
     */
    private String text;

    /**
     * TextPosition列表（包含坐标信息）
     */
    private List<TextPosition> positions;

    public TextWithPositions() {
        this.text = "";
        this.positions = new ArrayList<>();
    }

    public TextWithPositions(String text, List<TextPosition> positions) {
        this.text = text;
        this.positions = positions;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<TextPosition> getPositions() {
        return positions;
    }

    public void setPositions(List<TextPosition> positions) {
        this.positions = positions;
    }
}