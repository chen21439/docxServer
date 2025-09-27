(() => {
  let lastPointerDown = null;

  // 1) 记录用户最初按下时的元素（谁"发起"的）
  document.addEventListener(
    "pointerdown",
    (e) => {
      lastPointerDown = e.target;
    },
    true
  ); // 捕获阶段更稳

  // 2) 在松开时读取当前选区 & 源元素
  document.addEventListener(
    "pointerup",
    (e) => {
      const sel = window.getSelection?.();
      if (!sel || sel.isCollapsed) return; // 没有实际选中

      // 选区两端对应的元素
      const anchorEl =
        sel.anchorNode?.nodeType === Node.ELEMENT_NODE
          ? sel.anchorNode
          : sel.anchorNode?.parentElement;

      const focusEl =
        sel.focusNode?.nodeType === Node.ELEMENT_NODE
          ? sel.focusNode
          : sel.focusNode?.parentElement;

      // 这次选区的"归属"容器（两端的最近公共祖先）
      const range = sel.getRangeAt(0);
      const ownerNode = range.commonAncestorContainer;
      const ownerEl =
        ownerNode.nodeType === Node.ELEMENT_NODE
          ? ownerNode
          : ownerNode.parentElement;

      // 触发（松开）事件的元素 & 起始按下的元素
      const upTarget = e.target;
      const downTarget = lastPointerDown;

      // 如果你想知道是哪个单元格/段落触发，可用 closest：
      const fromCell = downTarget?.closest?.("td,th,[data-selectable],p,li");

      // 获取选区中所有带ID的元素
      const elementIds = [];
      const collectIds = (node) => {
        if (node.nodeType === Node.ELEMENT_NODE && node.id) {
          if (!elementIds.includes(node.id)) {
            elementIds.push(node.id);
          }
        }
        // 递归检查子元素
        if (node.childNodes) {
          for (let child of node.childNodes) {
            collectIds(child);
          }
        }
      };

      // 如果选区在单个容器内，收集该容器及其内部元素的ID
      if (range.startContainer === range.endContainer) {
        collectIds(ownerEl);
      } else {
        // 选区跨越多个元素，遍历选区内的所有节点
        const walker = document.createTreeWalker(
          ownerEl,
          NodeFilter.SHOW_ELEMENT,
          {
            acceptNode: (node) => {
              // 检查节点是否在选区内
              const nodeRange = document.createRange();
              nodeRange.selectNode(node);
              if (
                range.compareBoundaryPoints(Range.START_TO_END, nodeRange) >
                  0 &&
                range.compareBoundaryPoints(Range.END_TO_START, nodeRange) < 0
              ) {
                return NodeFilter.FILTER_ACCEPT;
              }
              return NodeFilter.FILTER_SKIP;
            },
          }
        );

        let node;
        while ((node = walker.nextNode())) {
          if (node.id) {
            elementIds.push(node.id);
          }
        }

        // 确保包含ownerEl的ID
        if (ownerEl.id && !elementIds.includes(ownerEl.id)) {
          elementIds.push(ownerEl.id);
        }
      }

      // 业务：打印或上报
      const payload = {
        text: sel.toString(),
        ownerEl,
        elementIds, // 使用数组保存所有ID
        anchorEl,
        focusEl,
        downTarget,
        upTarget,
        fromCell,
      };
      console.log("text-selected:", payload);
      console.log(
        "选中文本涉及的元素IDs:",
        elementIds.length > 0 ? elementIds : "无带ID的元素"
      );

      // 检查comment单选按钮是否勾选
      const commentRadio = document.getElementById("comment");
      console.log("Comment radio element:", commentRadio);
      console.log("Comment radio checked property:", commentRadio?.checked);

      if (commentRadio && commentRadio.checked) {
        console.log("Comment is checked, sending request...");
        if (elementIds.length > 0) {
          axios
            .post("http://localhost:8080/docx/comment", elementIds, {
              headers: { "Content-Type": "application/json" },
            })
            .then((res) => {
              console.log(res.data);
            });
        }
      }

      // 先保存选中的文本，因为高亮功能会清除选区
      const selectedTextForAI = sel.toString();

      // 检查highlight单选按钮是否勾选
      const highlightRadio = document.getElementById("highlight");
      console.log("Highlight radio checked:", highlightRadio?.checked);

      if (highlightRadio && highlightRadio.checked) {
        // 高亮选中的文字
        const range = sel.getRangeAt(0);
        const span = document.createElement("span");
        span.style.backgroundColor = "#ffff00"; // 黄色高亮
        span.className = "text-highlight";

        try {
          // 包裹选中的内容
          range.surroundContents(span);
        } catch (e) {
          // 如果选区跨越多个元素，使用extractContents方法
          const contents = range.extractContents();
          span.appendChild(contents);
          range.insertNode(span);
        }

        // 清除选区
        sel.removeAllRanges();
      }

      // 检查AIAcquisition单选按钮是否勾选
      const aiRadio = document.getElementById("AIAcquisition");
      console.log("AI radio checked:", aiRadio?.checked);

      if (aiRadio && aiRadio.checked) {
        // 显示右侧对话框
        const rightDlg = document.getElementById("rightDlg");

        if (rightDlg) {
          rightDlg.style.display = "block";

          // 使用之前保存的选中文本
          const selectedText = selectedTextForAI;

          // 将选中的文本显示在对话框中
          const selectedTextContent = document.getElementById(
            "selectedTextContent"
          );

          if (selectedTextContent) {
            selectedTextContent.textContent = selectedText;
            // 保存到全局变量，供后续使用
            window.currentSelectedText = selectedText;
            window.currentSelectedElementIds = elementIds;
          }

          // 自动聚焦到输入框
          const aiInput = document.getElementById("aiInput");
          if (aiInput) {
            setTimeout(() => aiInput.focus(), 100); // 延迟一下确保对话框完全显示
          }
        }
      }

      // 3) 可选：派发一个自定义事件，方便统一监听
      (ownerEl || document).dispatchEvent(
        new CustomEvent("text-selected", {
          bubbles: true,
          detail: payload,
        })
      );
    },
    true
  );
})();
