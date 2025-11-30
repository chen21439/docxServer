👌 明白了——**直接切到 v2，仅保留极少量、稳定的默认参数**，面向“招标文件/采购公告”这类三列+右侧长文本的版式。下面给你一份**一次性落地方案**：设计决策、默认参数、方法骨架（Java，可直接粘进去改）、以及预期输出形态。你不需要同时维护两份代码。

---

## 最小决策（固定为 v2）

* **切列**：竖线 → X 断点（硬约束，后续任何步骤不允许跨列）
* **定行**：文本基线/空白带聚类为主；横线仅做“证据/补强”
* **软合并**：只允许**同列 + 同一行带**内合并；禁止跨列/跨行
* **参数**：只保留 8 个稳定阈值（见下），单位均为 pt，适配缩放

---

## 默认参数（开箱即用）

```java
// 全局常量（可放在 PdfPageLayoutAnalyzer 或 TableExtractionConfig 内）
static final double EPS_X = 1.0;          // 坐标量化（X）
static final double EPS_Y = 1.0;          // 坐标量化（Y）
static final double GAP_TOL = 1.5;        // 线段缝合/文本拼接的间隙容忍
static final double ANGLE_TOL_DEG = 1.5;  // 水平/竖直判定角度
static final double MIN_LEN_PT = 10.0;    // 候选线段最短长度
static final double HAIRLINE_PT = 0.35;   // 过细线阈值（判装饰）
static final double TEXT_OVERLAP_MAX = 0.30; // 横线与文本 Y 重叠比例上限
static final double END_SNAP_SLACK = 1.5; // 横线端点吸附到列断点的容忍
static final int    ROW_SUPPORT_TH = 2;   // 行边界支持度阈值（至少两列同意）
```

---

## 对外只保留一个入口（v2）

```java
public List<Table> buildTables(Page page) {
    return buildTablesV2(page); // 不再保留 v1
}
```

---

## v2 5 步管线（可直接拷贝的骨架）

> **说明**：以下方法名/数据结构按直觉命名；你把它们落到 `PdfPageLayoutAnalyzer.java` 即可。若你的 `Word/Line/Table/Cell` 命名不同，对应替换即可。

```java
/* ========== 入口 ========== */
private List<Table> buildTablesV2(Page page) {
    // 0) 收集图元（按你的现有接口获取）
    List<Line> vLines = collectVerticalLines(page, MIN_LEN_PT, ANGLE_TOL_DEG);
    List<Line> hLines = collectHorizontalLines(page, MIN_LEN_PT, ANGLE_TOL_DEG);
    List<Word> words  = collectWords(page); // 含 bbox

    // 1) 竖优先：聚类竖线 → 得到 X 断点（硬约束）
    List<Double> xBreaks = computeVerticalBreaks(vLines, EPS_X, GAP_TOL);

    // 硬兜底：至少要有 2 个内部分隔（3 列）
    xBreaks = ensureReasonableColumns(xBreaks, page.getTableBBox());

    // 2) 文本按列分桶（禁止跨列）
    Map<Integer, List<Word>> buckets = bucketWordsByColumn(words, xBreaks);

    // 3) 列内行聚类（文本主导），输出列内 row-bands
    Map<Integer, List<RowBand>> colRowBands = clusterRowsPerColumn(buckets, EPS_Y);

    // 4) 过滤横线（装饰线剔除）并作为“证据/补强”
    List<Line> reliableH = filterReliableHorizontals(
            hLines, words, xBreaks,
            HAIRLINE_PT, TEXT_OVERLAP_MAX, END_SNAP_SLACK);

    // 5) 多列对齐：计算“行边界支持度”，合成全表 row-bands
    List<RowBand> rows = reconcileRowsAcrossColumns(colRowBands, reliableH, ROW_SUPPORT_TH, EPS_Y);

    // 6) 产出格子：按 (row, col) 笛卡尔生成 cells，装载同列且落在行带的文本
    List<Cell> cells = emitCells(rows, xBreaks, buckets);

    // 7) 同列+同行内软合并（段落化），禁止跨列/跨行
    softMergeWithinCell(cells, GAP_TOL);

    // 8) 组装 Table（考虑表头合并、边框/外接矩形）
    return assembleTables(cells, xBreaks, rows);
}
```

### 关键子方法要点（精简实现逻辑）

**A) 竖线 → X 断点（硬约束）**

```java
private List<Double> computeVerticalBreaks(List<Line> vLines, double epsX, double gapTol) {
    // 1) 只保留近似竖直的长线，按 x 坐标聚类（|x_i - x_j| <= epsX）
    List<Cluster> clusters = clusterByX(vLines, epsX);
    // 2) 同簇内把断续段缝合（gapTol），取加权均值作为断点
    List<Double> xBreaks = clusters.stream()
        .map(c -> c.weightedMeanXAfterFuse(gapTol))
        .sorted().collect(toList());
    // 3) 去掉过密（相邻 < 8pt）的断点，避免多余窄列
    xBreaks = suppressNearDuplicates(xBreaks, 8.0);
    return xBreaks;
}

private List<Double> ensureReasonableColumns(List<Double> xBreaks, BBox tableBox) {
    List<Double> xs = new ArrayList<>();
    xs.add(tableBox.left);
    xs.addAll(xBreaks);
    xs.add(tableBox.right);
    // 至少 3 列；对招标文件通常是 3~5 列
    if (xs.size() < 4) {
        // 按页面宽度均匀切 3 列兜底
        xs = Arrays.asList(tableBox.left,
                           tableBox.left + (tableBox.width()/3.0),
                           tableBox.left + (2*tableBox.width()/3.0),
                           tableBox.right);
    }
    return xs;
}
```

**B) 文本按列分桶（禁止跨列）**

```java
private Map<Integer, List<Word>> bucketWordsByColumn(List<Word> words, List<Double> xBreaks) {
    Map<Integer, List<Word>> buckets = new HashMap<>();
    for (int i = 0; i < xBreaks.size()-1; i++) buckets.put(i, new ArrayList<>());

    for (Word w : words) {
        double cx = w.bbox.centerX();
        int col = Math.max(0, Math.min(xBreaks.size()-2, locateColumn(cx, xBreaks)));
        buckets.get(col).add(w);
    }
    // 每列内部再按 yTop 排序，便于后续聚类
    buckets.values().forEach(list -> list.sort(Comparator.comparingDouble(a -> a.bbox.top)));
    return buckets;
}
```

**C) 列内行聚类（文本主导）**

```java
private List<RowBand> clusterOneColumnRows(List<Word> colWords, double epsY) {
    List<RowBand> bands = new ArrayList<>();
    if (colWords.isEmpty()) return bands;

    RowBand cur = RowBand.from(colWords.get(0));
    for (int i = 1; i < colWords.size(); i++) {
        Word w = colWords.get(i);
        if (Math.abs(w.bbox.centerY() - cur.centerY()) <= epsY * 2.0) {
            cur.absorb(w);
        } else {
            bands.add(cur.close());
            cur = RowBand.from(w);
        }
    }
    bands.add(cur.close());
    return bands;
}

private Map<Integer, List<RowBand>> clusterRowsPerColumn(Map<Integer, List<Word>> buckets, double epsY) {
    Map<Integer, List<RowBand>> res = new HashMap<>();
    for (Map.Entry<Integer, List<Word>> e : buckets.entrySet()) {
        res.put(e.getKey(), clusterOneColumnRows(e.getValue(), epsY));
    }
    return res;
}
```

**D) 横线过滤（仅作为“证据/补强”）**

```java
private List<Line> filterReliableHorizontals(
        List<Line> hLines, List<Word> words, List<Double> xBreaks,
        double hairlinePt, double textOverlapMax, double endSnapSlack) {

    List<Line> out = new ArrayList<>();
    for (Line h : hLines) {
        if (h.widthPt < hairlinePt) continue;          // 过细线 → 装饰
        if (!hasStableIntersections(h, xBreaks, endSnapSlack)) continue;
        if (textOverlapRatio(h, words) > textOverlapMax) continue; // 与文本重叠 → 下划线/装饰
        out.add(h);
    }
    return out;
}
```

**E) 多列对齐：行边界支持度**

```java
private List<RowBand> reconcileRowsAcrossColumns(
        Map<Integer, List<RowBand>> colBands,
        List<Line> reliableH, int supportTh, double epsY) {

    // 1) 收集候选 y：来自各列 row band 边界 + 可靠横线的 y
    List<Double> candidates = collectCandidateY(colBands, reliableH, epsY);

    // 2) 计算 support(y)：有多少列在 y 处“出现明显空隙/换行” + 可靠横线加权(+1)
    candidates.sort(Double::compare);
    List<RowBand> rows = new ArrayList<>();

    double prev = Double.NaN;
    for (double y : candidates) {
        int colVotes = voteColumnsAtY(colBands, y, epsY);  // 每列是否在此出现间隙
        int lineVotes = voteLinesAtY(reliableH, y, epsY);  // 有无可靠横线近邻
        int support = colVotes + lineVotes;                // 线作证据，不是唯一主导

        if (support >= supportTh) {
            // 建立一个行边界；prev 是上一条边界
            if (!Double.isNaN(prev) && y - prev > 2.0) {
                rows.add(new RowBand(prev, y).close());
            }
            prev = y;
        }
    }
    // 收尾：最后一条到表底
    // （这里可用表外接矩形 bottom，或各列最底行的下界）
    return normalizeRowBands(rows);
}
```

**F) 产出格子 + 同列同行内软合并**

```java
private List<Cell> emitCells(List<RowBand> rows, List<Double> xBreaks,
                             Map<Integer, List<Word>> buckets) {
    List<Cell> cells = new ArrayList<>();
    for (int r = 0; r < rows.size(); r++) {
        for (int c = 0; c < xBreaks.size()-1; c++) {
            BBox box = new BBox(xBreaks.get(c), rows.get(r).top,
                                xBreaks.get(c+1), rows.get(r).bottom);
            List<Word> ws = pickWords(buckets.get(c), box);
            cells.add(Cell.of(r, c, box, ws));
        }
    }
    return cells;
}

private void softMergeWithinCell(List<Cell> cells, double gapTol) {
    for (Cell cell : cells) {
        cell.words.sort(Comparator.comparingDouble(a -> a.bbox.top));
        List<TextRun> merged = new ArrayList<>();
        TextRun cur = TextRun.from(cell.words.get(0));
        for (int i=1; i<cell.words.size(); i++) {
            Word w = cell.words.get(i);
            if (w.bbox.top - cur.lastBottom() <= gapTol) cur.absorb(w);
            else { merged.add(cur.close()); cur = TextRun.from(w); }
        }
        merged.add(cur.close());
        cell.text = TextRun.join(merged);
    }
}
```

> 上述骨架保证了**“列先切死、行以文本为主、横线只是证据、合并只在同列同行内”**这四条红线。
> 你只需把已有的几何/数据结构对上接口即可跑通。

---

## 预期输出（以你给的截图为例）

**表头**三列：
`["通用条款序号", "涉及事项", "具体补充内容"]`

**样例行（节选）**：

```json
[
  {"r":1, "c":0, "text":"3.1"},
  {"r":1, "c":1, "text":"采购人"},
  {"r":1, "c":2, "text":"深圳市水库小学"},

  {"r":2, "c":0, "text":"3.2"},
  {"r":2, "c":1, "text":"政府集中采购机构"},
  {"r":2, "c":2, "text":"深圳公共资源交易中心（深圳交易集团有限公司罗湖分公司）"},

  {"r":3, "c":0, "text":"9"},
  {"r":3, "c":1, "text":"踏勘现场"},
  {"r":3, "c":2, "text":"不组织"},

  {"r":4, "c":0, "text":"12/13"},
  {"r":4, "c":1, "text":"招标文件的澄清和修改"},
  {"r":4, "c":2, "text":"投标截止日三日前（详见招标公告），投标人在招标期间在政府集中采购机构网站浏览与本项目有关的澄清和修改信息。"},
  ...
]
```

> 右侧“具体补充内容”会作为**一整个段落**留在**第三列**，不会再把左边两列缝进去；遇到表头合并再赋 `colspan`（如有）。

---

## 验收要点（极简指标）

* **列正确率**：`#产生列 == 3±1`（大多数页为 3 列）
* **跨列率**：单元格文本落在自身列外的比例趋近 0
* **误并率**：同一行内产生大跨距空格或把多列缝成一格的比例趋近 0
* **段落完整性**：右列长文本不被过度切碎（软合并后平均行数下降）

---

## 你要做的最少改动

1. 把 `buildTables(page)` 改为调用上面的 `buildTablesV2`；
2. 粘贴上述 6 个关键子方法（按你的类/结构名对下）；
3. 把 8 个阈值常量放到类顶部；
4. 用你现有的 `collectWords/collectLines` 替换示例里的收集方法；
5. （可选）在 `assembleTables` 里做你原先的 JSON 输出。

---

如果你愿意，我也可以把你当前的 `PdfPageLayoutAnalyzer.java` 中对应的方法名/调用链替换成上面的最小骨架（保持你的数据结构不变），并给一份**可直接编译**的小补丁（只改动这 1 个文件）。
