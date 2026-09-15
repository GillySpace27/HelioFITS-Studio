#!/usr/bin/env python3
"""Build the HelioFITS Studio field guide.

Content lives in guide_content.json (title/subtitle/byline + an ordered list of
blocks). This script renders that single source to BOTH
  - HFStudio-Guide.pdf   (reportlab, the polished handout)
  - HFStudio-Guide.md    (GitHub-friendly text companion)
so the two can never drift, and the prose can be edited/regenerated independently.

Run: python3 build_guide.py   ->   HFStudio-Guide.pdf + HFStudio-Guide.md

Block kinds: title/subtitle/byline (top-level), then blocks of kind
  rule | spacer{points} | h1{text} | h2{text} | p{text} |
  bullets{items,numbered} | callout{title,paras} | figure{file,caption}

Inline markup (tiny): **bold**, `mono`. Curly quotes and other Unicode are fine.
Write "Y" for Upsilon (Helvetica lacks the glyph). A figure whose file is
missing is silently skipped, so the build never breaks.

@VERSION@ and @REPO@ anywhere in the content are replaced with the repository's
VERSION file and the REPO defined in deploy_release.sh, so the guide never names
a stale version or repository.
"""

import os
import re
import json
from reportlab.lib.pagesizes import letter
from reportlab.lib.units import inch
from reportlab.lib import colors
from reportlab.lib.utils import ImageReader
from reportlab.platypus import (BaseDocTemplate, PageTemplate, Frame, Paragraph,
                                Spacer, ListFlowable, ListItem, Table, TableStyle,
                                HRFlowable, Image as RLImage)
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "guide_assets")
CONTENT_JSON = os.path.join(HERE, "guide_content.json")
PDF_OUT = os.path.join(HERE, "HFStudio-Guide.pdf")
MD_OUT = os.path.join(HERE, "HFStudio-Guide.md")

# Both are defined once elsewhere: the version in the repository's VERSION file, the GitHub
# repository in deploy_release.sh. Read them rather than repeating them here.
with open(os.path.join(HERE, "..", "VERSION")) as f:
    VERSION = f.read().strip()
with open(os.path.join(HERE, "deploy_release.sh")) as f:
    REPO = re.search(r'^REPO="([^"]+)"', f.read(), re.M).group(1)

NAVY = colors.HexColor("#16243f")
NAVY_SOFT = colors.HexColor("#2c4a7a")
GOLD = colors.HexColor("#c19a3e")
INK = colors.HexColor("#23262b")
BOX_BG = colors.HexColor("#f3f5f9")
BOX_BORDER = colors.HexColor("#c9d3e4")


def load_doc():
    with open(CONTENT_JSON) as f:
        return json.loads(f.read().replace("@VERSION@", VERSION).replace("@REPO@", REPO))


styles = {
    "title": ParagraphStyle("title", fontName="Helvetica-Bold", fontSize=22, leading=26,
                            textColor=NAVY, spaceAfter=2),
    "subtitle": ParagraphStyle("subtitle", fontName="Helvetica", fontSize=12, leading=15,
                               textColor=NAVY_SOFT, spaceAfter=1),
    "byline": ParagraphStyle("byline", fontName="Helvetica-Oblique", fontSize=9.5, leading=12,
                             textColor=colors.HexColor("#5b6675"), spaceAfter=4),
    "h1": ParagraphStyle("h1", fontName="Helvetica-Bold", fontSize=14.5, leading=18,
                         textColor=NAVY, spaceBefore=12, spaceAfter=4, keepWithNext=1),
    "h2": ParagraphStyle("h2", fontName="Helvetica-Bold", fontSize=11.5, leading=15,
                         textColor=NAVY_SOFT, spaceBefore=9, spaceAfter=2, keepWithNext=1),
    "body": ParagraphStyle("body", fontName="Helvetica", fontSize=9.7, leading=13.4,
                           textColor=INK, alignment=TA_LEFT, spaceAfter=4),
    "boxh": ParagraphStyle("boxh", fontName="Helvetica-Bold", fontSize=11, leading=14,
                           textColor=NAVY, spaceAfter=3),
    "boxbody": ParagraphStyle("boxbody", fontName="Helvetica", fontSize=9.4, leading=12.8,
                              textColor=INK, spaceAfter=3),
    "bullet": ParagraphStyle("bullet", fontName="Helvetica", fontSize=9.7, leading=13.2,
                             textColor=INK, spaceAfter=2),
    "caption": ParagraphStyle("caption", fontName="Helvetica-Oblique", fontSize=8.5, leading=11,
                              textColor=colors.HexColor("#5b6675"), alignment=TA_CENTER,
                              spaceBefore=2, spaceAfter=2),
}


def pdf_inline(s):
    """Neutral markup -> reportlab markup. Escape XML, then **bold** and `mono`."""
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", s)
    s = re.sub(r"`(.+?)`", r"<font face='Courier'>\1</font>", s)
    return s


def callout(title, paras):
    inner = [Paragraph(pdf_inline(title), styles["boxh"])]
    for p in paras:
        inner.append(Paragraph(pdf_inline(p), styles["boxbody"]))
    t = Table([[inner]], colWidths=[6.6 * inch])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), BOX_BG),
        ("BOX", (0, 0), (-1, -1), 0.75, BOX_BORDER),
        ("LEFTPADDING", (0, 0), (-1, -1), 12),
        ("RIGHTPADDING", (0, 0), (-1, -1), 12),
        ("TOPPADDING", (0, 0), (-1, -1), 9),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 9),
        ("LINEBEFORE", (0, 0), (0, -1), 3, GOLD),
    ]))
    return t


def bullets(items, numbered=False):
    lst = [ListItem(Paragraph(pdf_inline(t), styles["bullet"]), leftIndent=6) for t in items]
    return ListFlowable(lst, bulletType=("1" if numbered else "bullet"),
                        bulletColor=GOLD, start="1" if numbered else None,
                        bulletFontName="Helvetica-Bold", leftIndent=16, bulletDedent=8)


def rule():
    return HRFlowable(width="100%", thickness=1.4, color=GOLD, spaceBefore=3, spaceAfter=8)


def figure_flowables(filename, caption=None, max_w=6.6 * inch, max_h=4.2 * inch):
    path = os.path.join(ASSETS, filename)
    if not filename or not os.path.exists(path):
        return []
    iw, ih = ImageReader(path).getSize()
    w, h = max_w, max_w * ih / iw
    if h > max_h:
        h, w = max_h, max_h * iw / ih
    img = RLImage(path, width=w, height=h)
    img.hAlign = "CENTER"
    out = [Spacer(1, 4), img]
    if caption:
        out.append(Paragraph(pdf_inline(caption), styles["caption"]))
    out.append(Spacer(1, 4))
    return out


def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont("Helvetica", 7.5)
    canvas.setFillColor(colors.HexColor("#7a8493"))
    canvas.drawString(0.9 * inch, 0.55 * inch,
                      "HelioFITS Studio %s field guide  -  github.com/%s" % (VERSION, REPO))
    canvas.drawRightString(7.6 * inch, 0.55 * inch, "Page %d" % doc.page)
    canvas.setStrokeColor(BOX_BORDER)
    canvas.setLineWidth(0.5)
    canvas.line(0.9 * inch, 0.72 * inch, 7.6 * inch, 0.72 * inch)
    canvas.restoreState()


def build_pdf(doc_data):
    pdf = BaseDocTemplate(PDF_OUT, pagesize=letter,
                          leftMargin=0.9 * inch, rightMargin=0.9 * inch,
                          topMargin=0.8 * inch, bottomMargin=0.9 * inch,
                          title=doc_data.get("title", "HelioFITS Studio"),
                          author="Gilly, NWRA")
    frame = Frame(pdf.leftMargin, pdf.bottomMargin, pdf.width, pdf.height, id="main")
    pdf.addPageTemplates([PageTemplate(id="all", frames=[frame], onPage=footer)])

    S = []
    if doc_data.get("title"):
        S.append(Paragraph(pdf_inline(doc_data["title"]), styles["title"]))
    if doc_data.get("subtitle"):
        S.append(Paragraph(pdf_inline(doc_data["subtitle"]), styles["subtitle"]))
    if doc_data.get("byline"):
        S.append(Paragraph(pdf_inline(doc_data["byline"]), styles["byline"]))

    for b in doc_data["blocks"]:
        k = b["kind"]
        if k == "rule":
            S.append(rule())
        elif k == "spacer":
            S.append(Spacer(1, b.get("points", 6)))
        elif k in ("h1", "h2", "p"):
            S.append(Paragraph(pdf_inline(b["text"]), styles["body" if k == "p" else k]))
        elif k == "bullets":
            S.append(bullets(b["items"], b.get("numbered", False)))
        elif k == "callout":
            S.append(callout(b["title"], b["paras"]))
        elif k == "figure":
            S.extend(figure_flowables(b.get("file", ""), b.get("caption")))
    pdf.build(S)
    print("wrote", PDF_OUT)


def build_markdown(doc_data):
    L = []
    if doc_data.get("title"):
        L.append("# " + doc_data["title"])
    if doc_data.get("subtitle"):
        L.append("### " + doc_data["subtitle"])
    if doc_data.get("byline"):
        L.append("*" + doc_data["byline"] + "*")
    L.append("")

    for b in doc_data["blocks"]:
        k = b["kind"]
        if k == "rule":
            L.append("\n---\n")
        elif k == "spacer":
            L.append("")
        elif k == "h1":
            L.append("## " + b["text"])
        elif k == "h2":
            L.append("### " + b["text"])
        elif k == "p":
            L.append(b["text"])
        elif k == "bullets":
            for i, item in enumerate(b["items"], 1):
                L.append(("%d. " % i if b.get("numbered") else "- ") + item)
            L.append("")
        elif k == "callout":
            L.append("> **" + b["title"] + "**")
            L.append(">")
            for p in b["paras"]:
                L.append("> " + p)
                L.append(">")
            if L and L[-1] == ">":
                L.pop()
        elif k == "figure":
            pass  # figures are embedded in the PDF only; the .md stays a clean text companion
        L.append("")
    text = "\n".join(L)
    text = re.sub(r"\n{3,}", "\n\n", text).strip() + "\n"
    with open(MD_OUT, "w") as f:
        f.write(text)
    print("wrote", MD_OUT)


if __name__ == "__main__":
    os.makedirs(ASSETS, exist_ok=True)
    data = load_doc()
    build_pdf(data)
    build_markdown(data)
