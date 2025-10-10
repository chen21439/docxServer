(function () {
  "use strict";

  const API_BASE = "https://sppgpttest.gcycloud.cn/compliance/v1";
  let riskData = null; // 存储风险数据
  let htmlUrl = null; // 存储HTML URL

  // 初始化
  function init() {
    const loadBtn = document.getElementById("load-btn");
    const taskIdInput = document.getElementById("task-id");
    const tokenInput = document.getElementById("token");

    loadBtn.addEventListener("click", async function () {
      const taskId = taskIdInput.value.trim();
      const token = tokenInput.value.trim();

      if (!taskId) {
        showStatus("请输入 Task ID", "error");
        return;
      }

      if (!token) {
        showStatus("请输入 Token", "error");
        return;
      }

      await loadData(taskId, token);
    });
  }

  // 加载数据
  async function loadData(taskId, token) {
    try {
      showStatus("正在加载数据...", "");
      const loadBtn = document.getElementById("load-btn");
      loadBtn.disabled = true;

      console.log("=== 请求参数 ===");
      console.log("TaskID:", taskId);
      console.log("Token长度:", token.length);
      console.log("Token前50字符:", token.substring(0, 50));
      console.log("Token完整内容:", token);

      // 并行请求两个接口
      const [listResponse, htmlResponse] = await Promise.all([
        fetch(`${API_BASE}/task/review/list?_t=${Date.now()}`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: token,
          },
          body: JSON.stringify({
            taskId: taskId,
            reviewResult: 1,
          }),
        }),
        fetch(`${API_BASE}/task/html?_t=${Date.now()}`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: token,
          },
          body: JSON.stringify({
            taskId: taskId,
          }),
        }),
      ]);

      console.log("=== 响应状态 ===");
      console.log("List Response Status:", listResponse.status, listResponse.statusText);
      console.log("HTML Response Status:", htmlResponse.status, htmlResponse.statusText);

      // 检查响应状态
      if (!listResponse.ok) {
        const errorText = await listResponse.text();
        console.error("List Response Error:", errorText);
        throw new Error(`获取风险列表失败: ${listResponse.status} ${listResponse.statusText}`);
      }
      if (!htmlResponse.ok) {
        const errorText = await htmlResponse.text();
        console.error("HTML Response Error:", errorText);
        throw new Error(`获取HTML失败: ${htmlResponse.status} ${htmlResponse.statusText}`);
      }

      // 解析数据
      const listData = await listResponse.json();
      const htmlData = await htmlResponse.json();

      console.log("=== 响应数据 ===");
      console.log("List Data:", listData);
      console.log("HTML Data:", htmlData);

      if (!listData.success) {
        throw new Error(listData.errMsg || "获取风险列表失败");
      }
      if (!htmlData.success) {
        throw new Error(htmlData.errMsg || "获取HTML失败");
      }

      // 保存数据
      riskData = listData;
      htmlUrl = htmlData.data.htmlInfo;

      // 下载远程HTML内容并在本地显示
      await loadRemoteHTML(htmlUrl);

      showStatus(
        `成功加载 ${listData.data.dataList.length} 条风险记录`,
        "success"
      );
    } catch (error) {
      showStatus(`加载失败: ${error.message}`, "error");
      console.error("加载数据失败:", error);
    } finally {
      const loadBtn = document.getElementById("load-btn");
      loadBtn.disabled = false;
    }
  }

  // 加载远程HTML并注入main.js
  async function loadRemoteHTML(url) {
    try {
      showStatus("正在下载HTML内容...", "");

      // 获取远程HTML内容
      const response = await fetch(url);
      if (!response.ok) {
        throw new Error(`下载HTML失败: ${response.statusText}`);
      }

      let htmlContent = await response.text();

      // 在HTML中注入main.js和数据
      const scriptInjection = `
        <script>
          // 提供风险数据
          window.__getRiskData = function() {
            return ${JSON.stringify(riskData)};
          };
        </script>
        <script src="main.js?v=${Date.now()}"></script>
        <script>
          // 等待main.js加载完成后重写loadRiskList
          window.addEventListener('load', function() {
            if (typeof window.loadRiskList === 'function') {
              window.loadRiskList = async function() {
                try {
                  const data = window.__getRiskData();
                  if (data && data.success && data.data && data.data.dataList) {
                    if (typeof window.displayRiskList === 'function') {
                      window.displayRiskList(data.data);
                      if (typeof window.showStatus === 'function') {
                        window.showStatus('成功加载 ' + data.data.dataList.length + ' 条风险记录', 'success');
                      }
                    }
                  } else {
                    throw new Error('数据格式错误');
                  }
                } catch (error) {
                  if (typeof window.showStatus === 'function') {
                    window.showStatus('加载失败: ' + error.message, 'error');
                  }
                  console.error('加载风险列表失败:', error);
                }
              };
            }
          });
        </script>
      `;

      // 在</body>之前注入脚本
      htmlContent = htmlContent.replace("</body>", scriptInjection + "</body>");

      // 将处理后的HTML写入iframe
      const iframe = document.getElementById("html-frame");
      const iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
      iframeDoc.open();
      iframeDoc.write(htmlContent);
      iframeDoc.close();

      showStatus("HTML加载成功，可以使用高亮工具", "success");
    } catch (error) {
      showStatus(`加载HTML失败: ${error.message}`, "error");
      console.error("加载HTML失败:", error);
    }
  }

  // 显示状态消息
  function showStatus(message, type) {
    const statusDiv = document.getElementById("status");
    statusDiv.textContent = message;
    statusDiv.className = "status-text";
    if (type) {
      statusDiv.classList.add(type);
    }
  }

  // 页面加载完成后初始化
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
