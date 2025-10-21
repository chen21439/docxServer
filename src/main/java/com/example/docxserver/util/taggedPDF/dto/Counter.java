package com.example.docxserver.util.taggedPDF.dto;

/**
 * 计数器
 * 用于在提取PDF表格和段落时进行计数
 */
public class Counter {
    public int tableIndex = 0;      // 表格计数
    public int paragraphIndex = 0;  // 表格外段落计数

    @Override
    public String toString() {
        return "Counter{tableIndex=" + tableIndex + ", paragraphIndex=" + paragraphIndex + "}";
    }
}