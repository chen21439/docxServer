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

    @JsonProperty("blocks")
    private List<Block> blocks;

    @JsonProperty("sections")
    private List<Section> sections;  // 二次解析产物（当前为空）

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

    public List<Block> getBlocks() { return blocks; }
    public void setBlocks(List<Block> blocks) { this.blocks = blocks; }

    public List<Section> getSections() { return sections; }
    public void setSections(List<Section> sections) { this.sections = sections; }

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

        @JsonProperty("paragraph_count")
        private Integer paragraphCount;

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

        public Integer getParagraphCount() { return paragraphCount; }
        public void setParagraphCount(Integer paragraphCount) { this.paragraphCount = paragraphCount; }

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
        private String id;
        private Integer level;
        private String style;
        private String text;
        private String normalized;

        // 标题检测元数据
        @JsonProperty("heading_source")
        private String headingSource;       // 来源: "paragraph-outlineLvl", "style-outlineLvl", "style-name-map", "heuristic", "numbering-aux"

        @JsonProperty("heading_confidence")
        private Double headingConfidence;   // 置信度 (0.0-1.0)

        @JsonProperty("style_id")
        private String styleId;             // 样式ID

        @JsonProperty("style_name")
        private String styleName;           // 样式显示名

        @JsonProperty("outline_lvl_raw")
        private Integer outlineLvlRaw;      // 原始 outlineLvl 值 (0-based)

        @JsonProperty("numbering_id")
        private Integer numberingId;        // 编号ID

        @JsonProperty("numbering_ilvl")
        private Integer numberingIlvl;      // 编号级别

        @JsonProperty("is_candidate")
        private Boolean isCandidate;        // 是否为候选标题（待二次确认）

        private String evidence;            // 证据/关键词片段

        private Map<String, Object> features;
        private List<Block> blocks;
        private List<Section> children;

        public Section() {
            setType("section");
        }

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getNormalized() { return normalized; }
        public void setNormalized(String normalized) { this.normalized = normalized; }

        public String getHeadingSource() { return headingSource; }
        public void setHeadingSource(String headingSource) { this.headingSource = headingSource; }

        public Double getHeadingConfidence() { return headingConfidence; }
        public void setHeadingConfidence(Double headingConfidence) { this.headingConfidence = headingConfidence; }

        public String getStyleId() { return styleId; }
        public void setStyleId(String styleId) { this.styleId = styleId; }

        public String getStyleName() { return styleName; }
        public void setStyleName(String styleName) { this.styleName = styleName; }

        public Integer getOutlineLvlRaw() { return outlineLvlRaw; }
        public void setOutlineLvlRaw(Integer outlineLvlRaw) { this.outlineLvlRaw = outlineLvlRaw; }

        public Integer getNumberingId() { return numberingId; }
        public void setNumberingId(Integer numberingId) { this.numberingId = numberingId; }

        public Integer getNumberingIlvl() { return numberingIlvl; }
        public void setNumberingIlvl(Integer numberingIlvl) { this.numberingIlvl = numberingIlvl; }

        public Boolean getIsCandidate() { return isCandidate; }
        public void setIsCandidate(Boolean candidate) { isCandidate = candidate; }

        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }

        public Map<String, Object> getFeatures() { return features; }
        public void setFeatures(Map<String, Object> features) { this.features = features; }

        public List<Block> getBlocks() { return blocks; }
        public void setBlocks(List<Block> blocks) { this.blocks = blocks; }

        public List<Section> getChildren() { return children; }
        public void setChildren(List<Section> children) { this.children = children; }
    }

    /**
     * 标题原始特征（无决策，纯输入证据）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HeadingFeatures {
        @JsonProperty("style_id")
        private String styleId;

        @JsonProperty("style_name")
        private String styleName;

        @JsonProperty("outline_lvl_raw")
        private Integer outlineLvlRaw;

        @JsonProperty("numbering_id")
        private Integer numberingId;

        @JsonProperty("numbering_ilvl")
        private Integer numberingIlvl;

        @JsonProperty("font_max_size")
        private Integer fontMaxSize;

        @JsonProperty("is_bold")
        private Boolean isBold;

        @JsonProperty("text_length")
        private Integer textLength;

        // Getters and Setters
        public String getStyleId() { return styleId; }
        public void setStyleId(String styleId) { this.styleId = styleId; }

        public String getStyleName() { return styleName; }
        public void setStyleName(String styleName) { this.styleName = styleName; }

        public Integer getOutlineLvlRaw() { return outlineLvlRaw; }
        public void setOutlineLvlRaw(Integer outlineLvlRaw) { this.outlineLvlRaw = outlineLvlRaw; }

        public Integer getNumberingId() { return numberingId; }
        public void setNumberingId(Integer numberingId) { this.numberingId = numberingId; }

        public Integer getNumberingIlvl() { return numberingIlvl; }
        public void setNumberingIlvl(Integer numberingIlvl) { this.numberingIlvl = numberingIlvl; }

        public Integer getFontMaxSize() { return fontMaxSize; }
        public void setFontMaxSize(Integer fontMaxSize) { this.fontMaxSize = fontMaxSize; }

        public Boolean getIsBold() { return isBold; }
        public void setIsBold(Boolean isBold) { this.isBold = isBold; }

        public Integer getTextLength() { return textLength; }
        public void setTextLength(Integer textLength) { this.textLength = textLength; }
    }

    /**
     * 标题候选判断（一次解析产物）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HeadingCandidate {
        private String source;       // 检测来源：paragraph-outlineLvl, style-outlineLvl, style-name-map, heuristic
        private Integer level;       // 候选级别 (1-9)
        private Double confidence;   // 置信度 (0.0-1.0)

        @JsonProperty("is_candidate")
        private Boolean isCandidate; // 是否为候选标题（待二次确认）

        private String evidence;     // 证据/关键词片段

        // Getters and Setters
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }

        public Boolean getIsCandidate() { return isCandidate; }
        public void setIsCandidate(Boolean isCandidate) { this.isCandidate = isCandidate; }

        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }
    }

    /**
     * 标题最终决策（二次解析产物）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HeadingDecision {
        @JsonProperty("is_heading")
        private Boolean isHeading;   // 最终是否为标题

        private Integer level;       // 最终层级
        private Double confidence;   // 融合后的最终置信度
        private String rationale;    // 简短说明（供可观测性）

        @JsonProperty("section_id")
        private String sectionId;    // 该段作为标题时，绑定的 Section 节点 id

        // Getters and Setters
        public Boolean getIsHeading() { return isHeading; }
        public void setIsHeading(Boolean isHeading) { this.isHeading = isHeading; }

        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }

        public String getRationale() { return rationale; }
        public void setRationale(String rationale) { this.rationale = rationale; }

        public String getSectionId() { return sectionId; }
        public void setSectionId(String sectionId) { this.sectionId = sectionId; }
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
        private String id;
        private String text;
        private String style;

        // 标题相关字段
        @JsonProperty("heading_features")
        private HeadingFeatures headingFeatures;        // A. 原始版式/样式特征

        @JsonProperty("heading_candidate")
        private HeadingCandidate headingCandidate;      // B. 一次解析的候选判断

        @JsonProperty("heading_decision")
        private HeadingDecision headingDecision;        // C. 二次解析结果（当前为空）

        public ParagraphBlock() {
            setType("paragraph");
        }

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }

        public HeadingFeatures getHeadingFeatures() { return headingFeatures; }
        public void setHeadingFeatures(HeadingFeatures headingFeatures) { this.headingFeatures = headingFeatures; }

        public HeadingCandidate getHeadingCandidate() { return headingCandidate; }
        public void setHeadingCandidate(HeadingCandidate headingCandidate) { this.headingCandidate = headingCandidate; }

        public HeadingDecision getHeadingDecision() { return headingDecision; }
        public void setHeadingDecision(HeadingDecision headingDecision) { this.headingDecision = headingDecision; }
    }

    /**
     * Run（文本片段）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Run {
        private String id;
        private Boolean bold;
        private Boolean italic;

        @JsonProperty("fontSize")
        private Integer fontSize;

        private String text;

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

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
        private String id;
        private String caption;

        private Integer level;  // 嵌套层级：1=顶层，2=二层嵌套，依此类推

        @JsonProperty("parent_table_id")
        private String parentTableId;  // 父表格ID（仅嵌套表有值）

        private List<TableColumn> columns;

        @JsonProperty("body_row_count")
        private Integer bodyRowCount;

        @JsonProperty("detected_kind")
        private String detectedKind;

        @JsonProperty("evidence_terms")
        private List<String> evidenceTerms;

        @JsonProperty("rows")
        private List<TableRow> rows;  // 仅包含数据行（不包含表头行）

        private TableMetadata metadata;

        public TableBlock() {
            setType("table");
        }

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getCaption() { return caption; }
        public void setCaption(String caption) { this.caption = caption; }

        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public String getParentTableId() { return parentTableId; }
        public void setParentTableId(String parentTableId) { this.parentTableId = parentTableId; }

        public List<TableColumn> getColumns() { return columns; }
        public void setColumns(List<TableColumn> columns) { this.columns = columns; }

        public Integer getBodyRowCount() { return bodyRowCount; }
        public void setBodyRowCount(Integer bodyRowCount) { this.bodyRowCount = bodyRowCount; }

        public String getDetectedKind() { return detectedKind; }
        public void setDetectedKind(String detectedKind) { this.detectedKind = detectedKind; }

        public List<String> getEvidenceTerms() { return evidenceTerms; }
        public void setEvidenceTerms(List<String> evidenceTerms) { this.evidenceTerms = evidenceTerms; }

        public List<TableRow> getRows() { return rows; }
        public void setRows(List<TableRow> rows) { this.rows = rows; }

        public TableMetadata getMetadata() { return metadata; }
        public void setMetadata(TableMetadata metadata) { this.metadata = metadata; }
    }

    /**
     * 表格列
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableColumn {
        private String id;
        private String label;

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    /**
     * 表格元数据
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableMetadata {
        @JsonProperty("header_rows")
        private List<Integer> headerRows;  // 表头行索引（0-based）

        @JsonProperty("header_signals")
        private List<HeaderSignal> headerSignals;  // 表头检测信号

        // Getters and Setters
        public List<Integer> getHeaderRows() { return headerRows; }
        public void setHeaderRows(List<Integer> headerRows) { this.headerRows = headerRows; }

        public List<HeaderSignal> getHeaderSignals() { return headerSignals; }
        public void setHeaderSignals(List<HeaderSignal> headerSignals) { this.headerSignals = headerSignals; }
    }

    /**
     * 表头检测信号
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HeaderSignal {
        private String type;  // "tblHeader" 或 "firstRowStyle"
        private List<Integer> rows;  // 影响的行索引（0-based）
        private Double confidence;  // 置信度（0.0-1.0，预留字段）

        // Getters and Setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public List<Integer> getRows() { return rows; }
        public void setRows(List<Integer> rows) { this.rows = rows; }

        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
    }

    /**
     * 表格行
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableRow {
        private String id;
        private List<TableCell> cells;

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public List<TableCell> getCells() { return cells; }
        public void setCells(List<TableCell> cells) { this.cells = cells; }
    }

    /**
     * 表格单元格
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableCell {
        private String id;
        private String text;

        @JsonProperty("col_id")
        private String colId;

        @JsonProperty("nested_tables")
        private List<TableBlock> nestedTables;  // 单元格内的嵌套表格

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getColId() { return colId; }
        public void setColId(String colId) { this.colId = colId; }

        public List<TableBlock> getNestedTables() { return nestedTables; }
        public void setNestedTables(List<TableBlock> nestedTables) { this.nestedTables = nestedTables; }
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