const express = require('express');
const path = require('path');
const fs = require('fs');
const { DOMParser } = require('@xmldom/xmldom');

const app = express();
const PORT = 3000;

const XML_ELEMENT_NODE = 1;
const XML_TEXT_NODE = 3;
const XML_FILE_PATH = resolveXmlFilePath();
function resolveXmlFilePath() {
    const envValue = process.env.XML_FILE_PATH;
    if (envValue) {
        const candidate = path.isAbsolute(envValue) ? envValue : path.join(__dirname, envValue);
        if (!fs.existsSync(candidate)) {
            throw new Error(`环境变量 XML_FILE_PATH 指向的文件不存在: ${candidate}`);
        }
        return candidate;
    }

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

const xmlCache = {
    doc: null,
    mtimeMs: 0
};

app.use((req, res, next) => {
    res.header('Access-Control-Allow-Origin', '*');
    res.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.header('Access-Control-Allow-Headers', 'Content-Type');
    next();
});

app.use(express.json({ limit: '1mb' }));

app.use(express.static(__dirname));

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
    const { text, mode, value } = req.body || {};

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
        const html = buildResultsHtml(results, text, selector);
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
        const stats = fs.statSync(XML_FILE_PATH);
        if (!xmlCache.doc || stats.mtimeMs !== xmlCache.mtimeMs) {
            const xmlText = fs.readFileSync(XML_FILE_PATH, 'utf8');
            xmlCache.doc = new DOMParser().parseFromString(xmlText, 'text/xml');
            xmlCache.mtimeMs = stats.mtimeMs;
        }
        return xmlCache.doc;
    } catch (error) {
        xmlCache.doc = null;
        xmlCache.mtimeMs = 0;
        throw error;
    }
}

function readXmlText() {
    return fs.readFileSync(XML_FILE_PATH, 'utf8');
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

function buildResultsHtml(results, searchText, selector) {
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
    `;

    results.forEach((result) => {
        const hasTarget = Boolean(result.targetParent);
        const borderColor = hasTarget ? '#4CAF50' : '#FF9800';
        const escapedSelectorValue = escapeHtml(String(result.selectorValue));

        html += `
            <div style="margin-bottom: 30px; padding: 15px; background: white; border-radius: 5px; border: 2px solid ${borderColor};">
                <h3 style="color: ${borderColor}; margin-bottom: 10px;">
                    ✅ 匹配项 ${result.index}
                    ${!hasTarget ? '<span style="font-size: 14px; color: #FF9800;">（未找到目标父元素）</span>' : ''}
                </h3>
                <div style="margin-bottom: 15px;">
                    <strong>匹配文本：</strong>
                    <span class="text-content highlight">${escapeHtml(result.matchedText)}</span>
                </div>

                ${hasTarget ? `
                <div style="margin-top: 15px;">
                    <strong>${result.selectorType === 'level' ? `目标父级（向上 ${result.selectorValue} 层）` : '目标标签元素'}</strong>
                    <div class="xml-tree" style="margin-top: 10px;">
                        ${renderParentChain(result)}
                    </div>
                </div>
                ` : `
                <div style="margin-top: 15px; color: #999;">
                    未找到 "${escapedSelectorValue}" 的父级元素
                </div>
                `}
            </div>
        `;
    });

    return html;
}

function renderParentChain(result) {
    if (result.selectorType === 'tag') {
        if (!result.targetParent) {
            return '';
        }
        return renderElement(result.targetParent, result.matchedText, 0, true);
    }

    let html = '';

    html += `<div style="margin-bottom: 10px;">`;
    html += `<div style="color: #666; font-size: 12px; margin-bottom: 5px;">当前文本节点</div>`;
    html += renderElement(result.textElement, result.matchedText, 0, false);
    html += `</div>`;

    const targetParent = result.targetParent || (result.parentChain.length ? result.parentChain[result.parentChain.length - 1] : null);

    if (targetParent) {
        html += `<div style="margin-top: 15px; padding-top: 15px; border-top: 2px dashed #ddd;">`;
        html += `<div style="color: #666; font-size: 12px; margin-bottom: 5px;">目标父级（向上 ${result.selectorValue} 层）</div>`;
        html += renderElement(targetParent, result.matchedText, 1, true);
        html += `</div>`;
    }

    return html;
}

function renderElement(element, highlightText, level, isTarget) {
    if (!element || element.nodeType !== XML_ELEMENT_NODE) {
        return '';
    }

    const indent = level * 10;
    const tagName = element.nodeName || 'unknown';
    const targetStyle = isTarget ? 'background: #e8f5e9; padding: 10px; border-left: 4px solid #4CAF50;' : '';

    let html = `<div style="margin-left: ${indent}px; ${targetStyle}">`;
    html += `<span class="tag-name">&lt;${tagName}</span>`;

    if (element.attributes && element.attributes.length > 0) {
        for (let i = 0; i < element.attributes.length; i++) {
            const attr = element.attributes.item(i);
            html += ` <span class="attr-name">${attr.name}</span>=<span class="attr-value">"${escapeHtml(attr.value)}"</span>`;
        }
    }

    html += `<span class="tag-name">&gt;</span>`;

    const childNodes = element.childNodes ? nodeListToArray(element.childNodes) : [];

    if (childNodes.length > 0) {
        html += `<div class="element">`;
        childNodes.forEach((node) => {
            if (!node) return;
            if (node.nodeType === XML_ELEMENT_NODE) {
                html += renderElement(node, highlightText, level + 1, false);
            } else if (node.nodeType === XML_TEXT_NODE) {
                const rawText = node.data || '';
                const trimmed = rawText.trim();
                if (trimmed) {
                    const highlighted = trimmed.includes(highlightText)
                        ? `<span class="text-content highlight">${escapeHtml(trimmed)}</span>`
                        : `<span class="text-content">${escapeHtml(trimmed)}</span>`;
                    html += `<div class="text-node" style="margin-left: ${(level + 1) * 10}px;">${highlighted}</div>`;
                }
            }
        });
        html += `</div>`;
    } else {
        const text = element.textContent ? element.textContent.trim() : '';
        if (text) {
            if (text.includes(highlightText)) {
                html += `<span class="text-content highlight">${escapeHtml(text)}</span>`;
            } else {
                html += `<span class="text-content">${escapeHtml(text)}</span>`;
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






