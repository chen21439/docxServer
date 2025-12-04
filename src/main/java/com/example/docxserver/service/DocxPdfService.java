package com.example.docxserver.service;

import com.example.docxserver.util.AsposeCloudConverter;
import com.example.docxserver.util.docx.DocxHeaderFooterRemover;
import com.example.docxserver.util.taggedPDF.PdfTableExtractor;
import com.example.docxserver.util.tagged.PdfTextMatcher;
import com.example.docxserver.util.tagged.dto.MatchRequest;
import com.example.docxserver.util.tagged.dto.MatchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * PDF文档处理服务
 * 提供PDF结构提取、文本映射等功能
 */
@Slf4j
@Service
public class DocxPdfService {

    /**
     * 数据存储基础目录
     * - Linux: /data/docx_server
     * - Windows: 自动解析为当前盘符，如 E:\data\docx_server
     */
    private static final String BASE_PATH = "/data/docx_server";

    /**
     * 上传并保存DOCX文件
     *
     * @param file DOCX文件
     * @return 包含taskId、filePath等信息的Map
     * @throws IOException 文件保存异常
     */
    public Map<String, Object> uploadDocx(MultipartFile file) throws IOException {
        Map<String, Object> result = new HashMap<>();

        // 生成taskId
        String taskId = UUID.randomUUID().toString().replace("-", "");

        // 创建任务目录
        File taskDir = new File(BASE_PATH, taskId);
        if (!taskDir.exists()) {
            boolean created = taskDir.mkdirs();
            log.info("创建任务目录: {}, 结果: {}", taskDir.getAbsolutePath(), created);
        }

        // 保存文件
        String savedFileName = taskId + ".docx";
        File savedFile = new File(taskDir, savedFileName);

        // 使用流式写入，避免 transferTo 的路径问题
        try (InputStream is = file.getInputStream();
             OutputStream os = Files.newOutputStream(savedFile.toPath())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
        }

        log.info("文件保存成功: {}", savedFile.getAbsolutePath());

        // 返回结果
        result.put("taskId", taskId);
        result.put("filePath", savedFile.getAbsolutePath());
        result.put("taskDir", taskDir.getAbsolutePath());

        return result;
    }

    /**
     * 从PDF提取表格和段落结构到XML格式（默认不包含MCID）
     *
     * 生成两个文件：
     * - {taskId}_pdf_{timestamp}.txt：表格结构（带bbox）
     * - {taskId}_pdf_paragraph_{timestamp}.txt：表格外段落（带bbox）
     *
     * @param taskId 任务ID
     * @param pdfPath PDF文件路径
     * @param outputDir 输出目录
     * @throws IOException 文件读写异常
     */
    public void extractPdfToXml(String taskId, String pdfPath, String outputDir) throws IOException {
        extractPdfToXml(taskId, pdfPath, outputDir, false);
    }

    /**
     * 从PDF提取表格和段落结构到XML格式（可控制MCID输出）
     *
     * 生成两个文件：
     * - {taskId}_pdf_{timestamp}.txt：表格结构（带bbox）
     * - {taskId}_pdf_paragraph_{timestamp}.txt：表格外段落（带bbox）
     *
     * @param taskId 任务ID
     * @param pdfPath PDF文件路径
     * @param outputDir 输出目录
     * @param includeMcid 是否在输出中包含MCID和page属性
     * @throws IOException 文件读写异常
     */
    public void extractPdfToXml(String taskId, String pdfPath, String outputDir, boolean includeMcid) throws IOException {
        // 验证PDF文件存在
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            throw new IOException("PDF文件不存在: " + pdfPath);
        }

        // 验证输出目录存在，不存在则创建
        File outDir = new File(outputDir);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        // 调用PdfTableExtractor提取结构
        PdfTableExtractor.extractToXml(taskId, pdfPath, outputDir, includeMcid);
    }

    /**
     * 完整处理流程：上传DOCX -> 远程转换PDF -> 解析PDF得到TXT（默认不包含MCID）
     *
     * @param file DOCX文件
     * @return 包含处理结果的Map
     * @throws Exception 处理异常
     */
    public Map<String, Object> processDocxToPdfTxt(MultipartFile file) throws Exception {
        return processDocxToPdfTxt(file, false);
    }

    /**
     * 完整处理流程：上传DOCX -> 远程转换PDF -> 解析PDF得到TXT（可控制MCID输出）
     *
     * @param file DOCX文件
     * @param includeMcid 是否在TXT输出中包含MCID和page属性
     * @return 包含处理结果的Map
     * @throws Exception 处理异常
     */
    public Map<String, Object> processDocxToPdfTxt(MultipartFile file, boolean includeMcid) throws Exception {
        Map<String, Object> result = new HashMap<>();
        String taskId = null;
        String taskDir = null;

        try {
            // Step 1: 保存上传的DOCX文件
            log.info("Step 1: 保存DOCX文件...");
            Map<String, Object> uploadResult = uploadDocx(file);
            taskId = (String) uploadResult.get("taskId");
            String docxPath = (String) uploadResult.get("filePath");
            taskDir = (String) uploadResult.get("taskDir");

            result.put("taskId", taskId);
            result.put("docxPath", docxPath);

            // 更新状态：已上传
            updateTaskStatus(taskId, STATUS_UPLOADED, "文件已上传", null);

            // Step 1.5: 移除DOCX中的页眉、页脚和页码
            log.info("[taskId: {}] Step 1.5: 移除页眉页脚页码...", taskId);
            DocxHeaderFooterRemover.removeHeaderFooter(docxPath);
            log.info("[taskId: {}] 页眉页脚页码已移除", taskId);

            // Step 2: 调用Aspose Cloud API转换DOCX为PDF
            log.info("[taskId: {}] Step 2: 调用Aspose Cloud转换DOCX为PDF...", taskId);
            updateTaskStatus(taskId, STATUS_CONVERTING, "正在转换PDF", null);

            // PDF输出路径：与DOCX同目录，文件名改为.pdf
            String pdfPath = taskDir + File.separator + taskId + ".pdf";

            boolean convertSuccess = AsposeCloudConverter.convert(docxPath, pdfPath);
            if (!convertSuccess) {
                throw new IOException("Aspose Cloud转换失败");
            }

            File pdfFile = new File(pdfPath);
            if (!pdfFile.exists()) {
                throw new IOException("转换失败：PDF文件未生成");
            }
            result.put("pdfPath", pdfPath);
            log.info("[taskId: {}] PDF文件生成成功: {}", taskId, pdfPath);

            // Step 3: 解析PDF生成TXT（根据参数决定是否包含MCID）
            log.info("[taskId: {}] Step 3: 解析PDF生成TXT... (includeMcid={})", taskId, includeMcid);
            updateTaskStatus(taskId, STATUS_EXTRACTING, "正在解析PDF", null);

            extractPdfToXml(taskId, pdfPath, taskDir, includeMcid);

            // 查找生成的TXT文件
            File taskDirFile = new File(taskDir);
            File[] txtFiles = taskDirFile.listFiles((dir, name) -> name.endsWith(".txt"));
            if (txtFiles != null && txtFiles.length > 0) {
                for (File txt : txtFiles) {
                    if (txt.getName().contains("_pdf_") && !txt.getName().contains("_paragraph_")) {
                        result.put("txtPath", txt.getAbsolutePath());
                    } else if (txt.getName().contains("_paragraph_")) {
                        result.put("paragraphTxtPath", txt.getAbsolutePath());
                    }
                }
            }

            log.info("[taskId: {}] 处理完成！", taskId);
            result.put("success", true);

            // 更新状态：完成
            updateTaskStatus(taskId, STATUS_COMPLETED, "处理完成", result);

            return result;

        } catch (Exception e) {
            // 更新状态：失败
            if (taskId != null) {
                Map<String, Object> errorInfo = new HashMap<>();
                errorInfo.put("error", e.getMessage());
                updateTaskStatus(taskId, STATUS_FAILED, "处理失败: " + e.getMessage(), errorInfo);
            }
            throw e;
        }
    }

    /**
     * 异步处理：转换PDF并提取结构（后台执行）
     *
     * @param taskId 任务ID
     * @param docxPath DOCX文件路径
     * @param taskDir 任务目录
     * @param includeMcid 是否包含MCID
     */
    @Async
    public void processDocxToPdfTxtAsync(String taskId, String docxPath, String taskDir, boolean includeMcid) {
        log.info("[taskId: {}] 开始异步处理...", taskId);

        try {
            // Step 1.5: 移除DOCX中的页眉、页脚和页码
            log.info("[taskId: {}] Step 1.5: 移除页眉页脚页码...", taskId);
            updateTaskStatus(taskId, STATUS_PROCESSING, "正在移除页眉页脚", null);
            DocxHeaderFooterRemover.removeHeaderFooter(docxPath);
            log.info("[taskId: {}] 页眉页脚页码已移除", taskId);

            // Step 2: 调用Aspose Cloud API转换DOCX为PDF
            log.info("[taskId: {}] Step 2: 调用Aspose Cloud转换DOCX为PDF...", taskId);
            updateTaskStatus(taskId, STATUS_CONVERTING, "正在转换PDF", null);

            String pdfPath = taskDir + File.separator + taskId + ".pdf";
            boolean convertSuccess = AsposeCloudConverter.convert(docxPath, pdfPath);
            if (!convertSuccess) {
                throw new IOException("Aspose Cloud转换失败");
            }

            File pdfFile = new File(pdfPath);
            if (!pdfFile.exists()) {
                throw new IOException("转换失败：PDF文件未生成");
            }
            log.info("[taskId: {}] PDF文件生成成功: {}", taskId, pdfPath);

            // Step 3: 解析PDF生成TXT
            log.info("[taskId: {}] Step 3: 解析PDF生成TXT... (includeMcid={})", taskId, includeMcid);
            updateTaskStatus(taskId, STATUS_EXTRACTING, "正在解析PDF", null);

            extractPdfToXml(taskId, pdfPath, taskDir, includeMcid);

            // 构建结果信息
            Map<String, Object> resultInfo = new HashMap<>();
            resultInfo.put("pdfPath", pdfPath);

            // 查找生成的TXT文件
            File taskDirFile = new File(taskDir);
            File[] txtFiles = taskDirFile.listFiles((dir, name) -> name.endsWith(".txt"));
            if (txtFiles != null) {
                for (File txt : txtFiles) {
                    if (txt.getName().contains("_pdf_") && !txt.getName().contains("_paragraph_")) {
                        resultInfo.put("txtPath", txt.getAbsolutePath());
                    } else if (txt.getName().contains("_paragraph_")) {
                        resultInfo.put("paragraphTxtPath", txt.getAbsolutePath());
                    }
                }
            }

            log.info("[taskId: {}] 异步处理完成！", taskId);
            updateTaskStatus(taskId, STATUS_COMPLETED, "处理完成", resultInfo);

        } catch (Exception e) {
            log.error("[taskId: {}] 异步处理失败: {}", taskId, e.getMessage(), e);
            Map<String, Object> errorInfo = new HashMap<>();
            errorInfo.put("error", e.getMessage());
            updateTaskStatus(taskId, STATUS_FAILED, "处理失败: " + e.getMessage(), errorInfo);
        }
    }

    /**
     * 根据taskId获取任务目录
     */
    public String getTaskDir(String taskId) {
        return new File(BASE_PATH, taskId).getAbsolutePath();
    }

    /**
     * 任务状态常量
     */
    public static final String STATUS_UPLOADED = "UPLOADED";       // 文件已上传
    public static final String STATUS_PROCESSING = "PROCESSING";   // 正在处理（移除页眉页脚）
    public static final String STATUS_CONVERTING = "CONVERTING";   // 正在转换PDF
    public static final String STATUS_EXTRACTING = "EXTRACTING";   // 正在解析PDF
    public static final String STATUS_COMPLETED = "COMPLETED";     // 处理完成
    public static final String STATUS_FAILED = "FAILED";           // 处理失败
    public static final String STATUS_NOT_FOUND = "NOT_FOUND";     // 任务不存在

    private static final String STATUS_FILE_NAME = "status.json";

    /**
     * 更新任务状态（写入 status.json）
     *
     * @param taskId 任务ID
     * @param status 状态
     * @param message 状态消息
     * @param extra 额外信息（可选）
     */
    public void updateTaskStatus(String taskId, String status, String message, Map<String, Object> extra) {
        File taskDir = new File(BASE_PATH, taskId);
        File statusFile = new File(taskDir, STATUS_FILE_NAME);

        Map<String, Object> statusData = new HashMap<>();
        statusData.put("taskId", taskId);
        statusData.put("status", status);
        statusData.put("message", message);
        statusData.put("updateTime", System.currentTimeMillis());

        if (extra != null) {
            // 过滤掉不需要暴露的路径字段
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                String key = entry.getKey();
                // 排除路径相关字段
                if (!"pdfPath".equals(key) && !"txtPath".equals(key) && !"paragraphTxtPath".equals(key)) {
                    statusData.put(key, entry.getValue());
                }
            }
        }

        try {
            // 使用 Gson 写入 JSON
            String json = new com.google.gson.GsonBuilder()
                    .setPrettyPrinting()
                    .create()
                    .toJson(statusData);
            Files.write(statusFile.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("[taskId: {}] 写入状态文件失败: {}", taskId, e.getMessage());
        }
    }

    /**
     * 查询任务状态（读取 status.json）
     *
     * @param taskId 任务ID
     * @return 任务状态信息
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTaskStatus(String taskId) {
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);

        // 获取任务目录
        File taskDir = new File(BASE_PATH, taskId);

        // 检查任务是否存在
        if (!taskDir.exists() || !taskDir.isDirectory()) {
            result.put("exists", false);
            result.put("status", STATUS_NOT_FOUND);
            result.put("message", "任务不存在");
            return result;
        }

        result.put("exists", true);

        // 读取 status.json
        File statusFile = new File(taskDir, STATUS_FILE_NAME);
        if (statusFile.exists()) {
            try {
                String json = new String(Files.readAllBytes(statusFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                Map<String, Object> statusData = new com.google.gson.Gson().fromJson(json, Map.class);
                result.putAll(statusData);
            } catch (IOException e) {
                log.error("[taskId: {}] 读取状态文件失败: {}", taskId, e.getMessage());
                result.put("status", "UNKNOWN");
                result.put("message", "无法读取状态文件");
            }
        } else {
            // 没有状态文件，根据文件判断状态
            File docxFile = new File(taskDir, taskId + ".docx");
            if (docxFile.exists()) {
                result.put("status", STATUS_UPLOADED);
                result.put("message", "文件已上传，等待处理");
            } else {
                result.put("status", "UNKNOWN");
                result.put("message", "状态未知");
            }
        }

        return result;
    }

    /**
     * 匹配段落：根据用户提供的 [{pid, txt}] 数组查找 PDF 中的 page 和 bbox
     *
     * @param request 匹配请求（包含 taskId 和段落列表）
     * @return 匹配响应（包含 page, bbox, matchType 等）
     * @throws IOException 文件读取异常
     */
    public MatchResponse matchParagraphs(MatchRequest request) throws IOException {
        String taskId = request.getTaskId();
        String taskDir = getTaskDir(taskId);

        // 验证任务目录存在
        File taskDirFile = new File(taskDir);
        if (!taskDirFile.exists() || !taskDirFile.isDirectory()) {
            throw new IOException("任务不存在: " + taskId);
        }

        log.info("[taskId: {}] 开始匹配段落，共 {} 个", taskId,
                request.getParagraphs() != null ? request.getParagraphs().size() : 0);

        // 调用匹配器
        MatchResponse response = PdfTextMatcher.matchWithResponse(request, taskDir + File.separator);

        log.info("[taskId: {}] 匹配完成，匹配率: {}% ({}/{})",
                taskId,
                String.format("%.1f", response.getStatistics().getMatchRate()),
                response.getStatistics().getFoundCount(),
                response.getStatistics().getTotal());

        return response;
    }
}
