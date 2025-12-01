# -*- coding: utf-8 -*-
"""
把 sciwing\models\sectlabel.py 顶部无条件的 BowElmoEmbedder 导入
改为：仅当非 LITE 时才导入；LITE 下设为 None，避免 allennlp 依赖。
"""
import io
import os
from pathlib import Path

ROOT = Path(r"E:\programFile\AIProgram\docxServer\python\sciwing")
TARGET = ROOT / "sciwing" / "models" / "sectlabel.py"
BACKUP = TARGET.with_suffix(".py.bak_lite")

def main():
    if not TARGET.exists():
        raise SystemExit(f"找不到文件: {TARGET}")

    src = TARGET.read_text(encoding="utf-8", errors="ignore")

    sentinel = "from sciwing.modules.embedders.bow_elmo_embedder import BowElmoEmbedder"
    if sentinel not in src:
        print("未发现需要替换的导入行，可能此前已补丁或源码版本不同。略过。")
        return

    patched = src.replace(
        sentinel,
        (
            "import os\n"
            "if str(os.getenv('SCIWING_LITE','')).lower() in ('1','true','yes','y'):\n"
            "    BowElmoEmbedder = None  # LITE 模式：不触发 allennlp 依赖\n"
            "else:\n"
            "    from sciwing.modules.embedders.bow_elmo_embedder import BowElmoEmbedder\n"
        ),
        1,
    )

    # 备份 + 写回
    BACKUP.write_text(src, encoding="utf-8")
    TARGET.write_text(patched, encoding="utf-8")
    print(f"已打补丁并备份：\n  备份: {BACKUP}\n  目标: {TARGET}")

if __name__ == "__main__":
    main()
