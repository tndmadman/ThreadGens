#!/usr/bin/env python3
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer


REDDIT_IDEA_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string", "minLength": 3, "maxLength": 64},
        "body": {"type": "string", "minLength": 12, "maxLength": 300},
    },
    "required": ["title", "body"],
    "additionalProperties": False,
}

X_IDEA_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string", "minLength": 3, "maxLength": 48},
        "body": {"type": "string", "minLength": 12, "maxLength": 280},
    },
    "required": ["title", "body"],
    "additionalProperties": False,
}

SEED_REPAIR_ATTEMPTS = 2


def _prompt_value(prompt, label, fallback):
    match = re.search(rf"(?m)^{re.escape(label)}\s*(.+?)\s*$", prompt)
    if not match:
        return fallback
    value = " ".join(match.group(1).split())
    return value or fallback


def _compact_seed_prompt(prompt, kind):
    subject = _prompt_value(prompt, "Target subject family:", "an interesting everyday or technical subject")
    lens = _prompt_value(prompt, "Creative lens:", "a concrete angle with a clear hook")
    setting = _prompt_value(prompt, "Setting guidance:", "use a natural setting only if it helps the idea")

    if kind == "reddit":
        return (
            "Create one strong Reddit post seed.\n"
            f"Subject: {subject}\n"
            f"Angle: {lens}\n"
            f"Context: {setting}\n\n"
            "Write a natural Reddit-style title and a concrete body that gives people something specific to respond to.\n"
            "Title: non-empty, 5-11 words, 3-64 characters.\n"
            "Body: non-empty, 1-2 sentences, about 25-45 words, 12-300 characters.\n"
            "Return only the title and body required by the JSON schema."
        )

    return (
        "Create one strong X post seed.\n"
        f"Subject: {subject}\n"
        f"Angle: {lens}\n"
        f"Context: {setting}\n\n"
        "Write a concise visible post with a short hidden reply-style title.\n"
        "Title: non-empty, 2-8 words, 3-48 characters.\n"
        "Body: non-empty, concrete, 12-280 characters.\n"
        "Return only the title and body required by the JSON schema."
    )


def _normalize_seed_text(value):
    return " ".join(str(value or "").split()).strip()


def _seed_limits(kind):
    if kind == "x":
        return 48, 280
    return 64, 300


def _extract_seed_object(value, kind):
    if not isinstance(value, dict):
        return None, "seed JSON was not an object"

    title = _normalize_seed_text(value.get("title"))
    body = _normalize_seed_text(value.get("body"))

    # Recover a few common small-model schema drifts before asking Ollama again.
    # The normalized response sent back to PowerShell is still exactly title/body.
    if not title:
        for key in ("headline", "query", "subject"):
            candidate = _normalize_seed_text(value.get(key))
            if candidate:
                title = candidate
                break
    if not body:
        for key in ("text", "post", "content"):
            candidate = value.get(key)
            if isinstance(candidate, str) and _normalize_seed_text(candidate):
                body = _normalize_seed_text(candidate)
                break

    title_max, body_max = _seed_limits(kind)
    if len(title) < 3:
        return None, f"title was empty/short ({len(title)} chars)"
    if len(body) < 12:
        return None, f"body was empty/short ({len(body)} chars)"
    if len(title) > title_max:
        return None, f"title exceeded {title_max} chars ({len(title)})"
    if len(body) > body_max:
        return None, f"body exceeded {body_max} chars ({len(body)})"
    if kind == "x" and len(title.split()) > 8:
        return None, f"X title exceeded 8 words ({len(title.split())})"
    if kind == "reddit" and len(title.split()) > 11:
        return None, f"Reddit title exceeded 11 words ({len(title.split())})"

    return {"title": title, "body": body}, None


def _validate_ollama_seed_payload(payload_bytes, kind):
    try:
        outer = json.loads(payload_bytes.decode("utf-8"))
    except Exception as exc:
        return None, f"Ollama outer response was invalid JSON: {exc}"
    if not isinstance(outer, dict):
        return None, "Ollama outer response was not an object"

    raw_response = outer.get("response")
    if isinstance(raw_response, dict):
        inner = raw_response
    else:
        raw_text = str(raw_response or "").strip()
        if not raw_text:
            return None, "Ollama response field was empty"
        first = raw_text.find("{")
        last = raw_text.rfind("}")
        if first < 0 or last <= first:
            return None, f"Ollama response had no JSON object: {raw_text[:180]!r}"
        try:
            inner = json.loads(raw_text[first:last + 1])
        except Exception as exc:
            return None, f"Ollama seed JSON was invalid: {exc}; raw={raw_text[:180]!r}"

    seed, problem = _extract_seed_object(inner, kind)
    if seed is None:
        preview = json.dumps(inner, ensure_ascii=False, separators=(",", ":"))[:240]
        return None, f"{problem}; raw={preview}"

    # PowerShell expects response.response to contain a JSON object. Normalize it
    # here so a structurally valid seed cannot be misread because of extra fields.
    outer["response"] = json.dumps(seed, ensure_ascii=False, separators=(",", ":"))
    return json.dumps(outer, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), None


def _repair_seed_request(body, kind, prior_problem, repair_number):
    payload = json.loads(body.decode("utf-8"))
    base_prompt = str(payload.get("prompt") or "").strip()
    payload["prompt"] = (
        f"{base_prompt}\n\n"
        "Your previous structured result was unusable. "
        f"Problem: {prior_problem.split('; raw=', 1)[0]}. "
        "Return a NON-EMPTY JSON object with exactly two string keys: title and body. "
        "Do not return empty strings, nulls, arrays, nested objects, or extra keys."
    )
    # Some small models can get trapped producing legal-but-empty strings under a
    # strict grammar. Keep JSON mode for the repair while validating exact shape
    # ourselves before the response leaves the proxy.
    payload["format"] = "json"
    options = payload.get("options")
    if not isinstance(options, dict):
        options = {}
    options["temperature"] = max(0.50, 0.64 - ((repair_number - 1) * 0.06))
    options["top_p"] = 0.88
    options["top_k"] = 32
    options["repeat_penalty"] = 1.03
    options["num_predict"] = 180
    payload["options"] = options
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


def constrain_threadgens_idea_request(body):
    """Turn batch seed generation into a small, schema-bound Ollama task."""
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

    # Do not ask the 8B model to reason over the whole batch controller prompt.
    # The PowerShell code already owns novelty, cooldown, render-fit, and retry
    # decisions. Give the model only the creative axes needed for this one seed.
    payload["prompt"] = _compact_seed_prompt(prompt, kind)
    payload["format"] = schema

    # Tuned for short structured ideation: enough variation to avoid clones,
    # without the 1.10-temperature drift that produced unrelated JSON shapes.
    options = payload.get("options")
    if not isinstance(options, dict):
        options = {}
    options["temperature"] = 0.72
    options["top_p"] = 0.90
    options["top_k"] = 40
    options["repeat_penalty"] = 1.05
    options["num_predict"] = 180
    payload["options"] = options

    return json.dumps(payload, separators=(",", ":")).encode("utf-8"), kind


def _post_upstream(url, body, content_type, timeout):
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={"Content-Type": content_type or "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, response.headers.get("Content-Type", "application/json"), response.read()


class SerialProxyHandler(BaseHTTPRequestHandler):
    server_version = "ThreadGensOllamaProxy/1.3"

    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        if self.path == "/health":
            payload = b'{"status":"ok","seed_validation":"repair-and-normalize"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        original_body = self.rfile.read(length)
        body, constrained_kind = constrain_threadgens_idea_request(original_body)
        upstream = self.server.upstream.rstrip("/") + self.path
        started = time.time()
        self.server.request_counter += 1
        request_id = self.server.request_counter
        if constrained_kind:
            print(
                f"[ollama-proxy] #{request_id} tuned {constrained_kind} seed: compact prompt + strict title/body schema",
                flush=True,
            )
        print(f"[ollama-proxy] #{request_id} -> {self.path}", flush=True)

        content_type = self.headers.get("Content-Type", "application/json")
        try:
            status, response_type, payload = _post_upstream(
                upstream, body, content_type, self.server.upstream_timeout
            )

            if constrained_kind and status == 200:
                normalized, problem = _validate_ollama_seed_payload(payload, constrained_kind)
                repair_number = 0
                while normalized is None and repair_number < SEED_REPAIR_ATTEMPTS:
                    repair_number += 1
                    print(
                        f"[ollama-proxy] #{request_id} invalid {constrained_kind} seed "
                        f"({problem}); repair {repair_number}/{SEED_REPAIR_ATTEMPTS}",
                        file=sys.stderr,
                        flush=True,
                    )
                    repair_body = _repair_seed_request(body, constrained_kind, problem, repair_number)
                    status, response_type, payload = _post_upstream(
                        upstream, repair_body, content_type, self.server.upstream_timeout
                    )
                    if status != 200:
                        break
                    normalized, problem = _validate_ollama_seed_payload(payload, constrained_kind)

                if status == 200 and normalized is not None:
                    payload = normalized
                elif status == 200:
                    message = (
                        f"ThreadGens seed schema validation failed after "
                        f"{1 + SEED_REPAIR_ATTEMPTS} Ollama generations: {problem}"
                    )
                    print(f"[ollama-proxy] #{request_id} {message}", file=sys.stderr, flush=True)
                    payload = json.dumps({"error": message}, separators=(",", ":")).encode("utf-8")
                    status = 502
                    response_type = "application/json"

            self.send_response(status)
            self.send_header("Content-Type", response_type)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(payload)
            print(
                f"[ollama-proxy] #{request_id} <- {status} in {time.time() - started:.2f}s",
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
