package com.example.docxserver.util.pdf.highter;

import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF高亮坐标计算共用工具类
 *
 * 提供统一的坐标计算方法，确保所有高亮功能使用相同的坐标系统和padding策略
 *
 * 核心原理：
 * 1. 完全使用 DirAdj 坐标系（包含文本矩阵缩放）
 * 2. Y坐标计算：yTop = YDirAdj - HeightDir，yBottom = YDirAdj
 * 3. 动态padding：基于中位数高度，顶部6%，底部8%，横向4%
 * 4. 坐标转换：DirAdj（Y向下增）→ PDF用户空间（Y向上增）
 */
public class HighlighterSupport {

    /**
     * 四边形坐标（QuadPoints中的一个四边形）
     */
    public static class Quad {
        public float left;
        public float right;
        public float top;      // PDF用户空间：top > bottom
        public float bottom;

        public Quad(float left, float right, float top, float bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }

        /**
         * 转换为QuadPoints数组格式 [TL, TR, BL, BR]
         */
        public float[] toQuadPoints() {
            return new float[]{
                left, top,      // Top-Left
                right, top,     // Top-Right
                left, bottom,   // Bottom-Left
                right, bottom   // Bottom-Right
            };
        }
    }

    /**
     * 为一组TextPosition计算四边形坐标（带padding）
     *
     * 这是行业最佳实践的实现：
     * - 使用HeightDir而非fontDescriptor（包含缩放）
     * - 基于中位数高度动态padding
     * - 完整的坐标系转换
     *
     * @param positions TextPosition列表（通常是同一行的字符）
     * @param pageHeight 页面高度（用于Y轴转换）
     * @return Quad对象，包含计算好的四边形坐标
     */
    public static Quad calculateQuadWithPadding(List<TextPosition> positions, float pageHeight) {
        if (positions == null || positions.isEmpty()) {
            return new Quad(0, 0, 0, 0);
        }

        float leftDir = Float.MAX_VALUE;
        float rightDir = -Float.MAX_VALUE;
        float topDir = Float.MAX_VALUE;
        float botDir = -Float.MAX_VALUE;

        // 收集高度用于计算中位数
        List<Float> heights = new ArrayList<>();

        // 第一步：在DirAdj坐标系中计算边界（Y向下增）
        for (TextPosition tp : positions) {
            // X坐标（横向）
            float xL_dir = tp.getXDirAdj();
            float xR_dir = tp.getXDirAdj() + tp.getWidthDirAdj();

            // Y坐标（纵向，DirAdj系中Y向下增）
            // 关键：使用 getHeightDir() 而非 getHeightDirAdj()
            float yBottom_dir = tp.getYDirAdj();                      // 基线/底部
            float yTop_dir = tp.getYDirAdj() - tp.getHeightDir();     // 顶部 = 基线 - 高度

            heights.add(tp.getHeightDir());

            leftDir = Math.min(leftDir, xL_dir);
            rightDir = Math.max(rightDir, xR_dir);
            topDir = Math.min(topDir, yTop_dir);      // 顶部：取更小的Y
            botDir = Math.max(botDir, yBottom_dir);   // 底部：取更大的Y
        }

        // 第二步：计算中位数高度
        heights.sort(Float::compareTo);
        float hMed = heights.get(heights.size() / 2);

        // 第三步：动态padding（行业最佳实践）
        float padTopDir = Math.max(0.6f, 0.06f * hMed);      // 顶部6%，最小0.6pt
        float padBottomDir = Math.max(0.6f, 0.08f * hMed);   // 底部8%，最小0.6pt
        float padX = Math.max(0.4f, 0.04f * (rightDir - leftDir));  // 横向4%，最小0.4pt

        // 应用padding（DirAdj系：向上减小，向下增大）
        topDir -= padTopDir;
        botDir += padBottomDir;
        leftDir -= padX;
        rightDir += padX;

        // 第四步：转换到PDF用户空间（Y轴翻转）
        float top = pageHeight - topDir;       // 顶部：pageHeight - 小Y
        float bottom = pageHeight - botDir;    // 底部：pageHeight - 大Y

        // 自检：用户空间中 top 必须 > bottom
        if (top <= bottom) {
            System.err.printf("[WARN] HighlighterSupport: top<=bottom异常 (top=%.2f, bottom=%.2f, pageH=%.2f)%n",
                top, bottom, pageHeight);
            // 交换避免负高
            float temp = top;
            top = bottom;
            bottom = temp;
        }

        return new Quad(leftDir, rightDir, top, bottom);
    }

    /**
     * 为一组TextPosition计算四边形坐标（不带padding，仅用于调试）
     *
     * @param positions TextPosition列表
     * @param pageHeight 页面高度
     * @return Quad对象
     */
    public static Quad calculateQuadNoPadding(List<TextPosition> positions, float pageHeight) {
        if (positions == null || positions.isEmpty()) {
            return new Quad(0, 0, 0, 0);
        }

        float leftDir = Float.MAX_VALUE;
        float rightDir = -Float.MAX_VALUE;
        float topDir = Float.MAX_VALUE;
        float botDir = -Float.MAX_VALUE;

        for (TextPosition tp : positions) {
            float xL_dir = tp.getXDirAdj();
            float xR_dir = tp.getXDirAdj() + tp.getWidthDirAdj();
            float yBottom_dir = tp.getYDirAdj();
            float yTop_dir = tp.getYDirAdj() - tp.getHeightDir();

            leftDir = Math.min(leftDir, xL_dir);
            rightDir = Math.max(rightDir, xR_dir);
            topDir = Math.min(topDir, yTop_dir);
            botDir = Math.max(botDir, yBottom_dir);
        }

        // 转换到PDF用户空间
        float top = pageHeight - topDir;
        float bottom = pageHeight - botDir;

        if (top <= bottom) {
            float temp = top;
            top = bottom;
            bottom = temp;
        }

        return new Quad(leftDir, rightDir, top, bottom);
    }

    /**
     * 将多个Quad合并为QuadPoints数组
     *
     * @param quads Quad列表
     * @return QuadPoints数组（每8个float为一个四边形）
     */
    public static float[] quadsToArray(List<Quad> quads) {
        float[] result = new float[quads.size() * 8];
        int offset = 0;
        for (Quad quad : quads) {
            float[] qp = quad.toQuadPoints();
            System.arraycopy(qp, 0, result, offset, 8);
            offset += 8;
        }
        return result;
    }

    /**
     * 按行分组TextPosition
     *
     * @param positions TextPosition列表
     * @param lineThreshold Y坐标差异阈值（默认2.0f）
     * @return 分组后的行列表
     */
    public static List<List<TextPosition>> groupByLine(List<TextPosition> positions, float lineThreshold) {
        List<List<TextPosition>> lines = new ArrayList<>();
        if (positions == null || positions.isEmpty()) {
            return lines;
        }

        List<TextPosition> currentLine = new ArrayList<>();
        currentLine.add(positions.get(0));
        float lastY = positions.get(0).getYDirAdj();

        for (int i = 1; i < positions.size(); i++) {
            TextPosition tp = positions.get(i);
            float y = tp.getYDirAdj();

            // 判断是否同一行
            if (Math.abs(y - lastY) <= lineThreshold) {
                currentLine.add(tp);
            } else {
                // 新行
                lines.add(currentLine);
                currentLine = new ArrayList<>();
                currentLine.add(tp);
                lastY = y;
            }
        }

        if (!currentLine.isEmpty()) {
            lines.add(currentLine);
        }

        return lines;
    }

    /**
     * 按行分组（使用默认阈值2.0f）
     */
    public static List<List<TextPosition>> groupByLine(List<TextPosition> positions) {
        return groupByLine(positions, 2.0f);
    }
}