package com.example.docxserver.util.taggedPDF.dto;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * TextPosition列表（包含坐标信息，不区分页面）
     */
    private List<TextPosition> positions;

    /**
     * 按页分组的TextPosition映射（用于跨页内容）
     */
    private Map<PDPage, List<TextPosition>> positionsByPage;

    public TextWithPositions() {
        this.text = "";
        this.positions = new ArrayList<>();
        this.positionsByPage = new HashMap<>();
    }

    public TextWithPositions(String text, List<TextPosition> positions) {
        this.text = text;
        this.positions = positions;
        this.positionsByPage = new HashMap<>();
    }

    public TextWithPositions(String text, List<TextPosition> positions, Map<PDPage, List<TextPosition>> positionsByPage) {
        this.text = text;
        this.positions = positions;
        this.positionsByPage = positionsByPage != null ? positionsByPage : new HashMap<>();
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

    public Map<PDPage, List<TextPosition>> getPositionsByPage() {
        return positionsByPage;
    }

    public void setPositionsByPage(Map<PDPage, List<TextPosition>> positionsByPage) {
        this.positionsByPage = positionsByPage;
    }
}