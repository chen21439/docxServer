package com.example.docxserver.util.taggedPDF.dto;

/**
 * DOCX段落数据模型
 */
public class DocxParagraph {
    public String id;
    public String text;
    public ParagraphType type;

    public DocxParagraph(String id, String text, ParagraphType type) {
        this.id = id;
        this.text = text;
        this.type = type;
    }

    @Override
    public String toString() {
        return "DocxParagraph{id='" + id + "', text='" + text + "', type=" + type + "}";
    }

    public boolean isTableCell() {
        return type == ParagraphType.TABLE_CELL;
    }

    public boolean isNormalParagraph() {
        return type == ParagraphType.NORMAL;
    }
}