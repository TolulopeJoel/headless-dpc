#!/usr/bin/env python3
"""Turn OpenStreetMap roads into app/src/main/assets/roads.bin for MapView.

Usage: roads.py OUT.bin INPUT [INPUT ...]
  INPUT is an Overpass download (.json, `out geom tags`) or an OSM XML extract (.osm or .osm.xz, e.g. from
  extract.bbbike.org for the square round home).

Format (big-endian, read by MapView.Roads.load): magic "ROAD", name count, names (Java modified UTF-8), way count,
then per way: name index (short, -1 for none), class (byte: 0 main, 1 secondary, 2 street, 3 service/track),
point count (short), first point (int lat, int lon, in 1e-5 degrees), then each next point as short steps.
Ways in more than one tile are kept once. Road data © OpenStreetMap contributors (ODbL).
"""
import json
import struct
import sys

CLASS = {
    "motorway": 0, "trunk": 0, "primary": 0, "motorway_link": 0, "trunk_link": 0, "primary_link": 0,
    "secondary": 1, "tertiary": 1, "secondary_link": 1, "tertiary_link": 1,
    "unclassified": 2, "residential": 2, "living_street": 2, "pedestrian": 2,
    "service": 3, "track": 3,
}


def from_osm_xml(path):
    """Ways with their geometry, in the Overpass shape, from an OSM XML file (plain or .xz)."""
    import lzma
    import xml.etree.ElementTree as ET
    f = lzma.open(path) if path.endswith(".xz") else open(path, "rb")
    nodes, ways = {}, []
    for _, el in ET.iterparse(f, events=("end",)):
        if el.tag == "node":
            nodes[el.get("id")] = (float(el.get("lat")), float(el.get("lon")))
            el.clear()
        elif el.tag == "way":
            tags = {t.get("k"): t.get("v") for t in el.findall("tag")}
            if "highway" in tags:
                geom = [{"lat": nodes[n.get("ref")][0], "lon": nodes[n.get("ref")][1]} for n in el.findall("nd") if n.get("ref") in nodes]
                ways.append({"type": "way", "id": int(el.get("id")), "tags": tags, "geometry": geom})
            el.clear()
    return ways


def main(out, tiles):
    ways = {}
    for t in tiles:
        elements = from_osm_xml(t) if t.endswith((".osm", ".osm.xz")) else json.load(open(t))["elements"]
        for e in elements:
            if e.get("type") == "way" and e.get("geometry") and e["id"] not in ways:
                ways[e["id"]] = e
    names, index, body, count = [], {}, bytearray(), 0
    for e in ways.values():
        cls = CLASS.get(e["tags"].get("highway"))
        if cls is None:
            continue
        pts = [(round(p["lat"] * 1e5), round(p["lon"] * 1e5)) for p in e["geometry"]]
        nm = e["tags"].get("name")
        ni = -1
        if nm:
            if nm not in index:
                index[nm] = len(names)
                names.append(nm)
            ni = index[nm]
        if len(pts) < 2:
            continue
        body += struct.pack(">hbhii", ni, cls, len(pts), pts[0][0], pts[0][1])
        for a, b in zip(pts, pts[1:]):
            body += struct.pack(">hh", b[0] - a[0], b[1] - a[1])
        count += 1
    head = bytearray(struct.pack(">ii", 0x524F4144, len(names)))
    for n in names:
        b = n.encode("utf-8")
        head += struct.pack(">H", len(b)) + b
    head += struct.pack(">i", count)
    open(out, "wb").write(bytes(head + body))
    print(f"{count} ways, {len(names)} names, {len(head) + len(body)} bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
