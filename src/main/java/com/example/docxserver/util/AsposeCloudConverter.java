package com.example.docxserver.util;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 使用Aspose Cloud API将DOCX转换为PDF
 *
 * API文档: https://docs.aspose.cloud/words/convert/
 */
public class AsposeCloudConverter {

    // Aspose Cloud API端点
    private static final String API_URL = "https://api.aspose.cloud/v4.0/words/online/put/saveAs";

    // Bearer Token (固定值)
    private static final String BEARER_TOKEN = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJuYmYiOjE3NjQ0ODcxOTYsImV4cCI6MTc2NTA5MTk5NiwiaXNzIjoiaHR0cHM6Ly9hcGkuYXNwb3NlLmNsb3VkIiwiYXVkIjpbImh0dHBzOi8vYXBpLmFzcG9zZS5jbG91ZC9yZXNvdXJjZXMiLCJhcGkuYmlsbGluZyIsImFwaS5pZGVudGl0eSIsImFwaS5wcm9kdWN0cyIsImFwaS5zdG9yYWdlIl0sImNsaWVudF9pZCI6ImMzMzhlZTYxLWY4MjItNDFhMy04ZjA2LTRmMTBmMTRkMTFiMyIsImNsaWVudF9kZWZhdWx0X3N0b3JhZ2UiOiI3NWY4MTBlOC1jZTZmLTRjOGMtYTc4ZC00ZTQ1ZDAwZDVmNzgiLCJjbGllbnRfaWRlbnRpdHlfdXNlcl9pZCI6IjEwNDkxMDYiLCJzY29wZSI6WyJhcGkuYmlsbGluZyIsImFwaS5pZGVudGl0eSIsImFwaS5wcm9kdWN0cyIsImFwaS5zdG9yYWdlIl19.AEJKl5lV7wCaNyNp6D25FJfaYsr8HS9fmZcIJTRL8RDgW908d4KUhZRU2nmESz9CmYFgikriyXkXw8soYFQb5TKS_ItsrETDIOURIYvyYEkDV9zDtQ8PqWh_jMZJ79ZwP7YWuT9rdXalTUKErvKXREntwau2SVrXSbYnJ8ejDXqawdq2Q3KXf27_E5pPql7nfFPZhB7Sr69ZdP_wlWiVmKtUZ0X8KmX4NANYdYW7MzDeeUgYE_wJx_JR7m04rOGB1mLJh3WxuWRxmE4XejOzi1wdSKUJFEUOAfZAVv8oNpsTsQbGc1J13ZBHHPVHNGhpxYry7CBxYyetau4DZ1czRg";

    public AsposeCloudConverter() {
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

        System.out.println("开始转换: " + docxFile.getName());
        System.out.println("文件大小: " + (docxFile.length() / 1024) + " KB");

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
            connection.setRequestProperty("Authorization", "Bearer " + BEARER_TOKEN);
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
            System.out.println("响应状态码: " + responseCode + " (" + responseMessage + ")");

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

                    System.out.println("PDF保存成功: " + pdfPath);
                    System.out.println("PDF大小: " + (totalBytes / 1024) + " KB");
                    return true;
                }
            } else {
                // 读取错误信息
                String errorMessage = readErrorStream(connection);
                System.err.println("转换失败! 状态码: " + responseCode + " (" + responseMessage + ")");
                System.err.println("错误信息: " + errorMessage);
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
        return new AsposeCloudConverter().convertDocxToPdf(docxPath, pdfPath);
    }

    /**
     * 测试入口
     */
    public static void main(String[] args) throws Exception {
        // 测试文件路径
        String docxPath = "E:\\programFile\\AIProgram\\tender_ontology\\static\\upload\\25113013334324628923\\深圳理工大学家具采购.docx";
        String pdfPath = "E:\\programFile\\AIProgram\\tender_ontology\\static\\upload\\25113013334324628923\\深圳理工大学家具采购_converted.pdf";

        boolean success = AsposeCloudConverter.convert(docxPath, pdfPath);

        if (success) {
            System.out.println("\n转换完成!");
        } else {
            System.out.println("\n转换失败!");
        }
    }
}