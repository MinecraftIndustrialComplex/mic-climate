#!/usr/bin/env python3
"""Write data/mic_climate/structure/empty_5x5x5.nbt by hand.

A GameTest structure template is a plain gzipped NBT compound; there is no need
for a Minecraft runtime to produce one. This makes the smallest thing the
gametest framework will accept: a 5x5x5 box of air sitting on a 5x5 floor, so
tests have somewhere to stand blocks and something to measure the air above.
"""
import gzip
import struct
import sys

DATA_VERSION = 3955  # 1.21.1

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def s(text: str) -> bytes:
    b = text.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def named(tag_id: int, name: str, payload: bytes) -> bytes:
    return struct.pack(">B", tag_id) + s(name) + payload


def int_list(values) -> bytes:
    return struct.pack(">Bi", TAG_INT, len(values)) + b"".join(
        struct.pack(">i", v) for v in values
    )


def compound_list(payloads) -> bytes:
    return struct.pack(">Bi", TAG_COMPOUND, len(payloads)) + b"".join(payloads)


def empty_list() -> bytes:
    return struct.pack(">Bi", TAG_END, 0)


FLOOR = "minecraft:polished_andesite"
AIR = "minecraft:air"

SIZE_X, SIZE_Y, SIZE_Z = 5, 6, 5

palette = compound_list(
    [
        named(TAG_STRING, "Name", s(FLOOR)) + struct.pack(">B", TAG_END),
        named(TAG_STRING, "Name", s(AIR)) + struct.pack(">B", TAG_END),
    ]
)

blocks = []
for x in range(SIZE_X):
    for y in range(SIZE_Y):
        for z in range(SIZE_Z):
            state = 0 if y == 0 else 1
            blocks.append(
                named(TAG_LIST, "pos", int_list([x, y, z]))
                + named(TAG_INT, "state", struct.pack(">i", state))
                + struct.pack(">B", TAG_END)
            )

root = (
    named(TAG_INT, "DataVersion", struct.pack(">i", DATA_VERSION))
    + named(TAG_LIST, "size", int_list([SIZE_X, SIZE_Y, SIZE_Z]))
    + named(TAG_LIST, "palette", palette)
    + named(TAG_LIST, "blocks", compound_list(blocks))
    + named(TAG_LIST, "entities", empty_list())
    + struct.pack(">B", TAG_END)
)

document = struct.pack(">B", TAG_COMPOUND) + s("") + root

out = sys.argv[1]
with gzip.open(out, "wb") as f:
    f.write(document)
print(f"wrote {out}: {len(blocks)} blocks, size {SIZE_X}x{SIZE_Y}x{SIZE_Z}")
