# PageLayout DTO (Data Transfer Objects) Package

## 目录说明

本目录存放 PDF 页面布局分析相关的**数据传输对象**、**配置类**和**特征工程**组件。

## 设计原则

- **单一职责**：每个类只负责一个明确的功能
- **可扩展性**：支持新增特征和评分器，无需修改核心代码
- **可配置性**：关键参数支持外部配置文件覆盖
- **容错性**：配置加载失败时自动回退到默认值

## 目录结构

```
dto/
├── README.md                    # 本说明文件
├── PdfTableConfig.java          # 全局配置类（阈值、容差等）
├── Feature.java                 # 特征接口（可扩展）
├── features/                    # 特征实现类
│   ├── NoHLineBetween.java     # 特征：两行之间无水平线
│   ├── EdgeAlignSim.java       # 特征：左右边界对齐相似度
│   ├── BaselineGapKernel.java  # 特征：基线间距（em标准化）
│   ├── RowRplus1Empty.java     # 特征：下一行空比例
│   └── StyleMatch.java         # 特征：样式匹配（字体、旋转）
├── LinearScorer.java            # 线性打分器
├── PageContext.java             # 页面上下文（提供特征计算所需信息）
└── MergeCandidate.java          # 合并候选对象
```

## 使用示例

### 1. 加载配置

```java
PdfTableConfig config = PdfTableConfig.loadDefault();
// 或从 JSON 文件加载（可选覆盖）
// PdfTableConfig config = PdfTableConfig.loadFromJson("weights.json");
```

### 2. 创建打分器

```java
LinearScorer scorer = new LinearScorer(config);
```

### 3. 评估合并候选

```java
PageContext context = new PageContext(table, lines);
double score = scorer.score(upperCell, lowerCell, context);
if (score >= config.MERGE_SCORE_THRESHOLD) {
    // 执行软合并
}
```

## 配置文件格式（weights.json）

```json
{
  "MERGE_SCORE_THRESHOLD": 0.80,
  "EDGE_ALIGN_TOL_PT": 3.0,
  "BASELINE_GAP_EM_MIN": 1.0,
  "BASELINE_GAP_EM_MAX": 2.0,
  "ROW_EMPTY_RATIO": 0.80,

  "weights": {
    "no_hline_between": 1.00,
    "edge_align_sim": 0.80,
    "baseline_gap_em": 0.60,
    "row_rplus1_empty_ratio": 0.50,
    "style_match": 0.30
  }
}
```

## 扩展指南

### 添加新特征

1. 在 `features/` 目录创建新类，实现 `Feature` 接口
2. 在 `PdfTableConfig` 的默认权重中添加对应键值
3. 在 `LinearScorer` 的特征列表中注册（自动扫描或手动添加）

### 调整权重

无需修改代码，只需编辑 `weights.json` 文件即可。

## 维护日志

- 2025-01-XX: 初始化目录结构，实现核心特征和打分器