// 文本高亮工具 - UI 模块
(function () {
  "use strict";

  // 创建控制面板
  function createControlPanel() {
    const panel = document.createElement("div");
    panel.id = "highlight-panel";
    panel.style.cssText = `
            position: fixed;
            top: 20px;
            right: 20px;
            background: white;
            border: 1px solid #ccc;
            border-radius: 8px;
            padding: 15px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
            z-index: 10000;
            font-family: Arial, sans-serif;
            width: 400px;
            max-height: 80vh;
            overflow-y: auto;
        `;

    panel.innerHTML = `
            <div style="position: sticky; top: 0; background: white; z-index: 1; padding-bottom: 10px; margin-bottom: 10px; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; align-items: center;">
                <h3 style="margin: 0; font-size: 16px;">文本高亮工具</h3>
                <div style="display: flex; gap: 5px;">
                    <button id="enlarge-panel-btn" title="放大面板" style="padding: 4px 8px; background: #4CAF50; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 12px;">+</button>
                    <button id="shrink-panel-btn" title="缩小面板" style="padding: 4px 8px; background: #2196F3; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 12px;">−</button>
                    <button id="toggle-panel-btn" title="最小化/展开" style="padding: 4px 8px; background: #9E9E9E; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 12px;">_</button>
                    <button id="close-panel-btn" title="隐藏面板" style="padding: 4px 8px; background: #f44336; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 12px;">×</button>
                </div>
            </div>
            <div id="panel-content">

            <!-- 模式选择 -->
            <div style="margin-bottom: 15px; padding: 10px; background: #f0f0f0; border-radius: 4px;">
                <label style="display: block; margin-bottom: 8px; font-size: 14px; font-weight: bold;">选择模式:</label>
                <div style="margin-bottom: 5px;">
                    <input type="radio" id="mode-single" name="highlight-mode" value="single">
                    <label for="mode-single" style="margin-left: 5px; font-size: 13px;">单个元素高亮</label>
                </div>
                <div style="margin-bottom: 5px;">
                    <input type="radio" id="mode-prefix" name="highlight-mode" value="prefix">
                    <label for="mode-prefix" style="margin-left: 5px; font-size: 13px;">前缀匹配(整个单元格)</label>
                </div>
                <div>
                    <input type="radio" id="mode-list" name="highlight-mode" value="list" checked>
                    <label for="mode-list" style="margin-left: 5px; font-size: 13px;">风险列表</label>
                </div>
            </div>

            <!-- 手动输入模式 -->
            <div id="manual-input-area">
                <div style="margin-bottom: 10px;">
                    <label style="display: block; margin-bottom: 5px; font-size: 14px;">
                        <span id="input-label">元素ID:</span>
                    </label>
                    <input type="text" id="element-id" placeholder="例如: p-01102-r-001"
                        style="width: 100%; padding: 5px; border: 1px solid #ddd; border-radius: 4px; box-sizing: border-box;">
                    <div id="input-hint" style="font-size: 11px; color: #999; margin-top: 3px;">单个元素高亮</div>
                </div>
                <div style="margin-bottom: 10px;">
                    <label style="display: block; margin-bottom: 5px; font-size: 14px;">起始位置:</label>
                    <input type="number" id="start-pos" value="0" min="0"
                        style="width: 100%; padding: 5px; border: 1px solid #ddd; border-radius: 4px; box-sizing: border-box;">
                </div>
                <div style="margin-bottom: 10px;">
                    <label style="display: block; margin-bottom: 5px; font-size: 14px;">结束位置:</label>
                    <input type="number" id="end-pos" value="0" min="0"
                        style="width: 100%; padding: 5px; border: 1px solid #ddd; border-radius: 4px; box-sizing: border-box;">
                </div>
                <div style="margin-bottom: 10px;">
                    <button id="highlight-btn"
                        style="width: 100%; padding: 8px; background: #4CAF50; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 14px;">
                        确定高亮
                    </button>
                </div>
            </div>

            <!-- 风险列表模式 -->
            <div id="risk-list-area" style="display: none;">
                <div style="margin-bottom: 10px;">
                    <button id="load-list-btn"
                        style="width: 100%; padding: 8px; background: #FF9800; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 14px;">
                        加载风险列表
                    </button>
                </div>
                <div id="risk-list-container" style="display: none;">
                    <div id="risk-list-stats" style="font-size: 12px; color: #666; margin-bottom: 10px; padding: 8px; background: #f9f9f9; border-radius: 4px;"></div>
                    <div id="risk-list-items"></div>
                </div>
            </div>
            <div style="margin-bottom: 10px;">
                <button id="clear-btn"
                    style="width: 100%; padding: 8px; background: #f44336; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 14px;">
                    清除高亮
                </button>
            </div>
            <div id="status-msg" style="font-size: 12px; color: #666; margin-top: 10px;"></div>
            <div style="margin-top: 10px;">
                <button id="toggle-html-btn"
                    style="width: 100%; padding: 6px; background: #2196F3; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; display: none;">
                    显示/隐藏 HTML结构
                </button>
            </div>
            <div id="html-structure" style="margin-top: 10px; padding: 10px; background: #f5f5f5; border-radius: 4px; max-height: 200px; overflow-y: auto; display: none;">
                <div style="font-size: 13px; font-weight: bold; margin-bottom: 5px; color: #333;">HTML原始结构:</div>
                <pre id="html-content" style="margin: 0; font-size: 10px; white-space: pre-wrap; word-wrap: break-word; font-family: 'Courier New', monospace; color: #666;"></pre>
            </div>
            </div>
        `;

    document.body.appendChild(panel);

    // 添加控制按钮事件
    setupPanelControls(panel);
  }

  // 设置面板控制功能
  function setupPanelControls(panel) {
    const enlargeBtn = document.getElementById("enlarge-panel-btn");
    const shrinkBtn = document.getElementById("shrink-panel-btn");
    const toggleBtn = document.getElementById("toggle-panel-btn");
    const closeBtn = document.getElementById("close-panel-btn");
    const panelContent = document.getElementById("panel-content");

    let isMinimized = false;
    let currentWidth = 400; // 默认宽度

    // 放大面板
    enlargeBtn.addEventListener("click", function() {
      currentWidth = Math.min(currentWidth + 100, 1000); // 最大1000px
      panel.style.width = currentWidth + "px";
    });

    // 缩小面板
    shrinkBtn.addEventListener("click", function() {
      currentWidth = Math.max(currentWidth - 100, 300); // 最小300px
      panel.style.width = currentWidth + "px";
    });

    // 最小化/展开
    toggleBtn.addEventListener("click", function() {
      isMinimized = !isMinimized;
      if (isMinimized) {
        panelContent.style.display = "none";
        panel.style.height = "auto";
        panel.style.maxHeight = "none";
        toggleBtn.textContent = "□";
        toggleBtn.title = "展开";
      } else {
        panelContent.style.display = "block";
        panel.style.height = "";
        panel.style.maxHeight = "80vh";
        toggleBtn.textContent = "_";
        toggleBtn.title = "最小化";
      }
    });

    // 隐藏面板
    closeBtn.addEventListener("click", function() {
      panel.style.display = "none";
      // 创建一个重新显示的按钮
      createShowPanelButton();
    });
  }

  // 创建显示面板的按钮
  function createShowPanelButton() {
    // 检查是否已存在
    if (document.getElementById("show-panel-btn")) return;

    const showBtn = document.createElement("button");
    showBtn.id = "show-panel-btn";
    showBtn.innerHTML = "📝";
    showBtn.title = "显示高亮工具";
    showBtn.style.cssText = `
      position: fixed;
      top: 20px;
      right: 20px;
      width: 50px;
      height: 50px;
      background: #4CAF50;
      color: white;
      border: none;
      border-radius: 50%;
      cursor: pointer;
      font-size: 24px;
      box-shadow: 0 2px 10px rgba(0,0,0,0.2);
      z-index: 9999;
      display: flex;
      align-items: center;
      justify-content: center;
    `;

    showBtn.addEventListener("click", function() {
      const panel = document.getElementById("highlight-panel");
      if (panel) {
        panel.style.display = "block";
      }
      showBtn.remove();
    });

    document.body.appendChild(showBtn);
  }

  // 显示状态消息
  function showStatus(message, type) {
    const statusDiv = document.getElementById("status-msg");
    if (statusDiv) {
      statusDiv.textContent = message;
      statusDiv.style.color = type === "error" ? "#f44336" : "#4CAF50";
    }
  }

  // 设置事件绑定（需要传入核心功能函数）
  function setupEventBindings(handlers) {
    const {
      highlightText,
      highlightByPrefix,
      clearHighlight,
      loadRiskList,
      strictNormalizeText,
      getTextByDOMOrder
    } = handlers;

    const elementIdInput = document.getElementById("element-id");
    const startPosInput = document.getElementById("start-pos");
    const endPosInput = document.getElementById("end-pos");
    const highlightBtn = document.getElementById("highlight-btn");
    const clearBtn = document.getElementById("clear-btn");
    const inputLabel = document.getElementById("input-label");
    const inputHint = document.getElementById("input-hint");
    const toggleHtmlBtn = document.getElementById("toggle-html-btn");
    const htmlStructure = document.getElementById("html-structure");

    // 切换HTML结构显示/隐藏
    toggleHtmlBtn.addEventListener("click", function () {
      if (htmlStructure.style.display === "none") {
        htmlStructure.style.display = "block";
      } else {
        htmlStructure.style.display = "none";
      }
    });

    const manualInputArea = document.getElementById("manual-input-area");
    const riskListArea = document.getElementById("risk-list-area");
    const loadListBtn = document.getElementById("load-list-btn");

    // 监听模式切换
    document
      .querySelectorAll('input[name="highlight-mode"]')
      .forEach((radio) => {
        radio.addEventListener("change", function () {
          if (this.value === "single") {
            manualInputArea.style.display = "block";
            riskListArea.style.display = "none";
            inputLabel.textContent = "元素ID:";
            elementIdInput.placeholder = "例如: p-01102-r-001";
            inputHint.textContent = "单个元素高亮";
          } else if (this.value === "prefix") {
            manualInputArea.style.display = "block";
            riskListArea.style.display = "none";
            inputLabel.textContent = "单元格前缀:";
            elementIdInput.placeholder = "例如: t011-r168-c003";
            inputHint.textContent = "匹配整个单元格的所有段落文本";
          } else if (this.value === "list") {
            manualInputArea.style.display = "none";
            riskListArea.style.display = "block";
          }
          // 清空之前的状态
          htmlStructure.style.display = "none";
          toggleHtmlBtn.style.display = "none";
          showStatus("", "success");
        });
      });

    // 初始化模式
    const checkedMode = document.querySelector(
      'input[name="highlight-mode"]:checked'
    );
    if (checkedMode && checkedMode.value === "list") {
      manualInputArea.style.display = "none";
      riskListArea.style.display = "block";
    }

    // 加载列表按钮
    loadListBtn.addEventListener("click", function () {
      loadRiskList();
    });

    // 当输入元素ID时，自动设置结束位置为文本长度
    elementIdInput.addEventListener("change", function () {
      const elementId = this.value.trim();
      if (elementId) {
        const mode = document.querySelector(
          'input[name="highlight-mode"]:checked'
        ).value;

        if (mode === "single") {
          // 单个元素模式
          const element = document.getElementById(elementId);
          if (element) {
            // 使用 getTextByDOMOrder 确保文本顺序正确，然后规范化
            const text = strictNormalizeText(getTextByDOMOrder(element));
            const textLength = text.length;
            endPosInput.value = textLength;
            showStatus(`文本长度: ${textLength} 字符 (规范化后)`, "success");

            // 显示HTML原始结构（默认隐藏，需要点击按钮显示）
            const htmlContent = document.getElementById("html-content");
            htmlContent.textContent = element.innerHTML;
            toggleHtmlBtn.style.display = "block";
            htmlStructure.style.display = "none";
          } else {
            showStatus("警告: 未找到该元素", "error");
            htmlStructure.style.display = "none";
            toggleHtmlBtn.style.display = "none";
          }
        } else if (mode === "prefix") {
          // 前缀匹配模式
          const allSpans = document.querySelectorAll("span[id]");
          const matchingSpans = Array.from(allSpans).filter((span) =>
            span.id.startsWith(elementId)
          );

          if (matchingSpans.length > 0) {
            // 找到包含这些span的单元格
            let container = matchingSpans[0];
            while (container && container.tagName !== "TD") {
              container = container.parentElement;
            }

            if (container) {
              // 使用 getTextByDOMOrder 确保文本顺序正确，然后规范化
              const text = strictNormalizeText(getTextByDOMOrder(container));
              const textLength = text.length;
              endPosInput.value = textLength;
              showStatus(
                `找到 ${matchingSpans.length} 个匹配元素，文本长度: ${textLength} 字符`,
                "success"
              );

              // 显示整个td的HTML原始结构（默认隐藏，需要点击按钮显示）
              const htmlContent = document.getElementById("html-content");
              htmlContent.textContent = container.innerHTML;
              toggleHtmlBtn.style.display = "block";
              htmlStructure.style.display = "none";
            } else {
              showStatus("警告: 未找到包含元素的单元格", "error");
              htmlStructure.style.display = "none";
              toggleHtmlBtn.style.display = "none";
            }
          } else {
            showStatus("警告: 未找到匹配的元素", "error");
            htmlStructure.style.display = "none";
            toggleHtmlBtn.style.display = "none";
          }
        }
      }
    });

    // 确定按钮事件
    highlightBtn.addEventListener("click", function () {
      const elementId = elementIdInput.value.trim();
      const start = parseInt(startPosInput.value) || 0;
      const end = parseInt(endPosInput.value) || 0;

      if (!elementId) {
        showStatus("错误: 请输入元素ID或前缀", "error");
        return;
      }

      const mode = document.querySelector(
        'input[name="highlight-mode"]:checked'
      ).value;

      if (mode === "single") {
        highlightText(elementId, start, end);
      } else if (mode === "prefix") {
        highlightByPrefix(elementId, start, end);
      }
    });

    // 清除按钮事件
    clearBtn.addEventListener("click", function () {
      clearHighlight();
      showStatus("已清除所有高亮", "success");
    });
  }

  // 初始化UI（创建面板并绑定事件）
  function initUI(handlers) {
    createControlPanel();
    setupEventBindings(handlers);

    const loadTimestamp = new Date().toLocaleString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    console.log(
      `[${loadTimestamp}] 文本高亮工具UI已加载`
    );
  }

  // 暴露到全局
  window.HighlighterUI = {
    initUI,
    showStatus
  };
})();

