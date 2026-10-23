"""Start the two local judge bridges, reusing their dedicated signed-in Chrome profiles."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from urllib.request import urlopen


def main():
    root = Path(__file__).resolve().parents[1]
    bridge = root / "data/chatgpt-api-web"
    first = bridge / "data/chrome-profile"
    second = Path(os.environ["LOCALAPPDATA"]) / "DictaLab/chatgpt-judge/account-2"
    cdp_first = int((first / "DevToolsActivePort").read_text().splitlines()[0])
    cdp_second = int((second / "DevToolsActivePort").read_text().splitlines()[0])
    accounts = [("account-1", 3000, cdp_first, bridge / "data", first),
                ("account-2", 3001, cdp_second, second.parent / "bridge-account-2", second)]
    node = shutil.which("node")
    if not node:
        raise RuntimeError("Node.js not found")
    for ident, port, cdp, data, profile in accounts:
        health_url = f"http://127.0.0.1:{port}/health"
        try:
            with urlopen(health_url, timeout=3) as response:
                existing = json.load(response)
        except OSError:
            existing = None
        if existing:
            if existing.get("webQuotaProtocol") != 1 or existing.get("accountId") != ident:
                raise RuntimeError(f"{ident}: existing bridge needs a reviewed restart")
        else:
            # Never fall back to opening a different profile if the signed-in browser is gone.
            with urlopen(f"http://127.0.0.1:{cdp}/json/version", timeout=3):
                pass
            data.mkdir(parents=True, exist_ok=True)
            env = {**os.environ, "HOST": "127.0.0.1", "PORT": str(port), "CDP_PORT": str(cdp),
                "BRIDGE_DATA_DIR": str(data), "CHROME_PROFILE_DIR": str(profile),
                "WEB_ACCOUNT_ID": ident, "CHROME_HEADLESS": "false", "BRIDGE_DEBUG": "false",
                "RESPONSE_TIMEOUT": "180000",
                "CHROME_PATH": r"C:\Program Files\Google\Chrome\Application\chrome.exe"}
            with (data / "judge-bridge.log").open("ab") as log:
                process = subprocess.Popen([node, "index.js"], cwd=bridge, env=env,
                    stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                    creationflags=subprocess.CREATE_NO_WINDOW)
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                try:
                    with urlopen(health_url, timeout=2) as response:
                        existing = json.load(response)
                    break
                except OSError:
                    if process.poll() is not None:
                        raise RuntimeError(f"{ident}: bridge exited; inspect its private log")
                    time.sleep(0.5)
            else:
                raise RuntimeError(f"{ident}: bridge startup timed out")
        if (existing.get("webQuotaProtocol") != 1 or existing.get("accountId") != ident or
                existing.get("cdp") != f"http://127.0.0.1:{cdp}" or existing.get("pages") != 1):
            raise RuntimeError(f"{ident}: health/profile/tab check failed")
        print(json.dumps({"accountId": ident, "port": port, "pages": existing["pages"], "ready": True}))


if __name__ == "__main__":
    main()
