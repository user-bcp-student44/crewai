"""Abre solo el Pull Request de la última ejecución, usando output/.checkpoint.json."""
from main import load_checkpoint, open_pull_request
from crew.pull_request import pr_enabled


def run() -> None:
    if not pr_enabled():
        print("❌ CREWAI_OPEN_PR debe ser true para abrir el PR.")
        raise SystemExit(1)

    checkpoint = load_checkpoint()
    approved = {k: v.get("output", "") for k, v in checkpoint.get("steps", {}).items()
                if v.get("status") == "approved"}
    if "chapter" not in approved:
        print("❌ El checkpoint no tiene el paso Chapter aprobado; no hay nada que publicar.")
        raise SystemExit(1)

    feature = checkpoint.get("feature") or {
        "title": checkpoint.get("feature_title", "feature"),
        "description": "",
        "acceptance_criteria": [],
    }
    print(f"[PR] Feature de la última ejecución: {feature['title']}")
    open_pull_request(feature, approved, checkpoint)


if __name__ == "__main__":
    run()
