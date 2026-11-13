#!/usr/bin/env python3
"""Grok art studio: turns art/requests/*.json into images in art/out/<id>/ using the xAI image API.

Request file fields:
  id            folder name for the results (defaults to the file name)
  prompt        what to draw (the house style in art/STYLE.md is added automatically)
  references    list of repo image paths sent to Grok as <IMAGE_0>, <IMAGE_1>, ... (uses the edits endpoint)
  n             how many variations (default 4)
  aspect_ratio  e.g. "1:1", "3:4", "16:9", "21:9" (default "1:1")
  resolution    "1k" | "1.5k" | "2k" (default "1k")
  redo          bump this number to regenerate a request that already has results
A request is skipped when art/out/<id>/done.json exists with the same redo number.
"""
import base64, glob, json, os, sys, time, urllib.request, urllib.error

API = "https://api.x.ai/v1/images/"
MODEL = os.environ.get("XAI_IMAGE_MODEL", "grok-imagine-image-2.0")
KEY = os.environ.get("XAI_API_KEY", "")
STYLE = open("art/STYLE.md", encoding="utf-8").read().split("-->", 1)[-1].strip()

def data_uri(path):
    ext = os.path.splitext(path)[1].lower().lstrip(".")
    mime = {"jpg": "jpeg", "jpeg": "jpeg", "png": "png", "webp": "webp"}[ext]
    return "data:image/%s;base64,%s" % (mime, base64.b64encode(open(path, "rb").read()).decode())

def call(endpoint, body):
    req = urllib.request.Request(API + endpoint, data=json.dumps(body).encode(), method="POST",
                                 headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            msg = e.read().decode(errors="replace")
            if e.code in (429, 500, 502, 503) and attempt < 2:
                time.sleep(20 * (attempt + 1)); continue
            raise RuntimeError("HTTP %s: %s" % (e.code, msg[:2000]))

def run(path):
    spec = json.load(open(path, encoding="utf-8"))
    rid = spec.get("id") or os.path.splitext(os.path.basename(path))[0]
    out = os.path.join("art/out", rid); os.makedirs(out, exist_ok=True)
    done = os.path.join(out, "done.json")
    redo = spec.get("redo", 0)
    if os.path.exists(done) and json.load(open(done)).get("redo", 0) == redo:
        return False
    refs = spec.get("references", [])
    prompt = STYLE + "\n\nTASK:\n" + spec["prompt"].strip()
    body = {"model": spec.get("model", MODEL), "prompt": prompt, "n": int(spec.get("n", 4)),
            "aspect_ratio": spec.get("aspect_ratio", "1:1"), "resolution": spec.get("resolution", "1k"),
            "response_format": "b64_json"}
    if refs:
        imgs = [{"url": data_uri(p), "type": "image_url"} for p in refs]
        if len(imgs) == 1: body["image"] = imgs[0]
        else: body["images"] = imgs
    print("->", rid, "(%d refs, n=%d)" % (len(refs), body["n"]), flush=True)
    try:
        res = call("edits" if refs else "generations", body)
    except Exception as e:
        json.dump({"error": str(e), "redo": redo, "at": time.strftime("%Y-%m-%d %H:%M:%S")}, open(os.path.join(out, "error.json"), "w"), indent=1)
        print("   failed:", e, flush=True); return True
    for old in glob.glob(os.path.join(out, "v*.*")): os.remove(old)
    for i, d in enumerate(res.get("data", [])):
        ext = {"image/png": "png", "image/webp": "webp"}.get(d.get("mime_type"), "jpg")
        open(os.path.join(out, "v%d.%s" % (i + 1, ext)), "wb").write(base64.b64decode(d["b64_json"]))
    cost = (res.get("usage") or {}).get("cost_in_usd_ticks")
    if os.path.exists(os.path.join(out, "error.json")): os.remove(os.path.join(out, "error.json"))
    json.dump({"redo": redo, "images": len(res.get("data", [])), "cost_usd": (cost / 1e10) if cost else None,
               "model": body["model"], "at": time.strftime("%Y-%m-%d %H:%M:%S")}, open(done, "w"), indent=1)
    print("   saved %d image(s)" % len(res.get("data", [])), flush=True)
    return True

if __name__ == "__main__":
    if not KEY: sys.exit("XAI_API_KEY secret is not set. Add it under Settings > Secrets and variables > Actions.")
    changed = [run(p) for p in sorted(glob.glob("art/requests/*.json"))]
    print("processed", sum(changed), "request(s)")
