package com.example.docxserver.util;

import com.example.docxserver.service.AsposeTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 使用Aspose Cloud API将DOCX转换为PDF
 *
 * API文档: https://docs.aspose.cloud/words/convert/
 */
@Slf4j
@Component
public class AsposeCloudConverter {

    // Aspose Cloud API端点
    private static final String API_URL = "https://api.aspose.cloud/v4.0/words/online/put/saveAs";

    private static AsposeTokenService tokenService;

    @Autowired
    public void setTokenService(AsposeTokenService tokenService) {
        AsposeCloudConverter.tokenService = tokenService;
    }

    /**
     * 将DOCX文件转换为PDF
     *
     * @param docxPath DOCX文件路径
     * @param pdfPath  输出PDF文件路径
     * @return true如果转换成功
     * @throws IOException 转换失败时抛出异常
     */
    public boolean convertDocxToPdf(String docxPath, String pdfPath) throws IOException {
        File docxFile = new File(docxPath);
        if (!docxFile.exists()) {
            throw new FileNotFoundException("DOCX文件不存在: " + docxPath);
        }

        // 获取动态 Token
        String bearerToken = tokenService.getBearerToken();
        if (bearerToken == null || bearerToken.isEmpty()) {
            throw new IOException("无法获取 Aspose Cloud Token");
        }

        log.info("开始转换: {}", docxFile.getName());
        log.info("文件大小: {} KB", docxFile.length() / 1024);

        // 构建multipart请求
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();

        URL url = new URL(API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setConnectTimeout(60000);  // 60秒连接超时
            connection.setReadTimeout(300000);    // 5分钟读取超时（大文件转换可能需要较长时间）

            // 设置请求头
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/pdf");

            // 写入multipart body（顺序：Document在前，SaveOptionsData在后）
            try (OutputStream outputStream = connection.getOutputStream();
                 DataOutputStream dos = new DataOutputStream(outputStream)) {

                String lineEnd = "\r\n";
                String twoHyphens = "--";

                // Part 1: document (DOCX文件) - 二进制内容
                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"" + docxFile.getName() + "\"" + lineEnd);
                dos.writeBytes("Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document" + lineEnd);
                dos.writeBytes(lineEnd);

                // 写入文件二进制内容
                try (FileInputStream fis = new FileInputStream(docxFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        dos.write(buffer, 0, bytesRead);
                    }
                }
                dos.writeBytes(lineEnd);

                // Part 2: saveOptionsData (JSON配置)
                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"saveOptionsData\"" + lineEnd);
                dos.writeBytes("Content-Type: application/json" + lineEnd);
                dos.writeBytes(lineEnd);
                // 生成Tagged PDF (PDF/UA-2标准)，使PDF包含结构标签
                String pdfFileName = docxFile.getName().replace(".docx", ".pdf").replace(".DOCX", ".pdf");
                String saveOptionsJson = "{" +
                        "\"SaveFormat\": \"pdf\", " +
                        "\"FileName\": \"" + pdfFileName + "\", " +
                        "\"Compliance\": \"PdfUa2\", " +
                        "\"BookmarksOutlineLevel\": 1, " +
                        "\"ExportDocumentStructure\": true" +
                        "}";
                dos.writeBytes(saveOptionsJson);
                dos.writeBytes(lineEnd);

                // 结束boundary
                dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                dos.flush();
            }

            // 检查响应
            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();
            log.info("响应状态码: {} ({})", responseCode, responseMessage);

            // 2xx 状态码都视为成功
            if (responseCode >= 200 && responseCode < 300) {
                // 读取PDF内容并保存
                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream fos = new FileOutputStream(pdfPath)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }

                    log.info("PDF保存成功: {}", pdfPath);
                    log.info("PDF大小: {} KB", totalBytes / 1024);
                    return true;
                }
            } else {
                // 读取错误信息
                String errorMessage = readErrorStream(connection);
                log.error("转换失败! 状态码: {} ({})", responseCode, responseMessage);
                log.error("错误信息: {}", errorMessage);
                return false;
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 读取错误流
     */
    private String readErrorStream(HttpURLConnection connection) {
        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream == null) {
                return "无错误详情";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "读取错误信息失败: " + e.getMessage();
        }
    }

    /**
     * 静态方法：转换DOCX到PDF
     *
     * @param docxPath DOCX文件路径
     * @param pdfPath  输出PDF文件路径
     * @return true如果转换成功
     * @throws IOException 转换失败时抛出异常
     */
    public static boolean convert(String docxPath, String pdfPath) throws IOException {
        if (tokenService == null) {
            throw new IOException("AsposeCloudConverter 未初始化，请确保 Spring 容器已启动");
        }
        return new AsposeCloudConverter().convertDocxToPdf(docxPath, pdfPath);
    }
}