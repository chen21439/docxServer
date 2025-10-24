package com.example.docxserver.util.docx;

import java.util.*;

/**
 * 章节树构建器（Step 2 实现）
 *
 * 功能：
 * - 按 score 阈值筛选标题（>= 0.75 为强标题）
 * - 使用章节栈构建树形结构
 * - 表格挂载到最近章节
 * - 处理层级关系
 *
 * 算法：
 * 1. 遍历所有 blocks（段落+表格）
 * 2. 段落：如果是强标题（score >= 0.75），入栈建树；否则作为普通段落挂载到当前章节
 * 3. 表格：挂载到栈顶章节
 * 4. 章节栈：维护当前层级关系，按 level 进出栈
 */
public class SectionTreeBuilder {

    /**
     * 强标题阈值
     */
    private static final double STRONG_HEADING_THRESHOLD = 0.75;

    /**
     * 弱标题阈值（Step 4 使用，当前仅识别）
     */
    private static final double WEAK_HEADING_THRESHOLD = 0.55;

    /**
     * 从 blocks 构建章节树
     *
     * @param blocks 平铺的块列表（段落+表格）
     * @return 顶层章节列表
     */
    public static List<DocxAnalysisResult.Section> buildSectionTree(List<DocxAnalysisResult.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return new ArrayList<>();
        }

        List<DocxAnalysisResult.Section> roots = new ArrayList<>();
        Deque<DocxAnalysisResult.Section> stack = new ArrayDeque<>();
        int sectionCounter = 0;

        for (DocxAnalysisResult.Block block : blocks) {
            if (block instanceof DocxAnalysisResult.ParagraphBlock) {
                DocxAnalysisResult.ParagraphBlock para = (DocxAnalysisResult.ParagraphBlock) block;
                DocxAnalysisResult.HeadingCandidate candidate = para.getHeadingCandidate();

                // 检查是否为强标题
                if (candidate != null && candidate.getScore() != null && candidate.getScore() >= STRONG_HEADING_THRESHOLD) {
                    // 创建章节节点
                    sectionCounter++;
                    DocxAnalysisResult.Section section = createSection(para, sectionCounter);

                    // 入栈逻辑
                    pushSection(stack, section, roots);

                } else {
                    // 普通段落或弱标题：挂载到当前章节的 blocks
                    if (!stack.isEmpty()) {
                        DocxAnalysisResult.Section currentSection = stack.peekLast();
                        if (currentSection.getBlocks() == null) {
                            currentSection.setBlocks(new ArrayList<>());
                        }
                        currentSection.getBlocks().add(para);
                    } else {
                        // 没有当前章节，作为独立块（不常见，但需处理）
                        // 可以选择忽略或创建虚拟根节点
                    }
                }

            } else if (block instanceof DocxAnalysisResult.TableBlock) {
                // 表格挂载到栈顶章节
                if (!stack.isEmpty()) {
                    DocxAnalysisResult.Section currentSection = stack.peekLast();
                    if (currentSection.getBlocks() == null) {
                        currentSection.setBlocks(new ArrayList<>());
                    }
                    currentSection.getBlocks().add(block);
                } else {
                    // 没有章节，表格作为顶层块（创建虚拟根节点或直接跳过）
                    // 这里选择忽略（政采文档通常先有标题）
                }
            }
        }

        return roots;
    }

    /**
     * 创建章节节点
     *
     * @param para 段落块
     * @param sectionIndex 章节索引
     * @return 章节节点
     */
    private static DocxAnalysisResult.Section createSection(DocxAnalysisResult.ParagraphBlock para, int sectionIndex) {
        DocxAnalysisResult.Section section = new DocxAnalysisResult.Section();

        // 基本信息
        section.setId(String.format("sec-%05d", sectionIndex));
        section.setText(para.getText());
        section.setStyle(para.getStyle());
        section.setNormalized(ChineseHeadingDetector.normalizeCnTitle(para.getText()));

        // 标题候选信息
        DocxAnalysisResult.HeadingCandidate candidate = para.getHeadingCandidate();
        if (candidate != null) {
            section.setLevel(candidate.getLevel());
            section.setHeadingSource(candidate.getSource());
            section.setHeadingConfidence(candidate.getConfidence());
            section.setScore(candidate.getScore());
            section.setInitialLevel(candidate.getInitialLevel());
            section.setIsCandidate(candidate.getIsCandidate());
            section.setEvidence(candidate.getEvidence());
            section.setSignals(candidate.getSignals());
        }

        // 标题特征
        DocxAnalysisResult.HeadingFeatures features = para.getHeadingFeatures();
        if (features != null) {
            section.setStyleId(features.getStyleId());
            section.setStyleName(features.getStyleName());
            section.setOutlineLvlRaw(features.getOutlineLvlRaw());
            section.setNumberingId(features.getNumberingId());
            section.setNumberingIlvl(features.getNumberingIlvl());
        }

        // 初始化子节点列表
        section.setChildren(new ArrayList<>());
        section.setBlocks(new ArrayList<>());

        return section;
    }

    /**
     * 章节入栈逻辑
     *
     * 规则：
     * - 若新标题 level 比栈顶小（如栈顶L2，新来L1）→ 弹栈至合适层级，再入栈
     * - 若新标题 level 相同或更大 → 作为栈顶的兄弟或子节点
     *
     * @param stack 章节栈
     * @param newSection 新章节
     * @param roots 顶层章节列表
     */
    private static void pushSection(
            Deque<DocxAnalysisResult.Section> stack,
            DocxAnalysisResult.Section newSection,
            List<DocxAnalysisResult.Section> roots) {

        int newLevel = newSection.getLevel() != null ? newSection.getLevel() : 99;

        // 弹栈：移除所有level >= newLevel 的节点
        while (!stack.isEmpty()) {
            DocxAnalysisResult.Section top = stack.peekLast();
            int topLevel = top.getLevel() != null ? top.getLevel() : 99;

            if (topLevel >= newLevel) {
                stack.pollLast();
            } else {
                break;
            }
        }

        // 入栈：作为栈顶的子节点，或作为根节点
        if (stack.isEmpty()) {
            // 顶层章节
            roots.add(newSection);
        } else {
            // 子章节
            DocxAnalysisResult.Section parent = stack.peekLast();
            if (parent.getChildren() == null) {
                parent.setChildren(new ArrayList<>());
            }
            parent.getChildren().add(newSection);
        }

        // 新节点入栈
        stack.offerLast(newSection);
    }

    /**
     * 统计章节树信息（用于调试）
     *
     * @param sections 章节列表
     * @return 统计信息
     */
    public static Map<String, Object> getTreeStats(List<DocxAnalysisResult.Section> sections) {
        Map<String, Object> stats = new LinkedHashMap<>();

        int totalSections = countSections(sections);
        int totalBlocks = countBlocks(sections);
        int maxDepth = getMaxDepth(sections, 1);

        stats.put("total_sections", totalSections);
        stats.put("total_blocks", totalBlocks);
        stats.put("max_depth", maxDepth);

        return stats;
    }

    private static int countSections(List<DocxAnalysisResult.Section> sections) {
        if (sections == null || sections.isEmpty()) return 0;

        int count = sections.size();
        for (DocxAnalysisResult.Section section : sections) {
            if (section.getChildren() != null) {
                count += countSections(section.getChildren());
            }
        }
        return count;
    }

    private static int countBlocks(List<DocxAnalysisResult.Section> sections) {
        if (sections == null || sections.isEmpty()) return 0;

        int count = 0;
        for (DocxAnalysisResult.Section section : sections) {
            if (section.getBlocks() != null) {
                count += section.getBlocks().size();
            }
            if (section.getChildren() != null) {
                count += countBlocks(section.getChildren());
            }
        }
        return count;
    }

    private static int getMaxDepth(List<DocxAnalysisResult.Section> sections, int currentDepth) {
        if (sections == null || sections.isEmpty()) return currentDepth - 1;

        int maxDepth = currentDepth;
        for (DocxAnalysisResult.Section section : sections) {
            if (section.getChildren() != null && !section.getChildren().isEmpty()) {
                int childDepth = getMaxDepth(section.getChildren(), currentDepth + 1);
                maxDepth = Math.max(maxDepth, childDepth);
            }
        }
        return maxDepth;
    }
}