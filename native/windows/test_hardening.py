#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(sys.argv[1]) if len(sys.argv)>1 else Path(".")
main=(root/"main.js").read_text(encoding="utf-8")
security=(root/"security.js").read_text(encoding="utf-8")
checks={
 "context isolation": "contextIsolation: true" in main,
 "sandbox enabled": "sandbox: true" in main,
 "node integration disabled": "nodeIntegration: false" in main,
 "content protection": "setContentProtection(true)" in main,
 "blur privacy shield": 'win.on("blur", showPrivacyShield)' in main,
 "focus removes shield": 'win.on("focus", hidePrivacyShield)' in main,
 "minimize locks session": 'win.on("minimize", lockSession)' in main,
 "suspend locks session": 'powerMonitor.on("suspend", lockSession)' in main,
 "exact origin validation": 'url.hostname === "app"' in security and 'url.protocol === "ortogestion:"' in security,
 "realpath traversal validation": "fs.realpath(candidate)" in security,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(("PASS" if v else "FAIL")+": "+k)
if failed: raise SystemExit("Fallaron controles: "+", ".join(failed))
