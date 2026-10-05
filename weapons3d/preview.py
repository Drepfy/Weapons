"""Write each weapon's texture PNG and a scaled-up preview sheet."""
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from designs import PALETTE, WEAPONS  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "textures")


def hex_rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def texture(canvas):
    img = Image.new("RGBA", (canvas.w, canvas.h), (0, 0, 0, 0))
    for (x, y), (k, _) in canvas.px.items():
        img.putpixel((x, y), hex_rgb(PALETTE[k][0]) + (255,))
    return img


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    scale, pad = 8, 24
    imgs = []
    for name, fn in WEAPONS:
        img = texture(fn())
        img.save(os.path.join(OUT, name + ".png"))
        imgs.append(img.resize((img.width * scale, img.height * scale), Image.NEAREST))
    W = sum(i.width for i in imgs) + pad * (len(imgs) + 1)
    H = max(i.height for i in imgs) + pad * 2
    sheet = Image.new("RGBA", (W, H), (38, 38, 44, 255))
    x = pad
    for i in imgs:
        sheet.alpha_composite(i, (x, pad))
        x += i.width + pad
    sheet.save(sys.argv[1] if len(sys.argv) > 1 else os.path.join(OUT, "_sheet.png"))
