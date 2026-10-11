"""Minimal SVG path renderer for Material Symbols (M/L/H/V/Q/T/C/S/Z, absolute and relative).

Material Symbols paths use no arcs, so this is all the demo illustrations need. Draws into a
pycairo context; the caller sets the transform and the source colour.
"""
import re

_TOKEN = re.compile(r"[MmLlHhVvQqTtCcSsZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def path_data(svg_text):
    """All `d` attributes of the <path> elements in an SVG document."""
    return re.findall(r'<path[^>]*\sd="([^"]+)"', svg_text)


def view_box(svg_text):
    x, y, w, h = (float(v) for v in re.search(r'viewBox="([^"]+)"', svg_text).group(1).split())
    return x, y, w, h


def draw_path(ctx, d):
    tokens = _TOKEN.findall(d)
    i = 0
    cmd = None
    cx = cy = 0.0
    sx = sy = 0.0
    last_ctrl = None  # (x, y, kind) of the last control point, for T/S reflection
    qstart = None

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    def quad(x0, y0, qx, qy, x, y):
        # Quadratic -> cubic.
        c1x = x0 + 2.0 / 3.0 * (qx - x0)
        c1y = y0 + 2.0 / 3.0 * (qy - y0)
        c2x = x + 2.0 / 3.0 * (qx - x)
        c2y = y + 2.0 / 3.0 * (qy - y)
        ctx.curve_to(c1x, c1y, c2x, c2y, x, y)

    while i < len(tokens):
        t = tokens[i]
        if t.isalpha():
            cmd = t
            i += 1
            if cmd in "Zz":
                ctx.close_path()
                cx, cy = sx, sy
                last_ctrl = None
                continue
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = (cx, cy) if rel else (0.0, 0.0)
        if c == "M":
            cx, cy = ox + num(), oy + num()
            ctx.move_to(cx, cy)
            sx, sy = cx, cy
            cmd = "l" if rel else "L"
            last_ctrl = None
        elif c == "L":
            cx, cy = ox + num(), oy + num()
            ctx.line_to(cx, cy)
            last_ctrl = None
        elif c == "H":
            cx = (cx if rel else 0.0) + num()
            ctx.line_to(cx, cy)
            last_ctrl = None
        elif c == "V":
            cy = (cy if rel else 0.0) + num()
            ctx.line_to(cx, cy)
            last_ctrl = None
        elif c == "Q":
            qx, qy = ox + num(), oy + num()
            x, y = ox + num(), oy + num()
            quad(cx, cy, qx, qy, x, y)
            last_ctrl = (qx, qy, "Q")
            cx, cy = x, y
        elif c == "T":
            if last_ctrl and last_ctrl[2] == "Q":
                qx, qy = 2 * cx - last_ctrl[0], 2 * cy - last_ctrl[1]
            else:
                qx, qy = cx, cy
            x, y = ox + num(), oy + num()
            quad(cx, cy, qx, qy, x, y)
            last_ctrl = (qx, qy, "Q")
            cx, cy = x, y
        elif c == "C":
            x1, y1 = ox + num(), oy + num()
            x2, y2 = ox + num(), oy + num()
            x, y = ox + num(), oy + num()
            ctx.curve_to(x1, y1, x2, y2, x, y)
            last_ctrl = (x2, y2, "C")
            cx, cy = x, y
        elif c == "S":
            if last_ctrl and last_ctrl[2] == "C":
                x1, y1 = 2 * cx - last_ctrl[0], 2 * cy - last_ctrl[1]
            else:
                x1, y1 = cx, cy
            x2, y2 = ox + num(), oy + num()
            x, y = ox + num(), oy + num()
            ctx.curve_to(x1, y1, x2, y2, x, y)
            last_ctrl = (x2, y2, "C")
            cx, cy = x, y
        else:
            raise ValueError("unsupported path command " + cmd)
