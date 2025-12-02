package com.example.docxserver.util.taggedPDF;

import java.io.File;
import java.io.FilenameFilter;

/**
 * 招标文件PDF提取器
 *
 * 用于处理 tender_ontology 项目上传的PDF文件
 * 复用 PdfTableExtractor 的提取逻辑
 */
public class TenderPdfExtractor {

    // 配置：tender_ontology 上传目录
    public static String baseDir = "E:\\programFile\\AIProgram\\tender_ontology\\static\\upload\\25120110583313478093\\";
    public static String taskId = "25120110583313478093";

    public static void main(String[] args) throws Exception {

        // 查找目录中的PDF文件
        File dir = new File(baseDir);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("目录不存在: " + baseDir);
            return;
        }

        File[] pdfFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".pdf");
            }
        });

        if (pdfFiles == null || pdfFiles.length == 0) {
            System.err.println("目录中没有找到PDF文件");
            return;
        }

        // 打印找到的PDF文件
        System.out.println("=== 找到的PDF文件 ===");
        for (File pdf : pdfFiles) {
            System.out.println("  - " + pdf.getName() + " (" + (pdf.length() / 1024) + " KB)");
        }
        System.out.println();

        // 处理第一个PDF文件（如果有多个）
        File pdfFile = pdfFiles[0];
        String pdfPath = pdfFile.getAbsolutePath();

        System.out.println("=== 开始处理PDF: " + pdfFile.getName() + " ===");
        System.out.println();

        // 调用 PdfTableExtractor 提取表格和段落
        // 输出文件将保存在同一目录下
        System.out.println("=== 从PDF结构树提取表格和段落到XML格式 ===");
        PdfTableExtractor.extractToXml(taskId, pdfPath, baseDir);

        System.out.println();
        System.out.println("=== 提取完成 ===");
        System.out.println("输出目录: " + baseDir);
    }
}