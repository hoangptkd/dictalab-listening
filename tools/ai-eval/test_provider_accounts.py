import importlib.util
import io
import json
from pathlib import Path
from unittest.mock import patch

from dotenv import dotenv_values
import pytest

spec = importlib.util.spec_from_file_location("provider_accounts",Path(__file__).with_name("provider_accounts.py"))
accounts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(accounts)


@pytest.mark.parametrize("failure",[False,True])
def test_import_verifies_before_pruning_and_keeps_recoverable_backup(tmp_path,monkeypatch,failure):
    monkeypatch.setattr(accounts,"ROOT",tmp_path)
    for name in ("AI_PROVIDER_CONFIG_SOURCE","AI_PROVIDER_DATABASE_URL","AI_PROVIDER_ENCRYPTION_KEY",
                 "GEMINI_PROVIDERS_JSON","POSTGRES_USER","POSTGRES_PASSWORD","POSTGRES_DB"):
        monkeypatch.delenv(name,raising=False)
    source = dict(id="a",projectId="project-a",apiKey="fake-test-only",enabled=True)
    path = tmp_path/".env"
    original = "\n".join(("POSTGRES_DB=test","POSTGRES_USER=test","POSTGRES_PASSWORD=test-only",
        "GEMINI_API_KEY=fake-test-only","GEMINI_PROVIDER_B_API_KEY=old-fake", "KEEP_VALUE=preserved",
        "GEMINI_PROVIDERS_JSON='"+json.dumps([source])+"'"))+"\n"
    path.write_text(original,encoding="utf-8")
    with patch.object(accounts,"apply_accounts",side_effect=ValueError("synthetic failure") if failure else None,
                      return_value=({},[accounts.GeminiSource.model_validate(source)])):
        if failure:
            with pytest.raises(ValueError,match="synthetic failure"):
                accounts.import_env(path,{})
        else:
            accounts.import_env(path,{})
    config = dotenv_values(path)
    assert config["KEEP_VALUE"] == "preserved"
    assert bool(config.get("GEMINI_API_KEY")) == failure
    assert bool(config.get("GEMINI_PROVIDER_B_API_KEY")) == failure
    assert (config.get("AI_PROVIDER_CONFIG_SOURCE")=="database") != failure
    from app.provider_registry import decrypt_key
    encrypted = next(tmp_path.rglob("env-before-*.encrypted")).read_text()
    assert "fake-test-only" not in encrypted
    assert decrypt_key(config["AI_PROVIDER_ENCRYPTION_KEY"],dict(id="env-backup",platform="gemini",
        account_id="local",model="backup",endpoint="local",api_key_ciphertext=encrypted)) == original


def test_env_replace_failure_keeps_previous_file_and_cleans_temporary(tmp_path):
    path = tmp_path/".env"
    path.write_text("KEEP_VALUE=before\n",encoding="utf-8")
    with patch.object(accounts.os,"replace",side_effect=OSError("synthetic disk failure")):
        with pytest.raises(OSError):
            accounts.replace_env(path,"KEEP_VALUE=after\n")
    assert path.read_text()=="KEEP_VALUE=before\n"
    assert not list(tmp_path.glob(".env.registry-*"))


@pytest.mark.parametrize('master', ['private-master', '${AI_PROVIDER_ENCRYPTION_KEY}'])
def test_explicit_management_file_cannot_be_overridden_by_parent_credentials(tmp_path, monkeypatch, capsys, master):
    path = tmp_path / 'owner.env'
    path.write_text('AI_PROVIDER_DATABASE_URL=private-target\nAI_PROVIDER_ENCRYPTION_KEY=' + master + '\n', encoding='utf-8')
    monkeypatch.setenv('AI_PROVIDER_DATABASE_URL', 'legacy-parent')
    monkeypatch.setenv('AI_PROVIDER_ENCRYPTION_KEY', 'legacy-master')
    monkeypatch.setattr(accounts.sys, 'argv', ['provider_accounts.py', 'list', '--env-file', str(path)])
    with patch.object(accounts, 'load_registry', return_value=({}, [])) as read:
        accounts.main()
        read.assert_called_once_with('private-target', master)
    assert 'private-master' not in capsys.readouterr().out
