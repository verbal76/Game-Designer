#!/usr/bin/env bash
# Publishes a built channel directory as the GitHub prerelease "ota-<channel>" (never "latest").
# Usage: publish.sh <channel> <dir-with-manifest.json,manifest.sig,bundle.zip>
#
# The release keeps, besides the latest files older clients read (manifest.json, manifest.sig, bundle.zip):
#   manifest-<N>.json / manifest-<N>.sig / bundle-<N>.zip   one set per recent build, so a build can be chosen later
#   index.json                                              the list of those builds (newest 10 PER runtime generation)
# index.json is only a discovery hint: every build is still verified against its own signed manifest on the phone.
set -euo pipefail
CH="$1"; DIR="$2"; R="$GITHUB_REPOSITORY"; TAG="ota-$CH"
J() { python3 -c "import json,sys;print(json.load(open('$DIR/manifest.json'))['$1'])"; }
VER=$(J bundleVersion); NAME=$(J versionName)
cp "$DIR/manifest.json" "$DIR/manifest-$VER.json"; cp "$DIR/manifest.sig" "$DIR/manifest-$VER.sig"; cp "$DIR/bundle.zip" "$DIR/bundle-$VER.zip"

# Merge this build into the existing index (if any), keeping the newest 10 per runtime generation.
OLD="$(mktemp -d)"
gh release download "$TAG" --pattern index.json --dir "$OLD" --repo "$R" >/dev/null 2>&1 || echo '{"schema":1,"channel":"'"$CH"'","builds":[]}' > "$OLD/index.json"
DROP=$(python3 - "$DIR" "$OLD/index.json" "$CH" <<'PY'
import json, sys
d, old, ch = sys.argv[1:4]
m = json.load(open(f"{d}/manifest.json"))
idx = json.load(open(old))
entry = {"version": m["bundleVersion"], "versionName": m["versionName"], "sourceSha": m.get("sourceSha", ""), "createdAt": m.get("createdAt", ""),
         "shellApiLevel": m["shellApiLevel"], "runtimeFingerprint": m["runtimeFingerprint"]}
builds = [b for b in idx.get("builds", []) if b["version"] != entry["version"]] + [entry]
keep, groups = [], {}
for b in sorted(builds, key=lambda b: -b["version"]):
    g = groups.setdefault(b["runtimeFingerprint"], [])
    if len(g) < 10:
        g.append(b); keep.append(b)
dropped = sorted({b["version"] for b in builds} - {b["version"] for b in keep})
json.dump({"schema": 1, "channel": ch, "builds": sorted(keep, key=lambda b: -b["version"])}, open(f"{d}/index.json", "w"), indent=2)
print(" ".join(str(v) for v in dropped))
PY
)

NOTES="OTA $CH channel. Latest bundle: v$VER ($NAME). Consumed by the Game Designer app; not a downloadable app. Keeps the last builds so one can be chosen; replaced in place on each publish."
FILES=("$DIR/manifest.json" "$DIR/manifest.sig" "$DIR/bundle.zip" "$DIR/manifest-$VER.json" "$DIR/manifest-$VER.sig" "$DIR/bundle-$VER.zip" "$DIR/index.json")
if gh release view "$TAG" --repo "$R" >/dev/null 2>&1; then
  gh release upload "$TAG" "${FILES[@]}" --clobber --repo "$R"
  gh release edit "$TAG" --notes "$NOTES" --prerelease --latest=false --repo "$R"
else
  gh release create "$TAG" "${FILES[@]}" --repo "$R" --target "${GITHUB_SHA}" --title "OTA $CH channel" --notes "$NOTES" --prerelease --latest=false
fi
for v in $DROP; do
  for a in "manifest-$v.json" "manifest-$v.sig" "bundle-$v.zip"; do gh release delete-asset "$TAG" "$a" --yes --repo "$R" >/dev/null 2>&1 || true; done
done
echo "published $TAG v$VER ($NAME); dropped from history: ${DROP:-none}"
