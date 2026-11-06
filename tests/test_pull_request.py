"""Pruebas del agente de PR sin llamar a Copilot, git ni GitHub."""

import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import main as app
from crew import pull_request as pr

FEATURE = {"title": "Transferir fondos", "description": "d", "acceptance_criteria": ["c1"]}


class PullRequestTests(unittest.TestCase):
    def test_helpers(self):
        self.assertEqual(pr.slugify("Transferir fondos: ¡Ñandú!"), "transferir-fondos-nandu")
        self.assertEqual(pr.slugify("???"), "feature")
        self.assertEqual(pr.chapter_verdict("x\nVEREDICTO: RECHAZADO\nVEREDICTO: APROBADO"), "APROBADO")
        self.assertEqual(pr.chapter_verdict("sin veredicto"), "")
        self.assertEqual(pr.parse_pr_text("TITLE: Mi PR\n## Cuerpo", "f"), ("Mi PR", "## Cuerpo"))
        self.assertEqual(pr.parse_pr_text("## Solo cuerpo", "f"), ("f", "## Solo cuerpo"))

    def test_flag_and_target_validation(self):
        with patch.dict("os.environ", {"CREWAI_OPEN_PR": "quizas"}):
            with self.assertRaises(ValueError):
                pr.pr_enabled()
        with patch.dict("os.environ", {"PR_TARGET_DIR": "../fuera"}), self.assertRaises(RuntimeError):
            pr._target_dir("x")
        with patch.dict("os.environ", {"PR_TARGET_REPO": "--evil/repo"}), self.assertRaises(RuntimeError):
            pr._target_repo()

    def test_publish_copies_files_and_opens_draft(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "specs").mkdir()
            (out / "specs/a.md").write_text("a")
            (out / ".checkpoint.json").write_text("{}")
            (out / "node_modules/x").mkdir(parents=True)
            (out / "node_modules/x/i.js").write_text("x")
            self.assertEqual(pr.collect_files(out), ["specs/a.md"])

            calls = []

            def fake_run(cmd, cwd=None):
                calls.append(cmd)
                if cmd[:3] == ["gh", "repo", "clone"]:
                    Path(cmd[4]).mkdir(parents=True)
                if cmd[:2] == ["git", "rev-parse"]:
                    return "main"
                if cmd[:2] == ["gh", "pr"]:
                    return "https://github.com/o/r/pull/1"
                return ""

            env = {"PR_TARGET_REPO": "o/r", "PR_TARGET_DIR": ""}
            with patch.dict("os.environ", env), patch.object(pr, "_run", side_effect=fake_run), \
                    patch.object(pr.subprocess, "run") as diff:
                diff.return_value.returncode = 1
                url = pr.publish_pull_request(out, FEATURE["title"], "Titulo", "Cuerpo", draft=True)

            self.assertEqual(url, "https://github.com/o/r/pull/1")
            self.assertIn("--draft", calls[-1])
            self.assertIn("--title=Titulo", calls[-1])
            self.assertEqual(calls[0][:4], ["gh", "repo", "clone", "o/r"])
            self.assertTrue(any(c[:2] == ["git", "push"] for c in calls))

    def test_publish_reuses_existing_branch_and_pr(self):
        """Una segunda ejecución debe actualizar la misma rama/PR, no crear uno nuevo."""
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "specs").mkdir()
            (out / "specs/a.md").write_text("a")

            calls = []
            branch = f"crewai/{pr.slugify(FEATURE['title'])}"

            def fake_run(cmd, cwd=None):
                calls.append(cmd)
                if cmd[:3] == ["gh", "repo", "clone"]:
                    Path(cmd[4]).mkdir(parents=True)
                if cmd[:2] == ["git", "rev-parse"]:
                    return "main"
                if cmd[:3] == ["git", "ls-remote", "--heads"]:
                    return f"abc123\trefs/heads/{branch}"
                if cmd[:3] == ["gh", "pr", "list"]:
                    return "https://github.com/o/r/pull/7"
                if cmd[:3] == ["gh", "pr", "create"]:
                    return "https://github.com/o/r/pull/999"
                return ""

            env = {"PR_TARGET_REPO": "o/r", "PR_TARGET_DIR": ""}
            with patch.dict("os.environ", env), patch.object(pr, "_run", side_effect=fake_run), \
                    patch.object(pr.subprocess, "run") as diff:
                diff.return_value.returncode = 1
                url = pr.publish_pull_request(out, FEATURE["title"], "Titulo", "Cuerpo", draft=True)

            self.assertEqual(url, "https://github.com/o/r/pull/7")
            self.assertFalse(any(c[:3] == ["gh", "pr", "create"] for c in calls))
            self.assertIn(["git", "fetch", "origin", branch], calls)
            self.assertIn(["git", "checkout", "-B", branch, "FETCH_HEAD"], calls)

    def test_main_opens_pr_once_after_human_approval(self):
        checkpoint = {"steps": {}}
        approved = {"chapter": "ok\nVEREDICTO: APROBADO"}
        with tempfile.TemporaryDirectory() as tmp, contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(app, "OUTPUT_DIR", Path(tmp)))
            stack.enter_context(patch.object(app, "CHECKPOINT_FILE", Path(tmp) / ".checkpoint.json"))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            run = stack.enter_context(patch.object(app, "run_step", return_value="TITLE: PR\nBody"))
            stack.enter_context(patch.object(app, "hitl_approve", side_effect=[("r", "mas corto"), ("s", "")]))
            publish = stack.enter_context(
                patch.object(app, "publish_pull_request", return_value="https://x/pull/1")
            )

            app.open_pull_request(FEATURE, approved, checkpoint)
            self.assertEqual(run.call_count, 2)
            self.assertIn("mas corto", run.call_args_list[1].args[1].description)
            publish.assert_called_once_with(Path(tmp), FEATURE["title"], "PR", "Body", False)
            self.assertEqual(checkpoint["pr_url"], "https://x/pull/1")

            app.open_pull_request(FEATURE, approved, checkpoint)
            publish.assert_called_once()

    def test_rejecting_the_pr_publishes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(app, "OUTPUT_DIR", Path(tmp)))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(patch.object(app, "run_step", return_value="TITLE: PR\nBody"))
            stack.enter_context(patch.object(app, "hitl_approve", return_value=("n", "")))
            publish = stack.enter_context(patch.object(app, "publish_pull_request"))
            checkpoint = {"steps": {}}
            app.open_pull_request(FEATURE, {}, checkpoint)
            publish.assert_not_called()
            self.assertNotIn("pr_url", checkpoint)


if __name__ == "__main__":
    unittest.main()
