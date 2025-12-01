﻿const express = require('express');
const path = require('path');
const fs = require('fs');
const { DOMParser, XMLSerializer } = require('@xmldom/xmldom');

const app = express();
const PORT = 3000;

const XML_ELEMENT_NODE = 1;
const XML_TEXT_NODE = 3;
let currentProjectFolder = null;

function resolveXmlFilePath(projectFolder = null) {
    const envValue = process.env.XML_FILE_PATH;
    if (envValue) {
        const candidate = path.isAbsolute(envValue) ? envValue : path.join(__dirname, envValue);
        if (!fs.existsSync(candidate)) {
            throw new Error(`环境变量 XML_FILE_PATH 指向的文件不存在: ${candidate}`);
        }
        return candidate;
    }

    // 如果指定了项目文件夹名称，直接使用
    if (projectFolder) {
        const candidate = path.join(__dirname, projectFolder, 'word', 'document.xml');
        if (fs.existsSync(candidate)) {
            return candidate;
        }
        throw new Error(`指定的项目文件夹 "${projectFolder}" 中未找到 document.xml`);
    }

    // 否则自动查找第一个包含document.xml的文件夹
    const entries = fs.readdirSync(__dirname, { withFileTypes: true });
    for (const entry of entries) {
        if (!entry.isDirectory()) continue;
        const candidate = path.join(__dirname, entry.name, 'word', 'document.xml');
        if (fs.existsSync(candidate)) {
            return candidate;
        }
    }

    throw new Error('未找到 document.xml，请设置 XML_FILE_PATH 环境变量指明路径');
}

// 获取当前XML文件路径
function getCurrentXmlFilePath() {
    return resolveXmlFilePath(currentProjectFolder);
}

const xmlCache = new Map(); // 改为Map来支持多项目缓存

app.use((req, res, next) => {
    res.header('Access-Control-Allow-Origin', '*');
    res.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.header('Access-Control-Allow-Headers', 'Content-Type');
    next();
});

app.use(express.json({ limit: '1mb' }));

app.use(express.static(__dirname));

// 设置项目文件夹
app.post('/api/set-project', (req, res) => {
    const { projectFolder } = req.body || {};
    
    if (!projectFolder || typeof projectFolder !== 'string') {
        return res.status(400).json({ success: false, message: '请提供有效的项目文件夹名称' });
    }

    try {
        // 验证文件夹是否存在且包含document.xml
        const xmlPath = resolveXmlFilePath(projectFolder.trim());
        currentProjectFolder = projectFolder.trim();
        
        // 清除缓存，强制重新读取
        xmlCache.doc = null;
        xmlCache.mtimeMs = 0;
        
        res.json({ success: true, message: `已切换到项目文件夹: ${currentProjectFolder}`, xmlPath });
    } catch (error) {
        console.error('设置项目文件夹失败:', error);
        res.status(400).json({ success: false, message: error.message });
    }
});

// 获取可用的项目文件夹列表
app.get('/api/projects', (req, res) => {
    try {
        const entries = fs.readdirSync(__dirname, { withFileTypes: true });
        const projects = [];
        
        for (const entry of entries) {
            if (!entry.isDirectory()) continue;
            const xmlPath = path.join(__dirname, entry.name, 'word', 'document.xml');
            if (fs.existsSync(xmlPath)) {
                projects.push({
                    name: entry.name,
                    path: xmlPath,
                    isCurrent: entry.name === currentProjectFolder
                });
            }
        }
        
        res.json({ success: true, projects });
    } catch (error) {
        console.error('获取项目列表失败:', error);
        res.status(500).json({ success: false, message: '获取项目列表失败' });
    }
});

app.get('/api/document.xml', (req, res) => {
    try {
        const xmlText = readXmlText();
        res.set('Content-Type', 'application/xml; charset=utf-8');
        res.send(xmlText);
    } catch (error) {
        console.error('读取 XML 失败:', error);
        res.status(500).send('无法读取 XML 文件');
    }
});

app.post('/api/search', (req, res) => {
    const { text, mode, value, projectFolder } = req.body || {};

    // 如果请求中包含项目文件夹，先设置它
    if (projectFolder && projectFolder !== currentProjectFolder) {
        try {
            resolveXmlFilePath(projectFolder);
            currentProjectFolder = projectFolder;
        } catch (error) {
            return res.status(400).json({ success: false, message: `无法切换到项目文件夹 "${projectFolder}": ${error.message}` });
        }
    }

    if (typeof text !== 'string' || !text.trim()) {
        return res.status(400).json({ success: false, message: '请输入要搜索的文本内容' });
    }

    if (mode !== 'level' && mode !== 'tag') {
        return res.status(400).json({ success: false, message: '提取方式不正确' });
    }

    const selector = { type: mode, value };

    if (mode === 'level') {
        const level = parseInt(value, 10);
        if (!Number.isInteger(level) || level <= 0) {
            return res.status(400).json({ success: false, message: '父级层数必须是大于 0 的整数' });
        }
        selector.value = level;
    } else {
        if (typeof value !== 'string' || !value.trim()) {
            return res.status(400).json({ success: false, message: '请输入要查找的父级标签' });
        }
        selector.value = value.trim();
    }

    try {
        const xmlDoc = getXmlDocument();
        const results = findTextAndExtractParents(xmlDoc, text, selector);
        const html = buildResultsHtml(results, text, selector, xmlDoc);
        res.json({ success: true, html, count: results.length });
    } catch (error) {
        console.error('服务器搜索出错:', error);
        res.status(500).json({ success: false, message: '服务器处理搜索时出错，请稍后再试。' });
    }
});

app.listen(PORT, () => {
    console.log(`🚀 服务器已启动: http://localhost:${PORT}`);
    console.log(`📄 页面地址: http://localhost:${PORT}/index.html`);
});

function getXmlDocument() {
    try {
        const xmlFilePath = getCurrentXmlFilePath();
        const cacheKey = currentProjectFolder || 'default';
        const stats = fs.statSync(xmlFilePath);
        
        // 检查缓存
        const cached = xmlCache.get(cacheKey);
        if (!cached || stats.mtimeMs !== cached.mtimeMs) {
            const xmlText = fs.readFileSync(xmlFilePath, 'utf8');
            const doc = new DOMParser().parseFromString(xmlText, 'text/xml');
            xmlCache.set(cacheKey, {
                doc: doc,
                mtimeMs: stats.mtimeMs
            });
            console.log(`✅ XML文档已缓存: ${cacheKey}`);
            return doc;
        }
        
        console.log(`✅ 从缓存加载XML文档: ${cacheKey}`);
        return cached.doc;
    } catch (error) {
        const cacheKey = currentProjectFolder || 'default';
        xmlCache.delete(cacheKey);
        throw error;
    }
}

function readXmlText() {
    const xmlFilePath = getCurrentXmlFilePath();
    return fs.readFileSync(xmlFilePath, 'utf8');
}

function findTextAndExtractParents(xmlDoc, searchText, selector) {
    const results = [];
    const textElements = xmlDoc.getElementsByTagName('w:t');
    const trimmedSearch = searchText.trim();

    for (let i = 0; i < textElements.length; i++) {
        const textElement = textElements.item(i);
        const textContent = textElement.textContent || '';

        if (!textContent) continue;

        if (textContent.includes(searchText) || textContent.trim() === trimmedSearch) {
            let parentChain = [];
            let targetParent = null;

            if (selector.type === 'level') {
                parentChain = extractParentChainByLevel(textElement, selector.value);
                targetParent = parentChain.length > 0 ? parentChain[parentChain.length - 1] : null;
            } else {
                targetParent = findParentByTag(textElement, selector.value);
            }

            results.push({
                matchedText: textContent,
                textElement,
                parentChain,
                targetParent,
                selectorType: selector.type,
                selectorValue: selector.value
            });
        }
    }

    return results.map((result, index) => ({ ...result, index: index + 1 }));
}

function extractParentChainByLevel(element, level) {
    const chain = [];
    let current = element;

    for (let i = 0; i < level && current; i++) {
        current = current.parentNode;
        if (!current) {
            break;
        }
        if (current.nodeType === XML_ELEMENT_NODE) {
            chain.push(current);
        }
    }

    return chain;
}

function findParentByTag(element, tagFilter) {
    let current = element.parentNode;
    const lowerFilter = tagFilter.toLowerCase();

    while (current && current.nodeType === XML_ELEMENT_NODE) {
        const tagName = (current.nodeName || '').toLowerCase();
        if (
            tagName === lowerFilter ||
            tagName === `w:${lowerFilter}` ||
            tagName.endsWith(`:${lowerFilter}`) ||
            tagName.includes(lowerFilter)
        ) {
            return current;
        }
        current = current.parentNode;
    }

    return null;
}

function computePositionId(xmlDoc, textElement) {
    if (!textElement) return null;

    // 向上找到各级祖先
    const XML_ELEMENT_NODE = 1;
    function findAncestor(node, name) {
        let cur = node;
        const targets = Array.isArray(name) ? name : [name];
        while (cur && cur.nodeType === XML_ELEMENT_NODE) {
            const n = (cur.nodeName || '').toLowerCase();
            if (targets.some(t => n === t || n === ('w:' + t))) return cur;
            cur = cur.parentNode;
        }
        return null;
    }

    function indexAmongDirectChildren(parent, tag, targetNode) {
        if (!parent || !targetNode) return -1;
        let seen = 0;
        for (let i = 0; i < parent.childNodes.length; i++) {
            const node = parent.childNodes.item(i);
            if (node && node.nodeType === XML_ELEMENT_NODE) {
                const n = (node.nodeName || '').toLowerCase();
                if (n === tag || n === ('w:' + tag)) {
                    seen++;
                    if (node === targetNode) {
                        return seen; // 1-based
                    }
                }
            }
        }
        return -1;
    }

    // 获取各层节点
    const rNode = findAncestor(textElement, 'r');
    const pNode = findAncestor(textElement, 'p');
    const tcNode = findAncestor(textElement, 'tc');
    const trNode = findAncestor(textElement, 'tr');
    const tblNode = findAncestor(textElement, 'tbl');

    if (!rNode || !pNode || !tcNode || !trNode || !tblNode) return null;

    // 运行序号（段内 w:r 的序号）
    const runIndex = indexAmongDirectChildren(pNode, 'r', rNode);

    // 段落序号（单元格内 w:p 的序号）
    const paraIndex = indexAmongDirectChildren(tcNode, 'p', pNode);

    // 单元格序号（行内 w:tc 的序号）
    const cellIndex = indexAmongDirectChildren(trNode, 'tc', tcNode);

    // 行序号（表内 w:tr 的序号，仅统计表层直系子节点）

    const rowIndex = (() => {
        if (!tblNode) return -1;
        let idx = 0, seen = 0;
        for (let i = 0; i < tblNode.childNodes.length; i++) {
            const node = tblNode.childNodes.item(i);
            if (node && node.nodeType === XML_ELEMENT_NODE) {
                const n = (node.nodeName || '').toLowerCase();
                if (n === 'w:tr' || n === 'tr') {
                    seen++;
                    if (node === trNode) {
                        idx = seen;
                        break;
                    }
                }
            }
        }
        return idx;
    })();

    // 表序号（整篇文档中第几个 w:tbl，按文档顺序）
    const tbls = xmlDoc.getElementsByTagName('w:tbl');
    let tableIndex = -1;
    for (let i = 0; i < tbls.length; i++) {
        if (tbls.item(i) === tblNode) {
            tableIndex = i + 1; // 1-based
            break;
        }
    }
    if (tableIndex < 1 || rowIndex < 1 || cellIndex < 1 || paraIndex < 1 || runIndex < 1) return null;

    return `t${tableIndex}-r${rowIndex}-c${cellIndex}-p${paraIndex}-r${runIndex}`;
}

function buildResultsHtml(results, searchText, selector, xmlDoc) {
    if (!results.length) {
        return `
            <div class="info">
                未找到包含 "<strong>${escapeHtml(searchText)}</strong>" 的文本
            </div>
        `;
    }

    const extractDesc = selector.type === 'level'
        ? `向上 <strong>${selector.value}</strong> 层`
        : `最近的 <strong>${escapeHtml(String(selector.value))}</strong> 标签`;

    let html = `
        <div class="info">
            找到 <strong>${results.length}</strong> 个匹配项，提取方式: ${extractDesc}
        </div>
        <div style="margin:8px 0;color:#666;font-size:12px;">
          点击“查看对应HTML”将在下方结果中预览 docx.html 片段，并在预览 iframe 中高亮定位。
        </div>
    `;

    // 基于字符串长度限制，而不是简单的结果数量限制
    const maxResponseSize = 100000000; // 提高响应大小上限，避免截断
    let currentSize = html.length;
    let processedCount = 0;
    let truncatedBySize = false;

    for (let i = 0; i < results.length; i++) {
        const result = results[i];
        const hasTarget = Boolean(result.targetParent);
        const borderColor = hasTarget ? '#4CAF50' : '#FF9800';
        const escapedSelectorValue = escapeHtml(String(result.selectorValue));

        try {
            // 先渲染内容，检查大小
            const renderedContent = hasTarget ? renderParentChain(result, maxResponseSize - currentSize) : '';

            // 计算位置信息，供前端联动 docx.html
            let posId = null;
            try {
                posId = computePositionId(xmlDoc, result.textElement);
            } catch (e) {
                posId = null;
            }
            
            const controls = posId ? `
              <div style="margin:10px 0;">
                <button
                  class="btn-view-html"
                  data-pos="${posId}"
                  data-index="${result.index}"
                  data-sel-type="${result.selectorType}"
                  data-sel-val="${result.selectorValue}"
                  style="background:#2196F3"
                >查看对应HTML</button>
                <span style="margin-left:8px;color:#666;font-size:12px">定位ID: ${posId}</span>
              </div>
              <div class="html-preview" id="html-preview-${result.index}" style="margin-top:10px;padding:10px;background:#fafafa;border:1px dashed #ccc;border-radius:4px;"></div>
            ` : `
              <div style="margin:10px 0;color:#999;">未能计算到对应的 HTML 定位信息</div>
            `;

            const itemHtml = `
                <div style="margin-bottom: 30px; padding: 15px; background: white; border-radius: 5px; border: 2px solid ${borderColor};">
                    <h3 style="color: ${borderColor}; margin-bottom: 10px;">
                        ✅ 匹配项 ${result.index}
                        ${!hasTarget ? '<span style="font-size: 14px; color: #FF9800;">（未找到目标父元素）</span>' : ''}
                    </h3>
                    <div style="margin-bottom: 15px;">
                        <strong>匹配文本：</strong>
                        <span class="text-content highlight">${escapeHtml(result.matchedText)}</span>
                    </div>
                    ${controls}

                    ${hasTarget ? `
                    <div style="margin-top: 15px;">
                        <strong>${result.selectorType === 'level' ? `目标父级（向上 ${result.selectorValue} 层）` : '目标标签元素'}</strong>
                        <div class="xml-tree" style="margin-top: 10px;">
                            ${renderedContent}
                        </div>
                    </div>
                    ` : `
                    <div style="margin-top: 15px; color: #999;">
                        未找到 "${escapedSelectorValue}" 的父级元素
                    </div>
                    `}
                </div>
            `;

            // 检查添加这个项目后是否会超出大小限制
            if (currentSize + itemHtml.length > maxResponseSize) {
                truncatedBySize = true;
                break;
            }

            html += itemHtml;
            currentSize += itemHtml.length;
            processedCount++;

        } catch (error) {
            console.error('渲染匹配项出错:', error);
            const errorHtml = `
                <div style="margin-bottom: 30px; padding: 15px; background: #ffebee; border-radius: 5px; border: 2px solid #f44336;">
                    <h3 style="color: #f44336; margin-bottom: 10px;">
                        ❌ 匹配项 ${result.index} - 渲染失败
                    </h3>
                    <div style="margin-bottom: 15px;">
                        <strong>匹配文本：</strong>
                        <span class="text-content highlight">${escapeHtml(result.matchedText)}</span>
                    </div>
                    <div style="color: #666;">
                        内容过大或格式复杂，无法完整显示
                    </div>
                </div>
            `;
            
            if (currentSize + errorHtml.length <= maxResponseSize) {
                html += errorHtml;
                currentSize += errorHtml.length;
                processedCount++;
            } else {
                truncatedBySize = true;
                break;
            }
        }
    }

    // 添加截断提示
    if (truncatedBySize || processedCount < results.length) {
        const sizeInfo = Math.round(currentSize / 1024);
        html += `
            <div class="info" style="background: #fff3cd; color: #856404;">
                ⚠️ 响应内容已达到大小限制 (${sizeInfo}KB)，显示了 ${processedCount} 个匹配项（共 ${results.length} 个）<br>
                建议使用更具体的搜索条件或选择较小的父级层数来减少内容量
            </div>
        `;
    }

    return html;
}

function renderParentChain(result, maxSize = 100000) {
    if (result.selectorType === 'tag') {
        if (!result.targetParent) {
            return '';
        }
        return `<pre style="margin-left: 0px; background: #e8f5e9; padding: 10px; border-left: 4px solid #4CAF50;">${escapeHtml(new XMLSerializer().serializeToString(result.targetParent))}</pre>`;
    }

    let html = '';
    let currentSize = 0;

    // 当前文本节点部分
    const textNodeHtml = `<div style="margin-bottom: 10px;">
        <div style="color: #666; font-size: 12px; margin-bottom: 5px;">当前文本节点</div>
        <pre style="margin-left: 0px;">${escapeHtml(new XMLSerializer().serializeToString(result.textElement))}</pre>
    </div>`;
    
    html += textNodeHtml;
    currentSize += textNodeHtml.length;

    const targetParent = result.targetParent || (result.parentChain.length ? result.parentChain[result.parentChain.length - 1] : null);

    if (targetParent && currentSize < maxSize) {
        const remainingSize = maxSize - currentSize;
        const parentHtml = `<div style="margin-top: 15px; padding-top: 15px; border-top: 2px dashed #ddd;">
            <div style="color: #666; font-size: 12px; margin-bottom: 5px;">目标父级（向上 ${result.selectorValue} 层）</div>
            <pre style="margin-left: 0px; background: #e8f5e9; padding: 10px; border-left: 4px solid #4CAF50;">${escapeHtml(new XMLSerializer().serializeToString(targetParent))}</pre>
        </div>`;
        
        html += parentHtml;
    }

    return html;
}

function renderElementWithSizeLimit(element, highlightText, level, isTarget, maxSize) {
    if (!element || element.nodeType !== XML_ELEMENT_NODE || maxSize <= 0) {
        return '';
    }

    const indent = level * 10;
    const tagName = element.nodeName || 'unknown';
    const targetStyle = isTarget ? 'background: #e8f5e9; padding: 10px; border-left: 4px solid #4CAF50;' : '';

    let html = `<div style="margin-left: ${indent}px; ${targetStyle}">`;
    html += `<span class="tag-name">&lt;${tagName}</span>`;

    // 处理属性
    if (element.attributes && element.attributes.length > 0) {
        const maxAttrs = 3; // 减少属性数量
        const attrCount = element.attributes.length;
        
        for (let i = 0; i < Math.min(attrCount, maxAttrs); i++) {
            const attr = element.attributes.item(i);
            const attrValue = attr.value.length > 30 ? attr.value.substring(0, 30) + '...' : attr.value;
            html += ` <span class="attr-name">${attr.name}</span>=<span class="attr-value">"${escapeHtml(attrValue)}"</span>`;
        }
        
        if (attrCount > maxAttrs) {
            html += ` <span style="color: #999;">... (+${attrCount - maxAttrs})</span>`;
        }
    }

    html += `<span class="tag-name">&gt;</span>`;

    // 检查当前大小
    if (html.length > maxSize * 0.8) {
        html += `<span style="color: #999; font-style: italic;"> ... (内容过大，已截断)</span>`;
        html += `<span class="tag-name">&lt;/${tagName}&gt;</span></div>`;
        return html;
    }

    const childNodes = element.childNodes ? nodeListToArray(element.childNodes) : [];

    if (childNodes.length > 0) {
        const maxChildren = Math.min(10, childNodes.length); // 限制子节点数量
        const remainingSize = maxSize - html.length;
        const sizePerChild = Math.floor(remainingSize / maxChildren);
        
        html += `<div class="element">`;
        
        let processedChildren = 0;
        for (let i = 0; i < childNodes.length && processedChildren < maxChildren; i++) {
            const node = childNodes[i];
            if (!node) continue;
            
            if (node.nodeType === XML_ELEMENT_NODE) {
                const childHtml = renderElementWithSizeLimit(node, highlightText, level + 1, false, sizePerChild);
                if (html.length + childHtml.length > maxSize) {
                    html += `<div style="margin-left: ${(level + 1) * 10}px; color: #999; font-style: italic;">... (剩余内容过大，已截断)</div>`;
                    break;
                }
                html += childHtml;
                processedChildren++;
            } else if (node.nodeType === XML_TEXT_NODE) {
                const rawText = node.data || '';
                const trimmed = rawText.trim();
                if (trimmed) {
                    const displayText = trimmed.length > 100 ? trimmed.substring(0, 100) + '...' : trimmed;
                    const highlighted = displayText.includes(highlightText)
                        ? `<span class="text-content highlight">${escapeHtml(displayText)}</span>`
                        : `<span class="text-content">${escapeHtml(displayText)}</span>`;
                    html += `<div class="text-node" style="margin-left: ${(level + 1) * 10}px;">${highlighted}</div>`;
                }
            }
        }
        
        if (processedChildren < childNodes.length) {
            html += `<div style="margin-left: ${(level + 1) * 10}px; color: #999; font-style: italic;">... (还有 ${childNodes.length - processedChildren} 个子节点)</div>`;
        }
        
        html += `</div>`;
    } else {
        const text = element.textContent ? element.textContent.trim() : '';
        if (text) {
            const displayText = text.length > 200 ? text.substring(0, 200) + '...' : text;
            if (displayText.includes(highlightText)) {
                html += `<span class="text-content highlight">${escapeHtml(displayText)}</span>`;
            } else {
                html += `<span class="text-content">${escapeHtml(displayText)}</span>`;
            }
        }
    }

    html += `<span class="tag-name">&lt;/${tagName}&gt;</span>`;
    html += `</div>`;

    return html;
}

function renderElement(element, highlightText, level, isTarget, maxDepth = 5) {
    if (!element || element.nodeType !== XML_ELEMENT_NODE) {
        return '';
    }

    // 限制渲染深度，避免内容过大
    if (level > maxDepth) {
        return `<div style="margin-left: ${level * 10}px; color: #999; font-style: italic;">... (内容过深，已省略)</div>`;
    }

    const indent = level * 10;
    const tagName = element.nodeName || 'unknown';
    const targetStyle = isTarget ? 'background: #e8f5e9; padding: 10px; border-left: 4px solid #4CAF50;' : '';

    let html = `<div style="margin-left: ${indent}px; ${targetStyle}">`;
    html += `<span class="tag-name">&lt;${tagName}</span>`;

    // 限制属性数量显示
    if (element.attributes && element.attributes.length > 0) {
        const maxAttrs = 5;
        const attrCount = element.attributes.length;
        
        for (let i = 0; i < Math.min(attrCount, maxAttrs); i++) {
            const attr = element.attributes.item(i);
            const attrValue = attr.value.length > 50 ? attr.value.substring(0, 50) + '...' : attr.value;
            html += ` <span class="attr-name">${attr.name}</span>=<span class="attr-value">"${escapeHtml(attrValue)}"</span>`;
        }
        
        if (attrCount > maxAttrs) {
            html += ` <span style="color: #999;">... (+${attrCount - maxAttrs} 个属性)</span>`;
        }
    }

    html += `<span class="tag-name">&gt;</span>`;

    const childNodes = element.childNodes ? nodeListToArray(element.childNodes) : [];

    if (childNodes.length > 0) {
        // 限制子节点数量
        const maxChildren = 20;
        const childCount = childNodes.length;
        
        html += `<div class="element">`;
        
        childNodes.slice(0, maxChildren).forEach((node) => {
            if (!node) return;
            if (node.nodeType === XML_ELEMENT_NODE) {
                html += renderElement(node, highlightText, level + 1, false, maxDepth);
            } else if (node.nodeType === XML_TEXT_NODE) {
                const rawText = node.data || '';
                const trimmed = rawText.trim();
                if (trimmed) {
                    // 限制文本长度
                    const displayText = trimmed.length > 200 ? trimmed.substring(0, 200) + '...' : trimmed;
                    const highlighted = displayText.includes(highlightText)
                        ? `<span class="text-content highlight">${escapeHtml(displayText)}</span>`
                        : `<span class="text-content">${escapeHtml(displayText)}</span>`;
                    html += `<div class="text-node" style="margin-left: ${(level + 1) * 10}px;">${highlighted}</div>`;
                }
            }
        });
        
        if (childCount > maxChildren) {
            html += `<div style="margin-left: ${(level + 1) * 10}px; color: #999; font-style: italic;">... (还有 ${childCount - maxChildren} 个子节点)</div>`;
        }
        
        html += `</div>`;
    } else {
        const text = element.textContent ? element.textContent.trim() : '';
        if (text) {
            // 限制文本长度
            const displayText = text.length > 300 ? text.substring(0, 300) + '...' : text;
            if (displayText.includes(highlightText)) {
                html += `<span class="text-content highlight">${escapeHtml(displayText)}</span>`;
            } else {
                html += `<span class="text-content">${escapeHtml(displayText)}</span>`;
            }
        }
    }

    html += `<span class="tag-name">&lt;/${tagName}&gt;</span>`;
    html += `</div>`;

    return html;
}

function nodeListToArray(nodeList) {
    const items = [];
    if (!nodeList || typeof nodeList.length !== 'number') {
        return items;
    }
    for (let i = 0; i < nodeList.length; i++) {
        const item = nodeList.item(i);
        if (item) {
            items.push(item);
        }
    }
    return items;
}

function escapeHtml(text) {
    if (typeof text !== 'string') {
        return text;
    }
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}






