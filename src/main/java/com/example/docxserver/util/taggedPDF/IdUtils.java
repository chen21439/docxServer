package com.example.docxserver.util.taggedPDF;

import com.example.docxserver.util.taggedPDF.dto.CellLocation;

/**
 * ID相关的工具类
 * 包含单元格ID解析、格式化等功能
 */
public class IdUtils {

    /**
     * 解析单元格ID
     * 将ID字符串解析为CellLocation对象
     *
     * ID格式：t001-r007-c001-p001
     * 含义：table 001, row 007, column 001, paragraph 001
     *
     * @param cellId 单元格ID字符串
     * @return CellLocation对象，解析失败返回null
     */
    public static CellLocation parseCellId(String cellId) {
        try {
            // 示例：t001-r007-c001-p001
            String[] parts = cellId.split("-");
            if (parts.length != 4) {
                return null;
            }

            int tableIndex = Integer.parseInt(parts[0].substring(1)) - 1; // t001 -> 0
            int rowIndex = Integer.parseInt(parts[1].substring(1)) - 1;   // r007 -> 6
            int colIndex = Integer.parseInt(parts[2].substring(1)) - 1;   // c001 -> 0
            int paraIndex = Integer.parseInt(parts[3].substring(1)) - 1;  // p001 -> 0

            return new CellLocation(tableIndex, rowIndex, colIndex, paraIndex);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 格式化表格ID
     *
     * @param tableIndex 表格索引（从0开始）
     * @return 表格ID字符串（如 t001）
     */
    public static String formatTableId(int tableIndex) {
        return "t" + String.format("%03d", tableIndex + 1);
    }

    /**
     * 格式化行ID
     *
     * @param tableId 表格ID
     * @param rowIndex 行索引（从0开始）
     * @return 行ID字符串（如 t001-r007）
     */
    public static String formatRowId(String tableId, int rowIndex) {
        return tableId + "-r" + String.format("%03d", rowIndex + 1);
    }

    /**
     * 格式化单元格ID
     *
     * @param rowId 行ID
     * @param colIndex 列索引（从0开始）
     * @return 单元格ID字符串（如 t001-r007-c001-p001）
     */
    public static String formatCellId(String rowId, int colIndex) {
        return rowId + "-c" + String.format("%03d", colIndex + 1) + "-p001";
    }

    /**
     * 格式化段落ID
     *
     * @param paraIndex 段落索引（从0开始）
     * @return 段落ID字符串（如 p001）
     */
    public static String formatParagraphId(int paraIndex) {
        return "p" + String.format("%03d", paraIndex + 1);
    }
}