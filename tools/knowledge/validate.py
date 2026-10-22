#!/usr/bin/env python3
"""Validate the knowledge-library entries against the source transcript.

Usage: validate.py <transcript.txt> [entries_dir] [--report out.md]

The transcript is never stored in the repository; this script reads it from the path given. Checks:
  * schema: required fields, types, unique ids, valid related ids, tag vocabulary
  * traceability: start/end are real transcript timestamps, start <= end, and the game's name (or a significant word of it) is spoken inside the range
  * copyright safety: no explanatory field shares a run of NGRAM or more consecutive words with the transcript (game titles, numbers and
    the allow-listed design terms are removed from both sides first, so ordinary terminology and titles may overlap)
  * size: concise fields only
Also prints a coverage report: transcript ranges that no entry covers (to find games that were not extracted).
Exit status 1 on any failure.
"""
import json, re, sys, glob, os

NGRAM = 5
REQUIRED = ["id", "game", "tags", "problem", "mechanic", "effect", "question", "confidence"]
TIMED = ["start", "end"]
OPTIONAL = ["tradeoffs", "inference", "related", "source", "locator", "classification", "explicitOnly"]
CLASSES = {"SOURCE_SUPPORTED", "SOURCE_CONSTRUCT", "SOURCE_FRAMEWORK", "SOURCE_OBSERVATION", "SOURCE_CAVEAT"}
TEXT_FIELDS = ["problem", "mechanic", "effect", "tradeoffs", "inference", "question"]
MAXLEN = {"problem": 220, "mechanic": 520, "effect": 420, "tradeoffs": 320, "inference": 320, "question": 200}
# Named design concepts and ordinary terms that may legitimately appear in both places.
TERMS = ["difficulty curve", "negative feedback loop", "feedback loop", "diegetic difficulty", "artificial difficulty", "design by subtraction", "inverted pyramid of decision making",
         "output randomness", "input randomness", "procedural generation", "game feel", "immersive sim", "skill tree", "open world", "level design", "core loop", "role reversal",
         "stealth game", "action rpg", "tower defense", "social deduction", "level up", "game design", "player agency", "game designer", "game designers"]

def norm_words(s):
    s = s.lower().replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    return re.findall(r"[a-z0-9']+", s)

def load_transcript(path):
    lines = []
    for raw in open(path, encoding="utf-8"):
        m = re.match(r"\[(\d\d):(\d\d):(\d\d)\]\s*(.*)", raw.rstrip("\n"))
        if m:
            t = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3])
            lines.append((t, m[4]))
    return lines

def ts(s):
    m = re.fullmatch(r"(\d\d):(\d\d):(\d\d)", s)
    return int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) if m else None

def strip_allowed(words, titles):
    text = " ".join(words)
    for t in TERMS + titles:
        t = " ".join(norm_words(t))
        if t: text = re.sub(r"\b" + re.escape(t) + r"\b", " ", text)
    return text.split()

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    report = None
    if "--report" in sys.argv: report = sys.argv[sys.argv.index("--report") + 1]; args = [a for a in args if a != report]
    transcript = load_transcript(args[0])
    texts = {}
    for i, a in enumerate(sys.argv):
        if a == "--source": k, _, path = sys.argv[i + 1].partition("="); texts[k] = open(path, encoding="utf-8").read(); args = [x for x in args if x != sys.argv[i + 1]]
    sources = json.load(open(os.path.join(os.path.dirname(__file__), "../../docs/knowledge-library/sources.json")))
    d = args[1] if len(args) > 1 else os.path.join(os.path.dirname(__file__), "../../docs/knowledge-library/entries")
    entries = []
    for f in sorted(glob.glob(os.path.join(d, "part-*.json"))): entries += json.load(open(f, encoding="utf-8"))
    tags_ok = set(json.load(open(os.path.join(d, "../tags.json")))["tags"]) if os.path.exists(os.path.join(d, "../tags.json")) else None
    ids = [e.get("id") for e in entries]
    errors, warnings = [], []
    full_words = norm_words(" ".join(t for _, t in transcript))
    # titles anywhere: every capitalised word run in the entry's own game field, removed before comparing
    for e in entries:
        eid = e.get("id", "?")
        for k in REQUIRED:
            if k not in e or e[k] in (None, "", []): errors.append(f"{eid}: missing {k}")
        if ids.count(eid) > 1: errors.append(f"{eid}: duplicate id")
        src = e.get("source", "gmtk")
        if src not in sources: errors.append(f"{eid}: unknown source {src}")
        if src == "gmtk":
            for k in TIMED:
                if not e.get(k): errors.append(f"{eid}: missing {k}")
        else:
            if e.get("locator") not in sources.get(src, {}).get("sections", []): errors.append(f"{eid}: locator {e.get('locator')!r} is not a listed section of {src}")
            if e.get("classification") not in CLASSES: errors.append(f"{eid}: bad classification")
            if not e.get("tradeoffs") or not e.get("inference"): errors.append(f"{eid}: a non-transcript entry must state its tradeoffs and label its own inference")
            if src not in texts: errors.append(f"{eid}: no --source text given for {src}")
            else:
                titles = [e.get("game", "")] + re.findall(r"\b[A-Z][\w'’.:-]*(?:\s+(?:of|the|and|in|vs\.?|[A-Z0-9][\w'’.:-]*))*", " ".join(str(e.get(k) or "") for k in TEXT_FIELDS))
                tw = strip_allowed(norm_words(texts[src]), titles + ["vitality structure", "vitality structures", "final stretch", "meta spatial", "meta temporal"])
                grams = set(tuple(tw[i:i + NGRAM]) for i in range(len(tw) - NGRAM + 1))
                for k in TEXT_FIELDS:
                    w = strip_allowed(norm_words(e.get(k) or ""), titles + ["vitality structure", "vitality structures", "final stretch", "meta spatial", "meta temporal"])
                    for i in range(len(w) - NGRAM + 1):
                        if tuple(w[i:i + NGRAM]) in grams: errors.append(f"{eid}: {k} shares a {NGRAM}-word run with the source: \"{' '.join(w[i:i + NGRAM])}\""); break
        for k in e:
            if k not in REQUIRED + TIMED + OPTIONAL: errors.append(f"{eid}: unexpected field {k} (verbatim anchors and excerpts are not allowed)")
        for r in e.get("related") or []:
            if r not in ids: errors.append(f"{eid}: related id {r} does not exist")
        if tags_ok is not None:
            for t in e.get("tags", []):
                if t not in tags_ok: errors.append(f"{eid}: tag {t} is not in tags.json")
        for k, n in MAXLEN.items():
            if e.get(k) and len(e[k]) > n: errors.append(f"{eid}: {k} is {len(e[k])} chars (max {n})")
        if src != "gmtk": continue
        a, b = ts(e.get("start", "")), ts(e.get("end", ""))
        if a is None or b is None or a > b: errors.append(f"{eid}: bad timestamps"); continue
        times = [t for t, _ in transcript]
        if a not in times: errors.append(f"{eid}: start {e['start']} is not a transcript timestamp")
        if b < times[0] or b > times[-1] + 30: errors.append(f"{eid}: end outside the transcript")
        seg = " ".join(t for tm, t in transcript if a - 1 <= tm <= b + 1)
        game_words = [w for w in norm_words(re.sub(r"\(.*?\)", " ", e.get("game", ""))) if len(w) > 3 and w not in ("the", "with", "and", "from", "game", "ghost")]
        seg_words = set(norm_words(seg))
        if game_words and not any(w in seg_words for w in game_words): errors.append(f"{eid}: the game's name is not spoken in {e['start']}-{e['end']}")
        titles = [e.get("game", "")] + re.findall(r"\b[A-Z][\w'’.:-]*(?:\s+(?:of|the|and|in|vs\.?|[A-Z0-9][\w'’.:-]*))*", " ".join(str(e.get(k) or "") for k in TEXT_FIELDS))
        titles += re.findall(r"\(([^)]*)\)", e.get("game", ""))
        tw = strip_allowed(full_words, titles)
        grams = set(tuple(tw[i:i + NGRAM]) for i in range(len(tw) - NGRAM + 1))
        for k in TEXT_FIELDS:
            v = e.get(k)
            if not v: continue
            w = strip_allowed(norm_words(v), titles)
            for i in range(len(w) - NGRAM + 1):
                if tuple(w[i:i + NGRAM]) in grams:
                    errors.append(f"{eid}: {k} shares a {NGRAM}-word run with the transcript: \"{' '.join(w[i:i + NGRAM])}\"")
                    break
        if e.get("tradeoffs") is None and e.get("inference"): pass
    # coverage: spans of the transcript not inside any entry
    spans = sorted((ts(e["start"]), ts(e["end"])) for e in entries if e.get("source", "gmtk") == "gmtk" and ts(e.get("start", "")) is not None and ts(e.get("end", "")) is not None)
    gaps, cursor = [], transcript[0][0]
    first_game = 150  # the introduction ends at 00:02:29
    cursor = first_game
    for a, b in spans:
        if a - cursor > 60: gaps.append((cursor, a))
        cursor = max(cursor, b)
    if transcript[-1][0] - cursor > 90: gaps.append((cursor, transcript[-1][0]))
    fmt = lambda s: f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"
    out = [f"entries: {len(entries)}  games: {len(set(e['game'] for e in entries if e.get('source','gmtk') == 'gmtk'))}  errors: {len(errors)}"]
    out += [f"uncovered transcript range {fmt(a)}-{fmt(b)}" for a, b in gaps]
    out += errors
    text = "\n".join(out)
    print(text)
    if report: open(report, "w").write(text + "\n")
    sys.exit(1 if errors else 0)

if __name__ == "__main__": main()
