# -*- coding: utf-8 -*-
import argparse
import os
import sys
from allennlp.modules.elmo import Elmo, batch_to_ids
import torch

DEFAULT_OPTIONS = r"E:\models\elmo\elmo_2x4096_512_2048cnn_2xhighway_options.json"
DEFAULT_WEIGHTS = r"E:\models\elmo\elmo_2x4096_512_2048cnn_2xhighway_weights.hdf5"

def resolve_path(cli_value: str, env_key: str, default_value: str) -> str:
    # 优先用命令行，其次环境变量，最后默认值
    path = cli_value or os.environ.get(env_key) or default_value
    if not path or not os.path.isfile(path):
        raise FileNotFoundError(f"文件不存在：{path}（可用 --{env_key[5:].lower()} 或设置环境变量 {env_key}）")
    return path

def build_elmo(options_path: str, weights_path: str) -> Elmo:
    return Elmo(
        options_file=options_path,
        weight_file=weights_path,
        num_output_representations=1,
        dropout=0.0,
        do_layer_norm=False,
    )

def run_once(elmo: Elmo, text: str):
    tokens = text.strip().split()
    character_ids = batch_to_ids([tokens])
    with torch.no_grad():
        out = elmo(character_ids)
    elmo_repr = out["elmo_representations"][0]  # (1, seq_len, 1024)
    print("Tokens:", tokens)
    print("ELMo representation shape:", tuple(elmo_repr.shape))
    if elmo_repr.shape[1] >= 2:
        v0 = [round(x, 4) for x in elmo_repr[0, 0, :5].tolist()]
        v1 = [round(x, 4) for x in elmo_repr[0, 1, :5].tolist()]
        print("First token (5 dims):", v0)
        print("Second token (5 dims):", v1)

def main():
    parser = argparse.ArgumentParser(
        description="Load local ELMo (options/weights) and run a quick forward pass."
    )
    parser.add_argument("--options", help="Path to options.json（可不填，走默认/环境变量）")
    parser.add_argument("--weights", help="Path to weights.hdf5（可不填，走默认/环境变量）")
    parser.add_argument(
        "--text",
        default="Calzolari , N. ( 1982 ) Towards the organization of lexical definitions",
        help="A test sentence to embed",
    )
    args = parser.parse_args()

    options_path = resolve_path(args.options, "ELMO_OPTIONS", DEFAULT_OPTIONS)
    weights_path = resolve_path(args.weights, "ELMO_WEIGHTS", DEFAULT_WEIGHTS)

    elmo = build_elmo(options_path, weights_path)
    run_once(elmo, args.text)

if __name__ == "__main__":
    if sys.version_info[:2] != (3, 7):
        sys.stderr.write("Warning: 推荐在 Python 3.7 + AllenNLP 0.9 环境下运行以避免兼容问题。\n")
    main()
