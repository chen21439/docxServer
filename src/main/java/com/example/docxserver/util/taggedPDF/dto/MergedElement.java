package com.example.docxserver.util.taggedPDF.dto;

/**
 * 聚合元素（用于合并表格和段落，按page和bbox排序）
 */
public class MergedElement implements Comparable<MergedElement> {

    /**
     * 元素类型：TABLE 或 PARAGRAPH
     */
    public enum ElementType {
        TABLE,
        PARAGRAPH
    }

    private ElementType type;
    private String content;      // XML内容
    private String pageStr;      // 页码字符串（如 "5" 或 "16|17"）
    private String bboxStr;      // bbox字符串（如 "89.85,77.43,494.94,769.84" 或带|的跨页格式）
    private int firstPage;       // 第一页的页码（用于排序）
    private double firstBboxY;   // 第一页bbox的Y坐标（用于排序，取minY）

    public MergedElement(ElementType type, String content, String pageStr, String bboxStr) {
        this.type = type;
        this.content = content;
        this.pageStr = pageStr;
        this.bboxStr = bboxStr;

        // 解析第一页页码
        this.firstPage = parseFirstPage(pageStr);

        // 解析第一页bbox的Y坐标（取第一个bbox的minY）
        this.firstBboxY = parseFirstBboxY(bboxStr);
    }

    /**
     * 解析第一页页码
     */
    private int parseFirstPage(String pageStr) {
        if (pageStr == null || pageStr.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            // 取第一个页码（可能是 "5" 或 "16|17" 格式）
            String firstPart = pageStr.split("\\|")[0].trim();
            // 也可能是逗号分隔的格式
            firstPart = firstPart.split(",")[0].trim();
            return Integer.parseInt(firstPart);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 解析第一页bbox的Y坐标（minY，用于从上到下排序）
     */
    private double parseFirstBboxY(String bboxStr) {
        if (bboxStr == null || bboxStr.isEmpty()) {
            return Double.MAX_VALUE;
        }
        try {
            // 取第一个bbox（可能是单个或用|分隔的跨页格式）
            String firstBbox = bboxStr.split("\\|")[0].trim();
            // bbox格式: x0,y0,x1,y1
            String[] parts = firstBbox.split(",");
            if (parts.length >= 2) {
                return Double.parseDouble(parts[1].trim()); // y0 (minY)
            }
        } catch (NumberFormatException e) {
            // 忽略解析错误
        }
        return Double.MAX_VALUE;
    }

    /**
     * 比较方法：先按页码排序，再按Y坐标排序（从上到下）
     */
    @Override
    public int compareTo(MergedElement other) {
        // 1. 先按第一页页码排序
        int pageCompare = Integer.compare(this.firstPage, other.firstPage);
        if (pageCompare != 0) {
            return pageCompare;
        }

        // 2. 同一页内按Y坐标排序（minY越小越靠上）
        return Double.compare(this.firstBboxY, other.firstBboxY);
    }

    // Getters
    public ElementType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public String getPageStr() {
        return pageStr;
    }

    public String getBboxStr() {
        return bboxStr;
    }

    public int getFirstPage() {
        return firstPage;
    }

    public double getFirstBboxY() {
        return firstBboxY;
    }
}