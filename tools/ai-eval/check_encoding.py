"""Portable UTF-8/mojibake check for AI Platform sources and delivery files."""
import py_compile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ROOTS = ("services/ai-generation", "tools/ai-eval", "artifacts/prompts", "artifacts/reports",
         "src/main/java", "src/main/resources/application.yml", "src/test/java/com/dictalab/service/ConversationRolloutTest.java",
         ".github/workflows", "docs/ai-platform-runbook.md", "docs/ai-platform-baseline.md", ".env.example", "compose.yaml",
         ".env.ai-production.example", ".env.ai-local.example", "ops/observability", "src/main/resources/application-local.yml",
         "src/test/java/com/dictalab/service/AiPlatformClientTest.java", "src/test/java/com/dictalab/service/AiPlatformSmokeTest.java",
         "src/test/java/com/dictalab/service/AiBackgroundJobsIntegrationTest.java", "src/test/java/com/dictalab/service/ShopAiKeyClientTest.java")
MARKERS = ("\u00c3", "\u00c4", "\u00e1\u00bb", "\u00e1\u00ba", "\u00c6", "\u00e2\u20ac", "\u00f0\u0178", "\ufffd", "?" * 4)


def main():
    checked = 0
    for root in ROOTS:
        path = ROOT / root
        for file in ([path] if path.is_file() else path.rglob("*")):
            if not file.is_file() or file.suffix not in (".py", ".java", ".json", ".md", ".yaml", ".yml", ".example"):
                continue
            text = file.read_text(encoding="utf-8")
            if "encoding-guard: allow-mojibake-fixtures" not in text:
                assert not any(marker in text for marker in MARKERS), "encoding corruption in " + str(file)
            if file.suffix == ".py":
                py_compile.compile(str(file), doraise=True)
            checked += 1
    print(f"UTF-8 and compilation checks passed: {checked} files")


if __name__ == "__main__":
    main()
