"""
PyTorch.compile Daemon — 常驻 Python HTTP 服务

通过 HTTP POST 与 JVM 通信，避免每次测试重复加载 PyTorch。

API:
  POST /run    {"source": "<python code>"}
               → {"success": true, "exit_code": 0, "stdout": "...", "stderr": "...", "elapsed_ms": 100}

  GET  /health → {"status": "ok", "torch_available": true, "cuda_available": false, "uptime_seconds": 123}

  POST /shutdown → 优雅关闭

使用方法:
  python3 daemon/pytorch_daemon.py            # 启动 daemon（默认端口 34890）
  python3 daemon/pytorch_daemon.py --port 8888      # 指定端口
  python3 daemon/pytorch_daemon.py --test           # 自测试模式（验证 PyTorch 可用）
"""

import json
import signal
import sys
import threading
import time
import traceback
from http.server import HTTPServer, BaseHTTPRequestHandler
from io import StringIO

# 一次性导入 PyTorch（最耗时的部分）
TORCH_AVAILABLE = False
CUDA_AVAILABLE = False
_import_error = ""
_torch_import_detail = ""

try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    TORCH_AVAILABLE = True
    CUDA_AVAILABLE = torch.cuda.is_available()
except Exception as _ie:
    _import_error = repr(_ie)
    _torch_import_detail = traceback.format_exc()


# 每次 exec 的最大超时时间（秒），避免编译永久阻塞
EXEC_TIMEOUT_SECONDS = 120
DAEMON_START_TIME = time.time()


def _timeout_handler(signum, frame):
    """SIGALRM 处理器，抛出 TimeoutError。"""
    raise TimeoutError(f"exec timed out after {EXEC_TIMEOUT_SECONDS}s")


def run_source(source: str, timeout: int = EXEC_TIMEOUT_SECONDS) -> dict:
    """执行单次测试源码，捕获 stdout/stderr。

    使用 signal.alarm 进行超时保护：PyTorch 编译可能在某些输入下无限阻塞，
    此时 alarm 会触发 TimeoutError。
    """
    if not TORCH_AVAILABLE:
        return {
            "success": False,
            "exit_code": -1,
            "stdout": "",
            "stderr": f"PyTorch not available: {_import_error}",
            "elapsed_ms": 0,
        }

    # 保存原始 stdout/stderr
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    old_alarm = None
    
    # 重定向到 StringIO
    sys.stdout = StringIO()
    sys.stderr = StringIO()

    start = time.time()
    exit_code = 0
    success = True
    
    try:
        # 设置 alarm 超时
        if hasattr(signal, "SIGALRM"):
            signal.signal(signal.SIGALRM, _timeout_handler)
            old_alarm = signal.alarm(timeout)

        # 执行源码（提供 torch, nn, F 到命名空间）
        exec(source, {
            "torch": torch,
            "nn": nn,
            "F": F,
            "__builtins__": __builtins__,
        })
        success = True
        
    except SystemExit as e:
        # Python 脚本可能调用了 sys.exit()
        success = False
        exit_code = e.code if isinstance(e.code, int) else 1
        traceback.print_exc()
    except TimeoutError as e:
        success = False
        exit_code = -1
        print(f"TIMEOUT: {e}", file=sys.stderr)
    except Exception:
        traceback.print_exc()
        success = False
        exit_code = 1
    finally:
        # 恢复 alarm
        if hasattr(signal, "SIGALRM") and old_alarm is not None:
            signal.alarm(old_alarm)

    elapsed = int((time.time() - start) * 1000)
    captured_stdout = sys.stdout.getvalue()
    captured_stderr = sys.stderr.getvalue()
    
    # 恢复原始 stdout/stderr
    sys.stdout = old_stdout
    sys.stderr = old_stderr

    return {
        "success": success,
        "exit_code": exit_code,
        "stdout": captured_stdout,
        "stderr": captured_stderr,
        "elapsed_ms": elapsed,
    }


class DaemonRequestHandler(BaseHTTPRequestHandler):
    """HTTP 请求处理器。"""

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
        """处理 /run：执行 PyTorch 代码。"""
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length)
            req = json.loads(body)
        except Exception as e:
            self._json_response(400, {
                "success": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": f"Bad request: {e}",
                "elapsed_ms": 0,
            })
            return

        source = req.get("source", "")
        try:
            result = run_source(source)
            self._json_response(200, result)
        except Exception as e:
            self._json_response(500, {
                "success": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": f"Daemon internal error: {e}\n{traceback.format_exc()}",
                "elapsed_ms": 0,
            })

    def _handle_health(self):
        """处理 /health：返回 daemon 状态。"""
        self._json_response(200, {
            "status": "ok",
            "torch_available": TORCH_AVAILABLE,
            "cuda_available": CUDA_AVAILABLE,
            "uptime_seconds": int(time.time() - DAEMON_START_TIME),
            "import_error": _import_error if not TORCH_AVAILABLE else "",
        })

    def _handle_shutdown(self):
        """处理 /shutdown：优雅关闭。"""
        self._json_response(200, {"status": "shutting_down"})
        # 在响应发送后关闭服务
        threading.Thread(target=self.server.shutdown, daemon=True).start()

    def _json_response(self, status_code: int, data: dict):
        """发送 JSON 响应。"""
        body = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        """抑制默认的 HTTP 日志输出（太吵）。"""
        pass


def self_test():
    """自测试：验证 PyTorch 可用并运行一个简单的编译程序。"""
    if not TORCH_AVAILABLE:
        print(f"FAIL: PyTorch not available: {_import_error}")
        if _torch_import_detail:
            print(f"Detail:\n{_torch_import_detail}")
        sys.exit(1)
    print("PASS: PyTorch is available")
    print(f"  Version: {torch.__version__}")
    print(f"  CUDA available: {CUDA_AVAILABLE}")

    # 测试 torch.compile
    source = """
import torch
import torch.nn as nn

class TestModule(nn.Module):
    def forward(self, x):
        return torch.relu(x)

model = TestModule()
try:
    compiled = torch.compile(model)
    x = torch.randn(16, dtype=torch.float32)
    output = compiled(x)
    print(f"Success: output shape = {output.shape}")
except Exception as e:
    print(f"Compile/execution failed: {e}")
    raise
"""
    result = run_source(source)
    if result["success"]:
        print("PASS: torch.compile executed successfully")
        print(f"  stdout: {result['stdout'].strip()}")
    else:
        print("FAIL: torch.compile failed")
        print(f"  stderr: {result['stderr'].strip()}")
        sys.exit(1)


def main():
    import argparse
    
    parser = argparse.ArgumentParser(description="PyTorch.compile Daemon")
    parser.add_argument("--port", type=int, default=34890, help="HTTP server port (default: 34890)")
    parser.add_argument("--test", action="store_true", help="Run self-test and exit")
    args = parser.parse_args()

    if args.test:
        self_test()
        return

    # 启动 HTTP server
    port = args.port
    server = HTTPServer(("127.0.0.1", port), DaemonRequestHandler)
    server.socket.settimeout(1.0)  # 1 秒超时，让 serve_forever 能响应信号

    # 打印 ready 信息（JVM 将读取此行获取端口）
    import os
    server_info = {
        "type": "ready",
        "torch_available": TORCH_AVAILABLE,
        "cuda_available": CUDA_AVAILABLE,
        "tvm_available": TORCH_AVAILABLE,  # 兼容 DaemonClient.ReadyMessage
        "port": port,
        "pid": os.getpid(),
    }
    if not TORCH_AVAILABLE:
        server_info["import_error"] = _import_error
        server_info["import_detail"] = _torch_import_detail

    print(json.dumps(server_info), flush=True)

    # 注册信号处理：优雅退出
    def sig_handler(signum, frame):
        server.shutdown()

    signal.signal(signal.SIGTERM, sig_handler)
    signal.signal(signal.SIGINT, sig_handler)

    # 主循环
    try:
        while True:
            try:
                server.handle_request()
            except TimeoutError:
                pass  # socket 超时，继续循环
            except OSError:
                break
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
