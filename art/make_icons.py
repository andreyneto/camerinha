"""Regenerates launcher icons and the TV banner from art/icon.png (transparent PNG).

usage: python3 art/make_icons.py     (needs Pillow; banner font: Product Sans)
"""
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
BACKGROUND = (0xFA, 0xFA, 0xFA, 255)
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
FONT = Path.home() / "Library/Fonts/product_sans_regular.ttf"

source = Image.open(ROOT / "art/icon.png").convert("RGBA")
art = source.crop(source.getchannel("A").point(lambda v: 255 if v > 16 else 0).getbbox())


def placed(size, fraction, shadow=True):
    """The artwork centred on a transparent square, spanning `fraction` of its width."""
    scale = size * fraction / max(art.size)
    obj = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    at = ((size - obj.width) // 2, (size - obj.height) // 2)
    if shadow:  # the camera is off-white on an off-white tile: a soft shadow keeps its outline
        blur = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        blur.paste((40, 40, 45, 255), (at[0], at[1] + round(size * 0.012)), obj.getchannel("A"))
        blur = blur.filter(ImageFilter.GaussianBlur(size * 0.02))
        blur.putalpha(blur.getchannel("A").point(lambda v: v * 0.38))
        canvas = Image.alpha_composite(canvas, blur)
    canvas.alpha_composite(obj, at)
    return canvas


def save(image, folder, name):
    path = RES / folder
    path.mkdir(parents=True, exist_ok=True)
    image.save(path / name, lossless=True) if name.endswith(".webp") else image.save(path / name)


big = 1024
foreground = placed(big, 0.46)
# Themed-icon layer: only alpha matters. Keep the light body, drop the dark lens details.
flat = placed(big, 0.46, shadow=False)
luminance = flat.convert("L").point(lambda v: 255 if v > 120 else 0)
monochrome = Image.new("RGBA", (big, big), (0, 0, 0, 0))
monochrome.paste((0, 0, 0, 255), (0, 0), ImageChops.multiply(luminance, flat.getchannel("A")))

tile = Image.new("RGBA", (big, big), BACKGROUND)
tile.alpha_composite(placed(big, 0.66))
rounded = Image.new("L", (big, big), 0)
ImageDraw.Draw(rounded).rounded_rectangle((0, 0, big - 1, big - 1), radius=big * 0.22, fill=255)


def masked(mask):
    out = tile.copy()
    out.putalpha(mask)
    return out


for name, factor in DENSITIES.items():
    adaptive, legacy = round(108 * factor), round(48 * factor)
    folder = f"mipmap-{name}"
    save(foreground.resize((adaptive, adaptive), Image.LANCZOS), folder, "ic_launcher_foreground.webp")
    save(monochrome.resize((adaptive, adaptive), Image.LANCZOS), folder, "ic_launcher_monochrome.webp")
    save(masked(rounded).resize((legacy, legacy), Image.LANCZOS), folder, "ic_launcher.webp")

# TV banner, 160x90dp: drawn on a 1280x720 canvas, saved at xhdpi, xxhdpi and xxxhdpi
# (320x180, 480x270 and 640x360 px) for 4K launchers.
k = 4
banner = Image.new("RGBA", (320 * k, 180 * k), BACKGROUND)
icon = placed(84 * k, 1.0)
font = ImageFont.truetype(str(FONT), 31 * k)
text = "Camerinha"
left, top, right, bottom = font.getbbox(text)
gap = 16 * k
x = (banner.width - (icon.width + gap + right - left)) // 2
banner.alpha_composite(icon, (x, (banner.height - icon.height) // 2))
ImageDraw.Draw(banner).text(
    (x + icon.width + gap - left, (banner.height - (bottom - top)) // 2 - top), text, font=font, fill=(0x30, 0x30, 0x34, 255)
)
for folder, factor in (("mipmap-xhdpi", 2), ("mipmap-xxhdpi", 3), ("mipmap-xxxhdpi", 4)):
    size = (160 * factor, 90 * factor)
    save(banner.resize(size, Image.LANCZOS).convert("RGB"), folder, "ic_banner.png")
print("ok")
