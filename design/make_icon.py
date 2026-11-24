# -*- coding: utf-8 -*-
"""生成修改器图标：深海军蓝圆角底 + 金色剑。

设计要点：
- 深色底在任何 Windows 主题下都不刺眼，和程序界面同色系（#061430）
- 剑形是纯几何（尖三角 + 横护手），缩到 16x16 仍能看出是一把剑
- 金色高对比，任务栏小尺寸也能认出来
"""
from PIL import Image, ImageDraw, ImageFilter

S = 1024                      # 超采样画布，最后缩到 256
NAVY_DARK = (5, 16, 31)       # #05101F
NAVY_LIGHT = (14, 44, 86)     # #0E2C56
GOLD_HI = (246, 212, 136)     # #F6D488
GOLD_LO = (196, 136, 43)      # #C4882B
ACCENT = (58, 142, 255)       # #3A8EFF


def rounded_mask(size, radius):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1],
                                           radius=radius, fill=255)
    return mask


# ---------- 背景：中心微亮的径向渐变 ----------
glow = Image.radial_gradient("L").resize((S, S), Image.LANCZOS)
glow = glow.point(lambda v: int(v * 0.75))
background = Image.composite(Image.new("RGB", (S, S), NAVY_LIGHT),
                             Image.new("RGB", (S, S), NAVY_DARK), glow)

icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
icon.paste(background, (0, 0), rounded_mask(S, int(S * 0.22)))

draw = ImageDraw.Draw(icon)
draw.rounded_rectangle([3, 3, S - 4, S - 4], radius=int(S * 0.22),
                       outline=ACCENT + (110,), width=5)

# ---------- 剑形几何 ----------
cx = S / 2
blade = [
    (cx, 0.115 * S),                 # 剑尖
    (cx + 0.062 * S, 0.215 * S),     # 右刃
    (cx + 0.062 * S, 0.585 * S),     # 右底
    (cx - 0.062 * S, 0.585 * S),     # 左底
    (cx - 0.062 * S, 0.215 * S),     # 左刃
]
guard = [0.325 * S, 0.585 * S, 0.675 * S, 0.648 * S]
grip = [cx - 0.034 * S, 0.645 * S, cx + 0.034 * S, 0.805 * S]
pommel = [cx, 0.845 * S, 0.048 * S]

# 剑的选区（用于上色与光晕）
shape = Image.new("L", (S, S), 0)
sd = ImageDraw.Draw(shape)
sd.polygon(blade, fill=255)
sd.rounded_rectangle(guard, radius=int(S * 0.016), fill=255)
sd.rounded_rectangle(grip, radius=int(S * 0.012), fill=255)
sd.ellipse([pommel[0] - pommel[2], pommel[1] - pommel[2],
            pommel[0] + pommel[2], pommel[1] + pommel[2]], fill=255)

# 金色线性渐变（上亮下暗，模拟金属受光）
gold = Image.new("RGB", (S, S))
gd = ImageDraw.Draw(gold)
for y in range(S):
    t = min(1.0, y / (0.9 * S))
    gd.line([(0, y), (S, y)],
            fill=tuple(int(GOLD_HI[i] + (GOLD_LO[i] - GOLD_HI[i]) * t) for i in range(3)))

# 外发光：剑形模糊后垫在下面
halo = shape.filter(ImageFilter.GaussianBlur(S * 0.018))
icon.paste(Image.new("RGB", (S, S), (255, 198, 90)), (0, 0),
           halo.point(lambda v: int(v * 0.45)))
icon.paste(gold, (0, 0), shape)

# 刃面高光：剑身左侧一条竖向亮线
highlight = Image.new("L", (S, S), 0)
ImageDraw.Draw(highlight).polygon(
    [(cx - 0.030 * S, 0.235 * S), (cx - 0.012 * S, 0.235 * S),
     (cx - 0.012 * S, 0.560 * S), (cx - 0.030 * S, 0.560 * S)], fill=255)
highlight = highlight.filter(ImageFilter.GaussianBlur(S * 0.004))
icon.paste(Image.new("RGB", (S, S), (255, 248, 226)), (0, 0),
           highlight.point(lambda v: int(v * 0.55)))

# ---------- 导出 ----------
final = icon.resize((256, 256), Image.LANCZOS)
final.save("icon.png")
final.save("icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                              (64, 64), (128, 128), (256, 256)])
print("已生成 icon.png / icon.ico")
