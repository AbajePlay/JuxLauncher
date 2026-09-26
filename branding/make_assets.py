from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw

BRANDING = Path(__file__).resolve().parent
RESOURCES = BRANDING.parent / "src" / "main" / "resources" / "brand"

WORDMARK_SIZE = (624, 104)

WINDOW_ICON_SIZES = [16, 20, 24, 32, 40, 48, 64]
ICO_SIZES = WINDOW_ICON_SIZES + [256]

TILE_TOP = (0x26, 0x2B, 0x37)
TILE_BOTTOM = (0x12, 0x14, 0x19)
TILE_EDGE = (0x33, 0x3A, 0x49)
TILE_RADIUS = 0.225
GLYPH_HEIGHT = 0.78


def load_logo() -> Image.Image:
    logo = Image.open(BRANDING / "logo.webp").convert("RGBA")
    alpha = logo.getchannel("A").point(lambda a: 0 if a < 8 else a)
    logo.putalpha(alpha)
    return logo


def letters_box(logo: Image.Image, margin: int = 3) -> tuple[int, int, int, int]:
    left, top, right, bottom = logo.getchannel("A").point(lambda a: 255 if a > 16 else 0).getbbox()
    return max(left - margin, 0), max(top - margin, 0), min(right + margin, logo.width), min(bottom + margin, logo.height)


def wordmark(logo: Image.Image) -> Image.Image:
    letters = logo.crop(letters_box(logo))
    width, height = WORDMARK_SIZE
    scale = min((width - 4) / letters.width, (height - 2) / letters.height)
    letters = letters.resize((round(letters.width * scale), round(letters.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", WORDMARK_SIZE, (0, 0, 0, 0))
    canvas.alpha_composite(letters, ((width - letters.width) // 2, (height - letters.height) // 2))
    return canvas


def j_glyph(logo: Image.Image) -> Image.Image:
    letters = logo.crop(letters_box(logo))
    width, height = letters.size
    alpha = letters.getchannel("A").tobytes()
    solid = [a > 128 for a in alpha]

    owner = [-1] * (width * height)
    parts = 0
    for start in range(width * height):
        if not solid[start] or owner[start] >= 0:
            continue
        owner[start] = parts
        stack = [start]
        while stack:
            i = stack.pop()
            x, y = i % width, i // width
            for n, inside in ((i - 1, x > 0), (i + 1, x < width - 1), (i - width, y > 0), (i + width, y < height - 1)):
                if inside and solid[n] and owner[n] < 0:
                    owner[n] = parts
                    stack.append(n)
        parts += 1

    queue = deque(i for i in range(width * height) if owner[i] >= 0)
    while queue:
        i = queue.popleft()
        x, y = i % width, i // width
        for n, inside in ((i - 1, x > 0), (i + 1, x < width - 1), (i - width, y > 0), (i + width, y < height - 1)):
            if inside and owner[n] < 0:
                owner[n] = owner[i]
                queue.append(n)

    leftmost = min((i for i in range(width * height) if solid[i]), key=lambda i: i % width)
    j = owner[leftmost]
    mask = Image.frombytes("L", letters.size, bytes(255 if o == j else 0 for o in owner))
    glyph = Image.new("RGBA", letters.size, (0, 0, 0, 0))
    glyph.paste(letters, (0, 0), mask)
    return glyph.crop(glyph.getbbox())


def tile_icon(glyph: Image.Image, size: int) -> Image.Image:
    big = size * 8
    gradient = Image.new("RGBA", (1, big))
    for y in range(big):
        t = y / (big - 1)
        gradient.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(TILE_TOP, TILE_BOTTOM)) + (255,))
    gradient = gradient.resize((big, big))

    radius = big * TILE_RADIUS
    shape = Image.new("L", (big, big), 0)
    ImageDraw.Draw(shape).rounded_rectangle((0, 0, big - 1, big - 1), radius=radius, fill=255)
    edge_width = max(8, big // 64)
    edge = shape.copy()
    ImageDraw.Draw(edge).rounded_rectangle(
        (edge_width, edge_width, big - 1 - edge_width, big - 1 - edge_width), radius=radius - edge_width, fill=0,
    )

    tile = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    tile.paste(gradient, (0, 0), shape)
    tile.paste(Image.new("RGBA", (big, big), TILE_EDGE + (255,)), (0, 0), edge)
    tile = tile.resize((size, size), Image.BOX)

    scale = GLYPH_HEIGHT * size / glyph.height
    letter = glyph.resize((max(1, round(glyph.width * scale)), max(1, round(glyph.height * scale))), Image.LANCZOS)
    tile.alpha_composite(letter, ((size - letter.width) // 2, (size - letter.height) // 2))
    return tile


def main() -> None:
    logo = load_logo()
    RESOURCES.mkdir(parents=True, exist_ok=True)

    wordmark(logo).save(RESOURCES / "wordmark.png", optimize=True)

    glyph = j_glyph(logo)
    icons = {size: tile_icon(glyph, size) for size in ICO_SIZES}
    for size in WINDOW_ICON_SIZES:
        icons[size].save(RESOURCES / f"icon-{size}.png", optimize=True)
    icons[256].save(
        BRANDING / "JuxLauncher.ico",
        sizes=[(size, size) for size in ICO_SIZES],
        append_images=[icons[size] for size in ICO_SIZES if size != 256],
    )
    print(f"wordmark {WORDMARK_SIZE}, J glyph {glyph.size}, icons {ICO_SIZES}")


if __name__ == "__main__":
    main()
