"""
Make Gelasio usable as a Georgia stand-in.

Gelasio matches Georgia's letter WIDTHS but its line HEIGHT is ~46% taller
(win ascent+descent 3400/2048 em vs Georgia 2327/2048). Measured effect on the
corpus: Abhinav spilled to 2 pages; with these metrics all 9 resumes fit.

This script copies Gelasio's static TTFs, sets Georgia-like vertical metrics,
and renames the family to "Gelasio RT" (renaming keeps us clear of the OFL's
reserved-font-name rule for modified fonts). Map Georgia -> "Gelasio RT".

Usage: python patch_gelasio.py <dir with Gelasio-*.ttf> <out dir>
Source fonts: https://github.com/SorkinType/Gelasio (fonts/ttf), OFL 1.1.
"""
import sys, glob, os
from fontTools.ttLib import TTFont

ASC, DESC = 1878, 449          # Georgia, units per em 2048
NEW = "Gelasio RT"

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
for path in glob.glob(os.path.join(src, "Gelasio-*.ttf")):
    f = TTFont(path)
    f["hhea"].ascent, f["hhea"].descent, f["hhea"].lineGap = ASC, -DESC, 0
    os2 = f["OS/2"]
    os2.usWinAscent, os2.usWinDescent = ASC, DESC
    os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap = ASC, -DESC, 0
    style = os.path.basename(path)[len("Gelasio-"):-4]          # Regular, Bold, ...
    pretty = {"BoldItalic": "Bold Italic"}.get(style, style)
    for rec in f["name"].names:
        if rec.nameID in (1, 16):
            rec.string = NEW
        elif rec.nameID == 4:
            rec.string = f"{NEW} {pretty}"
        elif rec.nameID == 6:
            rec.string = f"GelasioRT-{style}"
    f.save(os.path.join(out, f"GelasioRT-{style}.ttf"))
    print("wrote", f"GelasioRT-{style}.ttf")
