#!/usr/bin/env python3
"""Ordered literal search-and-replace across a tree. Used by make-modules.sh.

Two deliberate choices:

  * LONGEST PATTERN FIRST, always — sorted here rather than trusted to the caller's
    table order. `neo01` before `neo-01` would leave `neo-NN1`-shaped wreckage, and a
    table is edited by whoever is in a hurry.
  * NO REGEX. An earlier rename in this project used `sed` with `\\b`, which BSD sed does
    not support: it matched nothing, exited 0, and the rename silently did not happen.
    Literal `str.replace` cannot fail that way.

  stamp.py <root> <pairs-file>          pairs: one `from<TAB>to` per line

Only text files are touched (see TEXT_EXT/TEXT_NAMES): a mangled binary is a worse
outcome than a missed string, and every string that matters here is in a text file.
"""
import os
import sys

SKIP_DIRS = {".git", "node_modules", "target", "dist", ".vscode", "__pycache__"}
TEXT_EXT = {
    ".java", ".yml", ".yaml", ".xml", ".json", ".js", ".jsx", ".ts", ".css", ".html",
    ".md", ".sh", ".sql", ".params", ".properties", ".txt", ".example", ".conf", ".mjs",
}
TEXT_NAMES = {"Dockerfile", ".env.example", ".gitignore", ".dockerignore", "nginx.conf"}


def is_text(path):
    name = os.path.basename(path)
    return name in TEXT_NAMES or os.path.splitext(name)[1] in TEXT_EXT


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    root, pairs_file = sys.argv[1], sys.argv[2]

    pairs = []
    with open(pairs_file) as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if "\t" not in line:
                sys.exit(f"stamp.py: no tab in pair line: {line!r}")
            src, dst = line.split("\t", 1)
            pairs.append((src, dst))
    pairs.sort(key=lambda p: len(p[0]), reverse=True)

    changed = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            path = os.path.join(dirpath, fn)
            if not is_text(path):
                continue
            try:
                with open(path, encoding="utf-8") as fh:
                    original = fh.read()
            except (UnicodeDecodeError, OSError):
                continue
            text = original
            for src, dst in pairs:
                text = text.replace(src, dst)
            if text != original:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(text)
                changed += 1
    print(f"  stamped {changed} files")


if __name__ == "__main__":
    main()
