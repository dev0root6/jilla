#!/usr/bin/env python3
"""TCP mesh simulator: a hub that emulates radio range with an adjacency graph.

Each node (emulator app with TcpMeshTransport, or the JVM sim node) connects,
says HELLO <name>, and from then on its broadcasts are delivered ONLY to the
nodes adjacent to it in the graph. That makes multi-hop, TTL and dedup
behaviour deterministic and testable without Bluetooth.

Graph file (JSON), re-read whenever it changes on disk:
    {"edges": [["A", "B"], ["B", "GW"]]}        undirected links
    {"full": true}                              everyone hears everyone

When an edge appears between two connected nodes (or a node connects next to
an existing neighbour) both get NEIGHBOUR_UP, which triggers store-carry-forward.

Frame: u32 BE length | type(1) | body.  0x00 HELLO, 0x01 PACKET, 0x02 NEIGHBOUR_UP
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import struct
from pathlib import Path

HELLO, PACKET, NEIGHBOUR_UP = 0, 1, 2
MAX_FRAME = 64 * 1024

log = logging.getLogger("mesh-sim")


class Hub:
    def __init__(self, graph_path: Path | None):
        self.graph_path = graph_path
        self.graph_mtime = 0.0
        self.full = graph_path is None
        self.edges: set[frozenset[str]] = set()
        self.nodes: dict[str, asyncio.StreamWriter] = {}

    def adjacent(self, a: str, b: str) -> bool:
        return a != b and (self.full or frozenset((a, b)) in self.edges)

    def neighbours(self, a: str) -> list[str]:
        return [b for b in self.nodes if self.adjacent(a, b)]

    def load_graph(self) -> None:
        if self.graph_path is None:
            return
        try:
            mtime = self.graph_path.stat().st_mtime
        except FileNotFoundError:
            return
        if mtime == self.graph_mtime:
            return
        self.graph_mtime = mtime
        try:
            g = json.loads(self.graph_path.read_text())
        except json.JSONDecodeError as e:
            log.error("graph parse error: %s", e)
            return
        old_full, old_edges = self.full, self.edges
        self.full = bool(g.get("full", False))
        self.edges = {frozenset(e) for e in g.get("edges", [])}
        log.info("graph loaded: %s", "FULL" if self.full else sorted(tuple(sorted(e)) for e in self.edges))
        for a in list(self.nodes):
            for b in list(self.nodes):
                if a < b and self.adjacent(a, b):
                    was = old_full or frozenset((a, b)) in old_edges
                    if not was:
                        self.link_up(a, b)

    def link_up(self, a: str, b: str) -> None:
        log.info("LINK UP %s <-> %s", a, b)
        self.send(a, NEIGHBOUR_UP, b.encode())
        self.send(b, NEIGHBOUR_UP, a.encode())

    def send(self, to: str, ftype: int, body: bytes) -> None:
        w = self.nodes.get(to)
        if w is None:
            return
        w.write(struct.pack(">IB", len(body) + 1, ftype) + body)

    async def watch_graph(self) -> None:
        while True:
            self.load_graph()
            await asyncio.sleep(0.5)

    async def handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        name = None
        try:
            ftype, body = await read_frame(reader)
            if ftype != HELLO:
                return
            name = body.decode()
            old = self.nodes.pop(name, None)
            if old:
                old.close()
            self.nodes[name] = writer
            log.info("JOIN %s (neighbours: %s)", name, self.neighbours(name))
            for n in self.neighbours(name):
                self.link_up(name, n)
            while True:
                ftype, body = await read_frame(reader)
                if ftype != PACKET:
                    continue
                targets = self.neighbours(name)
                log.info("PACKET %s -> %s (%d bytes)", name, targets or "nobody in range", len(body))
                for t in targets:
                    self.send(t, PACKET, body)
                await writer.drain()
        except (asyncio.IncompleteReadError, ConnectionError, ValueError):
            pass
        finally:
            if name and self.nodes.get(name) is writer:
                del self.nodes[name]
                log.info("LEAVE %s", name)
            writer.close()


async def read_frame(r: asyncio.StreamReader) -> tuple[int, bytes]:
    (n,) = struct.unpack(">I", await r.readexactly(4))
    if not 1 <= n <= MAX_FRAME:
        raise ValueError(f"bad frame length {n}")
    data = await r.readexactly(n)
    return data[0], data[1:]


async def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=7777)
    ap.add_argument("--graph", type=Path, help="adjacency JSON; omit for a fully connected mesh")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s mesh-sim %(message)s")
    hub = Hub(args.graph)
    hub.load_graph()
    server = await asyncio.start_server(hub.handle, "0.0.0.0", args.port)
    log.info("listening on :%d (%s)", args.port, args.graph or "full mesh")
    async with server:
        await asyncio.gather(server.serve_forever(), hub.watch_graph())


if __name__ == "__main__":
    asyncio.run(main())
