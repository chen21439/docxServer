package com.example.docxserver.util.docx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 模型辅助判定器（Step 5 实现）
 *
 * 功能：
 * - 对灰色地带的弱标题和子表进行模型辅助判定
 * - 批量调用，控制调用次数（最多 3-5 次）
 * - 局部窗口文本（600-1000 字以内）
 *
 * 触发条件：
 * 1. 弱标题上下文冲突（规则指标相互打架）
 * 2. 表格子表切块得分在 0.65~0.75 灰区
 */
public class ModelAssistant {

    private static final String MODEL_API_URL = "http://112.111.20.89:8888/v1/chat/completions";
    private static final String MODEL_NAME = "qwen3-32b";
    private static final double TEMPERATURE = 0.4;
    private static final double TOP_P = 0.7;
    private static final double REPETITION_PENALTY = 1.05;
    private static final int MAX_TOKENS = 8192;

    // 调用预算
    private static final int MAX_MODEL_CALLS = 5;
    private static final int TIMEOUT_SECONDS = 30;  // 增加到30秒，适应模型响应时间

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * 判定弱标题是否应该升级为章节
     *
     * @param decisions 待判定的弱标题列表
     * @return 模型判定结果（true=升级为章节，false=保持普通段落）
     */
    public static Map<String, Boolean> judgeWeakHeadings(List<WeakHeadingDecision> decisions) {
        Map<String, Boolean> results = new LinkedHashMap<>();

        if (decisions == null || decisions.isEmpty()) {
            return results;
        }

        // 批量构造 prompt（每批最多 3 个）
        int batchSize = 3;
        int callCount = 0;

        for (int i = 0; i < decisions.size() && callCount < MAX_MODEL_CALLS; i += batchSize) {
            int end = Math.min(i + batchSize, decisions.size());
            List<WeakHeadingDecision> batch = decisions.subList(i, end);

            System.out.println("  Calling model for batch " + (callCount + 1) + "/" + MAX_MODEL_CALLS +
                             " (processing " + batch.size() + " headings, from index " + i + ")...");

            String prompt = buildWeakHeadingPrompt(batch);
            String response = callModel(prompt);

            // 解析响应
            Map<String, Boolean> batchResults = parseWeakHeadingResponse(response, batch);
            results.putAll(batchResults);

            callCount++;
        }

        if (callCount >= MAX_MODEL_CALLS && results.size() < decisions.size()) {
            System.out.println("  Warning: Reached maximum model calls (" + MAX_MODEL_CALLS +
                             "), processed " + results.size() + "/" + decisions.size() + " decisions.");
        }

        return results;
    }

    /**
     * 判定子表是否为真实子表
     *
     * @param decisions 待判定的子表列表
     * @return 模型判定结果（true=是子表，false=不是）
     */
    public static Map<String, Boolean> judgeSubTables(List<SubTableDecision> decisions) {
        Map<String, Boolean> results = new LinkedHashMap<>();

        if (decisions == null || decisions.isEmpty()) {
            return results;
        }

        // 批量构造 prompt
        int batchSize = 2;
        int callCount = 0;

        for (int i = 0; i < decisions.size() && callCount < MAX_MODEL_CALLS; i += batchSize) {
            int end = Math.min(i + batchSize, decisions.size());
            List<SubTableDecision> batch = decisions.subList(i, end);

            System.out.println("  Calling model for batch " + (callCount + 1) + "/" + MAX_MODEL_CALLS +
                             " (processing " + batch.size() + " sub-tables, from index " + i + ")...");

            String prompt = buildSubTablePrompt(batch);
            String response = callModel(prompt);

            // 解析响应
            Map<String, Boolean> batchResults = parseSubTableResponse(response, batch);
            results.putAll(batchResults);

            callCount++;
        }

        if (callCount >= MAX_MODEL_CALLS && results.size() < decisions.size()) {
            System.out.println("  Warning: Reached maximum model calls (" + MAX_MODEL_CALLS +
                             "), processed " + results.size() + "/" + decisions.size() + " decisions.");
        }

        return results;
    }

    /**
     * 构造弱标题判定 prompt
     */
    private static String buildWeakHeadingPrompt(List<WeakHeadingDecision> decisions) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个文档结构分析专家。以下是一些可能的章节标题，请判断它们是否应该作为章节标题。\n\n");
        prompt.append("判断标准：\n");
        prompt.append("- 章节标题：短语形式，引领下文内容，通常后面跟着子内容或表格\n");
        prompt.append("- 普通段落：长句形式，描述性内容，不引领下文结构\n\n");

        for (int i = 0; i < decisions.size(); i++) {
            WeakHeadingDecision decision = decisions.get(i);
            prompt.append(String.format("【%d】ID: %s\n", i + 1, decision.id));
            prompt.append(String.format("文本: %s\n", decision.text));
            prompt.append(String.format("上下文: %s\n\n", decision.context));
        }

        prompt.append("请对每个候选项回答\"是\"或\"否\"，格式如下：\n");
        prompt.append("1. 是/否\n");
        prompt.append("2. 是/否\n");
        prompt.append("...\n");

        return prompt.toString();
    }

    /**
     * 构造子表判定 prompt
     */
    private static String buildSubTablePrompt(List<SubTableDecision> decisions) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个表格结构分析专家。以下是一些可能的表格内子表，请判断它们是否为独立的子表。\n\n");
        prompt.append("判断标准：\n");
        prompt.append("- 子表：有独立的列头，列数与主表不同，内容相对独立\n");
        prompt.append("- 非子表：只是主表的一部分，没有独立结构\n\n");

        for (int i = 0; i < decisions.size(); i++) {
            SubTableDecision decision = decisions.get(i);
            prompt.append(String.format("【%d】表格 ID: %s\n", i + 1, decision.tableId));
            prompt.append(String.format("候选列头: %s\n", String.join(", ", decision.columnHeaders)));
            prompt.append(String.format("主表列数: %d，候选子表列数: %d\n",
                decision.mainTableColCount, decision.subTableColCount));
            prompt.append(String.format("置信度: %.2f\n\n", decision.confidence));
        }

        prompt.append("请对每个候选项回答\"是\"或\"否\"，格式如下：\n");
        prompt.append("1. 是/否\n");
        prompt.append("2. 是/否\n");

        return prompt.toString();
    }

    /**
     * 调用模型 API（Java 8 兼容版本）
     */
    private static String callModel(String userPrompt) {
        HttpURLConnection conn = null;
        try {
            // 构造请求体
            ObjectNode requestBody = JSON_MAPPER.createObjectNode();
            requestBody.put("model", MODEL_NAME);
            requestBody.put("temperature", TEMPERATURE);
            requestBody.put("top_p", TOP_P);
            requestBody.put("repetition_penalty", REPETITION_PENALTY);
            requestBody.put("max_tokens", MAX_TOKENS);

            ArrayNode messages = requestBody.putArray("messages");

            // System message
            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "你是一个专业的文档结构分析助手，擅长判断标题和表格结构。");

            // User message
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            String requestBodyStr = JSON_MAPPER.writeValueAsString(requestBody);

            // 发送 HTTP 请求
            URL url = new URL(MODEL_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(TIMEOUT_SECONDS * 1000);
            conn.setReadTimeout(TIMEOUT_SECONDS * 1000);
            conn.setDoOutput(true);

            // 写入请求体
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBodyStr.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // 读取响应
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                }

                // 解析响应
                JsonNode responseJson = JSON_MAPPER.readTree(response.toString());
                JsonNode choices = responseJson.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode message = choices.get(0).get("message");
                    if (message != null) {
                        return message.get("content").asText();
                    }
                }
            } else {
                System.err.println("Model API error: " + responseCode);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        System.err.println(line);
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("Failed to call model API: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        return "";
    }

    /**
     * 解析弱标题判定响应
     */
    private static Map<String, Boolean> parseWeakHeadingResponse(String response, List<WeakHeadingDecision> decisions) {
        Map<String, Boolean> results = new LinkedHashMap<>();

        // 简单解析：查找 "是" 或 "否"
        String[] lines = response.split("\n");
        int decisionIndex = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.matches("^\\d+\\.?\\s*(是|否).*")) {
                boolean isHeading = line.contains("是");
                if (decisionIndex < decisions.size()) {
                    results.put(decisions.get(decisionIndex).id, isHeading);
                    decisionIndex++;
                }
            }
        }

        // 如果解析失败，使用默认值（保守策略：不升级）
        for (WeakHeadingDecision decision : decisions) {
            if (!results.containsKey(decision.id)) {
                results.put(decision.id, false);
            }
        }

        return results;
    }

    /**
     * 解析子表判定响应
     */
    private static Map<String, Boolean> parseSubTableResponse(String response, List<SubTableDecision> decisions) {
        Map<String, Boolean> results = new LinkedHashMap<>();

        String[] lines = response.split("\n");
        int decisionIndex = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.matches("^\\d+\\.?\\s*(是|否).*")) {
                boolean isSubTable = line.contains("是");
                if (decisionIndex < decisions.size()) {
                    results.put(decisions.get(decisionIndex).tableId, isSubTable);
                    decisionIndex++;
                }
            }
        }

        // 默认值（保守策略）
        for (SubTableDecision decision : decisions) {
            if (!results.containsKey(decision.tableId)) {
                results.put(decision.tableId, false);
            }
        }

        return results;
    }

    /**
     * 弱标题待判定项
     */
    public static class WeakHeadingDecision {
        public String id;           // 段落 ID
        public String text;         // 段落文本
        public String context;      // 上下文（前后若干字）
        public double score;        // 当前评分
        public List<String> signals; // 检测信号

        public WeakHeadingDecision(String id, String text, String context, double score, List<String> signals) {
            this.id = id;
            this.text = text;
            this.context = context;
            this.score = score;
            this.signals = signals;
        }
    }

    /**
     * 子表待判定项
     */
    public static class SubTableDecision {
        public String tableId;              // 表格 ID
        public List<String> columnHeaders;  // 列头
        public int mainTableColCount;       // 主表列数
        public int subTableColCount;        // 子表列数
        public double confidence;           // 置信度

        public SubTableDecision(String tableId, List<String> columnHeaders,
                int mainTableColCount, int subTableColCount, double confidence) {
            this.tableId = tableId;
            this.columnHeaders = columnHeaders;
            this.mainTableColCount = mainTableColCount;
            this.subTableColCount = subTableColCount;
            this.confidence = confidence;
        }
    }
}