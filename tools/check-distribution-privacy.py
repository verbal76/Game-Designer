#!/usr/bin/env python3
"""Fails if a distributed Android artifact (an APK, or an OTA bundle APK / bundle.zip) contains personal project data or owner-derived fixtures.

Usage: check-distribution-privacy.py <artifact> [<artifact> ...]      |      check-distribution-privacy.py --selftest
Checks every entry: forbidden file names (saved projects, backups, spec packages, nested archives), project-shaped JSON/text anywhere, and a
deny-list of strings that only occur in owner-derived material (names of real owner projects and export files). Prints an inventory so the
contents can be reviewed. Exit status 1 on any finding.
"""
import sys, zipfile, re, io, collections, tempfile, os

FORBIDDEN_NAME = re.compile(r"(^|/)(project\.json[^/]*|[^/]*_restore_backup[^/]*|game-designer-backup[^/]*|[^/]*_spec_v[^/]*\.zip|recovery[^/]*)$|\.(ab|aab|bak|corrupt)$", re.I)
# Reported for review but not fatal: third-party libraries may legitimately embed archives.
REVIEW_NAME = re.compile(r"\.(zip|apk)$|(^|/)projects/", re.I)
# Strings that exist only in owner-derived material. Names of public games are not listed: they may legitimately appear in generic knowledge.
MARKERS = [b"Climb_up_spec", b"Either Character", b"Surface Think", b"EitherCharacter", b"ClimbUpRegression", b"SurfaceThinkRegression", b"CLIMB_UP_GROUND_TRUTH"]
PROJECT_SHAPE = (b'"originalConcept"', b'"decisions"', b'"schemaVersion"')

def audit(path):
    findings, inventory, total = [], collections.Counter(), 0
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.is_dir(): continue
            name = info.filename; total += 1
            inventory[name.split("/")[0] if "/" in name else "(root)"] += 1
            if FORBIDDEN_NAME.search(name): findings.append(f"forbidden entry name: {name}")
            elif REVIEW_NAME.search(name): print(f"  REVIEW (not fatal): {name}")
            data = z.read(name)
            if all(m in data for m in PROJECT_SHAPE): findings.append(f"project-shaped content in {name}")
            for m in MARKERS:
                if m in data: findings.append(f"owner-derived marker {m.decode()!r} in {name}")
    return findings, inventory, total

def selftest():
    tmp = tempfile.mkdtemp()
    def make(entries):
        p = os.path.join(tmp, "t%d.zip" % len(os.listdir(tmp)))
        with zipfile.ZipFile(p, "w") as z:
            for n, d in entries.items(): z.writestr(n, d)
        return p
    ok = make({"classes.dex": b"dex 035 hello", "assets/ota/trusted_keys.txt": b"# keys"})
    assert not audit(ok)[0], "clean artifact flagged"
    for bad in ({"assets/x/project.json": b"{}"}, {"assets/x.ab": b"1"}, {"assets/game-designer-backup-1.zip": b"PK"}, {"assets/a.json": b'{"originalConcept":1,"decisions":{},"schemaVersion":1}'}, {"classes.dex": b"..Climb_up_spec.."}, {"assets/projects/a/project.json.bak": b"1"}):
        assert audit(make(bad))[0], f"not detected: {list(bad)}"
    print("selftest ok")

if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]: selftest(); sys.exit(0)
    bad = False
    for a in sys.argv[1:]:
        f, inv, n = audit(a)
        print(f"{a}: {n} entries; top-level: " + ", ".join(f"{k}={v}" for k, v in sorted(inv.items())))
        for x in f: print("  FINDING:", x)
        bad |= bool(f)
    sys.exit(1 if bad else 0)
