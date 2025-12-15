package com.example.docxserver.util.aspose;

import com.aspose.words.Document;
import com.aspose.words.PdfCompliance;
import com.aspose.words.PdfSaveOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Docx 转 PDF 工具类 (基于 Aspose.Words)
 * 支持批量转换，通过 JSON 文件跟踪转换状态
 */
public class DocxConvertPdf {

    private static final String JSON_FILE_NAME = "docx_covert_pdf_status.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * 将 docx 文件转换为 pdf
     *
     * @param docxPath docx 文件路径
     * @param pdfPath  输出的 pdf 文件路径
     * @throws Exception 转换异常
     */
    public static void convert(String docxPath, String pdfPath) throws Exception {
        Document doc = new Document(docxPath);

        // 配置 PDF 保存选项，生成 PDF/UA-2 Tagged PDF
        PdfSaveOptions saveOptions = new PdfSaveOptions();
        saveOptions.setCompliance(PdfCompliance.PDF_UA_2);
        saveOptions.setExportDocumentStructure(true);  // 生成 Tagged PDF
        saveOptions.getOutlineOptions().setDefaultBookmarksOutlineLevel(1);  // 书签大纲级别

        doc.save(pdfPath, saveOptions);
    }

    /**
     * 批量转换工作目录中的 docx 文件
     *
     * @param workDir 工作目录路径 (包含 docx 和 pdf 子目录)
     */
    public static void batchConvert(String workDir) {
        File docxDir = new File(workDir, "docx");
        File pdfDir = new File(workDir, "pdf");
        File jsonFile = new File(workDir, JSON_FILE_NAME);

        if (!docxDir.exists() || !docxDir.isDirectory()) {
            System.err.println("docx 目录不存在: " + docxDir.getAbsolutePath());
            return;
        }

        // 确保 pdf 输出目录存在
        if (!pdfDir.exists()) {
            pdfDir.mkdirs();
            System.out.println("创建 pdf 目录: " + pdfDir.getAbsolutePath());
        }

        // 读取或创建 JSON 状态文件
        ObjectNode root = loadOrCreateJson(jsonFile);
        ObjectNode conversions = (ObjectNode) root.get("conversions");

        // 扫描 docx 目录，找出需要转换的文件
        List<File> pendingFiles = new ArrayList<>();
        File[] files = docxDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".docx"));

        if (files == null || files.length == 0) {
            System.out.println("没有找到 .docx 文件");
            return;
        }

        for (File file : files) {
            String fileName = file.getName();
            if (!conversions.has(fileName)) {
                // 新文件，添加到待转换列表
                pendingFiles.add(file);
            } else {
                String status = conversions.get(fileName).get("status").asText();
                if ("pending".equals(status) || "failed".equals(status)) {
                    pendingFiles.add(file);
                }
            }
        }

        System.out.println("========================================");
        System.out.println("工作目录: " + workDir);
        System.out.println("总文件数: " + files.length);
        System.out.println("待转换数: " + pendingFiles.size());
        System.out.println("========================================\n");

        if (pendingFiles.isEmpty()) {
            System.out.println("所有文件已转换完成");
            return;
        }

        // 执行转换
        int successCount = 0;
        int failCount = 0;

        for (File docxFile : pendingFiles) {
            String fileName = docxFile.getName();
            String pdfName = fileName.substring(0, fileName.lastIndexOf('.')) + ".pdf";
            File pdfFile = new File(pdfDir, pdfName);

            System.out.println("转换中: " + fileName);

            ObjectNode fileNode = mapper.createObjectNode();
            long startTime = System.currentTimeMillis();

            try {
                convert(docxFile.getAbsolutePath(), pdfFile.getAbsolutePath());
                long elapsed = System.currentTimeMillis() - startTime;

                fileNode.put("status", "converted");
                fileNode.put("pdfPath", pdfName);
                fileNode.put("convertTime", LocalDateTime.now().format(formatter));
                fileNode.put("elapsedMs", elapsed);
                fileNode.putNull("error");

                System.out.println("  -> 成功 (" + elapsed + " ms)");
                successCount++;

            } catch (Exception e) {
                fileNode.put("status", "failed");
                fileNode.putNull("pdfPath");
                fileNode.put("convertTime", LocalDateTime.now().format(formatter));
                fileNode.put("error", e.getMessage());

                System.err.println("  -> 失败: " + e.getMessage());
                failCount++;
            }

            conversions.set(fileName, fileNode);

            // 每次转换后保存 JSON (防止中途崩溃丢失进度)
            root.put("lastUpdate", LocalDateTime.now().format(formatter));
            saveJson(jsonFile, root);
        }

        System.out.println("\n========================================");
        System.out.println("转换完成");
        System.out.println("成功: " + successCount);
        System.out.println("失败: " + failCount);
        System.out.println("========================================");
    }

    /**
     * 获取转换状态统计
     */
    public static void printStatus(String workDir) {
        File jsonFile = new File(workDir, JSON_FILE_NAME);

        if (!jsonFile.exists()) {
            System.out.println("状态文件不存在，尚未进行过转换");
            return;
        }

        try {
            ObjectNode root = (ObjectNode) mapper.readTree(jsonFile);
            ObjectNode conversions = (ObjectNode) root.get("conversions");

            int total = 0, converted = 0, pending = 0, failed = 0;

            Iterator<String> fieldNames = conversions.fieldNames();
            while (fieldNames.hasNext()) {
                String fileName = fieldNames.next();
                String status = conversions.get(fileName).get("status").asText();
                total++;
                if ("converted".equals(status)) converted++;
                else if ("pending".equals(status)) pending++;
                else if ("failed".equals(status)) failed++;
            }

            System.out.println("========================================");
            System.out.println("转换状态统计");
            System.out.println("----------------------------------------");
            System.out.println("总记录数: " + total);
            System.out.println("已转换:   " + converted);
            System.out.println("待转换:   " + pending);
            System.out.println("失败:     " + failed);
            System.out.println("最后更新: " + root.get("lastUpdate").asText());
            System.out.println("========================================");

        } catch (IOException e) {
            System.err.println("读取状态文件失败: " + e.getMessage());
        }
    }

    private static ObjectNode loadOrCreateJson(File jsonFile) {
        if (jsonFile.exists()) {
            try {
                return (ObjectNode) mapper.readTree(jsonFile);
            } catch (IOException e) {
                System.err.println("读取 JSON 文件失败，将创建新文件: " + e.getMessage());
            }
        }

        // 创建新的 JSON 结构
        ObjectNode root = mapper.createObjectNode();
        root.put("version", "1.0");
        root.putNull("lastUpdate");
        root.set("conversions", mapper.createObjectNode());
        return root;
    }

    private static void saveJson(File jsonFile, ObjectNode root) {
        try {
            mapper.writeValue(jsonFile, root);
        } catch (IOException e) {
            System.err.println("保存 JSON 文件失败: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        String workDir = "E:/models/data";

        // 打印当前状态
        printStatus(workDir);

        // 执行批量转换
        batchConvert(workDir);
    }
}