package com.example.docxserver.util.taggedPDF.dto;

/**
 * COCO 格式类别信息
 */
public class CocoCategory {
    private int id;
    private String name;

    public CocoCategory() {
    }

    public CocoCategory(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}