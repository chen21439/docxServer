package com.example.docxserver.controller;

import com.example.docxserver.service.DocxPdfService;
import lombok.extern.slf4j.Slf4j;
import com.example.docxserver.util.tagged.dto.MatchRequest;
import com.example.docxserver.util.tagged.dto.MatchResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.docxserver.util.taggedPDF.TenderPdfExtractor;

import java.io.*;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * DOCX/PDF文档处理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/docx-pdf")
public class DocxPdfController {

    @Autowired
    private DocxPdfService docxPdfService;

    /**
     * 上传DOCX文件
     *
     * @param file DOCX文件
     * @return 包含taskId和文件路径的响应
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocx(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();

        // 验证文件
        if (file.isEmpty()) {
            result.put("success", false);
            result.put("message", "文件不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".docx")) {
            result.put("success", false);
            result.put("message", "只支持.docx文件");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            // 调用Service保存文件
            Map<String, Object> uploadResult = docxPdfService.uploadDocx(file);

            // 返回结果
            result.put("success", true);
            result.put("taskId", uploadResult.get("taskId"));
            result.put("filePath", uploadResult.get("filePath"));
            result.put("originalFilename", originalFilename);
            result.put("message", "上传成功");

            return ResponseEntity.ok(result);

        } catch (IOException e) {
            result.put("success", false);
            result.put("message", "文件保存失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    /**
     * 完整处理流程：上传DOCX -> 远程转换PDF -> 解析PDF得到TXT（异步处理）
     *
     * 接收DOCX文件后立即返回taskId，后台异步执行转换和解析。
     * 使用 /status/{taskId} 轮询处理状态。
     * 处理完成后使用 /artifact/{taskId} 下载结果。
     *
     * @param file DOCX文件
     * @param includeMcid 是否在TXT输出中包含MCID和page属性（默认false）
     * @return 包含taskId的JSON响应
     */
    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processDocxToPdfTxt(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "includeMcid", required = false, defaultValue = "false") boolean includeMcid) {

        Map<String, Object> result = new HashMap<>();

        // 验证文件
        if (file.isEmpty()) {
            result.put("success", false);
            result.put("message", "文件不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".docx")) {
            result.put("success", false);
            result.put("message", "只支持.docx文件");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            log.info("接收文件: {}, includeMcid={}", originalFilename, includeMcid);

            // Step 1: 只保存文件，立即返回taskId
            Map<String, Object> uploadResult = docxPdfService.uploadDocx(file);
            String taskId = (String) uploadResult.get("taskId");
            String docxPath = (String) uploadResult.get("filePath");
            String taskDir = (String) uploadResult.get("taskDir");

            // 更新状态为已上传
            docxPdfService.updateTaskStatus(taskId, DocxPdfService.STATUS_UPLOADED, "文件已上传，开始处理", null);

            log.info("文件已保存: taskId={}, 开始异步处理", taskId);

            // Step 2: 异步执行后续处理（移除页眉页脚、转换PDF、解析TXT）
            docxPdfService.processDocxToPdfTxtAsync(taskId, docxPath, taskDir, includeMcid);

            // 立即返回taskId
            result.put("success", true);
            result.put("taskId", taskId);
            result.put("message", "文件已接收，正在后台处理。请使用 /status/{taskId} 查询进度，处理完成后使用 /artifact/{taskId} 下载结果");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    /**
     * 下载处理结果（ZIP压缩包，包含PDF和聚合TXT）
     *
     * @param taskId 任务ID
     * @return ZIP压缩包
     */
    @GetMapping("/artifact/{taskId}")
    public ResponseEntity<byte[]> downloadArtifact(@PathVariable String taskId) {
        try {
            // 获取任务目录
            String taskDir = docxPdfService.getTaskDir(taskId);
            File taskDirFile = new File(taskDir);

            if (!taskDirFile.exists() || !taskDirFile.isDirectory()) {
                log.warn("任务目录不存在: {}", taskDir);
                return ResponseEntity.notFound().build();
            }

            // 查找PDF和两个独立的TXT文件（表格和段落）
            File pdfFile = null;
            File tableTxtFile = null;      // {taskId}_pdf_*.txt（不含paragraph）
            File paragraphTxtFile = null;  // {taskId}_pdf_paragraph_*.txt

            File[] files = taskDirFile.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.endsWith(".pdf")) {
                        pdfFile = f;
                    } else if (name.startsWith(taskId + "_pdf_paragraph_") && name.endsWith(".txt")) {
                        // 段落文件：取最新的
                        if (paragraphTxtFile == null || name.compareTo(paragraphTxtFile.getName()) > 0) {
                            paragraphTxtFile = f;
                        }
                    } else if (name.startsWith(taskId + "_pdf_") && name.endsWith(".txt") && !name.contains("paragraph") && !name.contains("merged")) {
                        // 表格文件：取最新的
                        if (tableTxtFile == null || name.compareTo(tableTxtFile.getName()) > 0) {
                            tableTxtFile = f;
                        }
                    }
                }
            }

            // 打包成ZIP（包含PDF和两个独立的TXT文件）
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                if (pdfFile != null) {
                    addFileToZip(zos, pdfFile, taskId + ".pdf");
                }
                if (tableTxtFile != null) {
                    addFileToZip(zos, tableTxtFile, taskId + "_table.txt");
                }
                if (paragraphTxtFile != null) {
                    addFileToZip(zos, paragraphTxtFile, taskId + "_paragraph.txt");
                }
            }

            // 构建响应头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            String zipFileName = taskId + ".zip";
            headers.setContentDispositionFormData("attachment", URLEncoder.encode(zipFileName, "UTF-8"));

            log.info("下载artifact: taskId={}, 表格文件={}, 段落文件={}",
                    taskId,
                    tableTxtFile != null ? tableTxtFile.getName() : "无",
                    paragraphTxtFile != null ? paragraphTxtFile.getName() : "无");
            return new ResponseEntity<>(baos.toByteArray(), headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("下载artifact失败: taskId={}, error={}", taskId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 查询任务状态
     *
     * @param taskId 任务ID
     * @return 任务状态信息
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        log.info("查询任务状态: taskId={}", taskId);
        Map<String, Object> status = docxPdfService.getTaskStatus(taskId);
        return ResponseEntity.ok(status);
    }

    /**
     * 将文件添加到ZIP压缩包
     */
    private void addFileToZip(ZipOutputStream zos, File file, String entryName) throws IOException {
        if (!file.exists()) {
            log.warn("文件不存在，跳过: {}", file.getAbsolutePath());
            return;
        }

        zos.putNextEntry(new ZipEntry(entryName));
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
        }
        zos.closeEntry();
        log.info("已添加到ZIP: {} -> {}", file.getName(), entryName);
    }

    /**
     * 匹配段落：根据用户提供的段落列表，返回 PDF 中的 page 和 bbox
     *
     * @param request 匹配请求
     * @return 匹配结果
     */
    @PostMapping("/match")
    public ResponseEntity<MatchResponse> matchParagraphs(@RequestBody MatchRequest request) {
        if (request.getTaskId() == null || request.getTaskId().isEmpty()) {
            log.warn("匹配请求缺少 taskId");
            return ResponseEntity.badRequest().build();
        }

        if (request.getParagraphs() == null || request.getParagraphs().isEmpty()) {
            log.warn("匹配请求缺少 paragraphs");
            return ResponseEntity.badRequest().build();
        }

        try {
            log.info("接收匹配请求: taskId={}, 段落数={}", request.getTaskId(), request.getParagraphs().size());
            MatchResponse response = docxPdfService.matchParagraphs(request);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("匹配失败: taskId={}, error={}", request.getTaskId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 运行DOCX与PDF比较测试
     *
     * 根据taskId运行TenderPdfExtractor的比较流程：
     * 1. compareDocxAndPdfParagraphs - 比较表格外段落
     * 2. compareDocxAndPdfTableParagraphs - 比较表格段落
     *
     * @param taskId 任务ID（对应/data/docx_server/{taskId}/目录）
     * @return 比较结果
     */
    @GetMapping("/compare/{taskId}")
    public ResponseEntity<Map<String, Object>> compareDocxAndPdf(@PathVariable String taskId) {
        Map<String, Object> result = new HashMap<>();

        if (taskId == null || taskId.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "taskId不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            taskId = taskId.trim();
            // 使用项目的存储路径
            String baseDir = "/data/docx_server/" + taskId + "/";

            log.info("开始DOCX与PDF比较: taskId={}, baseDir={}", taskId, baseDir);

            // 检查目录是否存在
            File dir = new File(baseDir);
            if (!dir.exists() || !dir.isDirectory()) {
                result.put("success", false);
                result.put("message", "目录不存在: " + baseDir);
                return ResponseEntity.badRequest().body(result);
            }

            // 查找DOCX文件
            File[] docxFiles = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    return name.toLowerCase().endsWith(".docx");
                }
            });

            if (docxFiles == null || docxFiles.length == 0) {
                result.put("success", false);
                result.put("message", "目录中未找到DOCX文件: " + baseDir);
                return ResponseEntity.badRequest().body(result);
            }

            File docxFile = docxFiles[0];
            log.info("找到DOCX文件: {}", docxFile.getName());

            // 查找PDF表格TXT文件（{taskId}_pdf_*.txt）
            final String currentTaskId = taskId;
            File[] pdfTableFiles = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    return name.startsWith(currentTaskId + "_pdf_") &&
                           name.endsWith(".txt") &&
                           !name.contains("paragraph");
                }
            });

            if (pdfTableFiles == null || pdfTableFiles.length == 0) {
                result.put("success", false);
                result.put("message", "未找到PDF表格文件: " + taskId + "_pdf_*.txt");
                return ResponseEntity.badRequest().body(result);
            }

            // 按文件名排序，取最新的
            java.util.Arrays.sort(pdfTableFiles, new java.util.Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return f2.getName().compareTo(f1.getName());
                }
            });

            File pdfTableFile = pdfTableFiles[0];
            log.info("找到PDF表格文件: {}", pdfTableFile.getName());

            // 定义输出文件路径
            String docxTxtPath = baseDir + taskId + "_docx.txt";
            String docxTableTxtPath = baseDir + taskId + "_docx_table.txt";

            // 运行比较流程
            log.info("步骤1: 比较DOCX和PDF的表格外段落");
            TenderPdfExtractor.compareDocxAndPdfParagraphs(docxFile, docxTxtPath, taskId);

            log.info("步骤2: 比较DOCX和PDF的表格段落");
            TenderPdfExtractor.compareDocxAndPdfTableParagraphs(docxFile, docxTableTxtPath, pdfTableFile);

            result.put("success", true);
            result.put("taskId", taskId);
            result.put("baseDir", baseDir);
            result.put("docxFile", docxFile.getName());
            result.put("pdfTableFile", pdfTableFile.getName());
            result.put("message", "比较完成，详细结果请查看控制台日志");

            log.info("DOCX与PDF比较完成: taskId={}", taskId);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("比较失败: taskId={}, error={}", taskId, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "比较失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }
}
