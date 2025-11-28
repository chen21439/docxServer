package com.example.docxserver.util.taggedPDF.dto;

/**
 * COCO 格式图片信息
 */
public class CocoImage {
    private int id;
    private String file_name;
    private int width;
    private int height;

    public CocoImage() {
    }

    public CocoImage(int id, String fileName, int width, int height) {
        this.id = id;
        this.file_name = fileName;
        this.width = width;
        this.height = height;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getFile_name() {
        return file_name;
    }

    public void setFile_name(String file_name) {
        this.file_name = file_name;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }
}