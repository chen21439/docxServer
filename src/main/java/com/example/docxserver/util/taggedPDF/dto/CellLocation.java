package com.example.docxserver.util.taggedPDF.dto;

/**
 * 单元格位置信息
 * 用于解析和存储单元格ID（如 t001-r007-c001-p001）中的位置信息
 */
public class CellLocation {
    public int tableIndex;  // 表格索引（从0开始）
    public int rowIndex;    // 行索引（从0开始）
    public int colIndex;    // 列索引（从0开始）
    public int paraIndex;   // 段落索引（从0开始）

    public CellLocation(int tableIndex, int rowIndex, int colIndex, int paraIndex) {
        this.tableIndex = tableIndex;
        this.rowIndex = rowIndex;
        this.colIndex = colIndex;
        this.paraIndex = paraIndex;
    }

    @Override
    public String toString() {
        return String.format("CellLocation{table=%d, row=%d, col=%d, para=%d}",
                tableIndex, rowIndex, colIndex, paraIndex);
    }
}