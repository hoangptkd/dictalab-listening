import pytest

from training.inspect_model import inspect, target_module_names


def test_full_text_paths_avoid_vision_and_unselected_hybrid_layers():
    names = ["model.layers.0.self_attn.q_proj", "model.layers.0.self_attn.k_proj",
             "model.layers.1.linear_attn.in_proj_qkv", "model.layers.0.mlp.up_proj",
             "visual.blocks.0.attn.q_proj", "lm_head"]
    assert target_module_names(names) == names[:2]
    assert target_module_names(names, include_mlp=True) == names[:2] + [names[3]]


def test_inspection_requires_pinned_revision_before_importing_torch(tmp_path):
    with pytest.raises(ValueError, match="immutable"):
        inspect("Qwen/Qwen3.5-9B", tmp_path / "report.json", revision="main")


def test_inspection_preserves_existing_report(tmp_path):
    output = tmp_path / "report.json"
    output.write_text("existing", encoding="utf-8")
    with pytest.raises(ValueError, match="overwrite"):
        inspect("Qwen/Qwen3.5-9B", output, revision="a" * 40)
    assert output.read_text(encoding="utf-8") == "existing"
