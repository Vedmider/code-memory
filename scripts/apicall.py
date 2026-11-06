#!/usr/bin/env python3
"""Temporary endpoint client for the AI to call the two apps correctly.

The AI runs one subcommand per call and reasons over the returned JSON itself; this script
only does correct HTTP (right URL, params, headers) and prints the raw structured response.

  python3 scripts/apicall.py health
  python3 scripts/apicall.py retrieve "how are roles parsed?" --top-k 3 --roles engineering
  python3 scripts/apicall.py search   "RateLimitInterceptor.clientKey" --agent dev --top-k 8
  python3 scripts/apicall.py context  "where is the client IP resolved?" --agent dev --top-k 5
  python3 scripts/apicall.py index    --repo rag-app --root /repo
  python3 scripts/apicall.py sync     --repo rag-app --root /repo --paths src/a.java src/b.java

Endpoints (override with env): RAG_URL (default http://localhost:18080),
CM_URL (default http://localhost:8080).
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

RAG_URL = os.environ.get("RAG_URL", "http://localhost:18080")
CM_URL = os.environ.get("CM_URL", "http://localhost:8080")


def call(method, url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode()
            return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except urllib.error.URLError as e:
        return 0, json.dumps({"error": "unreachable", "detail": str(e.reason)})


def emit(status, raw):
    try:
        print(json.dumps(json.loads(raw), indent=2))
    except json.JSONDecodeError:
        print(raw)
    sys.exit(0 if 200 <= status < 300 else 1)


def main():
    parser = argparse.ArgumentParser(description="Call rag-app / code-memory endpoints.")
    sub = parser.add_subparsers(dest="cmd")

    sub.add_parser("health")

    p = sub.add_parser("retrieve")  # rag-app: concept/docs, no LLM
    p.add_argument("query")
    p.add_argument("--top-k", type=int, default=3)
    p.add_argument("--roles", default="")

    for name in ("search", "context"):  # code-memory: locators
        p = sub.add_parser(name)
        p.add_argument("query")
        p.add_argument("--agent", default="dev")
        p.add_argument("--top-k", type=int, default=5)
        p.add_argument("--repo", default="")  # scope results to one repo (empty = all)

    p = sub.add_parser("index")
    p.add_argument("--repo", required=True)
    p.add_argument("--root", required=True)
    p.add_argument("--force", action="store_true")  # wipe repo state first (clean full reindex)

    p = sub.add_parser("sync")
    p.add_argument("--repo", required=True)
    p.add_argument("--root", required=True)
    p.add_argument("--paths", nargs="*", default=None)

    p = sub.add_parser("ingest-docs")  # load files into rag-app as KB documents
    p.add_argument("files", nargs="+")
    p.add_argument("--prefix", default="")
    p.add_argument("--roles", default="engineering")

    args = parser.parse_args()

    if not args.cmd:
        parser.error("missing command "
                     "(health|retrieve|ingest-docs|search|context|index|sync)")

    if args.cmd == "health":
        for name, base in (("rag-app", RAG_URL), ("code-memory", CM_URL)):
            status, raw = call("GET", base + "/actuator/health")
            print("%-12s %s %s" % (name, status, raw))
        return
    if args.cmd == "retrieve":
        headers = {"X-Roles": args.roles} if args.roles else {}
        emit(*call("POST", RAG_URL + "/api/retrieve",
                   {"query": args.query, "topK": args.top_k}, headers))
    if args.cmd in ("search", "context"):
        key = "query" if args.cmd == "search" else "task"
        body = {key: args.query, "agent": args.agent, "topK": args.top_k}
        if args.repo:
            body["repo"] = args.repo
        emit(*call("POST", CM_URL + "/api/" + args.cmd, body))
    if args.cmd == "index":
        emit(*call("POST", CM_URL + "/api/index",
                   {"repo": args.repo, "root": args.root, "force": args.force}))
    if args.cmd == "sync":
        body = {"repo": args.repo, "root": args.root}
        if args.paths:
            body["paths"] = args.paths
        emit(*call("POST", CM_URL + "/api/sync", body))
    if args.cmd == "ingest-docs":
        acl = [r for r in args.roles.split(",") if r]
        docs = []
        for f in args.files:
            name = os.path.splitext(os.path.basename(f))[0]
            if name == "SKILL":
                name = os.path.basename(os.path.dirname(f))
            with open(f, encoding="utf-8", errors="ignore") as fh:
                docs.append({"documentId": args.prefix + name,
                             "sectionPath": os.path.basename(f),
                             "content": fh.read(), "aclTags": acl})
        emit(*call("POST", RAG_URL + "/api/documents", docs))


if __name__ == "__main__":
    main()
