package com.example.docxserver.util;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

/**
 * 解析 DOCX 表格合并信息，生成一个索引：
 *   key = "t%03d-r%03d-c%03d"
 *   value = MergeAttr{colspan,rowspan,vmerge,anchorId,logicalCol}
 *
 * 说明：
 * - colspan 来自 gridSpan（默认1）
 * - rowspan 仅在 vmerge=RESTART 时 >=1（向下统计连续 CONTINUE）
 * - vmerge: NONE / RESTART / CONTINUE
 * - anchorId：RESTART=自身id("t001-r003-c004")；CONTINUE=向上最近RESTART的id；NONE=null
 * - logicalCol：把 gridSpan 展开后的逻辑列起点（从0起）
 */
public final class MergeUtils {

    public enum VMergeState { NONE, RESTART, CONTINUE }

    public static final class MergeAttr {
        public final int colspan;
        public final int rowspan;         // 仅 RESTART 时 >1；其他为 1
        public final VMergeState vmerge;
        public final String anchorId;     // 合并块锚点；NONE 时为 null
        public final int logicalCol;      // 本行展开后的逻辑列起点（从0起）

        public MergeAttr(int colspan, int rowspan, VMergeState vmerge, String anchorId, int logicalCol) {
            this.colspan = colspan;
            this.rowspan = rowspan;
            this.vmerge = vmerge;
            this.anchorId = anchorId;
            this.logicalCol = logicalCol;
        }
    }

    // 用于第一/二遍计算的内部结构（提到方法外，避免可见性问题）
    private static final class Temp {
        int r, cPhys, logicalCol, colspan, rowspan;
        VMergeState vm;
        String anchor;
        String id;
    }

    /**
     * 构建全局合并索引。
     * @return Map key: "t%03d-r%03d-c%03d"
     */
    public static Map<String, MergeAttr> buildMergeIndex(File docx) throws IOException {
        Map<String, MergeAttr> idx = new LinkedHashMap<>();
        try (FileInputStream fis = new FileInputStream(docx); XWPFDocument doc = new XWPFDocument(fis)) {
            int tableIdx = 0;
            for (IBodyElement be : doc.getBodyElements()) {
                if (!(be instanceof XWPFTable)) continue;
                XWPFTable table = (XWPFTable) be;
                buildForOneTable(table, tableIdx, idx);
                tableIdx++;
            }
        }
        return idx;
    }

    private static void buildForOneTable(XWPFTable table, int tableIdx, Map<String, MergeAttr> out) {
        // 1) 第一遍：逐行计算 logicalCol，并记录 vmerge 与 colspan（rowspan 先占 1）
        int rowIdx = 0;
        List<List<Temp>> grid = new ArrayList<>();

        for (XWPFTableRow row : table.getRows()) {
            int logicalCol = 0;
            int cellIdx = 0;
            List<Temp> rowTemps = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                int colspan = getGridSpan(cell);
                VMergeState vm = getVMergeState(cell);

                String id = String.format("t%03d-r%03d-c%03d", tableIdx + 1, rowIdx + 1, cellIdx + 1);

                Temp t = new Temp();
                t.r = rowIdx;
                t.cPhys = cellIdx;
                t.logicalCol = logicalCol;
                t.colspan = colspan;
                t.vm = vm;
                t.rowspan = 1;   // 先置 1；RESTART 稍后回填
                t.anchor = (vm == VMergeState.RESTART) ? id : null;
                t.id = id;

                rowTemps.add(t);
                logicalCol += colspan;
                cellIdx++;
            }
            grid.add(rowTemps);
            rowIdx++;
        }

        // 2) 第二遍：为 CONTINUE 回填 anchor；为 RESTART 计算 rowspan
        // 2.1 回填 anchor（向上找覆盖该 logicalCol 的最近 RESTART）
        for (int r = 0; r < grid.size(); r++) {
            for (Temp t : grid.get(r)) {
                if (t.vm != VMergeState.CONTINUE) continue;
                String anchor = null;
                for (int up = r - 1; up >= 0 && anchor == null; up--) {
                    for (Temp u : grid.get(up)) {
                        boolean covered = t.logicalCol >= u.logicalCol && t.logicalCol < u.logicalCol + u.colspan;
                        if (covered && u.vm == VMergeState.RESTART) { anchor = u.anchor; break; }
                    }
                }
                t.anchor = anchor; // 允许为 null（极少见畸形文档）
            }
        }
        // 2.2 计算 rowspan（对每个 RESTART，向下统计连续 CONTINUE）
        for (int r = 0; r < grid.size(); r++) {
            for (Temp t : grid.get(r)) {
                if (t.vm != VMergeState.RESTART) continue;
                int span = 1;
                for (int down = r + 1; down < grid.size(); down++) {
                    Temp cont = findByLogicalCol(grid.get(down), t.logicalCol);
                    if (cont != null && cont.vm == VMergeState.CONTINUE && Objects.equals(cont.anchor, t.anchor)) {
                        span++;
                    } else break;
                }
                t.rowspan = span;
            }
        }

        // 3) 写入最终索引
        for (List<Temp> rowTemps : grid) {
            for (Temp t : rowTemps) {
                out.put(t.id, new MergeAttr(
                        t.colspan,
                        t.rowspan,
                        t.vm,
                        t.anchor,
                        t.logicalCol
                ));
            }
        }
    }

    private static Temp findByLogicalCol(List<Temp> row, int logicalCol) {
        for (Temp t : row) {
            if (logicalCol >= t.logicalCol && logicalCol < t.logicalCol + t.colspan) return t;
        }
        return null;
    }

    private static int getGridSpan(XWPFTableCell cell) {
        CTTcPr pr = cell.getCTTc().getTcPr();
        if (pr != null && pr.isSetGridSpan()) {
            BigInteger v = pr.getGridSpan().getVal(); // 通常是 BigInteger
            return (v == null) ? 1 : v.intValue();
        }
        return 1;
    }

    private static VMergeState getVMergeState(XWPFTableCell cell) {
        CTTcPr pr = cell.getCTTc().getTcPr();
        if (pr == null || !pr.isSetVMerge()) return VMergeState.NONE;
        CTVMerge vm = pr.getVMerge();
        if (vm == null || !vm.isSetVal()) return VMergeState.CONTINUE; // <w:vMerge/> == CONTINUE
        STMerge.Enum v = vm.getVal();
        if (STMerge.RESTART.equals(v)) return VMergeState.RESTART;
        return VMergeState.CONTINUE;
    }
}
