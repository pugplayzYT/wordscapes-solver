"""Rebuild Wordscapes dictionary from source already attributed in repository.

Run in GitHub Actions after fetching official CMUdict. Keep alphabetical
English 3-12 character entries, including the original bundled words.
"""
from pathlib import Path
import re
import sys

target = Path("app/src/main/assets/words.txt")
source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/cmudict.dict")
existing = {w.strip().upper() for w in target.read_text().splitlines()
            if re.fullmatch(r"[A-Za-z]{3,12}", w.strip())}
for row in source.read_text(encoding="utf-8").splitlines():
    if not row or row.startswith((";", "#")):
        continue
    word = row.split()[0].split("(")[0].strip()
    if re.fullmatch(r"[A-Za-z]{3,12}", word):
        existing.add(word.upper())
target.write_text("\n".join(sorted(existing)) + "\n", encoding="utf-8")
print(f"Dictionary contains {len(existing):,} entries, 3–12 letters")
