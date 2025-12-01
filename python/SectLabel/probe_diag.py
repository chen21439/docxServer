# probe_diag.py
import os
import sys
import platform
import pkgutil

def safe_import(name):
    try:
        mod = __import__(name)
        return mod, None
    except Exception as e:
        return None, str(e)

def print_kv(k, v):
    print("{:<45} {}".format(k, v))

def spaCy_smoke(spacy):
    try:
        if spacy is None:
            raise RuntimeError("spacy not installed")
        nlp = spacy.load("en_core_web_sm")
        doc = nlp("This is a test sentence.")
        print_kv("SPACY SMOKE", "OK -> " + str([(t.text, t.pos_) for t in doc]))
    except Exception as e:
        print_kv("SPACY SMOKE", "FAILED -> " + str(e))

def tbx_smoke(tbx):
    try:
        if tbx is None:
            raise RuntimeError("tensorboardX not installed")
        from tensorboardX import SummaryWriter
        print_kv("TENSORBOARDX SMOKE", "OK; SummaryWriter available = {}".format(callable(SummaryWriter)))
    except Exception as e:
        print_kv("TENSORBOARDX SMOKE", "FAILED -> " + str(e))

def main():
    print("=" * 60)
    print("RUNTIME")
    print("=" * 60)
    print_kv("python_executable", sys.executable)
    print_kv("python_version", sys.version.replace("\n", " "))
    print_kv("platform", platform.platform())

    print("\n" + "=" * 60)
    print("ENV VARS")
    print("=" * 60)
    print_kv("SCIWING_LITE", os.getenv("SCIWING_LITE"))
    print_kv("PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION", os.getenv("PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION"))

    print("\n" + "=" * 60)
    print("PACKAGES")
    print("=" * 60)

    pb, pb_err = safe_import("google.protobuf")
    spacy, spacy_err = safe_import("spacy")
    torch, torch_err = safe_import("torch")
    gensim, gensim_err = safe_import("gensim")
    tbx, tbx_err = safe_import("tensorboardX")
    lmdb, lmdb_err = safe_import("lmdb")
    sciwing, sciwing_err = safe_import("sciwing")

    print_kv("protobuf", getattr(pb, "__version__", "N/A") if pb else "NOT INSTALLED ({})".format(pb_err))
    print_kv("spacy", getattr(spacy, "__version__", "N/A") if spacy else "NOT INSTALLED ({})".format(spacy_err))
    print_kv("torch", getattr(torch, "__version__", "N/A") if torch else "NOT INSTALLED ({})".format(torch_err))
    print_kv("gensim", getattr(gensim, "__version__", "N/A") if gensim else "NOT INSTALLED ({})".format(gensim_err))
    print_kv("tensorboardX", getattr(tbx, "__version__", "N/A") if tbx else "NOT INSTALLED ({})".format(tbx_err))
    print_kv("lmdb", getattr(lmdb, "__version__", "N/A") if lmdb else "NOT INSTALLED ({})".format(lmdb_err))
    print_kv("sciwing", getattr(sciwing, "__version__", "N/A") if sciwing else "NOT INSTALLED ({})".format(sciwing_err))

    print("\n" + "=" * 60)
    print("SMOKE TESTS")
    print("=" * 60)
    spaCy_smoke(spacy)
    tbx_smoke(tbx)

    print("\n" + "=" * 60)
    print("DONE")
    print("=" * 60)

if __name__ == "__main__":
    main()
