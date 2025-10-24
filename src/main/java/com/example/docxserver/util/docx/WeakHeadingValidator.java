package com.example.docxserver.util.docx;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弱标题验证器（Step 4 实现）
 *
 * 功能：
 * - 对 Step 1 中 score 在 0.55~0.75 的弱标题进行邻域确认
 * - 检查后续 5~8 个块中是否有同级编号或结构性元素
 * - 若命中则升级为强标题，否则保持为普通段落
 *
 * 算法：
 * 1. 扫描所有段落，找出弱标题候选（0.55 <= score < 0.75）
 * 2. 对每个弱标题，检查后续 5~8 个块
 * 3. 检测信号：
 *    - 同级编号出现（如（一）→（二）、1→2）
 *    - 表格/列表等结构性元素
 * 4. 根据命中情况，升级或降级标题
 */
public class WeakHeadingValidator {

    /**
     * 弱标题阈值（下限）
     */
    private static final double WEAK_HEADING_THRESHOLD_MIN = 0.55;

    /**
     * 弱标题阈值（上限）
     */
    private static final double WEAK_HEADING_THRESHOLD_MAX = 0.75;

    /**
     * 邻域窗口大小（检查后续多少个块）
     */
    private static final int NEIGHBORHOOD_WINDOW_SIZE = 8;

    /**
     * 验证并升级弱标题
     *
     * @param blocks 平铺的块列表（段落+表格）
     * @return 升级统计信息
     */
    public static ValidationResult validateWeakHeadings(List<DocxAnalysisResult.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return new ValidationResult();
        }

        ValidationResult result = new ValidationResult();
        int totalWeakHeadings = 0;
        int upgradedCount = 0;
        int demotedCount = 0;

        // 遍历所有块，找出弱标题
        for (int i = 0; i < blocks.size(); i++) {
            DocxAnalysisResult.Block block = blocks.get(i);

            if (!(block instanceof DocxAnalysisResult.ParagraphBlock)) {
                continue;
            }

            DocxAnalysisResult.ParagraphBlock para = (DocxAnalysisResult.ParagraphBlock) block;
            DocxAnalysisResult.HeadingCandidate candidate = para.getHeadingCandidate();

            // 检查是否为弱标题
            if (candidate == null || candidate.getScore() == null) {
                continue;
            }

            double score = candidate.getScore();
            if (score < WEAK_HEADING_THRESHOLD_MIN || score >= WEAK_HEADING_THRESHOLD_MAX) {
                continue;
            }

            totalWeakHeadings++;

            // 邻域确认
            NeighborhoodSignals signals = analyzeNeighborhood(blocks, i, para, candidate);

            // 添加信号到 candidate（无论升级还是降级都要记录）
            if (candidate.getSignals() == null) {
                candidate.setSignals(new ArrayList<>());
            }
            candidate.getSignals().addAll(signals.getSignals());

            // 判定是否升级
            if (signals.shouldUpgrade()) {
                // 升级为强标题
                candidate.setScore(0.75);  // 提升到强标题阈值
                upgradedCount++;
                result.addUpgradedHeading(para.getId(), para.getText(), signals);

            } else {
                // 降级为普通段落（保持原 score，不会被建树）
                demotedCount++;
                result.addDemotedHeading(para.getId(), para.getText(), signals);
            }
        }

        result.setTotalWeakHeadings(totalWeakHeadings);
        result.setUpgradedCount(upgradedCount);
        result.setDemotedCount(demotedCount);

        return result;
    }

    /**
     * 分析弱标题的邻域（后续 5~8 个块）
     * 新增小节型列表判定逻辑，避免将普通条款式列表误判为标题
     *
     * @param blocks 所有块
     * @param currentIndex 当前弱标题索引
     * @param para 当前段落
     * @param candidate 标题候选信息
     * @return 邻域信号
     */
    private static NeighborhoodSignals analyzeNeighborhood(
            List<DocxAnalysisResult.Block> blocks,
            int currentIndex,
            DocxAnalysisResult.ParagraphBlock para,
            DocxAnalysisResult.HeadingCandidate candidate) {

        NeighborhoodSignals signals = new NeighborhoodSignals();
        String currentText = para.getText();
        Integer currentLevel = candidate.getLevel();
        Integer currentIlvl = getNumberingIlvl(para);

        // 提取当前标题的编号模式
        NumberingPattern currentPattern = extractNumberingPattern(currentText);

        // 检查后续 NEIGHBORHOOD_WINDOW_SIZE 个块
        int endIndex = Math.min(currentIndex + NEIGHBORHOOD_WINDOW_SIZE + 1, blocks.size());

        // A. 小节型列表判定：检查窗口内同级编号项是否有子结构
        List<Integer> sameLevelIndices = new ArrayList<>();
        for (int i = currentIndex; i < endIndex; i++) {
            DocxAnalysisResult.Block block = blocks.get(i);
            if (!(block instanceof DocxAnalysisResult.ParagraphBlock)) {
                continue;
            }

            DocxAnalysisResult.ParagraphBlock p = (DocxAnalysisResult.ParagraphBlock) block;
            NumberingPattern pattern = extractNumberingPattern(p.getText());

            // 发现同级编号项（包括当前项）
            if (currentPattern != null && pattern != null &&
                currentPattern.type.equals(pattern.type)) {
                sameLevelIndices.add(i);
            }
        }

        // 检查每个同级编号项是否有子结构
        int itemsWithSubstructure = 0;
        for (int itemIndex : sameLevelIndices) {
            // 查看该编号项后，直到下一个同级编号项或窗口末的内容
            int nextSameLevelIndex = Integer.MAX_VALUE;
            for (int idx : sameLevelIndices) {
                if (idx > itemIndex) {
                    nextSameLevelIndex = idx;
                    break;
                }
            }
            int itemEndIndex = Math.min(nextSameLevelIndex, endIndex);

            boolean hasSubstructure = false;

            // 检查该项后的块
            for (int i = itemIndex + 1; i < itemEndIndex; i++) {
                DocxAnalysisResult.Block block = blocks.get(i);

                // 子结构信号1：紧随表格
                if (block instanceof DocxAnalysisResult.TableBlock) {
                    hasSubstructure = true;
                    signals.addSignal("item-with-table",
                        "item at " + itemIndex + " has following table");
                    break;
                }

                // 子结构信号2：更深缩进的段落（更高 ilvl）
                if (block instanceof DocxAnalysisResult.ParagraphBlock) {
                    DocxAnalysisResult.ParagraphBlock p = (DocxAnalysisResult.ParagraphBlock) block;
                    Integer ilvl = getNumberingIlvl(p);
                    if (currentIlvl != null && ilvl != null && ilvl > currentIlvl) {
                        hasSubstructure = true;
                        signals.addSignal("item-with-deeper-indent",
                            "item at " + itemIndex + " has deeper indented paragraph");
                        break;
                    }
                }
            }

            if (hasSubstructure) {
                itemsWithSubstructure++;
            }
        }

        signals.setItemsWithSubstructure(itemsWithSubstructure);
        signals.setTotalSameLevelItems(sameLevelIndices.size());

        // 判定是否为小节型列表（k ≥ 2）
        boolean isSmallSectionList = itemsWithSubstructure >= 2;
        signals.setSmallSectionList(isSmallSectionList);

        if (!isSmallSectionList) {
            // 普通条款式列表，不升格
            signals.addSignal("plain-list",
                String.format("only %d/%d items have substructure (need ≥2)",
                    itemsWithSubstructure, sameLevelIndices.size()));
            return signals;
        }

        // B. 文本形态过滤
        boolean isShortTitle = isShortTitle(currentText);
        boolean hasColonTitle = hasColonTitle(currentText);

        signals.setShortTitle(isShortTitle);
        signals.setColonTitle(hasColonTitle);

        if (isShortTitle) {
            signals.addSignal("short-title", "text length ≤ 24 without sentence-ending marks");
        }
        if (hasColonTitle) {
            signals.addSignal("colon-title", "ends with colon (:)");
        }

        // C. 结构承载（只有小节型列表才检查）
        for (int i = currentIndex + 1; i < endIndex; i++) {
            DocxAnalysisResult.Block nextBlock = blocks.get(i);

            // 检测表格
            if (nextBlock instanceof DocxAnalysisResult.TableBlock) {
                signals.addSignal("structural-element", "table found at offset " + (i - currentIndex));
                signals.incrementStructuralElementCount();
            }
        }

        // 检查首个后续块是否为长段落
        if (currentIndex + 1 < blocks.size()) {
            DocxAnalysisResult.Block firstNext = blocks.get(currentIndex + 1);
            if (firstNext instanceof DocxAnalysisResult.ParagraphBlock) {
                DocxAnalysisResult.ParagraphBlock firstPara = (DocxAnalysisResult.ParagraphBlock) firstNext;
                if (isLongPlainParagraph(firstPara)) {
                    signals.setFirstIsLongParagraph(true);
                    signals.addSignal("first-long-paragraph", "first following block is long plain paragraph");
                }
            }
        }

        return signals;
    }

    /**
     * 判断是否为短标题
     * 长度 ≤ 24 且不含句号、分号等句子结束标记
     */
    private static boolean isShortTitle(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();

        // 移除编号前缀后判断长度
        String withoutNumbering = trimmed.replaceFirst("^[（(]?[一二三四五六七八九十\\d]+[）)、\\.、]\\s*", "");

        return withoutNumbering.length() <= 24 &&
               !withoutNumbering.matches(".*[。；;!！？\\?].*");
    }

    /**
     * 判断是否以冒号结尾
     */
    private static boolean hasColonTitle(String text) {
        if (text == null) {
            return false;
        }
        return text.trim().endsWith("：") || text.trim().endsWith(":");
    }

    /**
     * 判断是否为长普通段落（超过40字且不含编号）
     */
    private static boolean isLongPlainParagraph(DocxAnalysisResult.ParagraphBlock para) {
        String text = para.getText();
        if (text == null) {
            return false;
        }

        String trimmed = text.trim();

        // 不含编号前缀
        boolean hasNumbering = trimmed.matches("^[（(]?[一二三四五六七八九十\\d]+[）)、\\.、].*");

        return trimmed.length() > 40 && !hasNumbering;
    }

    /**
     * 获取段落的编号层级（ilvl）
     */
    private static Integer getNumberingIlvl(DocxAnalysisResult.ParagraphBlock para) {
        if (para.getHeadingFeatures() != null) {
            return para.getHeadingFeatures().getNumberingIlvl();
        }
        return null;
    }

    /**
     * 提取编号模式
     *
     * @param text 文本
     * @return 编号模式（如果有）
     */
    private static NumberingPattern extractNumberingPattern(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String trimmed = text.trim();

        // 模式1：中文括号序号（一）、（二）、（三）...
        Pattern cnParenPattern = Pattern.compile("^[（(]([一二三四五六七八九十百千]+)[）)]");
        Matcher m1 = cnParenPattern.matcher(trimmed);
        if (m1.find()) {
            String cnNum = m1.group(1);
            int arabicNum = chineseToArabic(cnNum);
            return new NumberingPattern("cn-paren", cnNum, arabicNum);
        }

        // 模式2：阿拉伯数字顿号：1、2、3...
        Pattern arabicDunPattern = Pattern.compile("^(\\d+)、");
        Matcher m2 = arabicDunPattern.matcher(trimmed);
        if (m2.find()) {
            int num = Integer.parseInt(m2.group(1));
            return new NumberingPattern("arabic-dun", String.valueOf(num), num);
        }

        // 模式3：阿拉伯数字点号：1. 2. 3.
        Pattern arabicDotPattern = Pattern.compile("^(\\d+)\\.");
        Matcher m3 = arabicDotPattern.matcher(trimmed);
        if (m3.find()) {
            int num = Integer.parseInt(m3.group(1));
            return new NumberingPattern("arabic-dot", String.valueOf(num), num);
        }

        // 模式4：带括号的阿拉伯数字：(1) (2) (3)
        Pattern arabicParenPattern = Pattern.compile("^[（(](\\d+)[）)]");
        Matcher m4 = arabicParenPattern.matcher(trimmed);
        if (m4.find()) {
            int num = Integer.parseInt(m4.group(1));
            return new NumberingPattern("arabic-paren", String.valueOf(num), num);
        }

        return null;
    }

    /**
     * 中文数字转阿拉伯数字（简化版）
     */
    private static int chineseToArabic(String cnNum) {
        Map<Character, Integer> cnMap = new HashMap<>();
        cnMap.put('一', 1);
        cnMap.put('二', 2);
        cnMap.put('三', 3);
        cnMap.put('四', 4);
        cnMap.put('五', 5);
        cnMap.put('六', 6);
        cnMap.put('七', 7);
        cnMap.put('八', 8);
        cnMap.put('九', 9);
        cnMap.put('十', 10);

        if (cnNum.length() == 1) {
            return cnMap.getOrDefault(cnNum.charAt(0), 0);
        }

        // 处理"十X"、"X十"、"X十X"等模式
        if (cnNum.startsWith("十")) {
            if (cnNum.length() == 1) return 10;
            return 10 + cnMap.getOrDefault(cnNum.charAt(1), 0);
        }

        if (cnNum.endsWith("十")) {
            return cnMap.getOrDefault(cnNum.charAt(0), 0) * 10;
        }

        if (cnNum.contains("十")) {
            String[] parts = cnNum.split("十");
            int tens = cnMap.getOrDefault(parts[0].charAt(0), 0) * 10;
            int ones = parts.length > 1 ? cnMap.getOrDefault(parts[1].charAt(0), 0) : 0;
            return tens + ones;
        }

        return cnMap.getOrDefault(cnNum.charAt(0), 0);
    }

    /**
     * 编号模式
     */
    private static class NumberingPattern {
        private String type;        // 编号类型
        private String rawNumber;   // 原始编号字符串
        private int arabicValue;    // 阿拉伯数字值

        public NumberingPattern(String type, String rawNumber, int arabicValue) {
            this.type = type;
            this.rawNumber = rawNumber;
            this.arabicValue = arabicValue;
        }

        public String getRawNumber() {
            return rawNumber;
        }

        /**
         * 判断是否为同级后继编号
         */
        public boolean isSameLevelSuccessor(NumberingPattern other) {
            if (other == null || !this.type.equals(other.type)) {
                return false;
            }

            // 判断是否为连续编号（差值为1或2，允许跳号）
            int diff = other.arabicValue - this.arabicValue;
            return diff >= 1 && diff <= 2;
        }
    }

    /**
     * 邻域信号（新评分机制）
     */
    public static class NeighborhoodSignals {
        // 小节型列表判定相关
        private boolean isSmallSectionList = false;   // 是否为小节型列表
        private int itemsWithSubstructure = 0;        // 有子结构的编号项数量
        private int totalSameLevelItems = 0;          // 窗口内同级编号项总数

        // 文本形态过滤
        private boolean isShortTitle = false;         // 是否为短标题
        private boolean hasColonTitle = false;        // 是否以冒号结尾

        // 结构承载
        private int structuralElementCount = 0;       // 结构性元素数量（表格）
        private boolean firstIsLongParagraph = false; // 首个后续块是否为长段落

        // 信号列表
        private List<String> signals = new ArrayList<>();

        // 旧字段（兼容性保留）
        private int sameLevelCount = 0;
        private int sameLevelHeadingCount = 0;
        private int listItemCount = 0;

        public void setSmallSectionList(boolean isSmallSectionList) {
            this.isSmallSectionList = isSmallSectionList;
        }

        public void setItemsWithSubstructure(int itemsWithSubstructure) {
            this.itemsWithSubstructure = itemsWithSubstructure;
        }

        public void setTotalSameLevelItems(int totalSameLevelItems) {
            this.totalSameLevelItems = totalSameLevelItems;
        }

        public void setShortTitle(boolean isShortTitle) {
            this.isShortTitle = isShortTitle;
        }

        public void setColonTitle(boolean hasColonTitle) {
            this.hasColonTitle = hasColonTitle;
        }

        public void setFirstIsLongParagraph(boolean firstIsLongParagraph) {
            this.firstIsLongParagraph = firstIsLongParagraph;
        }

        public void incrementSameLevelCount() {
            this.sameLevelCount++;
        }

        public void incrementSameLevelHeadingCount() {
            this.sameLevelHeadingCount++;
        }

        public void incrementStructuralElementCount() {
            this.structuralElementCount++;
        }

        public void incrementListItemCount() {
            this.listItemCount++;
        }

        public void addSignal(String type, String description) {
            signals.add(type + ": " + description);
        }

        public List<String> getSignals() {
            return signals;
        }

        /**
         * 判断是否应该升级为强标题（新评分机制）
         *
         * 评分公式：
         * - 基础分：0.40（小节型列表）
         * - isShortTitle: +0.15
         * - hasColonTitle: +0.10
         * - hasTable: +0.20
         * - firstIsLongParagraph: -0.15
         * - 阈值：≥ 0.80 才升格
         */
        public boolean shouldUpgrade() {
            // 如果不是小节型列表，直接不升格
            if (!isSmallSectionList) {
                return false;
            }

            // 计算评分
            double score = 0.40;  // 基础分（小节型列表）

            // B. 文本形态加分
            if (isShortTitle) {
                score += 0.15;
            }
            if (hasColonTitle) {
                score += 0.10;
            }

            // C. 结构承载加分（只有小节型列表才加分）
            if (structuralElementCount >= 1) {
                score += 0.20;
            }

            // 首个块为长段落，减分
            if (firstIsLongParagraph) {
                score -= 0.15;
            }

            // 记录评分到信号
            addSignal("final-score", String.format("%.2f (threshold: 0.80)", score));

            // 阈值判定
            return score >= 0.80;
        }

        @Override
        public String toString() {
            return String.format(
                "isSmallSectionList=%b, itemsWithSubstructure=%d/%d, isShortTitle=%b, hasColonTitle=%b, structuralElementCount=%d, firstIsLongParagraph=%b",
                isSmallSectionList, itemsWithSubstructure, totalSameLevelItems,
                isShortTitle, hasColonTitle, structuralElementCount, firstIsLongParagraph);
        }
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        private int totalWeakHeadings = 0;
        private int upgradedCount = 0;
        private int demotedCount = 0;
        private List<HeadingDecision> upgradedHeadings = new ArrayList<>();
        private List<HeadingDecision> demotedHeadings = new ArrayList<>();

        public void setTotalWeakHeadings(int totalWeakHeadings) {
            this.totalWeakHeadings = totalWeakHeadings;
        }

        public void setUpgradedCount(int upgradedCount) {
            this.upgradedCount = upgradedCount;
        }

        public void setDemotedCount(int demotedCount) {
            this.demotedCount = demotedCount;
        }

        public void addUpgradedHeading(String id, String text, NeighborhoodSignals signals) {
            upgradedHeadings.add(new HeadingDecision(id, text, signals));
        }

        public void addDemotedHeading(String id, String text, NeighborhoodSignals signals) {
            demotedHeadings.add(new HeadingDecision(id, text, signals));
        }

        public int getTotalWeakHeadings() {
            return totalWeakHeadings;
        }

        public int getUpgradedCount() {
            return upgradedCount;
        }

        public int getDemotedCount() {
            return demotedCount;
        }

        public List<HeadingDecision> getUpgradedHeadings() {
            return upgradedHeadings;
        }

        public List<HeadingDecision> getDemotedHeadings() {
            return demotedHeadings;
        }
    }

    /**
     * 标题决策
     */
    public static class HeadingDecision {
        private String id;
        private String text;
        private NeighborhoodSignals signals;

        public HeadingDecision(String id, String text, NeighborhoodSignals signals) {
            this.id = id;
            this.text = text;
            this.signals = signals;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        public NeighborhoodSignals getSignals() {
            return signals;
        }
    }
}