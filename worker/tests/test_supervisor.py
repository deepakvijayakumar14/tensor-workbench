import os
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class NoWork(BaseHTTPRequestHandler):
    """A fake API with an empty queue: every claim returns 204."""

    def do_POST(self) -> None:  # noqa: N802
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args: object) -> None:
        return None


def test_sigterm_stops_all_slots_promptly() -> None:
    """Regression: the signal handler must not deadlock on the shared shutdown Event."""
    server = ThreadingHTTPServer(("127.0.0.1", 0), NoWork)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    env = {
        **os.environ,
        "API_URL": f"http://127.0.0.1:{server.server_port}",
        "WORKER_SLOTS": "2",
        "WORKER_POLL_INTERVAL_SECONDS": "0.2",
    }
    worker = subprocess.Popen([sys.executable, "-m", "tensor_worker.main"], env=env, stderr=subprocess.PIPE, text=True)
    try:
        time.sleep(3)  # let both slots start polling
        started = time.monotonic()
        worker.send_signal(signal.SIGTERM)
        assert worker.wait(timeout=10) == 0
        assert time.monotonic() - started < 5
        log = worker.stderr.read() if worker.stderr else ""
        assert "slot-0 stopped" in log and "slot-1 stopped" in log and "Worker stopped" in log
    finally:
        if worker.poll() is None:
            worker.kill()
        server.shutdown()
