package com.example.docxserver.util.taggedPDF.dto;

/**
 * MCID和页码信息
 * 用于存储格式化后的MCID和页码字符串
 *
 * 格式说明：
 * - mcidStr: "1,2,3|4,5,6"（按页分组，用|分隔不同页）
 * - pageStr: "1|2"（对应的页码，用|分隔）
 */
public class McidPageInfo {
    public String mcidStr;  // MCID字符串
    public String pageStr;  // 页码字符串

    public McidPageInfo(String mcidStr, String pageStr) {
        this.mcidStr = mcidStr;
        this.pageStr = pageStr;
    }

    @Override
    public String toString() {
        return "McidPageInfo{mcidStr='" + mcidStr + "', pageStr='" + pageStr + "'}";
    }
}