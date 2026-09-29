#!/usr/bin/env python3
"""Convert box-based routes (hel.json) and breaker lists (breaker_aura.json)
into the ring format used by healer.json / healerbreaker.json.

Usage:
    python3 convert.py <route.json> <breaker.json> <out_dir> [--id NAME]

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
"""
import argparse
import json
from pathlib import Path

ORDER = {"stop": 0, "align": 1, "look": 2, "walk": 3, "jump": 4}
GATE_ARGS = ("TRIGGER", "TERM")
DROPPED = ("BOOM", "USE", "CHAT")


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
    args = parser.parse_args()
    route_path, breaker_path, out_dir = args.route, args.breaker, args.out_dir
    route_id = args.id or route_path.stem

    route, dropped, unknown = convert_route(json.loads(route_path.read_text()), route_id)
    breaker = convert_breaker(json.loads(breaker_path.read_text()))

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"{route_id}.json").write_text(json.dumps(route, indent=2) + "\n")
    (out_dir / f"{route_id}breaker.json").write_text(json.dumps(breaker, indent=2) + "\n")

    print(f"{route_id}.json: {len(route['rings'])} rings")
    print(f"{route_id}breaker.json: {len(breaker)} blocks")
    for title, nodes in (("Dropped (no ring equivalent)", dropped), ("Unknown node types (skipped)", unknown)):
        if nodes:
            print(f"\n{title}:")
            for node in nodes:
                print("  " + describe(node))


if __name__ == "__main__":
    main()
