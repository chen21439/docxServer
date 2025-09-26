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

      // 提取ownerEl的ID
      const ownerElId = ownerEl?.id || null;

      // 业务：打印或上报
      const payload = {
        text: sel.toString(),
        ownerEl,
        ownerElId, // 添加ID字段
        anchorEl,
        focusEl,
        downTarget,
        upTarget,
        fromCell,
      };
      console.log("text-selected:", payload);
      console.log("选中文本所在元素ID:", ownerElId || "该元素无ID");

      if (ownerElId != null) {
        axios
          .post("http://localhost:8080/docx/comment", [ownerElId], {
            headers: { "Content-Type": "application/json" },
          })
          .then((res) => {
            console.log(res.data);
          });
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

  // 4) 兼容键盘选择（Shift + 方向键等），没有鼠标事件时也能捕获
  let keyboardDebounce = null;
  document.addEventListener("selectionchange", () => {
    const sel = document.getSelection?.();
    if (!sel || sel.isCollapsed) return;

    clearTimeout(keyboardDebounce);
    keyboardDebounce = setTimeout(() => {
      const range = sel.rangeCount ? sel.getRangeAt(0) : null;
      if (!range) return;
      const ownerNode = range.commonAncestorContainer;
      const ownerEl =
        ownerNode.nodeType === Node.ELEMENT_NODE
          ? ownerNode
          : ownerNode.parentElement;

      // 提取ownerEl的ID（键盘选择）
      const ownerElId = ownerEl?.id || null;
      console.log("键盘选中文本所在元素ID:", ownerElId || "该元素无ID");

      (ownerEl || document).dispatchEvent(
        new CustomEvent("text-selected", {
          bubbles: true,
          detail: {
            text: sel.toString(),
            ownerEl,
            ownerElId, // 添加ID字段
            anchorEl: sel.anchorNode?.parentElement || sel.anchorNode,
            focusEl: sel.focusNode?.parentElement || sel.focusNode,
            downTarget: null,
            upTarget: null,
            fromCell: ownerEl?.closest?.("td,th,[data-selectable],p,li"),
          },
        })
      );
    }, 0); // 微任务后执行，确保选区稳定
  });
})();
