package com.example.docxserver.util.taggedPDF.dto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 计数器
 * 用于在提取PDF表格和段落时进行计数
 */
public class Counter {
    private static final Logger log = LoggerFactory.getLogger(Counter.class);

    public int tableIndex = 0;      // 表格计数
    public int paragraphIndex = 0;  // 表格外段落计数
    public int rowIndex = 0;        // 当前表格的行计数（用于细粒度日志）
    public int cellIndex = 0;       // 当前表格的单元格计数

    // 用于定期打印进度的时间戳
    private long lastLogTime = System.currentTimeMillis();
    private long startTime = System.currentTimeMillis();
    private static final long LOG_INTERVAL_MS = 5000;   // 每5秒打印一次
    private static final int ROW_LOG_INTERVAL = 100;    // 每100行打印一次

    /**
     * 检查是否需要打印进度日志
     * @param currentElement 当前处理的元素类型
     */
    public void logProgressIfNeeded(String currentElement) {
        long now = System.currentTimeMillis();
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            log.info("提取进度: 表格={}, 段落={}, 当前元素={}, 耗时={}ms",
                tableIndex, paragraphIndex, currentElement, now - startTime);
            lastLogTime = now;
        }
    }

    /**
     * 强制打印进度日志（用于关键节点）
     * @param currentElement 当前处理的元素类型
     */
    public void logProgress(String currentElement) {
        long now = System.currentTimeMillis();
        log.info("提取进度: 表格={}, 段落={}, 当前元素={}, 耗时={}ms",
            tableIndex, paragraphIndex, currentElement, now - startTime);
        lastLogTime = now;
    }

    /**
     * 开始处理新表格，重置行/单元格计数
     * @param tableId 表格ID
     */
    public void startTable(String tableId) {
        rowIndex = 0;
        cellIndex = 0;
    }

    /**
     * 处理完一行，检查是否需要打印进度
     * @param tableId 当前表格ID
     */
    public void incrementRow(String tableId) {
        rowIndex++;
        long now = System.currentTimeMillis();
        // 每100行或每5秒打印一次
        if (rowIndex % ROW_LOG_INTERVAL == 0 || now - lastLogTime >= LOG_INTERVAL_MS) {
            log.info("表格 {} 处理中: 第{}行, 已处理{}个单元格, 耗时={}ms",
                tableId, rowIndex, cellIndex, now - startTime);
            lastLogTime = now;
        }
    }

    /**
     * 处理完一个单元格
     */
    public void incrementCell() {
        cellIndex++;
    }

    /**
     * 表格处理完成
     * @param tableId 表格ID
     */
    public void finishTable(String tableId) {
        long now = System.currentTimeMillis();
        log.info("表格 {} 完成: 共{}行, {}个单元格, 耗时={}ms",
            tableId, rowIndex, cellIndex, now - startTime);
        lastLogTime = now;
    }

    @Override
    public String toString() {
        return "Counter{tableIndex=" + tableIndex + ", paragraphIndex=" + paragraphIndex + "}";
    }
}