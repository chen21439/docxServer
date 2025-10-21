package com.example.docxserver.util.taggedPDF.dto;

import java.util.List;

/**
 * 表格分组
 * 用于识别PDF段落中的表格区域
 */
public class TableGroup {
    public List<Integer> indices;  // 段落索引列表

    public TableGroup(List<Integer> indices) {
        this.indices = indices;
    }

    @Override
    public String toString() {
        return "TableGroup{indices=" + indices + "}";
    }
}