#!/usr/bin/env python3
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer


class SerialProxyHandler(BaseHTTPRequestHandler):
    server_version = "ThreadGensOllamaProxy/1.0"

    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        if self.path == "/health":
            payload = b'{"status":"ok"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length)
        upstream = self.server.upstream.rstrip("/") + self.path
        started = time.time()
        self.server.request_counter += 1
        request_id = self.server.request_counter
        print(f"[ollama-proxy] #{request_id} -> {self.path}", flush=True)

        request = urllib.request.Request(
            upstream,
            data=body,
            method="POST",
            headers={"Content-Type": self.headers.get("Content-Type", "application/json")},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.server.upstream_timeout) as response:
                payload = response.read()
                self.send_response(response.status)
                content_type = response.headers.get("Content-Type", "application/json")
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(payload)
                print(
                    f"[ollama-proxy] #{request_id} <- {response.status} in {time.time() - started:.2f}s",
                    flush=True,
                )
        except urllib.error.HTTPError as exc:
            payload = exc.read()
            self.send_response(exc.code)
            self.send_header("Content-Type", exc.headers.get("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(payload)
            print(
                f"[ollama-proxy] #{request_id} <- HTTP {exc.code} in {time.time() - started:.2f}s",
                flush=True,
            )
        except Exception as exc:
            payload = json.dumps({"error": f"Ollama proxy upstream failure: {exc}"}).encode("utf-8")
            self.send_response(502)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(payload)
            print(
                f"[ollama-proxy] #{request_id} <- proxy failure in {time.time() - started:.2f}s: {exc}",
                file=sys.stderr,
                flush=True,
            )


def main():
    parser = argparse.ArgumentParser(description="Serialize ThreadGens Ollama HTTP requests across parallel workers")
    parser.add_argument("--listen-host", default="127.0.0.1")
    parser.add_argument("--listen-port", type=int, required=True)
    parser.add_argument("--upstream", default="http://127.0.0.1:11434")
    parser.add_argument("--upstream-timeout", type=int, default=600)
    args = parser.parse_args()

    server = HTTPServer((args.listen_host, args.listen_port), SerialProxyHandler)
    server.upstream = args.upstream
    server.upstream_timeout = max(30, args.upstream_timeout)
    server.request_counter = 0
    print(
        f"[ollama-proxy] listening on http://{args.listen_host}:{args.listen_port} -> {args.upstream}",
        flush=True,
    )
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
