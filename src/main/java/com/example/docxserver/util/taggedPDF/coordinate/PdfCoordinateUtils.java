package com.example.docxserver.util.taggedPDF.coordinate;

import org.apache.pdfbox.text.TextPosition;

import java.util.List;

/**
 * PDF坐标转换工具类
 *
 * <h3>坐标系说明</h3>
 * <ul>
 *   <li><b>DirAdj坐标系</b>: TextPosition.getXDirAdj/getYDirAdj返回的坐标
 *     <ul>
 *       <li>已包含所有变换（CTM + Text Matrix + Font Matrix）</li>
 *       <li>YDirAdj可能为负数，需要取绝对值</li>
 *       <li>Y轴方向：向下递增（但YDirAdj为负表示从底部算起）</li>
 *     </ul>
 *   </li>
 *   <li><b>PDF用户空间</b>: 标准PDF坐标系
 *     <ul>
 *       <li>原点：左下角</li>
 *       <li>Y轴向上递增</li>
 *       <li>顶部Y > 底部Y</li>
 *     </ul>
 *   </li>
 *   <li><b>图像坐标系</b>: 渲染后的图像坐标
 *     <ul>
 *       <li>原点：左上角</li>
 *       <li>Y轴向下递增</li>
 *       <li>顶部Y < 底部Y</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public class PdfCoordinateUtils {

    /**
     * 从TextPosition列表计算边界框（PDF用户空间坐标）
     *
     * <h3>实现原理</h3>
     * <ol>
     *   <li>使用 DirAdj 系列方法（已包含所有变换）</li>
     *   <li>YDirAdj 取绝对值得到从底部算起的基线位置</li>
     *   <li>顶部 = 基线 + 高度（更大的Y）</li>
     *   <li>底部 = 基线（更小的Y）</li>
     * </ol>
     *
     * @param positions 文本位置列表
     * @return 边界框 [x0, y0, x1, y1]（PDF用户空间），null表示无法计算
     */
    public static double[] computeBoundingBox(List<TextPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }

        // 初始化边界
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;  // PDF用户空间的底部（较小的Y）
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;  // PDF用户空间的顶部（较大的Y）

        // 遍历所有文本位置，计算最小外接矩形
        for (TextPosition tp : positions) {
            // 使用DirAdj系列方法（已考虑所有变换）
            double x = tp.getXDirAdj();
            double width = tp.getWidthDirAdj();
            double height = tp.getHeightDir();

            // Y坐标转换：YDirAdj可能为负数，取绝对值得到从底部算起的Y坐标
            double yBase = Math.abs(tp.getYDirAdj());  // 基线位置（从底部算起）

            // PDF坐标系：左下角为原点，y轴向上
            // 文字顶部：基线 + 字体高度（更大的Y）
            // 文字底部：基线（更小的Y）
            double yTop = yBase + height;  // 顶部（y值较大）
            double yBottom = yBase;        // 底部（y值较小）

            // 更新边界
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + width);
            minY = Math.min(minY, yBottom);  // minY是底部（y值较小）
            maxY = Math.max(maxY, yTop);     // maxY是顶部（y值较大）
        }

        // 返回PDF坐标系的边界框 [x0, y0, x1, y1]
        // 注意：PDF坐标系是左下角为原点，y轴向上
        // y0=minY是底部（y值较小），y1=maxY是顶部（y值较大）
        return new double[]{minX, minY, maxX, maxY};
    }

    /**
     * 从TextPosition列表计算边界框（带调试信息）
     *
     * @param positions 文本位置列表
     * @return 边界框信息对象
     */
    public static BoundingBoxWithDebug computeBoundingBoxWithDebug(List<TextPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }

        BoundingBoxWithDebug result = new BoundingBoxWithDebug();

        // 记录第一个TextPosition的调试信息
        TextPosition firstTp = positions.get(0);
        result.yDirAdj_raw = firstTp.getYDirAdj();
        result.yDirAdj_abs = Math.abs(firstTp.getYDirAdj());
        result.xDirAdj_first = firstTp.getXDirAdj();
        result.widthDirAdj_first = firstTp.getWidthDirAdj();
        result.heightDir_first = firstTp.getHeightDir();

        // 计算边界框
        double[] bbox = computeBoundingBox(positions);
        if (bbox == null) {
            return null;
        }

        result.bbox = bbox;
        result.x0 = bbox[0];
        result.y0 = bbox[1];  // 底部
        result.x1 = bbox[2];
        result.y1 = bbox[3];  // 顶部
        result.width = bbox[2] - bbox[0];
        result.height = bbox[3] - bbox[1];

        return result;
    }

    /**
     * 将PDF用户空间坐标转换为图像坐标
     *
     * @param pdfBox PDF边界框 [x0, y0, x1, y1]（用户空间）
     * @param pdfWidth PDF页面宽度
     * @param pdfHeight PDF页面高度
     * @param imageWidth 图像宽度（像素）
     * @param imageHeight 图像高度（像素）
     * @return 图像坐标 [x, y, w, h]，null表示转换失败
     */
    public static double[] pdfToImageCoordinates(
            double[] pdfBox,
            float pdfWidth,
            float pdfHeight,
            int imageWidth,
            int imageHeight) {

        if (pdfBox == null || pdfBox.length != 4) {
            return null;
        }

        double x0_pdf = pdfBox[0];  // 左边界
        double y0_pdf = pdfBox[1];  // PDF底部（较小的Y）
        double x1_pdf = pdfBox[2];  // 右边界
        double y1_pdf = pdfBox[3];  // PDF顶部（较大的Y）

        // 计算缩放比例
        double scaleX = (double) imageWidth / pdfWidth;
        double scaleY = (double) imageHeight / pdfHeight;

        // 计算宽高
        double w = (x1_pdf - x0_pdf) * scaleX;
        double h = (y1_pdf - y0_pdf) * scaleY;

        // 边界检查
        if (w <= 0 || h <= 0) {
            return null;
        }

        // x坐标直接缩放
        double x = x0_pdf * scaleX;

        // y坐标需要翻转：使用PDF顶部y1_pdf，从图像顶部算起
        // 图像坐标系：左上角为原点，y轴向下
        double y = (pdfHeight - y1_pdf) * scaleY;

        // 裁剪到图像范围内
        x = Math.max(0, Math.min(x, imageWidth));
        y = Math.max(0, Math.min(y, imageHeight));
        w = Math.min(w, imageWidth - x);
        h = Math.min(h, imageHeight - y);

        // 再次检查裁剪后的有效性
        if (w <= 0 || h <= 0) {
            return null;
        }

        return new double[]{x, y, w, h};
    }

    /**
     * 边界框及调试信息
     */
    public static class BoundingBoxWithDebug {
        // 边界框坐标（PDF用户空间）
        public double[] bbox;       // [x0, y0, x1, y1]
        public double x0;           // 左边界
        public double y0;           // 底部（较小的Y）
        public double x1;           // 右边界
        public double y1;           // 顶部（较大的Y）
        public double width;        // 宽度
        public double height;       // 高度

        // 调试信息（第一个TextPosition）
        public double yDirAdj_raw;      // 原始YDirAdj（可能为负）
        public double yDirAdj_abs;      // 取绝对值后的YDirAdj
        public double xDirAdj_first;    // 第一个字符的X坐标
        public double widthDirAdj_first;// 第一个字符的宽度
        public double heightDir_first;  // 第一个字符的高度
    }
}