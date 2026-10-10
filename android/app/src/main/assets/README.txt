本目录用于放置 M1 实验 A 的播放素材。

把带 alpha 通道的 VP9 视频命名为 idle_vp9_alpha.webm 放到这里即可。
文件不存在时，PetView 会自动回退为 Canvas 占位动画，不影响链路验证。

生成命令（来自 docs/M1验证方案.md §2.3）：

  # 1) VP9 with Alpha（关键：yuva420p + alpha_mode=1）
  ffmpeg -framerate 12 -i frame_%04d.png \
    -c:v libvpx-vp9 -pix_fmt yuva420p \
    -auto-alt-ref 0 -b:v 0 -crf 30 \
    -metadata:s:v:0 alpha_mode=1 \
    idle_vp9_alpha.webm

两个坑：
- -auto-alt-ref 0 必须加，否则 alt-ref 帧会让 alpha 通道出错
- -pix_fmt 只能写 yuva420p，写 rgba 无效（Android 只认 yuva420p）
