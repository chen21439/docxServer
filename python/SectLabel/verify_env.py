# verify_env.py
import os, sys

def safe_import(mod):
    try:
        m = __import__(mod)
        return m, f"OK ({getattr(m, '__version__', 'no __version__')})"
    except Exception as e:
        return None, f"FAIL ({e.__class__.__name__}: {e})"

def main():
    print("="*60)
    print("PYTHON")
    print("="*60)
    print("exe:", sys.executable)
    print("version:", sys.version.split()[0])
    print()

    print("="*60)
    print("ENV VARS (runtime)")
    print("="*60)
    for k in ["SCIWING_LITE", "PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION",
              "HTTP_PROXY", "HTTPS_PROXY"]:
        print(f"{k:40s}", os.getenv(k))
    print()

    print("="*60)
    print("IMPORTS")
    print("="*60)
    for name in ["lmdb", "spacy", "gensim", "torch", "tensorboardX", "sciwing"]:
        mod, status = safe_import(name)
        print(f"{name:20s}", status)
        if name == "spacy" and mod:
            try:
                nlp = mod.load("en_core_web_sm")
                doc = nlp("This is a test sentence.")
                print("spacy pipeline:", [p for p in nlp.pipe_names])
                print("spacy smoke:", [(t.text, t.pos_) for t in doc])
            except Exception as e:
                print("spacy model load:", f"FAIL ({e.__class__.__name__}: {e})")
        if name == "lmdb" and mod:
            print("lmdb path:", getattr(mod, "__file__", None))
    print()

    print("="*60)
    print("DONE")
    print("="*60)

if __name__ == "__main__":
    main()
