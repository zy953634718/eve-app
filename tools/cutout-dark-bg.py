"""
针对「深色背景 + 亮白主体」的抠像。
判据：min(R,G,B) —— 白色主体高，深蓝背景低（R 通道是短板）。
流程：阈值 -> 最大连通域 -> 填洞 -> 高斯软边 -> bbox 裁剪。
"""
import sys
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

src = sys.argv[1]
dst = sys.argv[2]
thr = int(sys.argv[3]) if len(sys.argv) > 3 else 90
feather = float(sys.argv[4]) if len(sys.argv) > 4 else 1.2
pad = int(sys.argv[5]) if len(sys.argv) > 5 else 8

im = Image.open(src).convert("RGB")
W, H = im.size
a = np.array(im).astype(np.int32)
score = a.min(axis=2)  # 最暗通道

mask = score > thr
# 最大连通域
lab, n = ndimage.label(mask)
if n == 0:
    raise SystemExit("no component found")
sizes = ndimage.sum(mask, lab, range(1, n + 1))
big = (lab == (int(np.argmax(sizes)) + 1))
big = ndimage.binary_fill_holes(big)
big = ndimage.binary_opening(big, structure=np.ones((3, 3)))
big = ndimage.binary_fill_holes(big)

# 软边
alpha = Image.fromarray((big * 255).astype(np.uint8))
if feather > 0:
    alpha = alpha.filter(ImageFilter.GaussianBlur(feather))

out = im.convert("RGBA")
out.putalpha(alpha)

arr = np.array(alpha)
ys, xs = np.where(arr > 12)
if len(xs):
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    out = out.crop((max(0, x0 - pad), max(0, y0 - pad), min(W, x1 + pad), min(H, y1 + pad)))

out.save(dst)
cov = np.array(out)[:, :, 3]
print(f"OK {src} -> {dst}  size={out.size}  opaque={np.mean(cov > 200):.1%}")
