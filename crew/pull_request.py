"""Publicación determinista del PR: clona el repo del producto, sube una rama estable por
feature y actualiza el mismo PR en cada ejecución en lugar de abrir uno nuevo."""
import os
import re
import shutil
import subprocess
import tempfile
import unicodedata
from pathlib import Path

SKIP_DIRS = {"node_modules", "target", "dist", ".angular", ".git", "__pycache__"}
SKIP_FILES = {".checkpoint.json"}
_REPO_RE = re.compile(r"^(?!-)[\w.-]+/[\w.-]+$")


def pr_enabled() -> bool:
    value = os.getenv("CREWAI_OPEN_PR", "false").strip().lower()
    if value not in ("true", "false"):
        raise ValueError("CREWAI_OPEN_PR debe ser true o false")
    return value == "true"


def slugify(text: str) -> str:
    ascii_text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    slug = re.sub(r"[^a-z0-9]+", "-", ascii_text.lower()).strip("-")[:40].strip("-")
    return slug or "feature"


def chapter_verdict(text: str) -> str:
    found = re.findall(r"VEREDICTO:\s*(APROBADO|RECHAZADO)", str(text or ""), re.IGNORECASE)
    return found[-1].upper() if found else ""


def parse_pr_text(text: str, fallback_title: str) -> tuple[str, str]:
    lines = str(text or "").strip().splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"\s*(?:TITLE|T[IÍ]TULO)\s*:\s*(.+)", line, re.IGNORECASE)
        if m:
            title = m.group(1).strip().strip("`*\"'")[:120] or fallback_title
            return title, "\n".join(lines[:i] + lines[i + 1:]).strip()
    return fallback_title, "\n".join(lines).strip()


def collect_files(output_dir: Path) -> list[str]:
    files = []
    for path in Path(output_dir).rglob("*"):
        rel = path.relative_to(output_dir)
        if path.is_file() and path.name not in SKIP_FILES and not SKIP_DIRS.intersection(rel.parts):
            files.append(rel.as_posix())
    return sorted(files)


def _target_repo() -> str:
    repo = os.getenv("PR_TARGET_REPO") or f"{os.getenv('GITHUB_OWNER', '')}/{os.getenv('GITHUB_REPO', '')}"
    if not _REPO_RE.match(repo):
        raise RuntimeError("Define GITHUB_OWNER y GITHUB_REPO (o PR_TARGET_REPO=owner/repo) en .env")
    return repo


def _target_dir(slug: str) -> Path:
    target = Path(os.getenv("PR_TARGET_DIR") or f"crewai-output/{slug}")
    if target.is_absolute() or ".." in target.parts:
        raise RuntimeError("PR_TARGET_DIR debe ser una ruta relativa dentro del repo")
    return target


def _run(cmd: list[str], cwd: Path | None = None) -> str:
    try:
        done = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8")
    except FileNotFoundError as exc:
        raise RuntimeError(f"No se encontró '{cmd[0]}' en el PATH") from exc
    if done.returncode != 0:
        detail = (done.stderr or done.stdout).strip()
        raise RuntimeError(f"Falló '{' '.join(cmd[:3])}': {detail}")
    return done.stdout.strip()


def _remote_branch_exists(repo: str, branch: str, cwd: Path) -> bool:
    return bool(_run(["git", "ls-remote", "--heads", "origin", branch], cwd))


def _existing_pr_url(repo: str, branch: str, cwd: Path) -> str:
    """Devuelve la URL del PR abierto para `branch`, o '' si no hay ninguno."""
    return _run(["gh", "pr", "list", "--repo", repo, "--head", branch, "--state", "open",
                 "--json", "url", "--jq", ".[0].url"], cwd)


def publish_pull_request(output_dir: Path, feature_title: str, title: str, body: str, draft: bool) -> str:
    """Sube output_dir a una rama estable del repo del producto, reutilizando el PR si ya existe."""
    repo = _target_repo()
    slug = slugify(feature_title)
    target = _target_dir(slug)
    branch = f"crewai/{slug}"
    files = collect_files(output_dir)
    if not files:
        raise RuntimeError("output/ no tiene archivos para publicar")

    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        work = Path(tmp) / "repo"
        _run(["gh", "repo", "clone", repo, str(work), "--", "--depth", "1"])
        base = _run(["git", "rev-parse", "--abbrev-ref", "HEAD"], work)

        branch_exists = _remote_branch_exists(repo, branch, work)
        if branch_exists:
            _run(["git", "fetch", "origin", branch], work)
            _run(["git", "checkout", "-B", branch, "FETCH_HEAD"], work)
        else:
            _run(["git", "checkout", "-b", branch], work)

        for rel in files:
            dest = work / target / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(Path(output_dir) / rel, dest)

        _run(["git", "add", "-A", "--", target.as_posix()], work)
        if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=work).returncode == 0:
            if branch_exists and (existing := _existing_pr_url(repo, branch, work)):
                return existing
            raise RuntimeError("La rama no tiene cambios respecto a la base; no se abre el PR")
        _run(["git", "commit", "-m", f"feat: {title}"], work)
        _run(["git", "push", "-u", "origin", branch], work)

        if branch_exists and (existing := _existing_pr_url(repo, branch, work)):
            return existing

        body_file = Path(tmp) / "pr_body.md"
        body_file.write_text(body, encoding="utf-8")
        cmd = ["gh", "pr", "create", "--repo", repo, "--base", base, "--head", branch,
               f"--title={title}", "--body-file", str(body_file)]
        if draft:
            cmd.append("--draft")
        return _run(cmd, work).splitlines()[-1]
