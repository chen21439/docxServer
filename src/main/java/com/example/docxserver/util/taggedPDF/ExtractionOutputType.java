package com.example.docxserver.util.taggedPDF;

/**
 * PDF 提取输出类型
 */
public enum ExtractionOutputType {
    /**
     * TXT 输出（表格结构 + 段落结构 + 聚合文件）
     */
    TXT("txt"),

    /**
     * AI 推理用 JSON 输出（行级别 artifact）
     */
    INFER("infer");

    private final String value;

    ExtractionOutputType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 从字符串解析
     */
    public static ExtractionOutputType fromString(String str) {
        for (ExtractionOutputType type : values()) {
            if (type.value.equalsIgnoreCase(str)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的输出类型: " + str);
    }
}
