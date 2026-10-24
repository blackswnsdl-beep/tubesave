import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("canary", Path(__file__).with_name("canary.py"))
canary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(canary)

class CanaryTests(unittest.TestCase):
    def fake_ydl(self, mode):
        class FakeYDL:
            def __init__(self, options): self.options = options
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def extract_info(self, url, download):
                assert download is True
                assert self.options["test"] is True
                assert self.options["format"] == "bestaudio/best"
                if mode == "403": raise RuntimeError("HTTP Error 403: Forbidden")
                if mode == "audio":
                    Path(self.options["outtmpl"].replace("%(ext)s", "webm")).write_bytes(b"audio")
                return {"acodec": "opus" if mode != "video" else "none"}
        return FakeYDL

    def test_metadata_success_with_403_is_failure(self):
        with patch.object(canary.yt_dlp, "YoutubeDL", self.fake_ydl("403")):
            self.assertFalse(canary.test_client("android_vr"))

    def test_metadata_without_bytes_is_failure(self):
        with patch.object(canary.yt_dlp, "YoutubeDL", self.fake_ydl("empty")):
            self.assertFalse(canary.test_client("default"))

    def test_actual_audio_bytes_are_required(self):
        with patch.object(canary.yt_dlp, "YoutubeDL", self.fake_ydl("audio")):
            self.assertTrue(canary.test_client("default"))

    def test_video_only_is_failure(self):
        with patch.object(canary.yt_dlp, "YoutubeDL", self.fake_ydl("video")):
            self.assertFalse(canary.test_client("default"))

    def test_all_clients_fail_preserves_config(self):
        with tempfile.TemporaryDirectory() as temp:
            config = Path(temp) / "config.json"
            original = '{"extractor_args":"youtube:player_client=android_vr"}'
            config.write_text(original, encoding="utf-8")
            with patch.object(canary, "CONFIG_PATH", str(config)), patch.object(canary, "test_client", return_value=False), patch.object(canary, "commit_and_push") as publish:
                self.assertEqual(2, canary.main())
                self.assertEqual(original, config.read_text(encoding="utf-8"))
                publish.assert_not_called()

    def test_dry_run_does_not_execute_git(self):
        with patch.object(canary, "DRY_RUN", True), patch.object(canary, "git") as git:
            canary.commit_and_push("default", "android_vr")
            git.assert_not_called()

if __name__ == "__main__": unittest.main()
