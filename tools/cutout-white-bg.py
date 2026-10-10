import sys
from PIL import Image, ImageDraw
import numpy as np

src = sys.argv[1]
dst = sys.argv[2]
thresh = int(sys.argv[3]) if len(sys.argv) > 3 else 30

im = Image.open(src).convert("RGBA")
W, H = im.size

# Flood fill from the four corners: only near-white regions CONNECTED to the
# border get cleared, so white highlights inside the robot are preserved.
corners = [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]
for c in corners:
    ImageDraw.floodfill(im, c, (255, 255, 255, 0), thresh=thresh)

a = np.array(im)
alpha = a[:, :, 3]

# Kill any residual near-white pixel that is fully surrounded by transparency
# (leftover specks inside the cleared background).
rgb = a[:, :, :3].astype(np.int32)
near_white = rgb.min(axis=2) > 240
alpha[near_white & (alpha > 0)] = 0
a[:, :, 3] = alpha

out = Image.fromarray(a)

# Trim to content bbox (with a small margin) for precise CSS placement.
ys, xs = np.where(alpha > 8)
if len(xs):
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    m = 6
    out = out.crop((max(0, x0 - m), max(0, y0 - m), min(W, x1 + m), min(H, y1 + m)))
    print("bbox=%d,%d,%d,%d size=%dx%d" % (x0, y0, x1, y1, out.size[0], out.size[1]))

out.save(dst)
print("saved", dst, out.size)
