# ParagraphMapper 重构说明文档

## 📋 重构概述

将原来的 `ParagraphMapper.java`（2597行）拆分成了 **16个** 功能清晰、职责单一的类。

### 重构目标
- ✅ 单一职责原则
- ✅ 提高可维护性
- ✅ 提高可测试性
- ✅ 提高代码复用性
- ✅ 降低类之间的耦合度

---

## 📁 文件结构

```
com/example/docxserver/util/taggedPDF/
│
├── dto/                                    # 数据传输对象包（7个类）
│   ├── DocxParagraph.java                  # DOCX段落数据模型
│   ├── ParagraphType.java                  # 段落类型枚举（NORMAL/TABLE_CELL）
│   ├── CellLocation.java                   # 单元格位置信息
│   ├── CellIdWithLocation.java             # 带位置的单元格ID（用于排序）
│   ├── McidPageInfo.java                   # MCID和页码信息
│   ├── Counter.java                        # 计数器（表格/段落计数）
│   └── TableGroup.java                     # 表格分组
│
├── PdfStructureUtils.java                  # PDF结构工具类
├── TextUtils.java                          # 文本处理工具类
├── IdUtils.java                            # ID处理工具类
│
├── McidCollector.java                      # MCID收集器
├── DocxParagraphParser.java                # DOCX段落解析器
├── PdfTextExtractor.java                   # PDF文本提取器
├── PdfTableExtractor.java                  # PDF表格提取器
├── PdfIdLocator.java                       # PDF ID定位器
├── ParagraphMappingService.java            # 段落映射服务
│
├── ParagraphMapperRefactored.java          # 重构后的主类（协调器）
└── ParagraphMapper.java                    # 原始类（保留备份）
```

---

## 🔧 各类功能说明

### 1️⃣ DTO包（7个类）

#### `DocxParagraph.java`
- **功能**：DOCX段落数据模型
- **字段**：id, text, type
- **方法**：isTableCell(), isNormalParagraph()

#### `ParagraphType.java`
- **功能**：段落类型枚举
- **值**：NORMAL（普通段落）、TABLE_CELL（表格单元格）

#### `CellLocation.java`
- **功能**：单元格位置信息
- **字段**：tableIndex, rowIndex, colIndex, paraIndex（均从0开始）

#### `CellIdWithLocation.java`
- **功能**：带位置的单元格ID
- **用途**：批量查找时排序优化

#### `McidPageInfo.java`
- **功能**：MCID和页码信息
- **字段**：mcidStr（格式：1,2,3|4,5,6）、pageStr（格式：1|2）

#### `Counter.java`
- **功能**：计数器
- **字段**：tableIndex（表格计数）、paragraphIndex（段落计数）

#### `TableGroup.java`
- **功能**：表格分组
- **字段**：indices（段落索引列表）

---

### 2️⃣ 工具类（3个类）

#### `PdfStructureUtils.java`
**功能**：PDF结构相关工具方法

**主要方法**：
- `isParagraphType(String structType)` - 判断是否是段落型元素
- `isTableRelatedElement(String structType)` - 判断是否是表格相关元素
- `isUnderTable(PDStructureElement element)` - 判断元素是否在表格下
- `resolvePageByParentTree(...)` - 通过ParentTree解析MCID对应的页面
- `calculateBoundingBox(List<TextPosition> glyphs)` - 计算边界框

#### `TextUtils.java`
**功能**：文本处理工具方法

**主要方法**：
- `normalizeText(String text)` - 文本归一化（用于比对）
- `escapeHtml(String text)` - HTML转义
- `truncate(String text, int maxLength)` - 文本截断
- `repeatString(String str, int count)` - 重复字符串（Java 8兼容）

#### `IdUtils.java`
**功能**：ID处理工具方法

**主要方法**：
- `parseCellId(String cellId)` - 解析单元格ID
- `formatTableId(int tableIndex)` - 格式化表格ID（t001）
- `formatRowId(String tableId, int rowIndex)` - 格式化行ID（t001-r007）
- `formatCellId(String rowId, int colIndex)` - 格式化单元格ID
- `formatParagraphId(int paraIndex)` - 格式化段落ID（p001）

---

### 3️⃣ 功能类（6个类）

#### `McidCollector.java`
**功能**：MCID收集器

**主要方法**：
- `collectTableMCIDs(...)` - 递归收集所有表格的MCID
- `collectMcidsByPage(...)` - 收集元素后代的所有MCID（按页分桶）
- `collectAllMcidsRecursive(...)` - 递归收集所有MCID（不分页）
- `formatMcidsWithPage(...)` - 格式化MCID和页码信息

**特点**：
- 支持按页分桶
- 支持排除表格MCID
- 支持ParentTree兜底查找

#### `DocxParagraphParser.java`
**功能**：DOCX段落解析器

**主要方法**：
- `parseDocxParagraphsFromTxt(String txtPath)` - 从TXT文件解析DOCX段落

**特点**：
- 使用Jsoup解析HTML
- 区分普通段落和表格单元格
- 合并单元格内的多个段落

#### `PdfTextExtractor.java`
**功能**：PDF文本提取器

**主要方法**：
- `extractTextFromElement(PDStructureElement element, PDDocument doc)` - 从结构元素提取文本
- `extractTextFromElement(..., String cellId, Map<PDPage, Set<Integer>> tableMCIDsByPage)` - 带过滤的提取
- `collectAllMcids(PDStructureElement element)` - 收集所有MCID

**特点**：
- 优先使用ActualText
- 使用MCID按页分桶提取
- 支持表格MCID过滤
- ActualText fallback机制

#### `PdfTableExtractor.java`
**功能**：PDF表格提取器

**主要方法**：
- `extractToXml(String taskId, String pdfPath, String outputDir)` - 提取表格和段落到XML

**特点**：
- 两遍遍历（先收集表格MCID，再提取内容）
- 生成带ID的XML结构
- 支持表格和表格外段落提取
- HTML转义输出

**输出格式**：
```xml
<table id="t001">
  <tr id="t001-r001">
    <td>
      <p id="t001-r001-c001-p001" mcid="1,2,3" page="1">文本内容</p>
    </td>
  </tr>
</table>
```

#### `PdfIdLocator.java`
**功能**：PDF ID定位器

**主要方法**：
- `findTextByIdInPdf(String pdfPath, List<String> cellIds)` - 批量根据ID查找文本

**特点**：
- 批量查找（按table→row→col排序优化）
- 结构树遍历定位
- 静默模式（不输出调试信息）
- 返回Map<ID, 文本>

#### `ParagraphMappingService.java`
**功能**：段落映射服务

**主要方法**：
- `buildParagraphMappingById(...)` - 通过ID直接匹配
- `buildParagraphMapping(...)` - 顺序文本匹配（旧方法）
- `printMappingResult(...)` - 打印映射结果统计

**特点**：
- ID直接匹配（100%准确）
- 文本匹配（兼容方法）
- 详细统计输出

---

### 4️⃣ 主类（协调器）

#### `ParagraphMapperRefactored.java`
**功能**：重构后的主类，作为入口和协调器

**主要方法**：
- `extractPdfToXml(String taskId, String pdfPath)` - 提取PDF到XML
- `parseDocxParagraphs(String docxTxtPath)` - 解析DOCX段落
- `buildMappingById(String docxTxtPath, String pdfTxtPath)` - 建立映射
- `findTextByIds(String pdfPath, List<String> cellIds)` - 批量查找文本
- `extractPdfTextWithStripper(String pdfPath)` - 提取PDF全文

**特点**：
- 协调各个功能类
- 提供便利方法
- 简化调用流程

---

## 💡 使用示例

### 示例1：从PDF提取表格结构到XML

```java
// 方式1：直接调用
String taskId = "1978018096320905217";
String pdfPath = "E:/path/to/pdf/1978018096320905217_A2b.pdf";
String outputDir = "E:/path/to/output";

PdfTableExtractor.extractToXml(taskId, pdfPath, outputDir);

// 方式2：使用协调器
ParagraphMapperRefactored.extractPdfToXml(taskId, pdfPath);
```

### 示例2：解析DOCX段落

```java
String docxTxtPath = "E:/path/to/1978018096320905217_docx.txt";

List<DocxParagraph> paragraphs = DocxParagraphParser.parseDocxParagraphsFromTxt(docxTxtPath);

// 遍历段落
for (DocxParagraph para : paragraphs) {
    System.out.println("ID: " + para.id);
    System.out.println("Text: " + para.text);
    System.out.println("Type: " + para.type);
    System.out.println("Is Table Cell: " + para.isTableCell());
    System.out.println();
}
```

### 示例3：建立DOCX和PDF的映射关系

```java
String docxTxtPath = "E:/path/to/1978018096320905217_docx.txt";
String pdfTxtPath = "E:/path/to/1978018096320905217_pdf_20250101_123456.txt";

// 解析DOCX段落
List<DocxParagraph> docxParagraphs = DocxParagraphParser.parseDocxParagraphsFromTxt(docxTxtPath);

// 建立映射（ID直接匹配）
Map<String, String> mapping = ParagraphMappingService.buildParagraphMappingById(docxParagraphs, pdfTxtPath);

// 查看映射结果
for (Map.Entry<String, String> entry : mapping.entrySet()) {
    System.out.println("DOCX ID: " + entry.getKey());
    System.out.println("PDF Text: " + entry.getValue());
    System.out.println();
}
```

### 示例4：根据ID在PDF中查找文本

```java
String pdfPath = "E:/path/to/1978018096320905217_A2b.pdf";
List<String> cellIds = Arrays.asList(
    "t001-r001-c001-p001",
    "t001-r002-c001-p001",
    "t002-r001-c001-p001"
);

Map<String, String> results = PdfIdLocator.findTextByIdInPdf(pdfPath, cellIds);

// 查看结果
for (Map.Entry<String, String> entry : results.entrySet()) {
    System.out.println("ID: " + entry.getKey());
    System.out.println("Text: " + entry.getValue());
    System.out.println();
}
```

### 示例5：使用工具类

```java
// ID解析
CellLocation location = IdUtils.parseCellId("t001-r007-c001-p001");
System.out.println("Table: " + location.tableIndex);  // 0
System.out.println("Row: " + location.rowIndex);      // 6
System.out.println("Col: " + location.colIndex);      // 0

// ID格式化
String tableId = IdUtils.formatTableId(0);        // "t001"
String rowId = IdUtils.formatRowId("t001", 6);    // "t001-r007"
String cellId = IdUtils.formatCellId("t001-r007", 0);  // "t001-r007-c001-p001"

// 文本处理
String normalized = TextUtils.normalizeText("Hello World!");  // "helloworld"
String escaped = TextUtils.escapeHtml("<div>Test</div>");    // "&lt;div&gt;Test&lt;/div&gt;"
String truncated = TextUtils.truncate("Very long text...", 10);  // "Very long ..."
```

---

## 🔄 迁移指南

### 从旧代码迁移到新代码

| 旧方法 | 新方法 |
|--------|--------|
| `ParagraphMapper.toXML(taskId)` | `PdfTableExtractor.extractToXml(taskId, pdfPath, outputDir)` |
| `ParagraphMapper.parseDocxParagraphsFromTxt(path)` | `DocxParagraphParser.parseDocxParagraphsFromTxt(path)` |
| `ParagraphMapper.buildParagraphMappingById(...)` | `ParagraphMappingService.buildParagraphMappingById(...)` |
| `ParagraphMapper.findTextByIdInPdf(...)` | `PdfIdLocator.findTextByIdInPdf(...)` |
| `ParagraphMapper.normalizeText(text)` | `TextUtils.normalizeText(text)` |
| `ParagraphMapper.parseCellId(id)` | `IdUtils.parseCellId(id)` |

---

## ✅ 重构优势

### 1. 可维护性
- **单一职责**：每个类只负责一个功能模块
- **代码量小**：每个类平均200-300行，易于理解
- **命名清晰**：类名和方法名准确描述功能

### 2. 可测试性
- **独立测试**：每个类可以独立编写单元测试
- **Mock友好**：依赖注入，易于Mock
- **覆盖率高**：小类更容易达到高测试覆盖率

### 3. 可复用性
- **工具类**：TextUtils、IdUtils可在其他模块复用
- **功能类**：各功能类可独立使用
- **DTO分离**：数据模型可用于序列化、网络传输等

### 4. 可扩展性
- **开闭原则**：新增功能只需新增类，不修改现有代码
- **接口扩展**：可以为功能类定义接口，支持多种实现
- **插件化**：功能模块可插拔

---

## 📝 注意事项

1. **原始类保留**：`ParagraphMapper.java` 保留作为备份，建议使用新类
2. **Java版本**：保持Java 8兼容性
3. **PDFBox版本**：使用PDFBox 3.0.2 API
4. **导入调整**：需要更新import语句，使用新的包结构

---

## 🎯 下一步计划

1. **编写单元测试**：为每个类编写完善的单元测试
2. **性能优化**：分析性能瓶颈，优化关键路径
3. **文档完善**：补充JavaDoc注释
4. **集成测试**：编写端到端集成测试
5. **迁移现有代码**：逐步将使用旧类的代码迁移到新类

---

## 📞 联系方式

如有问题或建议，请联系开发团队。