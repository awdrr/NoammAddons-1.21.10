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

Walk rings: the old client kept drifting forward after a walk ring before it
turned, so the real turn happened 1-3 blocks past the ring. The new client
turns at the ring centre, so turning walk rings are moved forward to where the
old client really turned and keep their old yaw. The turn point is where the
incoming path meets the line through the next spot along the old yaw. The next
spot is the nearest walk/stop/align ring within 30 degrees of the old yaw (a
dropped node such as a BOOM box is used only when no ring is ahead).

Walk rings you start from standing still (a stop/align on the same spot),
turns under 15 degrees, and turns that don't fit the drift pattern stay in
place and aim straight at the next spot. Walk rings with nothing ahead are
left alone. Jump rings within a block of the new path are moved onto it, so
you pass through their centre. Pass --keep-yaw to keep every walk and jump
ring as it was.
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
TURN_MIN = 15.0
DRIFT_RANGE = (-0.5, 4.0)


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


def direction(yaw):
    a = math.radians(yaw)
    return -math.sin(a), math.cos(a)


def spot(ring):
    return ring["x"], ring["y"], ring["z"]


def old_turn_point(ring, yaw_in, target):
    """Where the incoming line (through the ring centre along yaw_in) meets the
    line through the target along the ring's old yaw, as blocks past the ring."""
    din, dout = direction(yaw_in), direction(ring["yaw"])
    det = din[0] * dout[1] - din[1] * dout[0]
    if abs(det) < 1e-9:
        return None
    dx, dz = target["x"] - ring["x"], target["z"] - ring["z"]
    past = (dx * dout[1] - dz * dout[0]) / det
    left = (din[0] * dz - din[1] * dx) / det
    if not DRIFT_RANGE[0] <= past <= DRIFT_RANGE[1] or left < 0.5:
        return None
    return past


def aim_walks(rings, dropped):
    targets = [dict(r) for r in rings if r["type"] in AIM_TYPES]
    fallback = [base_ring(node["type"], node) for node in dropped]
    walks = [r for r in rings if r["type"] == "walk"]
    original = {id(w): dict(w) for w in walks}
    target = {id(w): find_target(w, targets) or find_target(w, fallback) for w in walks}
    standing = {spot(r) for r in rings if r["type"] in ("stop", "align")}
    feeders = {}
    for w in walks:
        if target[id(w)]:
            feeders.setdefault(spot(target[id(w)]), []).append(w)

    new_yaw, changes = {}, []

    def resolve(ring, visiting):
        if id(ring) in new_yaw:
            return new_yaw[id(ring)]
        old, dest = original[id(ring)], target[id(ring)]
        if not dest:
            new_yaw[id(ring)] = old["yaw"]
            changes.append((old, ring, None, None))
            return old["yaw"]

        feeder = feeders.get(spot(old), [])
        past = None
        if spot(old) not in standing and len(feeder) == 1 and id(feeder[0]) not in visiting:
            yaw_in = resolve(feeder[0], visiting | {id(ring)})
            if abs(angle_diff(old["yaw"], yaw_in)) >= TURN_MIN:
                past = old_turn_point(old, yaw_in, dest)

        if past is not None and abs(past) > 0.1:
            din = direction(yaw_in)
            ring["x"] = num(round(old["x"] + past * din[0], 3))
            ring["z"] = num(round(old["z"] + past * din[1], 3))
            if dest["y"] < old["y"]:
                ring["y"], ring["height"] = num(old["y"] - 1), num(old["height"] + 1)
            elif dest["y"] > old["y"]:
                ring["height"] = num(old["height"] + 1)
        ring["yaw"] = num(round(bearing(ring, dest), 2))
        new_yaw[id(ring)] = ring["yaw"]
        changes.append((old, ring, dest, past if past is not None and abs(past) > 0.1 else None))
        return ring["yaw"]

    for w in walks:
        resolve(w, frozenset())

    by_spot = {}
    for w in walks:
        by_spot.setdefault(spot(original[id(w)]), []).append(w)
    path = []
    for w in walks:
        dest = target[id(w)]
        if not dest:
            continue
        d = direction(w["yaw"])
        length = max((e["x"] - w["x"]) * d[0] + (e["z"] - w["z"]) * d[1] for e in [dest] + by_spot.get(spot(dest), []))
        path.append((w, d, length, min(w["y"], dest["y"]) - 1, max(w["y"], dest["y"]) + 1))

    snapped = []
    for ring in rings:
        if ring["type"] != "jump":
            continue
        best = None
        for w, d, length, low, high in path:
            if not low <= ring["y"] <= high:
                continue
            along = min(max((ring["x"] - w["x"]) * d[0] + (ring["z"] - w["z"]) * d[1], 0), length)
            px, pz = w["x"] + along * d[0], w["z"] + along * d[1]
            off = math.hypot(ring["x"] - px, ring["z"] - pz)
            if best is None or off < best[0]:
                best = (off, px, pz)
        if best and 0.05 < best[0] <= 1.0:
            old = dict(ring)
            ring["x"], ring["z"] = num(round(best[1], 3)), num(round(best[2], 3))
            snapped.append((old, ring, best[0]))
    return changes, snapped


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
    parser.add_argument("--keep-yaw", action="store_true", help="keep the old walk rings instead of moving/re-aiming them")
    args = parser.parse_args()
    route_path, breaker_path, out_dir = args.route, args.breaker, args.out_dir
    route_id = args.id or route_path.stem

    route, dropped, unknown = convert_route(json.loads(route_path.read_text()), route_id)
    breaker = convert_breaker(json.loads(breaker_path.read_text()))
    aimed, snapped = ([], []) if args.keep_yaw else aim_walks(route["rings"], dropped)

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"{route_id}.json").write_text(json.dumps(route, indent=2) + "\n")
    (out_dir / f"{route_id}breaker.json").write_text(json.dumps(breaker, indent=2) + "\n")

    print(f"{route_id}.json: {len(route['rings'])} rings")
    print(f"{route_id}breaker.json: {len(breaker)} blocks")
    if aimed:
        print("\nWalk rings (old yaw -> new yaw):")
        for old, ring, dest, past in aimed:
            if not dest:
                print(f"  {where(old):<22} {old['yaw']:8.2f}    unchanged, nothing ahead")
            elif past is not None:
                print(f"  {where(old):<22} {old['yaw']:8.2f} -> {ring['yaw']:8.2f}  moved {past:.2f} blocks on to "
                      f"{where(ring)}, towards {dest['type']} {where(dest)}")
            else:
                print(f"  {where(old):<22} {old['yaw']:8.2f} -> {ring['yaw']:8.2f}  aimed at {dest['type']} {where(dest)}")
    if snapped:
        print("\nJump rings moved onto the path:")
        for old, ring, off in snapped:
            print(f"  {where(old):<22} -> {where(ring)}  ({off:.2f} blocks)")
    for title, nodes in (("Dropped (no ring equivalent)", dropped), ("Unknown node types (skipped)", unknown)):
        if nodes:
            print(f"\n{title}:")
            for node in nodes:
                print("  " + describe(node))


if __name__ == "__main__":
    main()
