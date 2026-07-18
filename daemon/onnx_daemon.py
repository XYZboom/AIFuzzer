"""
AiFuzzer ONNX Daemon — 常驻 Python HTTP 服务，通过 HTTP POST 与 JVM 通信。

API:
  POST /run    {"source": "<python code>"}
               → {"success": true, "exit_code": 0, "stdout": "...", "stderr": "...", "elapsed_ms": 1}

  GET  /health → {"status": "ok", "onnx_available": true, "uptime_seconds": 123}

  POST /shutdown → 优雅关闭

使用方法:
  python3 daemon/onnx_daemon.py                  # 启动 daemon（默认端口 34790）
  python3 daemon/onnx_daemon.py --port 8888      # 指定端口
  python3 daemon/onnx_daemon.py --test           # 自测试模式
"""

import json
import os
import signal
import sys
import subprocess
import tempfile
import threading
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

# 一次性导入 ONNX + ONNX Runtime
ONNX_AVAILABLE = False
ORT_AVAILABLE = False
_import_error = ""

try:
    from onnx import helper, TensorProto
    import onnx
    ONNX_AVAILABLE = True
except Exception as _ie:
    _import_error = repr(_ie)

try:
    import onnxruntime as ort
    ORT_AVAILABLE = True
except Exception as _ie:
    _import_error += "; " + repr(_ie)

EXEC_TIMEOUT_SECONDS = 30
DAEMON_START_TIME = time.time()


def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    """执行单次测试源码，用子进程运行 + 硬超时 kill。

    ONNX Runtime 的 C 扩展可能无限阻塞 in-process exec() 无法被 SIGALRM 中断。
    改用 subprocess.run(timeout=...) 确保超时后 SIGKILL 子进程，daemon 本身永不阻塞。
    """
    import tempfile
    import subprocess

    start = time.time()
    with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
        f.write(source)
        f.flush()
        script_path = f.name

    try:
        proc = subprocess.run(
            [sys.executable, script_path],
            capture_output=True, text=True,
            timeout=timeout,
            env={"PYTHONPATH": ":".join(sys.path),
                 "MKL_NUM_THREADS": "1", "OMP_NUM_THREADS": "1",
                 "OPENBLAS_NUM_THREADS": "1", "VECLIB_MAXIMUM_THREADS": "1"},
        )
        elapsed = int((time.time() - start) * 1000)
        return {
            "success": proc.returncode == 0,
            "exit_code": proc.returncode,
            "stdout": proc.stdout,
            "stderr": proc.stderr,
            "elapsed_ms": elapsed,
        }
    except subprocess.TimeoutExpired:
        elapsed = int((time.time() - start) * 1000)
        return {
            "success": False,
            "exit_code": -1,
            "stdout": "",
            "stderr": f"TIMEOUT (subprocess): execution timed out after {timeout}s",
            "elapsed_ms": elapsed,
        }
    finally:
        try:
            os.unlink(script_path)
        except OSError:
            pass


class DaemonRequestHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path == "/run":
            self._handle_run()
        elif self.path == "/shutdown":
            self._handle_shutdown()
        else:
            self._json_response(404, {"error": f"unknown path: {self.path}"})

    def do_GET(self):
        if self.path == "/health":
            self._handle_health()
        else:
            self._json_response(404, {"error": f"unknown path: {self.path}"})

    def _handle_run(self):
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length)
            req = json.loads(body)
        except Exception as e:
            return self._json_response(400, {"error": repr(e)})

        source = req.get("source", "")
        result = run_source(source)
        return self._json_response(200, result)

    def _handle_health(self):
        return self._json_response(200, {
            "status": "ok",
            "onnx_available": ONNX_AVAILABLE,
            "ort_available": ORT_AVAILABLE,
            "uptime_seconds": int(time.time() - DAEMON_START_TIME),
        })

    def _handle_shutdown(self):
        self._json_response(200, {"status": "shutting down"})
        threading.Thread(target=self.server.shutdown, daemon=True).start()

    def _json_response(self, status_code, data):
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode("utf-8"))

    def log_message(self, format, *args):
        pass  # 不输出 HTTP 日志到 stdout


def main():
    port = 34790  # 默认端口
    if "--port" in sys.argv:
        idx = sys.argv.index("--port")
        if idx + 1 < len(sys.argv):
            port = int(sys.argv[idx + 1])

    if "--test" in sys.argv:
        print(f"ONNX available: {ONNX_AVAILABLE}")
        print(f"ONNX Runtime available: {ORT_AVAILABLE}")
        if ONNX_AVAILABLE:
            import onnx
            print(f"ONNX version: {onnx.__version__}")
        if ORT_AVAILABLE:
            print(f"ONNX Runtime version: {ort.__version__}")
        if ONNX_AVAILABLE and ORT_AVAILABLE:
            print("All OK!")
        return

    server = HTTPServer(("127.0.0.1", port), DaemonRequestHandler)
    server.socket.settimeout(1.0)

    # 打印 JSON 就绪信息（JVM 将读取此信息获取端口等）
    server_info = {
        "type": "ready",
        "backend_available": ONNX_AVAILABLE and ORT_AVAILABLE,
        "port": port,
        "pid": os.getpid(),
    }
    if not (ONNX_AVAILABLE and ORT_AVAILABLE):
        server_info["import_error"] = _import_error

    print(json.dumps(server_info), flush=True)

    def sig_handler(signum, frame):
        server.shutdown()

    signal.signal(signal.SIGTERM, sig_handler)
    signal.signal(signal.SIGINT, sig_handler)

    try:
        while True:
            try:
                server.handle_request()
            except TimeoutError:
                pass
            except OSError:
                break
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
