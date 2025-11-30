# PDF表格提取问题分析报告

## 一、核心问题

**DOCX中的t009表格（货物清单明细）在PDF提取结果中没有出现**

用户在PDF阅读器中能看到第16-17页有货物清单表格内容，但提取的txt文件中：
- 完全没有 `page="15"`、`page="16"`、`page="17"` 的表格数据
- 导致表格映射CSV显示 `t009 -> MISSING_IN_PDF`

---

## 二、数据源信息

| 项目 | 值 |
|-----|-----|
| 目录 | `E:\programFile\AIProgram\docxServer\pdf\task\1980815235174494210\` |
| PDF文件 | `1980815235174494210_A2b.pdf`（131页） |
| DOCX txt | 25个表格（t001-t025） |
| PDF txt输出 | 24个表格（t001-t024），**少1个** |

### 表格映射结果（_table_mapping.csv）

```
DOCX_order,DOCX_id,PDF_order,PDF_id,similarity,pos_diff,status
1,t001,1,t001,1.0,0,MATCH
2,t002,2,t002,1.0,0,MATCH
...
8,t008,8,t008,1.0,0,MATCH
9,t009,,,,,MISSING_IN_PDF        <-- 问题所在
10,t010,9,t009,1.0,1,MATCH       <-- 后续表格ID全部偏移1位
...
```

---

## 三、排查过程与发现

### 3.1 PDF页面内容验证

使用 `PDFTextStripper` 直接从PDF内容流提取文本（不依赖结构树）：

| 页码 | 内容 | 字符数 |
|-----|------|-------|
| 第15页 | "二、货物清单明细"（标题） | 18 |
| **第16页** | **表格数据：展架1、边几、书架...** | **1161** |
| **第17页** | **表格继续 + 备注说明** | **1228** |
| 第18页 | "三、实质性条款"表格 | 1477 |

**结论：第16-17页在PDF内容流中确实有货物清单表格内容**

### 3.2 PDF结构树诊断

遍历PDF结构树，检查每个页面关联的结构元素：

| 页码 | 结构树中的元素 | 问题 |
|-----|--------------|------|
| 第15页 | H2 + Span（标题） | 正常 |
| **第16页** | **没有找到任何结构元素！** | **异常** |
| 第17页 | Standard + Span（普通文本，不是Table） | 异常 |
| 第18页 | 2个Table元素 | 正常 |

### 3.3 结构树中Table元素分布

运行诊断程序 `PdfTablePageDiagnostic.java`，分析所有Table元素：

```
Table #1:  Page 1,  7行
Table #2:  Page 3,  7行
Table #3:  Page 4,  2行
Table #4:  Page 4,  14行
Table #5:  Page 5,  21行  (跨页到5,6,7,8)
Table #6:  Page 12, 13行
Table #7:  Page 12, 3行
Table #8:  Page 14, 2行   <-- DOCX t008
Table #9:  Page 18, 2行   <-- 实质性条款（不是货物清单！）
Table #10: Page 18, 500行 <-- 货物清单主体（但getPage()返回18而不是16）
...
共24个Table
```

**关键发现：**
- Table #10（500行货物清单）的 `element.getPage()` 返回 **Page 18**
- 但实际上这个表格的内容从 **Page 16** 就开始了
- 导致第16-17页的表格内容没有被关联到任何Table结构元素

### 3.4 StructParents属性检查

检查每个页面的 `StructParents` 属性（PDF规范中用于关联页面与结构树）：

```
第15页: StructParents=14 ✓ 有结构化内容
第16页: StructParents=15 ✓ 有结构化内容
第17页: StructParents=16 ✓ 有结构化内容
第18页: StructParents=17 ✓ 有结构化内容
```

**矛盾点：**
- 第16页有 `StructParents=15`，说明PDF规范层面该页应该有结构化内容
- 但遍历结构树时，没有任何 `PDStructureElement.getPage()` 返回第16页

### 3.5 输出文件验证

检查最终输出的txt文件：

```bash
# 检查表格txt中的页码分布
grep -oP 'page="\d+"' 1980815235174494210_pdf_20251130_105217.txt | sort | uniq -c

# 结果：没有 page="15", page="16", page="17"
```

```bash
# 检查段落txt
grep 'page="1[567]"' 1980815235174494210_pdf_paragraph_20251130_105217.txt

# 结果：
page="15" -> "二、货物清单明细"（标题，H2类型）
page="17" -> 备注说明（Standard类型，不是表格）
# 没有 page="16" 的任何内容
```

---

## 四、问题根源分析

### 4.1 PDF结构树页面关联错误

```
PDF内容流实际分布：
  Page 15: 标题 "二、货物清单明细"
  Page 16: 货物清单表格数据（第1-45行）
  Page 17: 货物清单表格数据（第46-55行）+ 备注
  Page 18: 实质性条款表格

PDF结构树中的关联：
  Table #10.getPage() = Page 18  （错误！应该是Page 16）
  Table #10涉及的页面 = [18,19,20,...,42]  （错误！应该包含16,17）
```

### 4.2 可能的原因

1. **LibreOffice转换问题**
   - LibreOffice在将DOCX转换为PDF/A Tagged PDF时
   - 跨页表格的结构元素页面属性设置错误
   - 表格的起始页被错误地设置为后面的页面

2. **提取代码逻辑问题**
   - `element.getPage()` 返回null时的兜底处理可能有问题
   - `resolvePageByParentTree()` 方法可能没有正确工作
   - MCID收集时可能遗漏了某些页面

---

## 五、代码调用链路

```
ParagraphMapperRefactored.main()
  └── extractPdfToXml(taskId, pdfPath)
        └── PdfTableExtractor.extractToXml()
              ├── 第一遍：McidCollector.collectTableMCIDs()  收集所有表格MCID
              └── 第二遍：extractTablesFromElement()        提取表格内容
                    └── 检查 element.getStructureType() == "Table"
                          └── 遍历 TR -> TD -> 提取文本
```

### 关键代码位置

| 文件 | 方法 | 作用 |
|-----|------|-----|
| `PdfTableExtractor.java:234` | `extractTablesFromElement()` | 判断是否是Table元素 |
| `McidCollector.java:124` | `collectMcidsRecursive()` | 使用`element.getPage()`获取页面 |
| `PdfStructureUtils.java:150` | `resolvePageByParentTree()` | `getPage()`返回null时的兜底 |

---

## 六、待继续排查

### 6.1 ParentTree映射检查
- 第16页的 StructParents=15 对应的ParentTree条目是什么？
- 这些MCID对应的结构元素类型是什么？

### 6.2 McidCollector逻辑验证
- 遍历Table #10时，它的子元素（TR/TD）的 `getPage()` 返回什么？
- 是否有MCID关联到第16页但被遗漏？

### 6.3 resolvePageByParentTree兜底逻辑
- 当 `element.getPage()` 返回null时，兜底方法是否被调用？
- 兜底方法的比较逻辑是否正确？

---

## 七、诊断工具

已创建的诊断程序（位于 `src/test/java/com/example/docxserver/`）：

| 程序 | 作用 |
|-----|------|
| `PdfStructureDiagnostic.java` | 遍历结构树，统计每页的结构元素类型 |
| `PdfPageContentDiagnostic.java` | 用PDFTextStripper直接提取页面文本 |
| `PdfTablePageDiagnostic.java` | 分析每个Table元素的页面关联 |
| `PdfMcidPageDiagnostic.java` | 检查StructParents属性 |
| `PdfParentTreeDiagnostic.java` | 检查ParentTree中的MCID映射 |

---

## 八、临时解决方案（待验证）

如果确认是PDF结构树页面关联错误导致的问题，可考虑：

1. **方案A：不依赖 `element.getPage()`**
   - 直接从ParentTree反查MCID对应的实际页面
   - 修改 `McidCollector` 的页面获取逻辑

2. **方案B：更换PDF转换工具**
   - 使用Adobe Acrobat或其他工具重新转换DOCX
   - 验证生成的Tagged PDF结构是否正确

3. **方案C：后处理补全**
   - 对比PDF内容流和结构树提取结果
   - 识别遗漏的页面，使用非结构化方式补全