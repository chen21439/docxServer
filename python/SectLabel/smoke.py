# smoke.py
import sys
mods = ["lmdb","spacy","gensim","torch","tensorboardX","sciwing"]
print("py:", sys.version, "\nexe:", sys.executable)
for m in mods:
    try:
        mod = __import__(m)
        print(f"{m:12s} OK  {getattr(mod,'__version__','-')}")
    except Exception as e:
        print(f"{m:12s} FAIL {e.__class__.__name__}: {e}")
