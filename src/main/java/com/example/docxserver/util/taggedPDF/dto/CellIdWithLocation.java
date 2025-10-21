package com.example.docxserver.util.taggedPDF.dto;

/**
 * 带位置信息的单元格ID
 * 用于排序和批量查找优化
 */
public class CellIdWithLocation {
    public String cellId;
    public CellLocation location;

    public CellIdWithLocation(String cellId, CellLocation location) {
        this.cellId = cellId;
        this.location = location;
    }

    @Override
    public String toString() {
        return "CellIdWithLocation{cellId='" + cellId + "', location=" + location + "}";
    }
}