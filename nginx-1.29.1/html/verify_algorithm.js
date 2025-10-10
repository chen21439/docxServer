// 临时验证脚本：检查算法给的 start/end 是否正确
(async function() {
  console.log("================================================================================");
  console.log("开始验证算法 start/end 是否正确");
  console.log("================================================================================");

  // 1. 加载 tags.txt
  const tagsResponse = await fetch("http://localhost:8080/htmlUnitTest/1973557704605679618_tags.txt");
  const tagsText = await tagsResponse.text();
  console.log("✅ 已加载 tags.txt");

  // 2. 加载 list.json
  const listResponse = await fetch("http://localhost:8080/htmlUnitTest/list.json");
  const listData = await listResponse.json();
  console.log("✅ 已加载 list.json");

  // 3. 提取段落文本的函数
  function extractTextFromTags(tagsText, paragraphId) {
    const regex = new RegExp(`<p id="${paragraphId}">(.*?)</p>`, 's');
    const match = tagsText.match(regex);
    if (match && match[1]) {
      return match[1].replace(/<[^>]+>/g, '');
    }
    return null;
  }

  // 4. 统计变量
  let totalSpans = 0;
  let algorithmCorrect = 0;
  let algorithmError = 0;
  const errors = [];

  // 5. 遍历所有风险项
  const dataList = listData.data.dataList;
  dataList.forEach((item, itemIndex) => {
    if (!item.spanLocate || !item.spanList || item.spanList.length === 0) {
      return;
    }

    // 去重
    const uniqueSpans = new Map();
    item.spanList.forEach((span) => {
      const key = `${span.aiSourceRiskText || ""}_${span.aiSourceStart}_${span.aiSourceEnd}`;
      if (!uniqueSpans.has(key)) {
        uniqueSpans.set(key, span);
      }
    });

    // 验证每个唯一的 span
    Array.from(uniqueSpans.values()).forEach((span) => {
      totalSpans++;

      const aiSourceId = span.aiSourceId;
      const aiSourceStart = span.aiSourceStart;
      const aiSourceEnd = span.aiSourceEnd;
      const expectedText = span.aiSourceRiskText || '';

      // 从 tags.txt 提取段落文本
      const paragraphText = extractTextFromTags(tagsText, aiSourceId);

      if (!paragraphText) {
        console.warn(`⚠ 未在 tags.txt 中找到段落: ${aiSourceId}`);
        return;
      }

      // 用算法给的 start/end 截取文本
      const actualText = paragraphText.substring(aiSourceStart, aiSourceEnd);

      // 比对
      if (actualText === expectedText) {
        algorithmCorrect++;
      } else {
        algorithmError++;
        errors.push({
          itemIndex: itemIndex + 1,
          itemName: item.reviewItemName,
          sceneDesc: item.sceneDesc,
          aiSourceId,
          range: `[${aiSourceStart}, ${aiSourceEnd})`,
          expected: expectedText,
          actual: actualText,
          paragraphLength: paragraphText.length
        });
      }
    });
  });

  // 6. 输出结果
  console.log("\n" + "=".repeat(80));
  console.log("验证结果统计:");
  console.log("=".repeat(80));
  console.log(`总计检查: ${totalSpans} 个文本片段`);
  console.log(`✅ 算法正确: ${algorithmCorrect} 个 (${((algorithmCorrect / totalSpans) * 100).toFixed(2)}%)`);
  console.log(`❌ 算法错误: ${algorithmError} 个 (${((algorithmError / totalSpans) * 100).toFixed(2)}%)`);
  console.log("=".repeat(80));

  if (errors.length > 0) {
    console.log("\n算法错误详情:");
    console.log("=".repeat(80));
    errors.forEach((err, idx) => {
      console.log(`\n第 ${idx + 1} 个错误:`);
      console.log(`  风险项 #${err.itemIndex}: ${err.itemName} - ${err.sceneDesc}`);
      console.log(`  段落ID: ${err.aiSourceId}`);
      console.log(`  范围: ${err.range}`);
      console.log(`  段落长度: ${err.paragraphLength} 字符`);
      console.log(`  期望文本 (${err.expected.length}字符): "${err.expected}"`);
      console.log(`  实际文本 (${err.actual.length}字符): "${err.actual}"`);

      // 显示差异
      if (err.expected.length === err.actual.length) {
        // 长度相同，找出不同的字符
        let diffPos = [];
        for (let i = 0; i < err.expected.length; i++) {
          if (err.expected[i] !== err.actual[i]) {
            diffPos.push(i);
          }
        }
        if (diffPos.length > 0 && diffPos.length < 20) {
          console.log(`  差异位置: ${diffPos.join(', ')}`);
        }
      } else {
        console.log(`  长度差异: ${err.actual.length - err.expected.length} 字符`);
      }
    });
    console.log("\n" + "=".repeat(80));
  }

  console.log("\n✅ 验证完成！");

  if (algorithmError === 0) {
    console.log("🎉 所有算法给的 start/end 都是正确的！问题出在前端 HTML 提取逻辑。");
  } else {
    console.log("⚠ 算法本身存在位置错误，需要检查算法的文本提取和位置计算逻辑。");
  }
})();
