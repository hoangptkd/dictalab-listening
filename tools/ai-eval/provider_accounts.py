"""Local operator CLI. Account/pool JSON enters through stdin, never argv or logs."""
import argparse
import base64
import json
import os
from pathlib import Path
import sys
import tempfile
from datetime import datetime, timezone
from urllib.parse import quote
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "services/ai-generation"))
from dotenv import dotenv_values
from app.config import GeminiSource
from app.provider_registry import connect, load_registry, save_pool, save_source, encrypt_key, decrypt_key


async def probe(config, platform, generate=False):
    import httpx
    import time
    from app.config import Settings
    from app.model_gateway import ModelGateway
    settings = Settings(primary_provider=platform,ai_provider_config_source="database",
        operational_limits_enabled=config.get("DICTALAB_OPERATIONAL_LIMITS_ENABLED", "true"),
        ai_provider_database_url=config["AI_PROVIDER_DATABASE_URL"],
        ai_provider_encryption_key=config["AI_PROVIDER_ENCRYPTION_KEY"],
        gemini_quota_backend="redis",gemini_quota_redis_url=config.get("GEMINI_QUOTA_REDIS_URL","redis://127.0.0.1:16379/0"),
        num_predict=32,timeout_seconds=30)
    result = {"platform":platform,"accounts":[]}
    async with httpx.AsyncClient(timeout=15,follow_redirects=False) as client:
        for source in settings.sources_for(platform):
            response = await client.get(source.url.removesuffix("/chat/completions")+"/models",
                headers={"Authorization":"Bearer "+source.api_key})
            available = response.status_code==200 and any(
                item.get("id","").removeprefix("models/")==source.model for item in response.json().get("data",[]))
            result["accounts"].append(dict(id=source.id,http_status=response.status_code,model_available=available))
        if not result["accounts"] or not all(r["model_available"] for r in result["accounts"]):
            return result
        if generate:
            gateway = ModelGateway(settings,client)
            output,provider = await gateway.complete([{"role":"user","content":"Return exactly JSON {\"ok\":true}."}],
                {"type":"object","properties":{"ok":{"const":True}},"required":["ok"],"additionalProperties":False},
                time.monotonic()+30,"registry_probe")
            if output != {"ok":True}:
                raise ValueError("Probe output mismatch")
            result["generation"] = dict(ok=True,provider=provider,model=gateway.last_model,source_id=gateway.last_source_id)
    return result


def environment(path):
    return dict(dotenv_values(path)) | dict(os.environ)


def replace_env(path, text):
    # Protect before writing, then atomically replace; a disk/rename error retains the old config.
    fd, temporary = tempfile.mkstemp(dir=path.parent,prefix=".env.registry-",suffix=".tmp")
    try:
        with os.fdopen(fd,"w",encoding="utf-8",newline="\n") as handle:
            if os.name == "nt":
                import getpass
                import subprocess
                subprocess.run(["icacls",temporary,"/inheritance:r","/grant:r",
                    getpass.getuser()+":F","*S-1-5-18:F"],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary,path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def apply_accounts(dsn, master, payload):
    # One transaction: invalid accounts cannot leave a half-imported pool.
    with connect(dsn, readonly=False) as db:
        for pool in payload.get("pools", []):
            save_pool(db, pool["platform"], pool["default_model"], pool["global_rpm_limit"],
                      pool["global_rpd_limit"], pool["enabled"])
        for source in payload.get("accounts", []):
            save_source(db, master, source)
    return load_registry(dsn, master)


def copy_registry(source, target):
    """Copy to an initialized private DB. Config files and source rows are never changed."""
    from app.registry_migration import identity, snapshot, verify_private
    source_dsn, target_dsn = source["AI_PROVIDER_DATABASE_URL"], target["AI_PROVIDER_DATABASE_URL"]
    source_master, target_master = source["AI_PROVIDER_ENCRYPTION_KEY"], target["AI_PROVIDER_ENCRYPTION_KEY"]
    if source_master == target_master:
        raise ValueError("private registry requires an independent encryption key")
    with connect(source_dsn) as origin:
        origin.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        source_id = identity(origin)
        expected = snapshot(origin, source_master)
    if not expected["pools"]:
        raise ValueError("source registry is empty")
    with connect(target_dsn, readonly=False) as destination:
        target_id = identity(destination)
        if source_id["role"] == target_id["role"] or all(
                source_id[key] == target_id[key] for key in ("database", "server", "port")):
            raise ValueError("target must have an independent database and role")
        verify_private(destination)
        destination.execute("LOCK TABLE ai_provider_pools,ai_provider_accounts IN EXCLUSIVE MODE")
        current = snapshot(destination, target_master)
        if current["pools"] or current["accounts"]:
            if current != expected:
                raise ValueError("target contains a different registry; nothing overwritten")
            return {"copied": False, "pools": len(expected["pools"]), "accounts": len(expected["accounts"])}
        # A protected encrypted backup is written and decrypted for verification before import.
        backup = ROOT / "data/backups" / ("ai-registry-copy-" + uuid4().hex + ".encrypted")
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup_id = dict(id="registry-copy", platform="gemini", account_id="operator", model="backup", endpoint="local")
        envelope = encrypt_key(target_master, backup_id, json.dumps(expected, ensure_ascii=False, sort_keys=True))
        replace_env(backup, envelope)
        if json.loads(decrypt_key(target_master, backup_id | dict(api_key_ciphertext=backup.read_text(encoding="utf-8")))) != expected:
            raise ValueError("encrypted backup verification failed")
        for pool in expected["pools"]:
            save_pool(destination, pool["platform"], pool["default_model"], pool["global_rpm_limit"],
                pool["global_rpd_limit"], pool["enabled"])
        for account in expected["accounts"]:
            save_source(destination, target_master, account)
        if snapshot(destination, target_master) != expected:
            raise ValueError("copied registry verification failed")
        # A concurrent operator edit must prevent treating an obsolete snapshot as ready for cutover.
        with connect(source_dsn) as fresh:
            fresh.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            if snapshot(fresh, source_master) != expected:
                raise ValueError("source changed during copy; target transaction rolled back")
    with connect(target_dsn) as verified:
        if snapshot(verified, target_master) != expected:
            raise ValueError("post-commit registry verification failed")
    return {"copied": True, "pools": len(expected["pools"]), "accounts": len(expected["accounts"])}


def import_env(path, payload, config=None):
    config = environment(path) if config is None else config
    if config.get("AI_PROVIDER_CONFIG_SOURCE") == "database":
        raise ValueError("Already migrated; use upsert")
    sources = json.loads(config.get("GEMINI_PROVIDERS_JSON", "[]"))
    if not sources:
        raise ValueError("No existing Gemini pool to import")
    master = config.get("AI_PROVIDER_ENCRYPTION_KEY") or base64.b64encode(os.urandom(32)).decode()
    dsn = config.get("AI_PROVIDER_DATABASE_URL") or (
        "postgresql://" + quote(config["POSTGRES_USER"],safe="") + ":" + quote(config["POSTGRES_PASSWORD"],safe="")
        + "@127.0.0.1:" + config.get("POSTGRES_PORT","15433") + "/" + quote(config["POSTGRES_DB"],safe=""))
    legacy = dict(platform="gemini",default_model=config.get("GEMINI_MODEL","gemini-3.5-flash-lite"),
        global_rpm_limit=int(config.get("GEMINI_GLOBAL_RPM_LIMIT","39")),
        global_rpd_limit=int(config.get("GEMINI_GLOBAL_RPD_LIMIT","1350")),enabled=True)
    # Backup is encrypted and stored beside private runtime data before anything is pruned.
    backup = ROOT / "data/backups" / datetime.now(timezone.utc).strftime("provider-registry-%Y%m%d") / ("env-before-"+datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")+".encrypted")
    backup.parent.mkdir(parents=True, exist_ok=True)
    identity = dict(id="env-backup",platform="gemini",account_id="local",model="backup",endpoint="local")
    with backup.open("x",encoding="utf-8") as handle:
        handle.write(encrypt_key(master,identity,path.read_text(encoding="utf-8-sig")))
    # Retain the master key even if the subsequent database import fails.
    text = path.read_text(encoding="utf-8-sig")
    if not config.get("AI_PROVIDER_ENCRYPTION_KEY"):
        replace_env(path,text.rstrip()+"\nAI_PROVIDER_ENCRYPTION_KEY="+master+"\n")
    pools, imported = apply_accounts(dsn,master,dict(pools=[legacy,*payload.get("pools",[])],
        accounts=[*sources,*payload.get("accounts",[])]))
    expected = [GeminiSource.model_validate(s) for s in [*sources,*payload.get("accounts",[])]]
    for source in expected:
        actual = next(p for p in imported if p.id == source.id)
        if actual.api_key != source.api_key or actual.project_id != source.project_id:
            raise ValueError("Account verification failed; original env retained")
    remove = {"GEMINI_PROVIDERS_JSON","GEMINI_API_KEY","DICTALAB_AI_GEMINI_API_KEY",
              "GEMINI_MODEL","DICTALAB_AI_GEMINI_MODEL","DICTALAB_AI_GEMINI_BASE_URL","GEMINI_BASE_URL","GEMINI_URL",
              "GEMINI_GLOBAL_RPM_LIMIT","GEMINI_GLOBAL_RPD_LIMIT","GEMINI_QUOTA_ENABLED"}
    remove.update(k for k in config if k.startswith("GEMINI_PROVIDER_"))
    from urllib.parse import urlsplit, urlunsplit
    uri = urlsplit(dsn)
    container_dsn = (urlunsplit((uri.scheme,uri.netloc.rsplit("@",1)[0]+"@postgres:5432",uri.path,uri.query,uri.fragment))
                     if uri.hostname in ("localhost","127.0.0.1","::1") else dsn)
    updates = dict(AI_PROVIDER_CONFIG_SOURCE="database",AI_PROVIDER_DATABASE_URL=dsn,
        AI_PROVIDER_CONTAINER_DATABASE_URL=container_dsn,AI_PROVIDER_ENCRYPTION_KEY=master)
    lines = [line for line in path.read_text(encoding="utf-8-sig").splitlines()
             if line.split("=",1)[0].strip() not in remove | updates.keys()]
    replace_env(path,"\n".join([*lines,*(f"{k}={v}" for k,v in updates.items())])+"\n")
    return pools, imported


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=("import-env","upsert","list","probe","init-private","copy-registry"))
    parser.add_argument("--env-file",type=Path)
    parser.add_argument("--platform",choices=("gemini","deepseek","openai_compatible"),default="gemini")
    parser.add_argument("--generate",action="store_true",help="Probe only: send at most one bounded paid generation; never retry")
    parser.add_argument("--target-env",type=Path,help="Copy only: explicit protected management config for the private target")
    args = parser.parse_args()
    try:
        # An explicit file always wins over ambient credentials, including for later upserts/probes.
        path = args.env_file or ROOT/".env"
        config = dict(dotenv_values(path, interpolate=False)) if args.env_file or args.command in ("init-private", "copy-registry") else environment(path)
        if args.command == "init-private":
            from app.registry_migration import initialize
            initialize(config["AI_PROVIDER_DATABASE_URL"])
            print("Private AI registry schema verified; no account data changed.")
            return
        if args.command == "copy-registry":
            if args.target_env is None or path.resolve() == args.target_env.resolve():
                raise ValueError("distinct explicit target env file required")
            print(json.dumps(copy_registry(config, dict(dotenv_values(args.target_env, interpolate=False)))))
            return
        if args.command == "probe":
            import asyncio
            result = asyncio.run(probe(config,args.platform,args.generate))
            print(json.dumps(result,indent=2))
            if not result["accounts"] or not all(r["model_available"] for r in result["accounts"]):
                raise ValueError("Model probe failed")
            return
        if args.command == "import-env":
            pools,sources = import_env(path,json.load(sys.stdin),config)
        elif args.command == "upsert":
            pools,sources = apply_accounts(config["AI_PROVIDER_DATABASE_URL"],config["AI_PROVIDER_ENCRYPTION_KEY"],json.load(sys.stdin))
        else:
            pools,sources = load_registry(config["AI_PROVIDER_DATABASE_URL"],config["AI_PROVIDER_ENCRYPTION_KEY"])
        print(json.dumps(dict(pools=list(pools.values()),accounts=[dict(id=p.id,platform=p.platform,
            account_id=p.project_id,model=p.model,enabled=p.enabled,operating_rpm=p.effective_rpm,rpd_limit=p.rpd_limit)
            for p in sources]),indent=2))
    except Exception:
        raise SystemExit("Provider registry operation failed; no secrets logged. Check DB migration, configuration and input.") from None


if __name__ == "__main__":
    main()
