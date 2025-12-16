package com.example.docxserver.util.aspose;

import com.aspose.words.Document;
import com.aspose.words.PdfCompliance;
import com.aspose.words.PdfSaveOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.docxserver.util.taggedPDF.PdfTableExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(DocxConvertPdf.class);
    private static final String JSON_FILE_NAME = "docx_covert_pdf_status.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 覆盖模式：true=转换所有文件并覆盖记录，false=只转换未转换/失败的文件 */
    private static final boolean OVERWRITE_MODE = true;

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

        // 覆盖模式：重置 JSON 状态文件
        ObjectNode root;
        if (OVERWRITE_MODE) {
            root = mapper.createObjectNode();
            root.put("version", "1.0");
            root.putNull("lastUpdate");
            root.set("conversions", mapper.createObjectNode());
            System.out.println("[覆盖模式] 重置状态文件，将转换所有文件");
        } else {
            root = loadOrCreateJson(jsonFile);
        }
        ObjectNode conversions = (ObjectNode) root.get("conversions");

        // 扫描 docx 目录
        File[] files = docxDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".docx"));

        if (files == null || files.length == 0) {
            System.out.println("没有找到 .docx 文件");
            return;
        }

        // 确定待转换文件列表
        List<File> pendingFiles = new ArrayList<>();
        if (OVERWRITE_MODE) {
            // 覆盖模式：转换所有文件
            for (File file : files) {
                pendingFiles.add(file);
            }
        } else {
            // 增量模式：只转换未转换/失败的文件
            for (File file : files) {
                String fileName = file.getName();
                if (!conversions.has(fileName)) {
                    pendingFiles.add(file);
                } else {
                    String status = conversions.get(fileName).get("status").asText();
                    if ("pending".equals(status) || "failed".equals(status)) {
                        pendingFiles.add(file);
                    }
                }
            }
        }

        System.out.println("========================================");
        System.out.println("工作目录: " + workDir);
        System.out.println("模式: " + (OVERWRITE_MODE ? "覆盖" : "增量"));
        System.out.println("总文件数: " + files.length);
        System.out.println("待转换数: " + pendingFiles.size());
        System.out.println("========================================\n");

        if (pendingFiles.isEmpty()) {
            System.out.println("没有需要转换的文件");
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

                // 提取行级别文本
                extractLineLevel(pdfFile);

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

    /** JSON 输出目录 */
    private static final String JSON_OUTPUT_DIR = "E:/models/data/Section/tender_document/test";

    /**
     * 提取 PDF 的行级别文本
     *
     * @param pdfFile PDF 文件
     */
    private static void extractLineLevel(File pdfFile) {
        try {
            String pdfName = pdfFile.getName();
            String taskId = pdfName.substring(0, pdfName.lastIndexOf('.'));

            // 确保输出目录存在
            File outputDirFile = new File(JSON_OUTPUT_DIR);
            if (!outputDirFile.exists()) {
                outputDirFile.mkdirs();
            }

            System.out.println("  -> 提取行级别文本...");
            long startTime = System.currentTimeMillis();

            LineLevelArtifactGenerator.generate(taskId, pdfFile.getAbsolutePath(), JSON_OUTPUT_DIR);

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("  -> 行级别提取完成 (" + elapsed + " ms)");

        } catch (Exception e) {
            log.error("行级别提取失败: {}", e.getMessage(), e);
            System.err.println("  -> 行级别提取失败: " + e.getMessage());
        }
    }

    /**
     * 单文件转换（调试用）
     *
     * @param docxPath docx 文件路径
     */
    public static void convertSingle(String docxPath) {
        File docxFile = new File(docxPath);
        if (!docxFile.exists()) {
            System.err.println("文件不存在: " + docxPath);
            return;
        }

        // PDF 输出到同目录
        String pdfPath = docxPath.substring(0, docxPath.lastIndexOf('.')) + ".pdf";
        File pdfFile = new File(pdfPath);

        System.out.println("转换: " + docxFile.getName());

        try {
            long startTime = System.currentTimeMillis();
            convert(docxPath, pdfPath);
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("  -> 转换成功 (" + elapsed + " ms)");

            // 提取行级别文本
            extractLineLevel(pdfFile);

        } catch (Exception e) {
            System.err.println("  -> 转换失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        String workDir = "E:/models/data";

        // 批量转换模式（转换所有未转换的文件）
        batchConvert(workDir);

        // 只转换一个文件（从待转换列表中取第一个）
        // convertOne(workDir);
    }

    /**
     * 只转换一个文件（从待转换列表中取第一个）
     *
     * @param workDir 工作目录路径
     */
    public static void convertOne(String workDir) {
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
        }

        // 读取或创建 JSON 状态文件
        ObjectNode root = loadOrCreateJson(jsonFile);
        ObjectNode conversions = (ObjectNode) root.get("conversions");

        // 扫描 docx 目录，找出第一个需要转换的文件
        File[] files = docxDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".docx"));

        if (files == null || files.length == 0) {
            System.out.println("没有找到 .docx 文件");
            return;
        }

        File pendingFile = null;
        for (File file : files) {
            String fileName = file.getName();
            if (!conversions.has(fileName)) {
                pendingFile = file;
                break;
            } else {
                String status = conversions.get(fileName).get("status").asText();
                if ("pending".equals(status) || "failed".equals(status)) {
                    pendingFile = file;
                    break;
                }
            }
        }

        if (pendingFile == null) {
            System.out.println("所有文件已转换完成，没有待转换的文件");
            return;
        }

        // 转换这一个文件
        String fileName = pendingFile.getName();
        String pdfName = fileName.substring(0, fileName.lastIndexOf('.')) + ".pdf";
        File pdfFile = new File(pdfDir, pdfName);

        System.out.println("========================================");
        System.out.println("转换单个文件: " + fileName);
        System.out.println("========================================");

        ObjectNode fileNode = mapper.createObjectNode();
        long startTime = System.currentTimeMillis();

        try {
            convert(pendingFile.getAbsolutePath(), pdfFile.getAbsolutePath());
            long elapsed = System.currentTimeMillis() - startTime;

            fileNode.put("status", "converted");
            fileNode.put("pdfPath", pdfName);
            fileNode.put("convertTime", LocalDateTime.now().format(formatter));
            fileNode.put("elapsedMs", elapsed);
            fileNode.putNull("error");

            System.out.println("  -> 转换成功 (" + elapsed + " ms)");

            // 提取行级别文本
            extractLineLevel(pdfFile);

        } catch (Exception e) {
            fileNode.put("status", "failed");
            fileNode.putNull("pdfPath");
            fileNode.put("convertTime", LocalDateTime.now().format(formatter));
            fileNode.put("error", e.getMessage());

            System.err.println("  -> 转换失败: " + e.getMessage());
            e.printStackTrace();
        }

        conversions.set(fileName, fileNode);
        root.put("lastUpdate", LocalDateTime.now().format(formatter));
        saveJson(jsonFile, root);

        System.out.println("========================================");
    }
}