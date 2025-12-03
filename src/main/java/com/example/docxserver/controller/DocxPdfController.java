package com.example.docxserver.controller;

import com.example.docxserver.service.DocxPdfService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

            // 查找PDF和聚合TXT文件
            File pdfFile = null;
            File mergedTxtFile = null;

            File[] files = taskDirFile.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.endsWith(".pdf")) {
                        pdfFile = f;
                    } else if (name.contains("_merged_") && name.endsWith(".txt")) {
                        mergedTxtFile = f;
                    }
                }
            }

            // 打包成ZIP（只包含PDF和聚合文件）
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                if (pdfFile != null) {
                    addFileToZip(zos, pdfFile, taskId + ".pdf");
                }
                if (mergedTxtFile != null) {
                    addFileToZip(zos, mergedTxtFile, taskId + "_merged.txt");
                }
            }

            // 构建响应头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            String zipFileName = taskId + ".zip";
            headers.setContentDispositionFormData("attachment", URLEncoder.encode(zipFileName, "UTF-8"));

            log.info("下载artifact: taskId={}", taskId);
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
}