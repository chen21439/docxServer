# -*- coding: utf-8 -*-
# 适配：Python 3.7，已 pip install sciwing （以及其依赖）
# 作用：实例化 SciWING 预训练模型（NeuralParscit），
#       首次运行会自动下载所需的权重与嵌入，然后做一次预测。

import argparse
import sys
from sciwing.models.neural_parscit import NeuralParscit

def main():
    parser = argparse.ArgumentParser(
        description="Auto-download and run a pretrained SciWING model once."
    )
    parser.add_argument(
        "--text",
        default="Calzolari, N. (1982) Towards the organization of lexical definitions...",
        help="Input string to parse (a citation string).",
    )
    args = parser.parse_args()

    # 实例化即会触发：若本地无权重/嵌入 -> 自动下载到缓存目录，然后加载
    model = NeuralParscit()

    # 跑一次推理（确保下载完成且模型可用）
    pred = model.predict_for_text(args.text)
    print(pred)

if __name__ == "__main__":
    if sys.version_info[:2] != (3, 7):
        sys.stderr.write("Warning: SciWING 官方仅支持 Python 3.7，建议在 3.7 环境中运行。\n")
    main()
