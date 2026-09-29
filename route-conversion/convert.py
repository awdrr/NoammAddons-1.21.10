#!/usr/bin/env python3
"""Convert box-based routes (hel.json) and breaker lists (breaker_aura.json)
into the ring format used by healer.json / healerbreaker.json.

Usage:
    python3 convert.py <route.json> <breaker.json> <out_dir> [--id NAME] [--keep-yaw]

Route node mapping (source box -> ring):
    x/z = centre of the box, y = box bottom,
    radius = half the widest horizontal side, height = box height.

    WALK            -> walk  (yaw, pitch 0). TRIGGER / TERM args -> CLICK gate
    JUMP            -> jump
    EDGE            -> jump  (no edge-jump ring type is known in the target)
    STOP            -> stop
    ALIGN           -> align (+ look if it carries a LOOK sub)
    FAST_ALIGN      -> align (+ look if it carries a LOOK sub)
    LOOK            -> look  (yaw, pitch)
    BOOM, USE, CHAT -> dropped (no equivalent), listed in the report

Rings on the same box are ordered stop, align, look, walk, jump, and exact
duplicates (e.g. ALIGN + FAST_ALIGN on one box) are merged.

Walk yaws: the old client drifted through turns, so its walk yaws overshoot
each turn to compensate. The new client walks exactly where the yaw points, so
every walk ring is re-aimed straight at the next spot on the route: the
nearest walk/stop/align ring within 30 degrees of the old yaw (a dropped node
such as a BOOM box is used only when no ring is ahead). Walk rings with
nothing ahead keep their yaw. Pass --keep-yaw to skip this.
"""
import argparse
import json
import math
from pathlib import Path

ORDER = {"stop": 0, "align": 1, "look": 2, "walk": 3, "jump": 4}
GATE_ARGS = ("TRIGGER", "TERM")
DROPPED = ("BOOM", "USE", "CHAT")
AIM_TYPES = ("walk", "stop", "align")
AIM_CONE = 30.0
AIM_DISTANCE = (0.75, 40.0)
AIM_HEIGHT_CHANGE = (-50.0, 5.0)


def num(v):
    v = round(float(v), 6)
    return 0.0 if v == 0 else v


def box_key(node):
    mn, mx = node["min"], node["max"]
    return tuple(num(mn[k]) for k in "xyz") + tuple(num(mx[k]) for k in "xyz")


def base_ring(kind, node):
    mn, mx = node["min"], node["max"]
    return {
        "type": kind,
        "x": num((mn["x"] + mx["x"]) / 2),
        "y": num(mn["y"]),
        "z": num((mn["z"] + mx["z"]) / 2),
        "radius": num(max(mx["x"] - mn["x"], mx["z"] - mn["z"]) / 2),
        "height": num(mx["y"] - mn["y"]),
    }


def look_ring(node, yaw, pitch):
    ring = base_ring("look", node)
    return {"type": "look", "yaw": num(yaw), "pitch": num(pitch), **{k: v for k, v in ring.items() if k != "type"}}


def convert_node(node):
    kind = node["type"]
    args = node.get("args", {})

    if kind == "WALK":
        ring = base_ring("walk", node)
        ring = {"type": "walk", "yaw": num(node["yaw"]), "pitch": 0.0, **{k: v for k, v in ring.items() if k != "type"}}
        if any(args.get(a) for a in GATE_ARGS):
            ring["gates"] = [{"kind": "CLICK"}]
        return [ring]
    if kind in ("JUMP", "EDGE"):
        return [base_ring("jump", node)]
    if kind == "STOP":
        return [base_ring("stop", node)]
    if kind in ("ALIGN", "FAST_ALIGN"):
        rings = [base_ring("align", node)]
        look = node.get("sub", {}).get("LOOK")
        if look:
            rings.append(look_ring(node, look["yaw"], look["pitch"]))
        return rings
    if kind == "LOOK":
        return [look_ring(node, node["yaw"], node["pitch"])]
    return None


def describe(node):
    mn, mx = node["min"], node["max"]
    where = "({:g}, {:g}, {:g})".format(num((mn["x"] + mx["x"]) / 2), num(mn["y"]), num((mn["z"] + mx["z"]) / 2))
    extra = {k: v for k, v in node.items() if k not in ("type", "min", "max")}
    return f"{node['type']:<6} at {where} {json.dumps(extra)}"


def convert_route(nodes, route_id):
    groups = {}
    dropped, unknown = [], []
    for node in nodes:
        rings = convert_node(node)
        if rings is None:
            (dropped if node["type"] in DROPPED else unknown).append(node)
            continue
        group = groups.setdefault(box_key(node), [])
        for ring in rings:
            if ring not in group:
                group.append(ring)

    out = []
    for group in groups.values():
        out.extend(sorted(group, key=lambda r: ORDER[r["type"]]))
    return {"id": route_id, "rings": out}, dropped, unknown


def bearing(src, dst):
    yaw = math.degrees(math.atan2(src["x"] - dst["x"], dst["z"] - src["z"]))
    return 180.0 if yaw <= -180 else yaw


def angle_diff(a, b):
    return (a - b + 180) % 360 - 180


def find_target(walk, candidates):
    best = None
    for target in candidates:
        dist = math.hypot(target["x"] - walk["x"], target["z"] - walk["z"])
        if not AIM_DISTANCE[0] <= dist <= AIM_DISTANCE[1]:
            continue
        if not AIM_HEIGHT_CHANGE[0] <= target["y"] - walk["y"] <= AIM_HEIGHT_CHANGE[1]:
            continue
        if abs(angle_diff(bearing(walk, target), walk["yaw"])) > AIM_CONE:
            continue
        if best is None or dist < best[0]:
            best = (dist, target)
    return best[1] if best else None


def aim_walks(rings, dropped):
    targets = [r for r in rings if r["type"] in AIM_TYPES]
    fallback = [base_ring(node["type"], node) for node in dropped]
    changes = []
    for ring in rings:
        if ring["type"] != "walk":
            continue
        old = ring["yaw"]
        target = find_target(ring, targets) or find_target(ring, fallback)
        if target:
            ring["yaw"] = num(round(bearing(ring, target), 2))
        changes.append((ring, old, target))
    return changes


def where(ring):
    return "({:g}, {:g}, {:g})".format(ring["x"], ring["y"], ring["z"])


def convert_breaker(points):
    out, seen = [], set()
    for p in points:
        pos = tuple(int(round(float(p[k]))) for k in "xyz")
        if pos in seen:
            continue
        seen.add(pos)
        out.append({"x": pos[0], "y": pos[1], "z": pos[2], "type": "DUNGEON_BREAKER_AURA"})
    return out


def main():
    parser = argparse.ArgumentParser(description="Convert hel.json-style routes to the healer.json ring format.")
    parser.add_argument("route", type=Path)
    parser.add_argument("breaker", type=Path)
    parser.add_argument("out_dir", type=Path)
    parser.add_argument("--id", help="route id / output name (default: route file name)")
    parser.add_argument("--keep-yaw", action="store_true", help="keep the old walk yaws instead of re-aiming them")
    args = parser.parse_args()
    route_path, breaker_path, out_dir = args.route, args.breaker, args.out_dir
    route_id = args.id or route_path.stem

    route, dropped, unknown = convert_route(json.loads(route_path.read_text()), route_id)
    breaker = convert_breaker(json.loads(breaker_path.read_text()))
    aimed = [] if args.keep_yaw else aim_walks(route["rings"], dropped)

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"{route_id}.json").write_text(json.dumps(route, indent=2) + "\n")
    (out_dir / f"{route_id}breaker.json").write_text(json.dumps(breaker, indent=2) + "\n")

    print(f"{route_id}.json: {len(route['rings'])} rings")
    print(f"{route_id}breaker.json: {len(breaker)} blocks")
    if aimed:
        print("\nWalk yaws re-aimed (old -> new, towards):")
        for ring, old, target in aimed:
            if target:
                print(f"  {where(ring):<22} {old:8.2f} -> {ring['yaw']:8.2f} ({angle_diff(ring['yaw'], old):+6.2f})"
                      f"  {target['type']} {where(target)}")
        for ring, old, target in aimed:
            if not target:
                print(f"  {where(ring):<22} {old:8.2f}    unchanged, nothing ahead")
    for title, nodes in (("Dropped (no ring equivalent)", dropped), ("Unknown node types (skipped)", unknown)):
        if nodes:
            print(f"\n{title}:")
            for node in nodes:
                print("  " + describe(node))


if __name__ == "__main__":
    main()
