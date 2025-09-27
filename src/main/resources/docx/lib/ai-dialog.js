// AI对话框功能
(() => {
  // 等待DOM加载完成
  document.addEventListener("DOMContentLoaded", () => {
    // 初始化AI对话功能
    function initAIDialog() {
      // 获取发送按钮和输入框
      const sendBtn = document.getElementById("aiSendBtn");
      const inputArea = document.getElementById("aiInput");
      const chatMessages = document.getElementById("chatMessages");

      if (!sendBtn || !inputArea || !chatMessages) {
        console.warn("AI对话框元素未找到");
        return;
      }

      // 发送消息函数
      function sendMessage() {
        const message = inputArea.value.trim();
        if (!message) return;

        // 获取当前选中的文本（从全局变量）
        const selectedText = window.currentSelectedText || "";

        // 添加用户消息到对话框
        addMessageToChat("user", message);

        // 清空输入框
        inputArea.value = "";

        // 准备发送的数据
        const requestData = {
          question: message,
          context: selectedText,
          elementIds: window.currentSelectedElementIds || [],
        };

        // 发送到AI接口
        if (typeof axios !== "undefined") {
          // 构建请求体
          const apiRequestData = {
            model: "qwen3-14b",
            messages: [
              {
                role: "system",
                content: selectedText
                  ? `请基于以下文本内容回答用户问题：\n${selectedText}`
                  : "你是一个AI助手，请回答用户的问题。",
              },
              {
                role: "user",
                content: message,
              },
            ],
            temperature: 0.4,
            top_p: 0.7,
            repetition_penalty: 1.05,
            max_tokens: 8192,
            response_format: { type: "json_object" },
            stream: false,
            stop: ["<|im_end|>"],
          };

          // 显示加载状态
          addMessageToChat("ai", "正在思考中...");

          axios
            .post(
              "http://49.4.54.140:30901/openapi/model-service/v1/chat/completions",
              apiRequestData,
              {
                headers: {
                  "Content-Type": "application/json",
                  Authorization: "Bearer ai-0lXtWLDkRImSEtwXorLiUjzFWaWcWALu",
                },
              }
            )
            .then((res) => {
              // 移除加载消息
              const messages = chatMessages.querySelectorAll("div");
              if (messages.length > 0) {
                const lastMessage = messages[messages.length - 1];
                if (lastMessage.textContent.includes("正在思考中...")) {
                  lastMessage.remove();
                }
              }

              // 提取AI回复内容
              const aiResponse =
                res.data?.choices?.[0]?.message?.content ||
                res.data?.message ||
                "暂无回复";
              addMessageToChat("ai", aiResponse);
            })
            .catch((err) => {
              // 移除加载消息
              const messages = chatMessages.querySelectorAll("div");
              if (messages.length > 0) {
                const lastMessage = messages[messages.length - 1];
                if (lastMessage.textContent.includes("正在思考中...")) {
                  lastMessage.remove();
                }
              }

              console.error("AI请求失败:", err);
              const errorMsg =
                err.response?.data?.message ||
                err.message ||
                "请求失败，请稍后再试";
              addMessageToChat("ai", `抱歉，${errorMsg}`);
            });
        } else {
          // 如果没有axios，显示错误
          addMessageToChat("ai", "错误：axios库未加载，无法发送请求");
        }
      }

      // 添加消息到聊天区域
      function addMessageToChat(sender, content) {
        const messageDiv = document.createElement("div");
        messageDiv.style.cssText = "margin-bottom: 12px;";

        const senderLabel = document.createElement("div");
        senderLabel.style.cssText = `
          font-size: 12px;
          color: ${sender === "user" ? "#007bff" : "#28a745"};
          margin-bottom: 4px;
          font-weight: 600;
        `;
        senderLabel.textContent = sender === "user" ? "您：" : "AI助手：";

        const messageContent = document.createElement("div");
        messageContent.style.cssText = `
          background: ${sender === "user" ? "#f0f8ff" : "#f0fff0"};
          padding: 8px 12px;
          border-radius: 8px;
          font-size: 14px;
          line-height: 1.5;
          word-wrap: break-word;
        `;
        messageContent.textContent = content;

        messageDiv.appendChild(senderLabel);
        messageDiv.appendChild(messageContent);
        chatMessages.appendChild(messageDiv);

        // 滚动到底部
        const chatArea = document.getElementById("chatArea");
        if (chatArea) {
          chatArea.scrollTop = chatArea.scrollHeight;
        }
      }

      // 绑定发送按钮点击事件
      sendBtn.addEventListener("click", sendMessage);

      // 绑定回车键发送（Shift+Enter换行）
      inputArea.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) {
          e.preventDefault();
          sendMessage();
        }
      });
    }

    // 监听自定义事件，保存最后选中的元素ID
    document.addEventListener("text-selected", (e) => {
      if (e.detail && e.detail.elementIds) {
        window.lastSelectedElementIds = e.detail.elementIds;
      }
    });

    // 初始化
    setTimeout(initAIDialog, 100); // 确保DOM完全加载
  });

  // 全局函数：显示/隐藏AI对话框
  window.toggleAIDialog = function (show) {
    const rightDlg = document.getElementById("rightDlg");
    if (rightDlg) {
      rightDlg.style.display = show ? "block" : "none";
    }
  };

  // 全局函数：设置选中的文本
  window.setSelectedTextInDialog = function (text) {
    const selectedTextContent = document.getElementById("selectedTextContent");
    if (selectedTextContent) {
      selectedTextContent.textContent = text;
    }
  };
})();
