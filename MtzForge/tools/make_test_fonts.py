#!/usr/bin/env python3
"""Genera fuentes de prueba propias (sin licencias de terceros) para los tests.

Uso: python3 make_test_fonts.py <directorio_salida>
Requiere: pip install fonttools
"""
import sys, os
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.ttLib import TTCollection, TTFont

CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 "


def box(pen, w):
    pen.moveTo((50, 0)); pen.lineTo((50, 700)); pen.lineTo((w - 50, 700)); pen.lineTo((w - 50, 0)); pen.closePath()


def build(family, style, cff, out):
    glyphs = [".notdef"] + ["uni%04X" % ord(c) for c in CHARS]
    cmap = {ord(c): "uni%04X" % ord(c) for c in CHARS}
    fb = FontBuilder(1000, isTTF=not cff)
    fb.setupGlyphOrder(glyphs)
    fb.setupCharacterMap(cmap)
    adv = {g: (250 if g == "uni0020" else 600) for g in glyphs}
    if cff:
        cs = {}
        for g in glyphs:
            pen = T2CharStringPen(adv[g], None)
            if g != "uni0020":
                box(pen, adv[g])
            cs[g] = pen.getCharString()
        fb.setupCFF(family.replace(" ", "") + "-" + style, {"FullName": family + " " + style}, cs, {})
    else:
        gl = {}
        for g in glyphs:
            pen = TTGlyphPen(None)
            if g != "uni0020":
                box(pen, adv[g])
            gl[g] = pen.glyph()
        fb.setupGlyf(gl)
    fb.setupHorizontalMetrics({g: (adv[g], 50) for g in glyphs})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupNameTable({"familyName": family, "styleName": style})
    fb.setupOS2(sTypoAscender=800, usWinAscent=800, usWinDescent=200)
    fb.setupPost()
    fb.save(out)


def main(d):
    os.makedirs(d, exist_ok=True)
    build("Forge Test Sans", "Regular", False, os.path.join(d, "ForgeTestSans-Regular.ttf"))
    build("Forge Test Serif", "Regular", True, os.path.join(d, "ForgeTestSerif-Regular.otf"))
    build("Forge Test Bold", "Bold", False, os.path.join(d, "_b.ttf"))
    c = TTCollection()
    c.fonts = [TTFont(os.path.join(d, "ForgeTestSans-Regular.ttf")), TTFont(os.path.join(d, "_b.ttf"))]
    c.save(os.path.join(d, "ForgeTestCollection.ttc"))
    os.remove(os.path.join(d, "_b.ttf"))
    with open(os.path.join(d, "not-a-font.ttf"), "wb") as f:
        f.write(b"This is not a font file at all. " * 8)


if __name__ == "__main__":
    main(sys.argv[1])
