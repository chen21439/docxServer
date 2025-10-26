package com.example.docxserver.util.untaggedPDF.pageLayout.dto;

import com.example.docxserver.util.untaggedPDF.pageLayout.PdfPageLayoutAnalyzer.Cell;

/**
 * 合并候选对象
 *
 * 表示一对可能需要合并的相邻单元格（纵向相邻）
 */
public class MergeCandidate implements Comparable<MergeCandidate> {

    /** 上方单元格 */
    public final Cell upper;

    /** 下方单元格 */
    public final Cell lower;

    /** 合并得分 */
    public double score;

    public MergeCandidate(Cell upper, Cell lower) {
        this.upper = upper;
        this.lower = lower;
        this.score = 0.0;
    }

    /**
     * 按得分降序排序（得分高的排在前面）
     */
    @Override
    public int compareTo(MergeCandidate other) {
        return Double.compare(other.score, this.score);
    }

    @Override
    public String toString() {
        return String.format("MergeCandidate{row=%d->%d, col=%d, score=%.3f, upper='%s', lower='%s'}",
                upper.rowIndex, lower.rowIndex, upper.colIndex, score,
                upper.text, lower.text);
    }
}