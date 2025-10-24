package com.example.docxserver.util.docx;

import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 中文标题检测器（Step 1 实现）
 *
 * 功能：
 * - 黑名单检测（目录、封面、页眉页脚）
 * - 中文规范标题正则匹配（L1-L5）
 * - 启发式判断增强
 * - 综合打分
 *
 * 优先级（从强到弱）：
 * 1. style-outlineLvl (score=1.0)
 * 2. paragraph-outlineLvl (score=0.95)
 * 3. 中文规范标题正则 (score=0.70-0.90)
 * 4. 启发式判断 (score≤0.70)
 */
public class ChineseHeadingDetector {

    // ==================== 常量定义 ====================

    /**
     * L1 章/卷/册 正则模式
     */
    private static final List<Pattern> L1_PATTERNS = Arrays.asList(
        Pattern.compile("^第[一二三四五六七八九十百零〇两]+章[：:、\\s]*.*"),
        Pattern.compile("^第[一二三四五六七八九十百零〇两]+册[：:、\\s]*.*"),
        Pattern.compile("^第[一二三四五六七八九十百零〇两]+卷[：:、\\s]*.*"),
        Pattern.compile("^第[一二三四五六七八九十百零〇两]+编[：:、\\s]*.*")
    );

    /**
     * L2 节/常见大标题 正则模式
     */
    private static final List<Pattern> L2_PATTERNS = Arrays.asList(
        Pattern.compile("^第[一二三四五六七八九十百零〇两]+节[：:、\\s]*.*"),
        Pattern.compile("^(项目概况|评标信息|评审办法|技术部分|商务部分|综合实力|诚信情况|投标人须知|开标信息|资格条件|评分标准|评分细则|技术要求|商务要求|采购需求|服务要求|履约要求)$")
    );

    /**
     * L3 条/款/项 正则模式
     */
    private static final List<Pattern> L3_PATTERNS = Arrays.asList(
        Pattern.compile("^第[一二三四五六七八九十百零〇两]+条[：:、\\s]*.*"),
        Pattern.compile("^第[一二三四五六七八九十百零〇两]+款[：:、\\s]*.*"),
        Pattern.compile("^第[一二三四五六七八九十百零〇两]+项[：:、\\s]*.*"),
        Pattern.compile("^\\d+\\.[：:、\\s]*.*"),  // 1. 2. 3.
        Pattern.compile("^\\d+、.*")  // 1、2、3、
    );

    /**
     * L4 中文括号编号 正则模式
     */
    private static final List<Pattern> L4_PATTERNS = Arrays.asList(
        Pattern.compile("^（[一二三四五六七八九十]+）[：:、\\s]*.*"),
        Pattern.compile("^\\([一二三四五六七八九十]+\\)[：:、\\s]*.*"),
        Pattern.compile("^（\\d+）[：:、\\s]*.*"),
        Pattern.compile("^\\(\\d+\\)[：:、\\s]*.*")
    );

    /**
     * L5 小数点编号 正则模式
     */
    private static final List<Pattern> L5_PATTERNS = Arrays.asList(
        Pattern.compile("^\\d+\\.\\d+[：:、\\s]*.*"),  // 1.1 1.2 2.1
        Pattern.compile("^\\d+\\.\\d+\\.\\d+[：:、\\s]*.*")  // 1.1.1 1.2.3
    );

    /**
     * 黑名单关键词（封面、政采文档标识）
     */
    private static final Set<String> BLACKLIST_KEYWORDS = new HashSet<>(Arrays.asList(
        "封面", "政府采购", "招标文件", "货物类", "工程类", "服务类",
        "招标", "采购文件", "竞争性磋商", "竞争性谈判", "询价", "单一来源"
    ));

    /**
     * TOC 关键词
     */
    private static final Set<String> TOC_KEYWORDS = new HashSet<>(Arrays.asList(
        "目录", "目录", "contents", "CONTENTS", "Contents"
    ));

    /**
     * 页眉页脚模式
     */
    private static final Pattern HEADER_FOOTER_PATTERN = Pattern.compile(
        "第\\s*\\d+\\s*页([\\/|]|\\s*共\\s*\\d+\\s*页)?|Page\\s+\\d+|页码[:：]?\\s*\\d+"
    );

    /**
     * 常见标题关键词（用于启发式加分）
     */
    private static final Set<String> TITLE_KEYWORDS = new HashSet<>(Arrays.asList(
        "评分标准", "评审办法", "评标方法", "技术要求", "商务要求", "资格要求",
        "投标人须知", "采购需求", "服务要求", "项目概况", "评分细则"
    ));

    // ==================== 公共方法 ====================

    /**
     * 检测段落是否为黑名单内容（封面、目录、页眉页脚）
     *
     * @param text 段落文本
     * @return true 如果是黑名单内容
     */
    public static boolean isBlacklisted(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        String normalized = normalizeCnTitle(text);

        // 1. TOC 检测
        if (isTocLine(normalized)) {
            return true;
        }

        // 2. 页眉页脚检测
        if (HEADER_FOOTER_PATTERN.matcher(normalized).find()) {
            return true;
        }

        // 3. 封面关键词检测
        for (String keyword : BLACKLIST_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检测是否为目录行
     *
     * @param text 段落文本（已归一化）
     * @return true 如果是目录行
     */
    public static boolean isTocLine(String text) {
        String s = text.replaceAll("\\s+", "");

        // 检查目录关键词
        for (String keyword : TOC_KEYWORDS) {
            if (s.contains(keyword)) {
                return true;
            }
        }

        // 检查省略号 + 页码模式（如："第一章 总则 ............... 12"）
        if (s.matches(".*(\\.{3,}|…{2,})\\s*\\d{1,4}$")) {
            return true;
        }

        return false;
    }

    /**
     * 中文标题文本归一化
     *
     * @param text 原始文本
     * @return 归一化后的文本
     */
    public static String normalizeCnTitle(String text) {
        if (text == null) return "";

        String s = text;

        // 1. 统一空白（保留中文括号、中文数字）
        s = s.replaceAll("\\s+", "");

        // 2. 去尾部点线
        s = s.replaceAll("[·•●◦∙．。]+$", "");

        // 3. 全半角统一（可选，这里保持原样）

        return s;
    }

    /**
     * 中文规范标题正则匹配（优先级3）
     *
     * @param normalizedText 归一化后的文本
     * @return RegexHit 对象，如果匹配成功；否则返回 null
     */
    public static RegexHit matchCnRegex(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) {
            return null;
        }

        // L1: 章/卷/册/编
        for (int i = 0; i < L1_PATTERNS.size(); i++) {
            if (L1_PATTERNS.get(i).matcher(normalizedText).matches()) {
                return new RegexHit(1, "L1-pattern-" + i);
            }
        }

        // L2: 节/常见大标题
        for (int i = 0; i < L2_PATTERNS.size(); i++) {
            if (L2_PATTERNS.get(i).matcher(normalizedText).matches()) {
                return new RegexHit(2, "L2-pattern-" + i);
            }
        }

        // L3: 条/款/项/数字编号
        for (int i = 0; i < L3_PATTERNS.size(); i++) {
            if (L3_PATTERNS.get(i).matcher(normalizedText).matches()) {
                return new RegexHit(3, "L3-pattern-" + i);
            }
        }

        // L4: 中文括号编号
        for (int i = 0; i < L4_PATTERNS.size(); i++) {
            if (L4_PATTERNS.get(i).matcher(normalizedText).matches()) {
                return new RegexHit(4, "L4-pattern-" + i);
            }
        }

        // L5: 小数点编号
        for (int i = 0; i < L5_PATTERNS.size(); i++) {
            if (L5_PATTERNS.get(i).matcher(normalizedText).matches()) {
                return new RegexHit(5, "L5-pattern-" + i);
            }
        }

        return null;
    }

    /**
     * 启发式判断增强（优先级4）
     * 支持增量打分
     *
     * @param para 段落对象
     * @param text 段落文本
     * @param normalizedText 归一化文本
     * @param avgBodyFontSize 正文平均字号（可选）
     * @return HeadingInfo 对象，如果判断为候选标题；否则返回 null
     */
    public static HeadingInfo detectHeuristicEnhanced(
            XWPFParagraph para, String text, String normalizedText, Double avgBodyFontSize) {

        List<XWPFRun> runs = para.getRuns();
        if (runs.isEmpty()) {
            return null;
        }

        // 获取第一个 Run 的格式
        XWPFRun firstRun = runs.get(0);
        boolean isBold = firstRun.isBold();
        int fontSize = firstRun.getFontSize();

        double score = 0.0;
        int level = 3;  // 默认保守层级
        List<String> signals = new ArrayList<>();

        // 1. 字号打分
        if (avgBodyFontSize != null && fontSize > 0) {
            if (fontSize >= avgBodyFontSize + 2.0) {
                score += 0.20;
                signals.add("fontSize+" + (fontSize - avgBodyFontSize.intValue()));
            }
        } else if (fontSize >= 16) {
            score += 0.20;
            signals.add("fontSize=" + fontSize);
        }

        // 2. 加粗打分
        if (isBold) {
            score += 0.10;
            signals.add("bold");
        }

        // 3. 居中对齐打分（如果有居中）
        // POI 中检查对齐方式：para.getAlignment()
        try {
            if (para.getAlignment() != null &&
                para.getAlignment().toString().toLowerCase().contains("center")) {
                score += 0.05;
                signals.add("centered");
            }
        } catch (Exception e) {
            // 忽略
        }

        // 4. 短文本打分
        if (text.length() < 25) {
            score += 0.05;
            signals.add("short<25");
        }

        // 5. 无句号打分
        if (!text.contains("。")) {
            score += 0.05;
            signals.add("no-period");
        }

        // 6. 包含标题关键词打分
        boolean hasKeyword = false;
        for (String keyword : TITLE_KEYWORDS) {
            if (normalizedText.contains(keyword)) {
                score += 0.05;
                signals.add("keyword:" + keyword);
                hasKeyword = true;
                break;
            }
        }

        // 限制最大分数为 0.70
        score = Math.min(score, 0.70);

        // 如果分数太低，不返回
        if (score < 0.30) {
            return null;
        }

        // 根据字号和关键词调整层级
        if (hasKeyword || (fontSize >= 18 && isBold)) {
            level = 2;
        } else if (fontSize >= 22 && isBold) {
            level = 1;
        }

        HeadingInfo info = new HeadingInfo(level, "heuristic", score);
        info.setScore(score);
        info.setInitialLevel(level);
        info.setCandidate(true);
        info.setSignals(signals);
        info.setEvidence("heuristic: score=" + String.format("%.2f", score));

        return info;
    }

    /**
     * 中文正则匹配结果
     */
    public static class RegexHit {
        private int level;
        private String patternId;

        public RegexHit(int level, String patternId) {
            this.level = level;
            this.patternId = patternId;
        }

        public int getLevel() { return level; }
        public String getPatternId() { return patternId; }
    }

    /**
     * 标题信息（简化版，仅用于内部）
     */
    public static class HeadingInfo {
        private Integer level;
        private String source;
        private double confidence;
        private double score;
        private Integer initialLevel;
        private boolean isCandidate;
        private boolean isToc;
        private String evidence;
        private List<String> signals;

        public HeadingInfo(Integer level, String source, double confidence) {
            this.level = level;
            this.source = source;
            this.confidence = confidence;
            this.score = confidence;
            this.initialLevel = level;
            this.isCandidate = false;
            this.isToc = false;
            this.signals = new ArrayList<>();
        }

        // Getters and Setters
        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }

        public Integer getInitialLevel() { return initialLevel; }
        public void setInitialLevel(Integer initialLevel) { this.initialLevel = initialLevel; }

        public boolean isCandidate() { return isCandidate; }
        public void setCandidate(boolean candidate) { isCandidate = candidate; }

        public boolean isToc() { return isToc; }
        public void setToc(boolean toc) { isToc = toc; }

        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }

        public List<String> getSignals() { return signals; }
        public void setSignals(List<String> signals) { this.signals = signals; }
        public void addSignal(String signal) {
            if (this.signals == null) this.signals = new ArrayList<>();
            this.signals.add(signal);
        }
    }
}