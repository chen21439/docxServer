package com.example.docxserver.util.pdf.highter;

import java.util.ArrayList;
import java.util.List;

/**
 * 高亮目标DTO
 *
 * 用于批量高亮时传递目标位置信息
 * - 每个DTO代表一个高亮区域（一个段落/单元格）
 * - 一个区域可能包含多个MCID（跨页或多个标记）
 */
public class HighlightTarget {
    /**
     * 页码（0-based，如0表示第1页）
     */
    private int page;

    /**
     * MCID列表（该区域包含的所有MCID）
     * 例如：["4", "5", "6"] 表示MCID 4、5、6
     */
    private List<String> mcids;

    /**
     * 可选：关联的ID（如 t001-r007-c001-p001）
     * 用于调试和日志输出
     */
    private String id;

    /**
     * 可选：段落的完整文本（用于定位）
     * 在 MCID 区域内查找此完整文本，以确定段落位置
     */
    private String pidText;

    /**
     * 可选：要高亮的文本内容（pidText 的子串）
     * 当此字段不为空时，会在指定的 page + mcids 范围内查找此文本，并做字符级别的高亮
     * 如果为空或null，则直接高亮整个MCID区域
     */
    private String text;

    /**
     * 可选：原始page字符串（用于跨页，如 "83|84"）
     */
    private String pageStr;

    /**
     * 可选：原始mcid字符串（用于跨页，如 "146,147,148|0,1,2,3"）
     */
    private String mcidStr;

    /**
     * 默认构造函数
     */
    public HighlightTarget() {
        this.mcids = new ArrayList<>();
    }

    /**
     * 完整构造函数
     *
     * @param page 页码（0-based）
     * @param mcids MCID字符串列表
     */
    public HighlightTarget(int page, List<String> mcids) {
        this.page = page;
        this.mcids = mcids != null ? mcids : new ArrayList<>();
    }

    /**
     * 完整构造函数（带ID）
     *
     * @param page 页码（0-based）
     * @param mcids MCID字符串列表
     * @param id 关联的ID
     */
    public HighlightTarget(int page, List<String> mcids, String id) {
        this.page = page;
        this.mcids = mcids != null ? mcids : new ArrayList<>();
        this.id = id;
    }

    /**
     * 完整构造函数（带ID和文本）
     *
     * @param page 页码（0-based）
     * @param mcids MCID字符串列表
     * @param id 关联的ID
     * @param text 要高亮的文本内容
     */
    public HighlightTarget(int page, List<String> mcids, String id, String text) {
        this.page = page;
        this.mcids = mcids != null ? mcids : new ArrayList<>();
        this.id = id;
        this.text = text;
    }

    /**
     * 完整构造函数（带ID、pidText 和 text）
     *
     * @param page 页码（0-based）
     * @param mcids MCID字符串列表
     * @param id 关联的ID
     * @param pidText 段落完整文本（用于定位）
     * @param text 要高亮的文本内容（pidText的子串）
     */
    public HighlightTarget(int page, List<String> mcids, String id, String pidText, String text) {
        this.page = page;
        this.mcids = mcids != null ? mcids : new ArrayList<>();
        this.id = id;
        this.pidText = pidText;
        this.text = text;
    }

    /**
     * 从页码字符串和MCID字符串创建（用于与PdfTextFinder.FindResult集成）
     *
     * @param pageStr 页码字符串（如 "1" 表示第1页，会转换为0-based）
     * @param mcidStr MCID字符串（逗号分隔，如 "4,5,6"）
     * @param id 关联的ID
     * @return HighlightTarget对象
     */
    public static HighlightTarget fromStrings(String pageStr, String mcidStr, String id) {
        int page = 0;
        if (pageStr != null && !pageStr.isEmpty()) {
            try {
                // 将1-based页码转换为0-based
                page = Integer.parseInt(pageStr.trim()) - 1;
            } catch (NumberFormatException e) {
                System.err.println("[警告] 无法解析页码: " + pageStr + "，使用默认值0");
            }
        }

        List<String> mcids = new ArrayList<>();
        if (mcidStr != null && !mcidStr.isEmpty()) {
            String[] parts = mcidStr.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    mcids.add(trimmed);
                }
            }
        }

        return new HighlightTarget(page, mcids, id);
    }

    /**
     * 将MCID字符串列表转换为整数集合
     *
     * @return 整数集合（用于HighlightByMCID.highlightByMcid）
     */
    public java.util.Set<Integer> getMcidIntegers() {
        java.util.Set<Integer> result = new java.util.LinkedHashSet<>();
        for (String mcidStr : mcids) {
            try {
                result.add(Integer.parseInt(mcidStr.trim()));
            } catch (NumberFormatException e) {
                System.err.println("[警告] 无法解析MCID: " + mcidStr);
            }
        }
        return result;
    }

    /**
     * 是否有效（包含有效的MCID）
     */
    public boolean isValid() {
        return mcids != null && !mcids.isEmpty();
    }

    // Getters and Setters

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public List<String> getMcids() {
        return mcids;
    }

    public void setMcids(List<String> mcids) {
        this.mcids = mcids;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPidText() {
        return pidText;
    }

    public void setPidText(String pidText) {
        this.pidText = pidText;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    /**
     * 是否包含pidText（用于判断是否有完整段落文本）
     */
    public boolean hasPidText() {
        return pidText != null && !pidText.trim().isEmpty();
    }

    /**
     * 是否包含text（用于判断是否使用字符级别高亮）
     */
    public boolean hasText() {
        return text != null && !text.trim().isEmpty();
    }

    /**
     * 是否为跨页（检查pageStr或mcidStr是否包含"|"）
     */
    public boolean isAcrossPages() {
        return (pageStr != null && pageStr.contains("|")) ||
               (mcidStr != null && mcidStr.contains("|"));
    }

    public String getPageStr() {
        return pageStr;
    }

    public void setPageStr(String pageStr) {
        this.pageStr = pageStr;
    }

    public String getMcidStr() {
        return mcidStr;
    }

    public void setMcidStr(String mcidStr) {
        this.mcidStr = mcidStr;
    }

    @Override
    public String toString() {
        return "HighlightTarget{" +
                "page=" + (page + 1) +  // 显示1-based页码
                ", mcids=" + mcids +
                ", id='" + id + '\'' +
                (hasText() ? ", text='" + text + '\'' : "") +
                (isAcrossPages() ? " [跨页]" : "") +
                '}';
    }
}