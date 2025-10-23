package com.example.docxserver.util.docx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

/**
 * Docx文档结构分析结果
 *
 * 包含文档元数据、布局统计、文档树等信息
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocxAnalysisResult {

    @JsonProperty("doc_meta")
    private DocMeta docMeta;

    @JsonProperty("layout_stats")
    private LayoutStats layoutStats;

    @JsonProperty("entities")
    private Entities entities;

    @JsonProperty("toc")
    private List<TocEntry> toc;

    @JsonProperty("tree")
    private List<Section> tree;

    @JsonProperty("derived_features")
    private DerivedFeatures derivedFeatures;

    // Getters and Setters
    public DocMeta getDocMeta() { return docMeta; }
    public void setDocMeta(DocMeta docMeta) { this.docMeta = docMeta; }

    public LayoutStats getLayoutStats() { return layoutStats; }
    public void setLayoutStats(LayoutStats layoutStats) { this.layoutStats = layoutStats; }

    public Entities getEntities() { return entities; }
    public void setEntities(Entities entities) { this.entities = entities; }

    public List<TocEntry> getToc() { return toc; }
    public void setToc(List<TocEntry> toc) { this.toc = toc; }

    public List<Section> getTree() { return tree; }
    public void setTree(List<Section> tree) { this.tree = tree; }

    public DerivedFeatures getDerivedFeatures() { return derivedFeatures; }
    public void setDerivedFeatures(DerivedFeatures derivedFeatures) { this.derivedFeatures = derivedFeatures; }

    /**
     * 文档元数据
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocMeta {
        private String filename;

        @JsonProperty("title_coreprop")
        private String titleCoreprop;

        @JsonProperty("page_count")
        private Integer pageCount;

        @JsonProperty("word_count")
        private Integer wordCount;

        private String created;
        private String modified;

        // Getters and Setters
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }

        public String getTitleCoreprop() { return titleCoreprop; }
        public void setTitleCoreprop(String titleCoreprop) { this.titleCoreprop = titleCoreprop; }

        public Integer getPageCount() { return pageCount; }
        public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }

        public Integer getWordCount() { return wordCount; }
        public void setWordCount(Integer wordCount) { this.wordCount = wordCount; }

        public String getCreated() { return created; }
        public void setCreated(String created) { this.created = created; }

        public String getModified() { return modified; }
        public void setModified(String modified) { this.modified = modified; }
    }

    /**
     * 布局统计信息
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LayoutStats {
        @JsonProperty("heading_counts")
        private Map<String, Integer> headingCounts;

        @JsonProperty("table_count")
        private Integer tableCount;

        @JsonProperty("image_count")
        private Integer imageCount;

        @JsonProperty("table_density")
        private Double tableDensity;

        @JsonProperty("avg_heading_gap")
        private Integer avgHeadingGap;

        @JsonProperty("list_block_count")
        private Integer listBlockCount;

        // Getters and Setters
        public Map<String, Integer> getHeadingCounts() { return headingCounts; }
        public void setHeadingCounts(Map<String, Integer> headingCounts) { this.headingCounts = headingCounts; }

        public Integer getTableCount() { return tableCount; }
        public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }

        public Integer getImageCount() { return imageCount; }
        public void setImageCount(Integer imageCount) { this.imageCount = imageCount; }

        public Double getTableDensity() { return tableDensity; }
        public void setTableDensity(Double tableDensity) { this.tableDensity = tableDensity; }

        public Integer getAvgHeadingGap() { return avgHeadingGap; }
        public void setAvgHeadingGap(Integer avgHeadingGap) { this.avgHeadingGap = avgHeadingGap; }

        public Integer getListBlockCount() { return listBlockCount; }
        public void setListBlockCount(Integer listBlockCount) { this.listBlockCount = listBlockCount; }
    }

    /**
     * 实体信息（预留扩展点）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Entities {
        @JsonProperty("project_numbers")
        private List<String> projectNumbers;

        @JsonProperty("budget_amounts")
        private List<String> budgetAmounts;

        private List<String> dates;
        private List<String> orgs;

        // Getters and Setters
        public List<String> getProjectNumbers() { return projectNumbers; }
        public void setProjectNumbers(List<String> projectNumbers) { this.projectNumbers = projectNumbers; }

        public List<String> getBudgetAmounts() { return budgetAmounts; }
        public void setBudgetAmounts(List<String> budgetAmounts) { this.budgetAmounts = budgetAmounts; }

        public List<String> getDates() { return dates; }
        public void setDates(List<String> dates) { this.dates = dates; }

        public List<String> getOrgs() { return orgs; }
        public void setOrgs(List<String> orgs) { this.orgs = orgs; }
    }

    /**
     * 目录条目
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TocEntry {
        private Integer level;
        private String text;

        @JsonProperty("page_hint")
        private Integer pageHint;

        // Getters and Setters
        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public Integer getPageHint() { return pageHint; }
        public void setPageHint(Integer pageHint) { this.pageHint = pageHint; }
    }

    /**
     * 树节点基类
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TreeNode {
        private String type; // "section" 或 "block"

        // Getters and Setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    /**
     * 章节节点
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Section extends TreeNode {
        private Integer level;
        private String style;
        private String text;
        private String normalized;

        private Map<String, Object> features;
        private List<Block> blocks;
        private List<Section> children;

        public Section() {
            setType("section");
        }

        // Getters and Setters
        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getNormalized() { return normalized; }
        public void setNormalized(String normalized) { this.normalized = normalized; }

        public Map<String, Object> getFeatures() { return features; }
        public void setFeatures(Map<String, Object> features) { this.features = features; }

        public List<Block> getBlocks() { return blocks; }
        public void setBlocks(List<Block> blocks) { this.blocks = blocks; }

        public List<Section> getChildren() { return children; }
        public void setChildren(List<Section> children) { this.children = children; }
    }

    /**
     * 内容块（段落、表格等）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Block {
        private String type; // "paragraph", "table", "list", etc.

        // Getters and Setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    /**
     * 段落块
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParagraphBlock extends Block {
        private String text;
        private String style;
        private List<Run> runs;

        public ParagraphBlock() {
            setType("paragraph");
        }

        // Getters and Setters
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }

        public List<Run> getRuns() { return runs; }
        public void setRuns(List<Run> runs) { this.runs = runs; }
    }

    /**
     * Run（文本片段）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Run {
        private Boolean bold;
        private Boolean italic;

        @JsonProperty("fontSize")
        private Integer fontSize;

        private String text;

        // Getters and Setters
        public Boolean getBold() { return bold; }
        public void setBold(Boolean bold) { this.bold = bold; }

        public Boolean getItalic() { return italic; }
        public void setItalic(Boolean italic) { this.italic = italic; }

        public Integer getFontSize() { return fontSize; }
        public void setFontSize(Integer fontSize) { this.fontSize = fontSize; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    /**
     * 表格块
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableBlock extends Block {
        private String caption;

        @JsonProperty("header_row")
        private List<String> headerRow;

        @JsonProperty("body_row_count")
        private Integer bodyRowCount;

        @JsonProperty("detected_kind")
        private String detectedKind;

        @JsonProperty("evidence_terms")
        private List<String> evidenceTerms;

        public TableBlock() {
            setType("table");
        }

        // Getters and Setters
        public String getCaption() { return caption; }
        public void setCaption(String caption) { this.caption = caption; }

        public List<String> getHeaderRow() { return headerRow; }
        public void setHeaderRow(List<String> headerRow) { this.headerRow = headerRow; }

        public Integer getBodyRowCount() { return bodyRowCount; }
        public void setBodyRowCount(Integer bodyRowCount) { this.bodyRowCount = bodyRowCount; }

        public String getDetectedKind() { return detectedKind; }
        public void setDetectedKind(String detectedKind) { this.detectedKind = detectedKind; }

        public List<String> getEvidenceTerms() { return evidenceTerms; }
        public void setEvidenceTerms(List<String> evidenceTerms) { this.evidenceTerms = evidenceTerms; }
    }

    /**
     * 派生特征（预留扩展点）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DerivedFeatures {
        @JsonProperty("has_terms")
        private Map<String, Boolean> hasTerms;

        @JsonProperty("section_presence")
        private Map<String, Boolean> sectionPresence;

        @JsonProperty("top_evidence_spans")
        private List<String> topEvidenceSpans;

        // Getters and Setters
        public Map<String, Boolean> getHasTerms() { return hasTerms; }
        public void setHasTerms(Map<String, Boolean> hasTerms) { this.hasTerms = hasTerms; }

        public Map<String, Boolean> getSectionPresence() { return sectionPresence; }
        public void setSectionPresence(Map<String, Boolean> sectionPresence) { this.sectionPresence = sectionPresence; }

        public List<String> getTopEvidenceSpans() { return topEvidenceSpans; }
        public void setTopEvidenceSpans(List<String> topEvidenceSpans) { this.topEvidenceSpans = topEvidenceSpans; }
    }
}