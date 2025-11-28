package com.example.docxserver.util.taggedPDF.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * COCO 格式数据集
 */
public class CocoDataset {
    private List<CocoImage> images;
    private List<CocoAnnotation> annotations;
    private List<CocoCategory> categories;

    public CocoDataset() {
        this.images = new ArrayList<>();
        this.annotations = new ArrayList<>();
        this.categories = new ArrayList<>();
    }

    public List<CocoImage> getImages() {
        return images;
    }

    public void setImages(List<CocoImage> images) {
        this.images = images;
    }

    public List<CocoAnnotation> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<CocoAnnotation> annotations) {
        this.annotations = annotations;
    }

    public List<CocoCategory> getCategories() {
        return categories;
    }

    public void setCategories(List<CocoCategory> categories) {
        this.categories = categories;
    }
}