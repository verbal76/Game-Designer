#!/usr/bin/env python3
"""Discovers on-device LiteRT-LM model packages (.litertlm) on Hugging Face and records VERIFIED metadata:
exact file, size and SHA-256 (from the LFS pointer, so no model is downloaded), license and whether the repository is gated.
Output: catalog.json. Run in CI (the authoring sandbox cannot reach Hugging Face)."""
import json, sys, urllib.request, urllib.parse, time

AUTHORS = ["litert-community"]
EXTRA_REPOS = json.load(open(sys.argv[1])).get("repos", []) if len(sys.argv) > 1 else []
OUT = sys.argv[2] if len(sys.argv) > 2 else "catalog.json"
API = "https://huggingface.co/api"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "gd-model-discovery/1"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except Exception as e:
            err = e; time.sleep(2 * (attempt + 1))
    return {"_error": str(err)}

repos = list(EXTRA_REPOS)
for a in AUTHORS:
    page = get(f"{API}/models?author={a}&limit=300&sort=downloads&direction=-1")
    if isinstance(page, list):
        repos += [m["id"] for m in page]
    else:
        print("author listing failed", a, page)
repos = list(dict.fromkeys(repos))
print("candidate repos:", len(repos))

models = []
for rid in repos:
    info = get(f"{API}/models/{urllib.parse.quote(rid, safe='/')}?blobs=true")
    if "_error" in info or "siblings" not in info:
        print("skip", rid, info.get("_error", "no siblings")); continue
    files = [s for s in info["siblings"] if s.get("rfilename", "").endswith(".litertlm")]
    if not files: continue
    card = info.get("cardData") or {}
    for f in files:
        lfs = f.get("lfs") or {}
        models.append({
            "repo": rid, "file": f["rfilename"], "size": f.get("size") or lfs.get("size"), "sha256": lfs.get("sha256") or lfs.get("oid"),
            "license": card.get("license") or next((t.split(":", 1)[1] for t in info.get("tags", []) if t.startswith("license:")), None),
            "gated": info.get("gated", False), "downloads": info.get("downloads"), "lastModified": info.get("lastModified"),
            "url": f"https://huggingface.co/{rid}/resolve/main/{urllib.parse.quote(f['rfilename'])}",
            "tags": info.get("tags", [])[:12], "baseModel": (card.get("base_model") if isinstance(card.get("base_model"), str) else None),
        })
models.sort(key=lambda m: (m["size"] or 0))
json.dump({"generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "models": models}, open(OUT, "w"), indent=1)
print("models with .litertlm files:", len(models))
for m in models: print(f"{m['size']/1e6:8.0f} MB  {m['license']!s:14} gated={m['gated']!s:6} {m['repo']}/{m['file']}  sha={str(m['sha256'])[:12]}")
