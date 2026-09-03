#!/usr/bin/env python3
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer


REDDIT_IDEA_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "body": {"type": "string"},
    },
    "required": ["title", "body"],
    "additionalProperties": False,
}

X_IDEA_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "body": {"type": "string"},
    },
    "required": ["title", "body"],
    "additionalProperties": False,
}


def constrain_threadgens_idea_request(body):
    """Force batch seed requests into the exact title/body shape ThreadGens expects."""
    try:
        payload = json.loads(body.decode("utf-8"))
    except Exception:
        return body, None

    if not isinstance(payload, dict):
        return body, None

    prompt = str(payload.get("prompt") or "")
    if "Create one NEW ThreadGens Reddit seed." in prompt:
        schema = REDDIT_IDEA_SCHEMA
        kind = "reddit"
    elif "Create one NEW ThreadGens X seed." in prompt:
        schema = X_IDEA_SCHEMA
        kind = "x"
    else:
        return body, None

    # format='json' only guarantees some JSON. A JSON schema guarantees the
    # two fields the batch parser actually consumes and blocks unrelated
    # article/search-shaped objects.
    payload["format"] = schema

    # The batch prompt previously used temperature 1.10. That is useful for
    # free-form ideation but unnecessarily increases schema drift. Keep enough
    # randomness for varied seeds while making the structured response stable.
    options = payload.get("options")
    if not isinstance(options, dict):
        options = {}
    try:
        temperature = float(options.get("temperature", 0.80))
    except (TypeError, ValueError):
        temperature = 0.80
    options["temperature"] = min(temperature, 0.80)
    payload["options"] = options

    return json.dumps(payload, separators=(",", ":")).encode("utf-8"), kind


class SerialProxyHandler(BaseHTTPRequestHandler):
    server_version = "ThreadGensOllamaProxy/1.1"

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
        body, constrained_kind = constrain_threadgens_idea_request(body)
        upstream = self.server.upstream.rstrip("/") + self.path
        started = time.time()
        self.server.request_counter += 1
        request_id = self.server.request_counter
        if constrained_kind:
            print(
                f"[ollama-proxy] #{request_id} constrained {constrained_kind} seed to title/body schema",
                flush=True,
            )
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
