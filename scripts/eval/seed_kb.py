#!/usr/bin/env python3
"""
Seed 知识库（CI 门禁用）
========================
清空 default 租户后，把 golden-set/v1/documents/ 下所有文档 POST 到 /api/rag/index。
CI 环境从零起，必须先灌库再评测，否则 Recall@5 全 0。

用法:
    python seed_kb.py [documents目录]   # 默认 src/test/resources/golden-set/v1/documents
环境变量:
    GOLDEN_SET_BASE_URL  默认 http://localhost:8082
"""

import glob
import json
import os
import pathlib
import sys
import urllib.request

BASE_URL = os.getenv("GOLDEN_SET_BASE_URL", "http://localhost:8082")
API_PREFIX = "/api/rag"


def clear_collections():
    req = urllib.request.Request(
        f"{BASE_URL}{API_PREFIX}/collections", method="DELETE",
        headers={"X-Tenant-Id": "default"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode())


def index_file(path: str) -> int:
    with open(path, "rb") as f:
        data = f.read()
    filename = pathlib.Path(path).name
    boundary = "----agent-platform-seed"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: text/markdown\r\n\r\n"
    ).encode("utf-8") + data + f"\r\n--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}{API_PREFIX}/index", data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "X-Tenant-Id": "default"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode()).get("data", 0)


def main():
    doc_dir = sys.argv[1] if len(sys.argv) > 1 else "src/test/resources/golden-set/v1/documents"
    files = sorted(glob.glob(os.path.join(doc_dir, "*")))
    if not files:
        print(f"❌ 目录无文档: {doc_dir}")
        sys.exit(1)
    print("清空 default 租户集合...")
    clear_collections()
    total = 0
    for f in files:
        try:
            n = index_file(f)
            total += n
            print(f"✅ {os.path.basename(f)}: {n} 切片")
        except Exception as e:
            print(f"❌ {os.path.basename(f)}: {e}")
            sys.exit(1)
    print(f"\n知识库 seed 完成，共 {total} 切片（docs/{len(files)}）")


if __name__ == "__main__":
    main()
