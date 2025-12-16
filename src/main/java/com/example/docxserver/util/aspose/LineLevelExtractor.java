package com.example.docxserver.util.aspose;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.TextPosition;

import java.util.*;

/**
 * 行级别文本提取器
 *
 * 将段落级的 TextPosition 列表按 Y 坐标切分成行级粒度
 * 每行生成独立的 bbox 和文本
 */
public class LineLevelExtractor {

    /**
     * 默认行高阈值（Y 坐标差值超过此值认为是新行）
     */
    private static final float DEFAULT_LINE_THRESHOLD = 5.0f;

    /**
     * 将 TextPosition 列表按 Y 坐标分组成行
     *
     * @param positions 所有 TextPosition
     * @return 按行分组的结果
     */
    public static List<LineInfo> splitIntoLines(List<TextPosition> positions) {
        return splitIntoLines(positions, DEFAULT_LINE_THRESHOLD);
    }

    /**
     * 将 TextPosition 列表按 Y 坐标分组成行
     *
     * @param positions     所有 TextPosition
     * @param lineThreshold Y 坐标差值阈值
     * @return 按行分组的结果
     */
    public static List<LineInfo> splitIntoLines(List<TextPosition> positions, float lineThreshold) {
        if (positions == null || positions.isEmpty()) {
            return Collections.emptyList();
        }

        // 按 Y 坐标排序（从上到下）
        List<TextPosition> sorted = new ArrayList<>(positions);
        sorted.sort((a, b) -> {
            int yCompare = Float.compare(a.getYDirAdj(), b.getYDirAdj());
            if (yCompare != 0) return yCompare;
            // Y 相同时按 X 排序
            return Float.compare(a.getXDirAdj(), b.getXDirAdj());
        });

        List<LineInfo> lines = new ArrayList<>();
        List<TextPosition> currentLine = new ArrayList<>();
        float currentLineY = sorted.get(0).getYDirAdj();

        for (TextPosition tp : sorted) {
            float y = tp.getYDirAdj();

            // Y 坐标差值超过阈值 → 新的一行
            if (!currentLine.isEmpty() && Math.abs(y - currentLineY) > lineThreshold) {
                lines.add(buildLineInfo(currentLine));
                currentLine = new ArrayList<>();
                currentLineY = y;
            }

            currentLine.add(tp);
        }

        // 保存最后一行
        if (!currentLine.isEmpty()) {
            lines.add(buildLineInfo(currentLine));
        }

        return lines;
    }

    /**
     * 从一行的 TextPosition 构建 LineInfo
     */
    private static LineInfo buildLineInfo(List<TextPosition> linePositions) {
        // 按 X 坐标排序（从左到右）
        linePositions.sort((a, b) -> Float.compare(a.getXDirAdj(), b.getXDirAdj()));

        // 拼接文本
        StringBuilder text = new StringBuilder();
        for (TextPosition tp : linePositions) {
            String unicode = tp.getUnicode();
            if (unicode != null) {
                text.append(unicode);
            }
        }

        // 计算 bbox
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;

        for (TextPosition tp : linePositions) {
            float x = tp.getXDirAdj();
            float y = Math.abs(tp.getYDirAdj());
            float w = tp.getWidth();
            float h = tp.getHeight();

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
        }

        String bbox = String.format("%.2f,%.2f,%.2f,%.2f", minX, minY, maxX, maxY);
        return new LineInfo(text.toString(), bbox);
    }

    /**
     * 行信息封装类
     */
    public static class LineInfo {
        private final String text;
        private final String bbox;

        public LineInfo(String text, String bbox) {
            this.text = text;
            this.bbox = bbox;
        }

        public String getText() {
            return text;
        }

        public String getBbox() {
            return bbox;
        }
    }
}