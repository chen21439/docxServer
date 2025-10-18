package com.example.docxserver.util.untaggedPDF;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF文本位置提取器
 * 提取PDF中每个字符的文本内容和位置信息（坐标、字体、大小等）
 */
public class PdfTextPositionExtractor extends PDFTextStripper {

    private int currentPageNum = 0;
    private List<TextPositionInfo> textPositions = new ArrayList<>();

    /**
     * 文本位置信息封装类
     */
    public static class TextPositionInfo {
        public String text;
        public float x1;  // 左上角 x
        public float y1;  // 左上角 y
        public float x2;  // 右下角 x
        public float y2;  // 右下角 y
        public String fontName;
        public float fontSize;
        public int pageNum;

        @Override
        public String toString() {
            // JSON 格式输出，坐标使用数组 [[x1, y1], [x2, y2]]
            return String.format("{\"page\": %d, \"text\": \"%s\", \"xy\": [[%.2f, %.2f], [%.2f, %.2f]], \"font\": \"%s\", \"size\": %.2f}",
                    pageNum, escapeJson(text), x1, y1, x2, y2, escapeJson(fontName), fontSize);
        }

        /**
         * JSON 字符串转义（处理引号、换行等特殊字符）
         */
        private String escapeJson(String str) {
            if (str == null) {
                return "";
            }
            return str.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t");
        }
    }

    public PdfTextPositionExtractor() throws IOException {
        super();
    }

    /**
     * 重写文本写入方法，捕获文本位置信息
     * 将连续的字符合并成一个文本块，坐标使用边界框（左上角xy + 合并后的宽高）
     */
    @Override
    protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
        if (textPositions == null || textPositions.isEmpty()) {
            return;
        }

        // 合并文本（保持原有文本内容）
        StringBuilder textBuilder = new StringBuilder();
        for (TextPosition tp : textPositions) {
            textBuilder.append(tp.getUnicode());
        }

        // 计算边界框：找到左上角坐标和右下角坐标
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;

        for (TextPosition tp : textPositions) {
            float x = tp.getX();
            float y = tp.getY();
            float width = tp.getWidth();
            float height = tp.getHeight();

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + width);
            maxY = Math.max(maxY, y + height);
        }

        // 创建合并后的文本位置信息
        TextPositionInfo info = new TextPositionInfo();
        info.text = textBuilder.toString();
        info.x1 = minX;  // 左上角 x 坐标
        info.y1 = minY;  // 左上角 y 坐标
        info.x2 = maxX;  // 右下角 x 坐标
        info.y2 = maxY;  // 右下角 y 坐标
        info.fontName = textPositions.get(0).getFont().getName(); // 使用第一个字符的字体
        info.fontSize = textPositions.get(0).getFontSize(); // 使用第一个字符的字号
        info.pageNum = currentPageNum;

        this.textPositions.add(info);
    }

    /**
     * 重写页面处理方法，记录当前页码
     */
    @Override
    protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
        currentPageNum++;
        super.startPage(page);
    }

    /**
     * 提取PDF文本位置信息并写入JSON文件
     *
     * @param pdfPath PDF文件路径
     * @param outputPath 输出JSON文件路径
     */
    public static void extractToFile(String pdfPath, String outputPath) throws IOException {
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            throw new IOException("PDF文件不存在: " + pdfPath);
        }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {

            PdfTextPositionExtractor extractor = new PdfTextPositionExtractor();

            // 提取所有页面
            extractor.setSortByPosition(true);
            extractor.getText(document);

            // 写入 JSON 数组格式
            try (FileWriter fileWriter = new FileWriter(outputPath);
                 PrintWriter printWriter = new PrintWriter(fileWriter)) {

                printWriter.println("[");

                List<TextPositionInfo> positions = extractor.getTextPositions();
                for (int i = 0; i < positions.size(); i++) {
                    printWriter.print("  ");  // 缩进
                    printWriter.print(positions.get(i).toString());

                    // 最后一个元素不加逗号
                    if (i < positions.size() - 1) {
                        printWriter.println(",");
                    } else {
                        printWriter.println();
                    }
                }

                printWriter.println("]");
            }

            System.out.println("提取完成！");
            System.out.println("输出文件: " + outputPath);
            System.out.println("总页数: " + document.getNumberOfPages());
            System.out.println("提取的文本位置数量: " + extractor.textPositions.size());
        }
    }

    /**
     * 获取提取的文本位置列表
     */
    public List<TextPositionInfo> getTextPositions() {
        return textPositions;
    }

    /**
     * 测试方法
     */
    public static void main(String[] args) {
        try {
            // 示例：提取PDF文本位置信息
            String pdfPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\鄂尔多斯市蒙医医院.pdf";
            String outputPath = "E:\\programFile\\AIProgram\\docxServer\\pdf\\index\\鄂尔多斯市蒙医医院.json";

            // 如果有命令行参数，使用参数
            if (args.length >= 1) {
                pdfPath = args[0];
            }
            if (args.length >= 2) {
                outputPath = args[1];
            } else {
                // 自动生成输出文件名
                outputPath = pdfPath.replace(".pdf", "_positions.json");
            }

            extractToFile(pdfPath, outputPath);

        } catch (Exception e) {
            System.err.println("提取失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}