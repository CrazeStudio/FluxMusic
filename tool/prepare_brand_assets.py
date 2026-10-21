#!/usr/bin/env python3
"""Generate bundled SVG brand assets and Android launcher-icon resources."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android" / "app" / "src" / "main" / "res"
BRAND = ROOT / "assets" / "brand"
ICON = BRAND / "fluxmusic.svg"
FOREGROUND = BRAND / "fluxmusic_foreground.svg"

def rasterize(source: Path, output: Path, size: int) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["rsvg-convert", "--width", str(size), "--height", str(size), str(source), "--output", str(output)],
        check=True,
    )

for folder, size in (
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
):
    rasterize(ICON, RES / folder / "ic_launcher.png", size)

rasterize(FOREGROUND, RES / "drawable-nodpi" / "fluxmusic_icon_foreground.png", 432)

adaptive_dir = RES / "mipmap-anydpi-v26"
adaptive_dir.mkdir(parents=True, exist_ok=True)
(adaptive_dir / "ic_launcher.xml").write_text(
    '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/fluxmusic_icon_background"/>
    <foreground android:drawable="@drawable/fluxmusic_icon_foreground"/>
</adaptive-icon>
''',
    encoding="utf-8",
)

values_dir = RES / "values"
values_dir.mkdir(parents=True, exist_ok=True)
(values_dir / "fluxmusic_icon_colors.xml").write_text(
    '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="fluxmusic_icon_background">#F3E8D2</color>
</resources>
''',
    encoding="utf-8",
)
print("Generated FluxMusic launcher icons (legacy density + adaptive Android icon).")
