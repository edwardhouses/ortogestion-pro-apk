#!/usr/bin/env python3
"""Install the reviewed Windows/Electron hardening into an extracted wrapper."""
from pathlib import Path
import json
import shutil
import sys

repo = Path(__file__).resolve().parents[2]
root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
shutil.copy2(repo / "native/windows/main.js", root / "main.js")
shutil.copy2(repo / "native/windows/security.js", root / "security.js")
tests = root / "tests"
tests.mkdir(exist_ok=True)
shutil.copy2(repo / "native/windows/security.test.cjs", tests / "security.test.cjs")
package_path = root / "package.json"
package = json.loads(package_path.read_text(encoding="utf-8"))
package.setdefault("scripts", {})["test"] = "node tests/smoke.test.cjs && node tests/security.test.cjs"
files = package.setdefault("build", {}).setdefault("files", [])
if "security.js" not in files:
    files.append("security.js")
package_path.write_text(json.dumps(package, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Windows/Electron hardening aplicado al wrapper extraído.")
