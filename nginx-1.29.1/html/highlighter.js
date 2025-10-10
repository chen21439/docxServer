// 元素定位高亮功能模块
(function () {
  "use strict";


  // 高亮指定范围的文本（支持嵌套标签，只使用规范化模式）
  function highlightText(elementId, start, end) {
    const timestamp = new Date().toLocaleString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    console.log(
      `[${timestamp}] 开始高亮处理 - ID: ${elementId}, start: ${start}, end: ${end}`
    );

    // 清除之前的高亮
    clearHighlight();

    const element = document.getElementById(elementId);
    if (!element) {
      showStatus('错误: 未找到ID为 "' + elementId + '" 的元素', "error");
      console.log(`[${timestamp}] 错误: 未找到元素`);
      return false;
    }

    // 使用 getTextByDOMOrder 确保文本顺序正确
    const originalText = getTextByDOMOrder(element);

    // 规范化：只去除换行符和制表符，保留普通空格，并合并连续空格
    const offsetMapping = { type: "normalize", map: [] };
    let normalizedPos = 0;

    for (let i = 0; i < originalText.length; i++) {
      const char = originalText[i];
      // 跳过换行符、回车符、制表符
      if (char === "\n" || char === "\r" || char === "\t") {
        continue;
      }
      // 跳过多余的空格（连续空格只保留一个）
      if (char === " " && i > 0 && originalText[i - 1] === " ") {
        continue;
      }
      offsetMapping.map[normalizedPos] = i;
      normalizedPos++;
    }
    // 去除换行、回车、制表符，合并连续空格
    const text = originalText
      .replace(/[\n\r\t]/g, "")
      .replace(/ +/g, " ")
      .trim();
    console.log(
      `[${timestamp}] 规范化文本 - 原始: ${originalText.length}字符, 规范化后: ${text.length}字符`
    );

    console.log(
      `[${timestamp}] 内容预览(前50字符): "${text.substring(0, 50)}..."`
    );
    console.log(
      `[${timestamp}] 高亮范围: [${start}, ${end}), 实际字符数: ${end - start}`
    );

    // 验证范围
    if (start < 0 || end > text.length || start >= end) {
      showStatus(
        `错误: 无效的范围 [${start}, ${end}) (文本长度: ${text.length})`,
        "error"
      );
      console.log(`[${timestamp}] 错误: 范围无效`);
      return false;
    }

    const highlightedText = text.substring(start, end);

    // 保存原始内容
    if (!element.hasAttribute("data-original-html")) {
      element.setAttribute("data-original-html", element.innerHTML);
    }

    // 使用递归方式处理节点，保留嵌套结构
    const newElement = element.cloneNode(true);
    let charCount = 0;
    const highlightClass = "text-highlight-" + Date.now();

    // 计算实际DOM中的高亮位置
    const actualStart = offsetMapping.map[start] || 0;
    // 修复：end位置应该映射到下一个字符的起始位置
    let actualEnd;
    if (end >= offsetMapping.map.length) {
      actualEnd = originalText.length;
    } else {
      actualEnd = offsetMapping.map[end] || originalText.length;
    }
    console.log(
      `[${timestamp}] 位置映射 - 规范化[${start}, ${end}) -> 原始DOM[${actualStart}, ${actualEnd})`
    );
    console.log(`[${timestamp}] 高亮文本: "${highlightedText}"`);

    function processNode(node) {
      if (node.nodeType === Node.TEXT_NODE) {
        const nodeText = node.textContent;
        const nodeStart = charCount;
        const nodeEnd = charCount + nodeText.length;

        // 检查当前文本节点是否与高亮范围有交集
        if (nodeEnd > actualStart && nodeStart < actualEnd) {
          const fragment = document.createDocumentFragment();

          // 计算在当前节点内的相对位置
          const relStart = Math.max(0, actualStart - nodeStart);
          const relEnd = Math.min(nodeText.length, actualEnd - nodeStart);

          // 前置文本
          if (relStart > 0) {
            fragment.appendChild(
              document.createTextNode(nodeText.substring(0, relStart))
            );
          }

          // 高亮文本
          const highlightSpan = document.createElement("span");
          highlightSpan.className = highlightClass;
          highlightSpan.style.cssText =
            "background-color: yellow; font-weight: bold;";
          highlightSpan.textContent = nodeText.substring(relStart, relEnd);
          fragment.appendChild(highlightSpan);

          // 后置文本
          if (relEnd < nodeText.length) {
            fragment.appendChild(
              document.createTextNode(nodeText.substring(relEnd))
            );
          }

          node.parentNode.replaceChild(fragment, node);
        }
        // 无论是否高亮，都要更新字符计数
        charCount += nodeText.length;
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        const childNodes = Array.from(node.childNodes);
        childNodes.forEach((child) => processNode(child));
      }
    }

    processNode(newElement);
    element.innerHTML = newElement.innerHTML;

    // 滚动到元素位置
    element.scrollIntoView({ behavior: "smooth", block: "center" });

    showStatus(
      `成功高亮 [${start}, ${end}): 共${end - start}个字符`,
      "success"
    );
    return true;
  }

  // 根据前缀查找所有匹配的span,合并文本并高亮
  // 使用场景：输入 t011-r168-c003，会匹配该单元格中所有段落（t011-r168-c003-p001, t011-r168-c003-p002...）
  // 将整个单元格的文本合并后，根据start和end进行高亮
  function highlightByPrefix(prefix, start, end) {
    const timestamp = new Date().toLocaleString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    console.log(
      `[${timestamp}] 开始前缀匹配高亮 - 前缀: ${prefix}, start: ${start}, end: ${end}`
    );

    // 清除之前的高亮
    clearHighlight();

    // 查找所有ID以该前缀开头的span元素
    const allSpans = document.querySelectorAll("span[id]");
    const matchingSpans = Array.from(allSpans).filter((span) =>
      span.id.startsWith(prefix)
    );

    if (matchingSpans.length === 0) {
      showStatus(`错误: 未找到前缀为 "${prefix}" 的元素`, "error");
      console.log(`[${timestamp}] 错误: 未找到匹配的元素`);
      return false;
    }

    console.log(`[${timestamp}] 找到 ${matchingSpans.length} 个匹配的span元素`);
    console.log(
      `[${timestamp}] 匹配的span IDs:`,
      matchingSpans.map((s) => s.id).join(", ")
    );

    // 找到包含这些span的td单元格容器
    let container = matchingSpans[0];
    while (container && container.tagName !== "TD") {
      container = container.parentElement;
    }

    if (!container) {
      showStatus(`错误: 未找到包含这些元素的单元格`, "error");
      console.log(`[${timestamp}] 错误: 未找到td容器`);
      return false;
    }

    console.log(`[${timestamp}] 找到容器: ${container.tagName}`);

    // 获取容器的原始文本
    // 使用 getTextByDOMOrder 确保文本顺序正确
    const originalText = getTextByDOMOrder(container);

    // 规范化：只去除换行符和制表符，保留普通空格，并合并连续空格
    const offsetMapping = { type: "normalize", map: [] };
    let normalizedPos = 0;

    for (let i = 0; i < originalText.length; i++) {
      const char = originalText[i];
      // 跳过换行符、回车符、制表符
      if (char === "\n" || char === "\r" || char === "\t") {
        continue;
      }
      // 跳过多余的空格（连续空格只保留一个）
      if (char === " " && i > 0 && originalText[i - 1] === " ") {
        continue;
      }
      offsetMapping.map[normalizedPos] = i;
      normalizedPos++;
    }

    // 去除换行、回车、制表符，合并连续空格
    const text = originalText
      .replace(/[\n\r\t]/g, "")
      .replace(/ +/g, " ")
      .trim();
    console.log(
      `[${timestamp}] 容器规范化文本 - 原始: ${originalText.length}字符, 规范化后: ${text.length}字符`
    );

    console.log(
      `[${timestamp}] 内容预览(前50字符): "${text.substring(0, 50)}..."`
    );
    console.log(
      `[${timestamp}] 高亮范围: [${start}, ${end}), 实际字符数: ${end - start}`
    );

    // 验证范围
    if (start < 0 || end > text.length || start >= end) {
      showStatus(
        `错误: 无效的范围 [${start}, ${end}) (文本长度: ${text.length})`,
        "error"
      );
      console.log(`[${timestamp}] 错误: 范围无效`);
      return false;
    }

    const highlightedText = text.substring(start, end);

    // 保存原始内容
    if (!container.hasAttribute("data-original-html")) {
      container.setAttribute("data-original-html", container.innerHTML);
    }

    // 使用递归方式处理节点，保留嵌套结构
    const newContainer = container.cloneNode(true);
    let charCount = 0;
    const highlightClass = "text-highlight-" + Date.now();

    // 计算实际DOM中的高亮位置
    const actualStart = offsetMapping.map[start] || 0;
    // 修复：end位置应该映射到下一个字符的起始位置
    let actualEnd;
    if (end >= offsetMapping.map.length) {
      actualEnd = originalText.length;
    } else {
      actualEnd = offsetMapping.map[end] || originalText.length;
    }
    console.log(
      `[${timestamp}] 位置映射 - 规范化[${start}, ${end}) -> 原始DOM[${actualStart}, ${actualEnd})`
    );
    console.log(`[${timestamp}] 高亮文本: "${highlightedText}"`);

    function processNode(node) {
      if (node.nodeType === Node.TEXT_NODE) {
        const nodeText = node.textContent;
        const nodeStart = charCount;
        const nodeEnd = charCount + nodeText.length;

        // 检查当前文本节点是否与高亮范围有交集
        if (nodeEnd > actualStart && nodeStart < actualEnd) {
          const fragment = document.createDocumentFragment();

          // 计算在当前节点内的相对位置
          const relStart = Math.max(0, actualStart - nodeStart);
          const relEnd = Math.min(nodeText.length, actualEnd - nodeStart);

          // 前置文本
          if (relStart > 0) {
            fragment.appendChild(
              document.createTextNode(nodeText.substring(0, relStart))
            );
          }

          // 高亮文本
          const highlightSpan = document.createElement("span");
          highlightSpan.className = highlightClass;
          highlightSpan.style.cssText =
            "background-color: yellow; font-weight: bold;";
          highlightSpan.textContent = nodeText.substring(relStart, relEnd);
          fragment.appendChild(highlightSpan);

          // 后置文本
          if (relEnd < nodeText.length) {
            fragment.appendChild(
              document.createTextNode(nodeText.substring(relEnd))
            );
          }

          node.parentNode.replaceChild(fragment, node);
        }
        // 无论是否高亮，都要更新字符计数
        charCount += nodeText.length;
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        const childNodes = Array.from(node.childNodes);
        childNodes.forEach((child) => processNode(child));
      }
    }

    processNode(newContainer);
    container.innerHTML = newContainer.innerHTML;

    // 滚动到容器位置
    container.scrollIntoView({ behavior: "smooth", block: "center" });

    showStatus(
      `成功高亮 [${start}, ${end}): 共${end - start}个字符 (匹配${
        matchingSpans.length
      }个span)`,
      "success"
    );
    return true;
  }

  // 清除所有高亮
  function clearHighlight() {
    const highlighted = document.querySelectorAll("[data-original-html]");
    highlighted.forEach((element) => {
      element.innerHTML = element.getAttribute("data-original-html");
      element.removeAttribute("data-original-html");
    });
  }

  // 加载风险列表（暴露到全局，供外部调用）
  async function loadRiskList() {
    try {
      showStatus("正在加载风险列表...", "success");

      // 从浏览器 URL 中获取当前 HTML 文件名
      const currentUrl = window.location.pathname;
      const htmlFileName = currentUrl.substring(currentUrl.lastIndexOf('/') + 1);

      // 提取文件名（去掉 .html 后缀），然后拼接 .json
      let jsonFileName = 'list1.json'; // 默认值
      if (htmlFileName && htmlFileName.endsWith('.html')) {
        const baseFileName = htmlFileName.replace('.html', '');
        jsonFileName = baseFileName + '.json';
      }

      const jsonUrl = `http://localhost:80/${jsonFileName}`;
      console.log(`正在加载 JSON 文件: ${jsonUrl}`);

      const response = await fetch(jsonUrl);
      if (!response.ok) {
        throw new Error("加载失败: " + response.statusText);
      }
      const data = await response.json();

      if (data.success && data.data && data.data.dataList) {
        displayRiskList(data.data);
        showStatus(
          `成功加载 ${data.data.dataList.length} 条风险记录`,
          "success"
        );
      } else {
        throw new Error("数据格式错误");
      }
    } catch (error) {
      showStatus("加载失败: " + error.message, "error");
      console.error("加载风险列表失败:", error);
    }
  }

  // ============================================================================
  // 公共方法：按 DOM 顺序提取文本
  // ============================================================================
  // 解决 textContent 提取顺序不正确的问题
  // 例如: <span>A<span>B</span>C</span>
  // textContent 可能提取为 "ABC" 或 "BAC"，取决于浏览器实现
  // 此函数保证按 DOM 树的 childNodes 顺序提取，结果总是 "ABC"
  function getTextByDOMOrder(element) {
    let result = '';

    // 按顺序遍历所有子节点
    element.childNodes.forEach(node => {
      if (node.nodeType === Node.TEXT_NODE) {
        // 文本节点：直接添加文本内容
        result += node.nodeValue;
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        // 元素节点：递归获取其文本
        result += getTextByDOMOrder(node);
      }
    });

    return result;
  }

  // ============================================================================
  // 公共方法：规范化文本（轻量级）
  // ============================================================================
  // 用于位置计算和显示，保留基本结构
  // 去除换行、回车、制表符，合并连续空格，去除首尾空格
  function normalizeText(text) {
    return text
      .replace(/[\n\r\t]/g, "")  // 去除换行、回车、制表符
      .replace(/ +/g, " ")        // 合并连续空格为单个空格
      .trim();                     // 去除首尾空格
  }

  // ============================================================================
  // 公共方法：严格规范化文本（用于匹配）
  // ============================================================================
  // 移除所有空白字符，用于文本匹配和验证
  function strictNormalizeText(text) {
    return text.replace(/\s+/g, "");  // 去除所有空白字符（空格、换行、制表符等）
  }

  // ============================================================================
  // 公共方法：根据 pid 查找匹配的 span 元素
  // ============================================================================
  function findMatchingSpans(pid) {
    const allSpans = document.querySelectorAll("span[id]");
    const pidParts = pid.split("-");
    let matchingSpans = [];

    // 1. 优先尝试精确匹配
    matchingSpans = Array.from(allSpans).filter(s => s.id === pid);
    if (matchingSpans.length > 0) {
      return { spans: matchingSpans, method: "精确匹配" };
    }

    // 2. 根据格式选择匹配策略
    if (pidParts.length === 4 && pidParts[0].startsWith("t") && pidParts[3].startsWith("p")) {
      // t格式: t005-r015-c005-p001 -> 匹配整个单元格 t005-r015-c005-pXXX-rXXX
      const cellPrefix = pidParts.slice(0, 3).join("-");
      matchingSpans = Array.from(allSpans).filter(s => {
        if (!s.id.startsWith(cellPrefix + "-")) return false;
        const parts = s.id.split("-");
        return parts.length >= 5 && parts[3].startsWith("p") && parts[4].startsWith("r");
      });
      return { spans: matchingSpans, method: `单元格前缀匹配 ("${cellPrefix}-pXXX-rXXX")` };
    } else if (pidParts.length >= 2 && pidParts[0].startsWith("p")) {
      // p格式: p-00097 -> 匹配 p-00097-r-XXX
      matchingSpans = Array.from(allSpans).filter(s => {
        return s.id.startsWith(pid + "-") && s.id.split("-").length >= 3;
      });
      return { spans: matchingSpans, method: "p-格式前缀匹配 (pid + '-')" };
    } else {
      // 其他格式：尝试通用前缀匹配
      matchingSpans = Array.from(allSpans).filter(s => s.id.startsWith(pid + "-"));
      return { spans: matchingSpans, method: "通用前缀匹配 (pid + '-')" };
    }
  }

  // ============================================================================
  // 公共方法：过滤嵌套的 span（只保留顶层 span）
  // ============================================================================
  function filterTopLevelSpans(spans) {
    return spans.filter((spanA) => {
      return !spans.some((spanB) => {
        return spanA !== spanB && spanB.contains(spanA);
      });
    });
  }

  // ============================================================================
  // 公共方法：根据匹配的 span 获取容器和容器文本
  // ============================================================================
  function getContainerAndText(pid, matchingSpans) {
    const pidParts = pid.split("-");
    const targetTag = pidParts[0].startsWith("t") ? "TD" : "P";

    // 找到容器
    let container = matchingSpans[0];
    while (container && container.tagName !== targetTag && container.tagName !== "BODY") {
      container = container.parentElement;
    }

    if (!container || container.tagName === "BODY") {
      return { container: null, text: "", rawText: "", spanTextMap: [] };
    }

    // 从容器直接提取完整文本（包括所有子节点，不只是匹配的 span）
    // 这样可以包含那些没有 id 的文本节点（如 "，得20分；"）
    const rawText = getTextByDOMOrder(container);

    // 记录每个匹配的 span 的文本信息（用于高亮时的精确定位）
    const spanTextMap = [];
    let currentPosRaw = 0;           // 原始文本位置
    let currentPosNormalized = 0;     // 轻量规范化位置
    let currentPosStrict = 0;         // 严格规范化位置

    // 递归遍历容器的所有子节点，找到匹配的 span 并记录位置
    function traverseAndMap(node) {
      if (node.nodeType === Node.TEXT_NODE) {
        // 文本节点：更新所有位置
        const nodeRawText = node.nodeValue;
        const nodeNormalizedText = normalizeText(nodeRawText);
        const nodeStrictText = strictNormalizeText(nodeRawText);

        currentPosRaw += nodeRawText.length;
        currentPosNormalized += nodeNormalizedText.length;
        currentPosStrict += nodeStrictText.length;
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        // 检查是否是我们匹配的 span
        const isMatchingSpan = matchingSpans.some(s => s === node);

        if (isMatchingSpan) {
          // 记录这个 span 的信息（所有三种规范化）
          const spanRawText = getTextByDOMOrder(node);
          const spanNormalizedText = normalizeText(spanRawText);
          const spanStrictText = strictNormalizeText(spanRawText);

          spanTextMap.push({
            span: node,
            rawText: spanRawText,
            normalizedText: spanNormalizedText,
            strictText: spanStrictText,
            rawStart: currentPosRaw,
            rawEnd: currentPosRaw + spanRawText.length,
            normalizedStart: currentPosNormalized,
            normalizedEnd: currentPosNormalized + spanNormalizedText.length,
            strictStart: currentPosStrict,
            strictEnd: currentPosStrict + spanStrictText.length
          });

          // 更新位置（跳过这个 span 的内容）
          currentPosRaw += spanRawText.length;
          currentPosNormalized += spanNormalizedText.length;
          currentPosStrict += spanStrictText.length;
        } else {
          // 不是匹配的 span，递归处理子节点
          node.childNodes.forEach(child => traverseAndMap(child));
        }
      }
    }

    // 执行遍历和映射
    container.childNodes.forEach(child => traverseAndMap(child));

    // 规范化整个容器文本（轻量级和严格）
    const normalizedText = normalizeText(rawText);
    const strictText = strictNormalizeText(rawText);

    return {
      container,
      text: normalizedText,      // 轻量规范化文本
      strictText: strictText,    // 严格规范化文本（用于匹配）
      rawText: rawText,          // 原始文本（包含所有文本节点）
      spanTextMap: spanTextMap   // 每个匹配span的文本映射信息（包含所有规范化级别）
    };
  }

  // ============================================================================
  // 验证函数 - 匹配逻辑说明
  // ============================================================================
  //
  // 【HTML 结构说明】
  // 表格单元格的 ID 命名规则：
  //   - 表格(table): t + 编号，如 t005
  //   - 行(row): r + 编号，如 r018
  //   - 列(column): c + 编号，如 c005
  //   - 段落(paragraph): p + 编号，如 p001
  //   - 文本块(run): r + 编号，如 r001
  //
  // 【单元格内段落 (表格内)】
  // 格式: tXXX-rXXX-cXXX-pXXX-rXXX
  // 示例: t005-r018-c005-p001-r001
  // 说明: 表示 t005 表格、第 r018 行、第 c005 列、第 p001 段落、第 r001 个文本块
  // 容器: <td>
  //
  // 【单元格外段落 (表格外)】
  // 格式: p-XXXXX-r-XXX
  // 示例: p-00097-r-001
  // 说明: 表示独立段落 p-00097 的第 r-001 个文本块
  // 容器: <p>
  //
  // 【三种验证指标】
  //
  // 1. 📝 文字匹配率
  //    验证: 容器文本中是否包含期望文本（忽略空格差异）
  //    方法: normalize(containerText).includes(normalize(expectedText))
  //    通过条件: 文本存在于容器中（允许位置偏差）
  //
  // 2. 🎯 定位准确率
  //    验证: 是否能通过 pid 前缀找到对应元素
  //    方法:
  //      - 精确匹配: id === pid
  //      - 前缀匹配: id.startsWith(pid + "-")
  //    通过条件: 能找到至少一个匹配的元素
  //
  // 3. 📍 位置精确率 (新增)
  //    验证: start + end + text 是否完全一致
  //    方法: containerText.substring(start, end) === text
  //    通过条件: 三者完全匹配
  //
  // 【ID定位策略】
  //
  // A. 表格内段落 (t格式)
  //    JSON中的pid: t005-r018-c005-p001
  //    匹配策略: 提取单元格前缀 t005-r018-c005，匹配所有 pXXX-rXXX
  //    匹配格式: t005-r018-c005-pXXX-rXXX
  //    原因: JSON的start/end是基于整个单元格文本的偏移量
  //
  // B. 表格外段落 (p格式)
  //    JSON中的pid: p-00237
  //    匹配策略: 使用 pid + "-" 前缀匹配
  //    匹配格式: p-00237-r-XXX
  //    原因: JSON的start/end是基于该段落文本的偏移量
  //
  // 【容器文本获取】
  //
  // 1. 找到容器: t格式→TD, p格式→P
  // 2. 过滤嵌套span (只保留顶层span)
  // 3. 手动拼接span的textContent (与JSON的pidText格式一致)
  //
  // ============================================================================

  // 验证风险项的文本是否匹配和定位是否准确
  function validateRiskItem(item) {
    if (!item.spanLocate || !item.spanList || item.spanList.length === 0) {
      return {
        textValid: true,
        locationValid: true,
        positionValid: true,
        textMismatches: [],
        locationMismatches: [],
        positionMismatches: []
      };
    }

    const uniqueSources = new Map();
    item.spanList.forEach((span) => {
      const key = `${span.text || ""}_${span.start}_${span.end}`;
      if (!uniqueSources.has(key)) {
        uniqueSources.set(key, span);
      }
    });

    const textMismatches = [];
    const locationMismatches = [];
    const positionMismatches = [];

    Array.from(uniqueSources.values()).forEach((span) => {
      const expectedText = span.text || "";
      const pid = span.pid;

      // 1. 使用公共方法查找匹配的 span
      const { spans: rawMatchingSpans } = findMatchingSpans(pid);
      const locationAccurate = rawMatchingSpans.length > 0;

      if (!locationAccurate) {
        locationMismatches.push({
          reason: "element_not_found",
          span,
          pid: pid
        });
        return;
      }

      // 2. 使用公共方法过滤嵌套的 span
      const matchingSpans = filterTopLevelSpans(rawMatchingSpans);

      if (matchingSpans.length === 0) {
        textMismatches.push({ reason: "no_top_level_spans", span });
        return;
      }

      // 3. 使用公共方法获取容器和容器文本
      const { container, text: containerText, rawText } = getContainerAndText(pid, matchingSpans);

      if (!container) {
        textMismatches.push({ reason: "container_not_found", span });
        return;
      }

      // 4. 检查文本是否存在于容器中（允许位置偏差，忽略空格差异）
      // 使用严格规范化进行匹配（去除所有空格）
      const normalizedExpected = strictNormalizeText(expectedText);
      const normalizedContainer = strictNormalizeText(containerText);

      if (!normalizedContainer.includes(normalizedExpected)) {
        // 文本不存在
        textMismatches.push({
          reason: "text_not_found",
          span,
          expected: expectedText,
          containerLength: containerText.length,
          rawTextLength: rawText.length,
          textExists: false,
        });
      }

      // 5. 检查位置是否精确匹配（start + end + text 完全一致）
      const extractedText = containerText.substring(span.start, span.end);
      if (extractedText !== expectedText) {
        positionMismatches.push({
          reason: "position_mismatch",
          span,
          expected: expectedText,
          actual: extractedText,
          start: span.start,
          end: span.end,
          containerLength: containerText.length,
          containerText: containerText,
          rawTextLength: rawText.length
        });
      }
    });

    return {
      textValid: textMismatches.length === 0,
      locationValid: locationMismatches.length === 0,
      positionValid: positionMismatches.length === 0,
      textMismatches,
      locationMismatches,
      positionMismatches
    };
  }

  // 显示风险列表（暴露到全局，供外部调用）
  function displayRiskList(data) {
    const statsDiv = document.getElementById("risk-list-stats");
    const itemsDiv = document.getElementById("risk-list-items");
    const container = document.getElementById("risk-list-container");

    console.log("=".repeat(80));
    console.log("开始批量验证所有风险项...");
    console.log("验证策略：");
    console.log("  1. 文字匹配率：检查文本内容是否匹配（允许位置偏差）");
    console.log("  2. 定位准确率：检查是否能通过 pid 前缀找到对应元素");
    console.log("  3. 位置精确率：检查 start + end + text 是否完全一致");
    console.log("=".repeat(80));

    // 统计匹配情况
    let totalItems = 0;
    let textMatchedItems = 0;
    let textMismatchedItems = 0;
    let locationAccurateItems = 0;
    let locationInaccurateItems = 0;
    let positionAccurateItems = 0;
    let positionInaccurateItems = 0;
    let noSpanItems = 0;

    // 显示统计信息（占位，后续会追加准确率信息）
    const stats = data.stats;
    statsDiv.innerHTML = `
            <div><strong>文件:</strong> ${data.fileName}</div>
            <div><strong>总风险数:</strong> ${stats.totalNum} | <strong>通过:</strong> ${stats.passNum}</div>
            <div><strong>场景数:</strong> ${stats.sceneNum} | <strong>成功:</strong> ${stats.sceneSuccessNum} | <strong>失败:</strong> ${stats.sceneFailureNum}</div>
            <div id="accuracy-stats" style="margin-top: 8px; padding: 8px; background: #f5f5f5; border-radius: 4px;">
                <div style="font-size: 12px; color: #666;">正在计算准确率...</div>
            </div>
        `;

    // 显示风险项列表
    itemsDiv.innerHTML = "";

    // 调试：只显示特定项（设为 false 显示全部）
    const DEBUG_MODE = false;
    const DEBUG_FILTER_TEXT = "优质冷轧钢板制成";

    data.dataList.forEach((item, index) => {
      // 调试模式：过滤数据
      if (DEBUG_MODE) {
        let shouldShow = false;
        if (item.spanList && item.spanList.length > 0) {
          item.spanList.forEach((span) => {
            if (span.text && span.text.includes(DEBUG_FILTER_TEXT)) {
              shouldShow = true;
            }
          });
        }
        if (!shouldShow) {
          return; // 跳过不符合条件的项
        }
      }

      totalItems++;
      const itemDiv = document.createElement("div");
      itemDiv.style.cssText = `
                margin-bottom: 10px;
                padding: 10px;
                background: white;
                border: 1px solid #ddd;
                border-radius: 4px;
                cursor: pointer;
                transition: background 0.2s;
            `;
      itemDiv.onmouseover = () => (itemDiv.style.background = "#f0f0f0");
      itemDiv.onmouseout = () => (itemDiv.style.background = "white");

      const hasSpans =
        item.spanLocate && item.spanList && item.spanList.length > 0;

      // 验证文本匹配和定位准确性
      const validation = hasSpans
        ? validateRiskItem(item)
        : {
            textValid: true,
            locationValid: true,
            positionValid: true,
            textMismatches: [],
            locationMismatches: [],
            positionMismatches: []
          };
      const hasTextMismatch = !validation.textValid;
      const hasLocationInaccuracy = !validation.locationValid;
      const hasPositionInaccuracy = !validation.positionValid;

      // 统计
      if (!hasSpans) {
        noSpanItems++;
      } else {
        // 文字匹配统计
        if (hasTextMismatch) {
          textMismatchedItems++;
        } else {
          textMatchedItems++;
        }

        // 定位准确统计
        if (hasLocationInaccuracy) {
          locationInaccurateItems++;
        } else {
          locationAccurateItems++;
        }

        // 位置精确统计
        if (hasPositionInaccuracy) {
          positionInaccurateItems++;
        } else {
          positionAccurateItems++;
        }

        // 输出详细日志
        if (hasTextMismatch || hasLocationInaccuracy || hasPositionInaccuracy) {
          console.group(
            `${hasTextMismatch ? '❌' : '✅'} [#${index + 1}] ${item.reviewItemName} - ${item.sceneDesc}`
          );
          console.log(`uniqueId: ${item.uniqueId}`);

          // 输出文字匹配情况
          if (hasTextMismatch && validation.textMismatches.length > 0) {
            console.log('\n📝 文字匹配问题:');
            validation.textMismatches.forEach((m, idx) => {
              console.log(`  源 ${idx + 1}: ${m.span ? m.span.pid : "N/A"}`);
              if (m.expected) {
                console.log(
                  `    期望文本 (${m.expected.length}字符): "${m.expected}"`
                );
              }
              console.log(`    ❌ 文本未在容器中找到`);
            });
          }

          // 输出定位准确性情况
          if (hasLocationInaccuracy && validation.locationMismatches.length > 0) {
            console.log('\n🎯 定位准确性问题:');
            validation.locationMismatches.forEach((m, idx) => {
              console.log(`  源 ${idx + 1}: ${m.pid}`);
              console.log(`    ❌ 无法通过 pid 前缀找到元素`);
            });
          }

          // 输出位置精确性情况
          if (hasPositionInaccuracy && validation.positionMismatches.length > 0) {
            console.log('\n📍 位置精确性问题:');
            validation.positionMismatches.forEach((m, idx) => {
              console.log(`  源 ${idx + 1}: ${m.span.pid} [${m.start}, ${m.end})`);
              console.log(`    期望: "${m.expected}"`);
              console.log(`    实际: "${m.actual}"`);
            });
          }

          console.groupEnd();
        } else {
          console.log(
            `✅ [完全匹配 #${index + 1}] ${item.reviewItemName} - ${item.sceneDesc}`
          );
        }
      }

      // 处理spanList，去重相同的text + start/end
      let spanDetailsHtml = "";
      if (hasSpans) {
        const uniqueSpans = new Map();
        item.spanList.forEach((span) => {
          const key = `${span.text || ""}_${span.start}_${span.end}`;
          if (!uniqueSpans.has(key)) {
            uniqueSpans.set(key, span);
          }
        });

        spanDetailsHtml = Array.from(uniqueSpans.values())
          .map(
            (span, idx) => `
                    <div style="margin-top: 8px; padding: 8px; background: #f9f9f9; border-left: 3px solid #2196F3; border-radius: 2px;">
                        <div style="font-size: 11px; color: #666; margin-bottom: 4px;">
                            <strong>片段 ${idx + 1}:</strong> ${
              span.pid || "N/A"
            } [${span.start}, ${span.end})
                        </div>
                        <div style="font-size: 11px; color: #999; margin-bottom: 4px;">
                            <strong>源位置:</strong> ${span.pid || "N/A"} [${
              span.start
            }, ${span.end})
                        </div>
                        ${
                          span.text
                            ? `
                            <div style="font-size: 11px; color: #333; background: white; padding: 4px; border-radius: 2px; margin-top: 4px;">
                                "${span.text}"
                            </div>
                        `
                            : ""
                        }
                    </div>
                `
          )
          .join("");
      }

      // 构建不匹配信息HTML
      let mismatchWarningHtml = "";

      // 文字匹配警告
      if (hasTextMismatch && validation.textMismatches.length > 0) {
        mismatchWarningHtml += `
          <div style="margin-top: 8px; padding: 8px; background: #fff3cd; border-left: 3px solid #ff9800; border-radius: 2px;">
            <div style="font-size: 11px; color: #856404; font-weight: bold; margin-bottom: 4px;">
              ⚠ 文字不匹配 (${validation.textMismatches.length} 处)
            </div>
            ${validation.textMismatches
              .map((m, idx) => {
                if (m.reason === "text_not_found") {
                  return `
                  <div style="font-size: 10px; color: #d32f2f; margin-top: 4px; padding: 4px; background: white; border-radius: 2px; border-left: 2px solid #d32f2f;">
                    <div><strong>源 ${idx + 1}:</strong> ${m.span.pid}</div>
                    <div style="margin-top: 4px;"><strong>期望文本 (${m.expected.length}字符):</strong></div>
                    <div style="background: #f9f9f9; padding: 4px; border-radius: 2px; white-space: pre-wrap; word-break: break-all; max-height: 100px; overflow-y: auto;">"${m.expected}"</div>
                    <div style="margin-top: 4px; color: #d32f2f;">❌ 文本未在容器中找到</div>
                  </div>
                `;
                } else {
                  return `<div style="font-size: 10px; color: #856404;">源 ${
                    idx + 1
                  }: ${m.reason}</div>`;
                }
              })
              .join("")}
          </div>
        `;
      }

      // 定位准确性警告
      if (hasLocationInaccuracy && validation.locationMismatches.length > 0) {
        mismatchWarningHtml += `
          <div style="margin-top: 8px; padding: 8px; background: #ffe0e0; border-left: 3px solid #f44336; border-radius: 2px;">
            <div style="font-size: 11px; color: #c62828; font-weight: bold; margin-bottom: 4px;">
              🎯 定位不准确 (${validation.locationMismatches.length} 处)
            </div>
            ${validation.locationMismatches
              .map((m, idx) => {
                return `
                <div style="font-size: 10px; color: #c62828; margin-top: 4px; padding: 4px; background: white; border-radius: 2px; border-left: 2px solid #c62828;">
                  <div><strong>源 ${idx + 1}:</strong> ${m.pid}</div>
                  <div>❌ 无法通过 pid 前缀找到元素</div>
                </div>
              `;
              })
              .join("")}
          </div>
        `;
      }

      // 位置精确性警告
      if (hasPositionInaccuracy && validation.positionMismatches.length > 0) {
        mismatchWarningHtml += `
          <div style="margin-top: 8px; padding: 8px; background: #e3f2fd; border-left: 3px solid #2196F3; border-radius: 2px;">
            <div style="font-size: 11px; color: #1565c0; font-weight: bold; margin-bottom: 4px;">
              📍 位置不精确 (${validation.positionMismatches.length} 处)
            </div>
            ${validation.positionMismatches
              .map((m, idx) => {
                return `
                <div style="font-size: 10px; color: #1565c0; margin-top: 4px; padding: 4px; background: white; border-radius: 2px; border-left: 2px solid #2196F3;">
                  <div><strong>源 ${idx + 1}:</strong> ${m.span.pid} [${m.start}, ${m.end})</div>
                  <div style="margin-top: 4px;"><strong>期望文本 (${m.expected.length}字符):</strong></div>
                  <div style="background: #f9f9f9; padding: 4px; border-radius: 2px; white-space: pre-wrap; word-break: break-all; max-height: 80px; overflow-y: auto;">"${m.expected}"</div>
                  <div style="margin-top: 4px;"><strong>实际提取 (${m.actual.length}字符):</strong></div>
                  <div style="background: #fff3e0; padding: 4px; border-radius: 2px; white-space: pre-wrap; word-break: break-all; max-height: 80px; overflow-y: auto;">"${m.actual}"</div>
                  <div style="margin-top: 4px;"><strong>容器文本 (${m.containerLength}字符):</strong></div>
                  <div style="background: #f5f5f5; padding: 4px; border-radius: 2px; white-space: pre-wrap; word-break: break-all; max-height: 100px; overflow-y: auto; font-size: 9px;">"${m.containerText || ''}"</div>
                </div>
              `;
              })
              .join("")}
          </div>
        `;
      }

      itemDiv.innerHTML = `
                <div style="font-weight: bold; color: #d32f2f; font-size: 13px; margin-bottom: 5px;">
                    ${index + 1}. ${item.reviewItemName} - ${item.sceneDesc}
                    ${
                      hasTextMismatch
                        ? '<span style="color: #ff9800; margin-left: 8px;" title="文字不匹配">⚠</span>'
                        : ""
                    }
                    ${
                      hasLocationInaccuracy
                        ? '<span style="color: #f44336; margin-left: 8px;" title="定位不准确">🎯</span>'
                        : ""
                    }
                </div>
                <div style="font-size: 12px; color: #666; margin-bottom: 5px;">
                    ${item.showRiskTip || ""}
                </div>
                <div style="font-size: 11px; color: #999; margin-bottom: 5px;">
                    ${
                      hasSpans
                        ? `✓ 可高亮 (${item.spanList.length} 个片段，${
                            Array.from(
                              new Map(
                                item.spanList.map((s) => [
                                  `${s.text}_${s.start}_${s.end}`,
                                  s,
                                ])
                              ).values()
                            ).length
                          } 个唯一源)`
                        : "✗ 无位置信息"
                    }
                </div>
                ${mismatchWarningHtml}
                ${spanDetailsHtml}
            `;

      if (hasSpans) {
        itemDiv.onclick = () => highlightRiskItem(item);
      } else {
        itemDiv.style.opacity = "0.6";
        itemDiv.style.cursor = "not-allowed";
      }

      itemsDiv.appendChild(itemDiv);
    });

    // 输出统计汇总
    const validItems = totalItems - noSpanItems;
    const textMatchRate = validItems > 0 ? ((textMatchedItems / validItems) * 100).toFixed(2) : 0;
    const locationAccuracyRate = validItems > 0 ? ((locationAccurateItems / validItems) * 100).toFixed(2) : 0;
    const positionAccuracyRate = validItems > 0 ? ((positionAccurateItems / validItems) * 100).toFixed(2) : 0;

    console.log("=".repeat(80));
    console.log("验证完成！统计汇总:");
    console.log(`  总计: ${totalItems} 项`);
    console.log(`  ⚪ 无位置信息: ${noSpanItems} 项`);
    console.log(`  有效项: ${validItems} 项`);
    console.log(`\n  📝 文字匹配统计:`);
    console.log(`    ✅ 匹配: ${textMatchedItems} 项`);
    console.log(`    ❌ 不匹配: ${textMismatchedItems} 项`);
    console.log(`    文字匹配率: ${textMatchRate}%`);
    console.log(`\n  🎯 定位准确统计:`);
    console.log(`    ✅ 准确: ${locationAccurateItems} 项`);
    console.log(`    ❌ 不准确: ${locationInaccurateItems} 项`);
    console.log(`    定位准确率: ${locationAccuracyRate}%`);
    console.log(`\n  📍 位置精确统计 (start+end+text完全一致):`);
    console.log(`    ✅ 精确: ${positionAccurateItems} 项`);
    console.log(`    ❌ 不精确: ${positionInaccurateItems} 项`);
    console.log(`    位置精确率: ${positionAccuracyRate}%`);
    console.log("=".repeat(80));

    // 更新准确率统计信息到页面
    const accuracyStatsDiv = document.getElementById("accuracy-stats");
    if (accuracyStatsDiv) {
      let accuracyHtml = `
        <div style="font-size: 12px; font-weight: bold; margin-bottom: 6px; color: #333;">
          📊 准确率统计 (有效项: ${validItems}/${totalItems})
        </div>
        <div style="display: flex; gap: 10px; flex-wrap: wrap;">
          <div style="flex: 1; min-width: 140px; padding: 6px; background: ${textMatchRate >= 90 ? '#e8f5e9' : textMatchRate >= 70 ? '#fff3cd' : '#ffe0e0'}; border-radius: 4px;">
            <div style="font-size: 11px; color: #666; margin-bottom: 2px;">📝 文字匹配率</div>
            <div style="font-size: 18px; font-weight: bold; color: ${textMatchRate >= 90 ? '#2e7d32' : textMatchRate >= 70 ? '#f57c00' : '#c62828'};">
              ${textMatchRate}%
            </div>
            <div style="font-size: 10px; color: #666; margin-top: 2px;">
              ${textMatchedItems}/${validItems} 项
            </div>
          </div>
          <div style="flex: 1; min-width: 140px; padding: 6px; background: ${locationAccuracyRate >= 90 ? '#e8f5e9' : locationAccuracyRate >= 70 ? '#fff3cd' : '#ffe0e0'}; border-radius: 4px;">
            <div style="font-size: 11px; color: #666; margin-bottom: 2px;">🎯 定位准确率</div>
            <div style="font-size: 18px; font-weight: bold; color: ${locationAccuracyRate >= 90 ? '#2e7d32' : locationAccuracyRate >= 70 ? '#f57c00' : '#c62828'};">
              ${locationAccuracyRate}%
            </div>
            <div style="font-size: 10px; color: #666; margin-top: 2px;">
              ${locationAccurateItems}/${validItems} 项
            </div>
          </div>
          <div style="flex: 1; min-width: 140px; padding: 6px; background: ${positionAccuracyRate >= 90 ? '#e8f5e9' : positionAccuracyRate >= 70 ? '#fff3cd' : '#ffe0e0'}; border-radius: 4px;">
            <div style="font-size: 11px; color: #666; margin-bottom: 2px;">📍 位置精确率</div>
            <div style="font-size: 18px; font-weight: bold; color: ${positionAccuracyRate >= 90 ? '#2e7d32' : positionAccuracyRate >= 70 ? '#f57c00' : '#c62828'};">
              ${positionAccuracyRate}%
            </div>
            <div style="font-size: 10px; color: #666; margin-top: 2px;">
              ${positionAccurateItems}/${validItems} 项
            </div>
          </div>
        </div>
      `;

      if (textMismatchedItems > 0 || locationInaccurateItems > 0 || positionInaccurateItems > 0) {
        const issues = [];
        if (textMismatchedItems > 0) issues.push(`⚠ ${textMismatchedItems} 项文字不匹配`);
        if (locationInaccurateItems > 0) issues.push(`🎯 ${locationInaccurateItems} 项定位不准确`);
        if (positionInaccurateItems > 0) issues.push(`📍 ${positionInaccurateItems} 项位置不精确`);

        accuracyHtml += `
          <div style="margin-top: 8px; font-size: 11px; color: #666;">
            ${issues.join(' | ')}
          </div>
        `;
      }

      accuracyStatsDiv.innerHTML = accuracyHtml;
    }

    container.style.display = "block";
  }

  // ============================================================================
  // 高亮风险项 - 核心逻辑说明
  // ============================================================================
  //
  // 【整体流程】
  // 1. 根据 pid 定位元素（ID定位）
  // 2. 获取容器文本
  // 3. 根据 text 搜索高亮位置（文本搜索高亮）
  // 4. 在对应的 span 中添加高亮标记
  //
  // ============================================================================
  // 【第一步：ID定位 - 根据 pid 查找匹配的 span 元素】
  // ============================================================================
  //
  // A. 表格内段落 (t格式)
  //    JSON中的pid: t005-r018-c005-p001
  //    匹配策略: 使用单元格前缀 t005-r018-c005 匹配整个单元格的所有段落
  //    匹配格式: t005-r018-c005-pXXX-rXXX
  //
  //    示例HTML:
  //    <td>
  //      <span id="t005-r018-c005-p001-r001">文本1</span>
  //      <span id="t005-r018-c005-p002-r001">文本2</span>
  //      <span id="t005-r018-c005-p003-r002">文本3</span>
  //    </td>
  //
  //    匹配结果: 所有 t005-r018-c005-pXXX-rXXX 格式的span
  //    容器类型: TD
  //
  // B. 表格外段落 (p格式)
  //    JSON中的pid: p-00237
  //    匹配策略: 使用 pid + "-" 前缀匹配该段落的所有文本块
  //    匹配格式: p-00237-r-XXX
  //
  //    示例HTML:
  //    <p>
  //      <span id="p-00237-r-001">文本1</span>
  //      <span id="p-00237-r-002">文本2</span>
  //      <span id="p-00237-r-003">文本3</span>
  //    </p>
  //
  //    匹配结果: 所有 p-00237-r-XXX 格式的span
  //    容器类型: P
  //
  // ============================================================================
  // 【第二步：获取容器文本】
  // ============================================================================
  //
  // 1. 找到容器元素
  //    - t格式: 向上查找到 <td> 标签
  //    - p格式: 向上查找到 <p> 标签
  //
  // 2. 过滤嵌套的 span (只保留顶层span)
  //    示例: <span id="a">A<span id="b">B</span></span>
  //    - span#a 和 span#b 都匹配
  //    - span#b 嵌套在 span#a 内，过滤掉 span#b
  //    - 只保留 span#a
  //
  // 3. 手动拼接所有顶层span的textContent
  //    重要: 不能用 container.textContent，会包含标签间的空格和换行
  //
  //    错误方式: container.textContent
  //    结果: "(14)耗材配置\n  文本1\n  文本2" (包含换行和缩进)
  //
  //    正确方式: 手动拼接span.textContent
  //    结果: "(14)耗材配置文本1文本2" (纯文本拼接)
  //
  //    这样拼接出的文本与JSON中的pidText格式完全一致
  //
  // ============================================================================
  // 【第三步：文本搜索高亮 - 根据 text 查找高亮位置】
  // ============================================================================
  //
  // 1. 在容器文本中搜索期望文本(expectedText)
  //    使用: containerText.indexOf(expectedText)
  //
  // 2. 找到文本位置后，检查是否与JSON提供的位置一致
  //    - 完全一致: start/end/text 三者匹配 → 位置精确
  //    - 位置偏差: text匹配但start/end不一致 → 文字匹配但位置不精确
  //    - 找不到: text在容器中不存在 → 文字不匹配
  //
  // 3. 高亮策略: 优先使用文本搜索的位置，不依赖JSON的start/end
  //    原因: JSON的start/end可能因为空格、换行等原因产生偏差
  //
  // ============================================================================
  // 【第四步：添加高亮标记】
  // ============================================================================
  //
  // 1. 遍历所有匹配的span，计算每个span在拼接文本中的位置
  // 2. 判断该span是否与高亮范围有交集
  // 3. 如果有交集，在该span内部的文本节点添加高亮标记
  // 4. 递归处理嵌套结构，保留原有的HTML结构
  //
  // ============================================================================

  function highlightRiskItem(item) {
    const timestamp = new Date().toLocaleString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    console.log(
      `[${timestamp}] 高亮风险项 - ID: ${item.uniqueId}, 场景: ${item.sceneDesc}`
    );

    // 清除之前的高亮
    clearHighlight();

    if (!item.spanList || item.spanList.length === 0) {
      showStatus("该风险项无位置信息", "error");
      return;
    }

    let highlightedCount = 0;
    const highlightClass = "risk-highlight-" + Date.now();

    // 去重：相同的 text + start/end 只高亮一次
    const uniqueSources = new Map();
    item.spanList.forEach((span) => {
      const key = `${span.text || ""}_${span.start}_${span.end}`;
      if (!uniqueSources.has(key)) {
        uniqueSources.set(key, span);
      }
    });

    console.log(`[${timestamp}] 去重后的唯一源: ${uniqueSources.size} 个`);

    // 为每个唯一的源位置添加高亮
    Array.from(uniqueSources.values()).forEach((span, spanIndex) => {
      // 1. 使用公共方法查找匹配的 span
      const { spans: rawMatchingSpans, method: matchMethod } = findMatchingSpans(span.pid);

      console.log(`[${timestamp}] 原始 pid: "${span.pid}"`);
      console.log(`[${timestamp}] 匹配方式: ${matchMethod}`);
      console.log(
        `[${timestamp}] 匹配到 ${rawMatchingSpans.length} 个 span:`,
        rawMatchingSpans.map((s) => s.id)
      );

      if (rawMatchingSpans.length === 0) {
        console.warn(`未找到匹配 ${span.pid} 的元素（包括所有前缀匹配策略）`);
        return;
      }

      // 2. 使用公共方法过滤嵌套的 span
      const beforeFilter = rawMatchingSpans.length;
      const matchingSpans = filterTopLevelSpans(rawMatchingSpans);

      console.log(
        `[${timestamp}] 过滤嵌套: ${beforeFilter} -> ${matchingSpans.length} 个顶层 span`
      );
      console.log(
        `[${timestamp}] 顶层 span IDs:`,
        matchingSpans.map((s) => s.id)
      );
      console.log(
        `[${timestamp}] 每个 span 的文本:`,
        matchingSpans.map((s) => `"${getTextByDOMOrder(s)}"`)
      );

      // 3. 使用公共方法获取容器和容器文本
      const { container, text: containerText, strictText: strictContainerText, rawText, spanTextMap } = getContainerAndText(span.pid, matchingSpans);

      if (!container) {
        console.warn(`未找到 ${span.pid} 的容器`);
        return;
      }

      console.log(
        `[${timestamp}] 找到容器: ${container.tagName}, 包含 ${matchingSpans.length} 个匹配的 span`
      );

      const expectedText = span.text || "";
      const element = container;

      console.log(
        `[${timestamp}] 容器文本长度: ${containerText.length} (轻量规范化), 原始: ${rawText.length}, 期望文本长度: ${expectedText.length}`
      );
      console.log(`[${timestamp}] JSON提供范围: [${span.start}, ${span.end}) (已废弃，不再使用)`);

      // ============================================================================
      // 新策略：使用严格规范化 + 前端计算位置（frontStart/frontEnd）
      // ============================================================================
      // 1. 严格规范化期望文本（去除所有空格）
      const strictExpected = strictNormalizeText(expectedText);

      console.log(`[${timestamp}] 严格规范化后 - 容器: ${strictContainerText.length}字符, 期望: ${strictExpected.length}字符`);
      console.log(`[${timestamp}] 严格规范化容器文本完整内容: "${strictContainerText}"`);
      console.log(`[${timestamp}] 严格规范化期望文本完整内容: "${strictExpected}"`);

      // 2. 在严格规范化的文本中查找位置（frontStart/frontEnd）
      const frontStart = strictContainerText.indexOf(strictExpected);

      let searchStart, searchEnd;

      if (frontStart !== -1) {
        // ✅ 找到了！计算 frontEnd
        const frontEnd = frontStart + strictExpected.length;

        console.log(`[${timestamp}] ✅ 严格匹配成功: frontStart=${frontStart}, frontEnd=${frontEnd}`);
        console.log(`[${timestamp}] 严格规范化后的文本片段: "${strictContainerText.substring(frontStart, frontEnd)}"`);

        // 3. 使用严格规范化的位置进行高亮
        searchStart = frontStart;
        searchEnd = frontEnd;
      } else {
        // ❌ 完全找不到文本
        console.error(`❌ [严格匹配失败] ${span.pid}`);
        console.error(`期望文本 (${expectedText.length}字符): "${expectedText}"`);
        console.error(`严格规范化期望 (${strictExpected.length}字符): "${strictExpected}"`);
        console.error(`严格规范化容器 (${strictContainerText.length}字符): "${strictContainerText.substring(0, 200)}..."`);
        console.error(`容器文本预览: "${containerText.substring(0, 200)}..."`);

        // 跳过此项，不进行高亮
        return;
      }

      // ============================================================================
      // 高亮渲染：基于严格规范化的位置（frontStart/frontEnd）
      // ============================================================================
      spanTextMap.forEach((spanInfo) => {
        const matchSpan = spanInfo.span;
        const spanStrictStart = spanInfo.strictStart;
        const spanStrictEnd = spanInfo.strictEnd;

        // 检查当前span是否与高亮范围有交集（基于严格规范化的位置）
        if (spanStrictEnd > searchStart && spanStrictStart < searchEnd) {
          // 保存原始内容
          if (!matchSpan.hasAttribute("data-original-html")) {
            matchSpan.setAttribute("data-original-html", matchSpan.innerHTML);
          }

          // 计算在当前span的严格规范化文本内的相对位置
          const relStart = Math.max(0, searchStart - spanStrictStart);
          const relEnd = Math.min(spanInfo.strictText.length, searchEnd - spanStrictStart);

          // 克隆并处理该span - 在原始文本中进行高亮
          const newSpan = matchSpan.cloneNode(true);

          // 在span的原始textContent中对严格规范化后的文本进行定位和高亮
          // 需要找到严格规范化文本在原始文本中的对应位置
          const spanRawText = spanInfo.rawText;

          // 在原始文本中查找对应的位置（将严格规范化位置映射回原始文本）
          let rawStartIdx = -1;
          let rawEndIdx = -1;

          // 遍历原始文本，找到对应的严格规范化片段位置
          let strictIdx = 0;
          for (let rawIdx = 0; rawIdx < spanRawText.length; rawIdx++) {
            const char = spanRawText[rawIdx];
            // 跳过所有空白字符（严格规范化：移除所有空格）
            if (/\s/.test(char)) {
              continue;
            }

            // 找到起始位置
            if (strictIdx === relStart && rawStartIdx === -1) {
              rawStartIdx = rawIdx;
            }

            strictIdx++;

            // 找到结束位置
            if (strictIdx === relEnd && rawEndIdx === -1) {
              rawEndIdx = rawIdx + 1;
              break;
            }
          }

          // 如果找到了对应位置，进行高亮
          if (rawStartIdx !== -1 && rawEndIdx !== -1) {
            let spanCharCount = 0;

            function processNode(node) {
              if (node.nodeType === Node.TEXT_NODE) {
                const nodeText = node.textContent;
                const nodeStart = spanCharCount;
                const nodeEnd = spanCharCount + nodeText.length;

                if (nodeEnd > rawStartIdx && nodeStart < rawEndIdx) {
                  const fragment = document.createDocumentFragment();
                  const nodeRelStart = Math.max(0, rawStartIdx - nodeStart);
                  const nodeRelEnd = Math.min(nodeText.length, rawEndIdx - nodeStart);

                  if (nodeRelStart > 0) {
                    fragment.appendChild(
                      document.createTextNode(nodeText.substring(0, nodeRelStart))
                    );
                  }

                  const highlightSpan = document.createElement("span");
                  highlightSpan.className = highlightClass;
                  highlightSpan.style.cssText =
                    "background-color: #ffeb3b; font-weight: bold; border-bottom: 2px solid #f44336;";
                  highlightSpan.textContent = nodeText.substring(nodeRelStart, nodeRelEnd);
                  fragment.appendChild(highlightSpan);

                  if (nodeRelEnd < nodeText.length) {
                    fragment.appendChild(
                      document.createTextNode(nodeText.substring(nodeRelEnd))
                    );
                  }

                  node.parentNode.replaceChild(fragment, node);
                }
                spanCharCount += nodeText.length;
              } else if (node.nodeType === Node.ELEMENT_NODE) {
                const childNodes = Array.from(node.childNodes);
                childNodes.forEach((child) => processNode(child));
              }
            }

            processNode(newSpan);
            matchSpan.innerHTML = newSpan.innerHTML;
          }
        }
      });

      highlightedCount++;

      // 验证高亮结果：检查高亮的内容是否正确
      const highlightedElements = element.querySelectorAll(
        `.${highlightClass}`
      );
      let actualHighlightedText = "";
      highlightedElements.forEach((el) => {
        actualHighlightedText += el.textContent;
      });

      // 标准化比较（忽略空格差异）
      if (strictNormalizeText(actualHighlightedText) === strictNormalizeText(expectedText)) {
        console.log(`✅ [高亮验证成功] 高亮内容与期望一致`);
        console.log(`  期望: "${expectedText}"`);
        console.log(`  实际: "${actualHighlightedText}"`);
      } else {
        console.error(`❌ [高亮验证失败] 高亮内容与期望不一致`);
        console.error(`  期望 (${expectedText.length}字符): "${expectedText}"`);
        console.error(
          `  实际 (${actualHighlightedText.length}字符): "${actualHighlightedText}"`
        );
        console.error(
          `  差异: ${
            expectedText.length - actualHighlightedText.length
          } 字符差异`
        );
      }

      // 滚动到第一个高亮位置
      if (spanIndex === 0 && matchingSpans.length > 0) {
        matchingSpans[0].scrollIntoView({
          behavior: "smooth",
          block: "center",
        });
      }
    });

    const totalUniqueSources = uniqueSources.size;
    showStatus(
      `成功高亮风险项: ${item.sceneDesc} (${highlightedCount}/${totalUniqueSources} 个源位置)`,
      "success"
    );
  }

  // 显示状态消息（委托给UI模块）
  function showStatus(message, type) {
    if (window.HighlighterUI && window.HighlighterUI.showStatus) {
      window.HighlighterUI.showStatus(message, type);
    }
  }

  // 初始化
  function init() {
    // 等待DOM加载完成
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", setup);
    } else {
      setup();
    }
  }

  function setup() {
    // 初始化UI并传入核心功能函数
    if (window.HighlighterUI && window.HighlighterUI.initUI) {
      window.HighlighterUI.initUI({
        highlightText,
        highlightByPrefix,
        clearHighlight,
        loadRiskList,
        normalizeText,
        getTextByDOMOrder
      });
    }

    const loadTimestamp = new Date().toLocaleString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    console.log(
      `[${loadTimestamp}] 文本高亮工具核心已加载 - 版本: v7.0 (UI模块分离)`
    );
  }

  // 启动
  init();

  // 暴露函数到全局，供外部调用
  window.loadRiskList = loadRiskList;
  window.displayRiskList = displayRiskList;
  window.showStatus = showStatus;
})();
