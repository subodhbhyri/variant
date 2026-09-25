"""Python reference for PHASE3_SPEC.md sections 2-3: sections, blocks, header fields.
Operates on a normalized .docx (after Phase 2 onboarding normalization)."""
import re, zipfile
from lxml import etree

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PR = "http://schemas.openxmlformats.org/package/2006/relationships"
q = lambda t: "{%s}%s" % (W, t)
GLYPHS = "•●▪◦‣➢❖■□►✓\uf0b7\uf0a7"

VOCAB = {
    "projects": ["project", "research paper", "publication", "open-source", "open source", "contribution", "portfolio"],
    "experience": ["experience", "employment", "internship", "work history"],
    "other": ["education", "skill", "certification", "award", "honor", "achievement", "leadership", "activit",
              "extracurricular", "summary", "objective", "coursework", "profile", "interest", "volunteer",
              "position", "language", "hobbies", "reference"],
}

def text(el):
    return "".join(t.text or "" for t in el.iter(q("t")))

def role_of(t):
    s = t.lower().strip(" :")
    for role in ("projects", "experience", "other"):
        if any(k in s for k in VOCAB[role]):
            return role
    return None

def signature(p):
    ppr = p.find(q("pPr"))
    style = ppr.find(q("pStyle")).get(q("val")) if ppr is not None and ppr.find(q("pStyle")) is not None else ""
    runs = [r for r in p.iter(q("r")) if text(r).strip()]
    bold = bool(runs) and all(r.find(q("rPr") + "/" + q("b")) is not None for r in runs)
    letters = [c for c in text(p) if c.isalpha()]
    caps = bool(letters) and all(c.isupper() for c in letters)
    border = ppr is not None and ppr.find(q("pBdr")) is not None
    return (style if style.lower().startswith("heading") else "", caps, bold, border)

def is_short(p):
    t = text(p).strip()
    return 0 < len(t) <= 40 and len(t.split()) <= 5

def body_paragraphs(root):
    body = root.find(q("body"))
    return [p for p in body.iter(q("p"))
            if not any(a.tag in (q("tbl"), q("txbxContent")) for a in p.iterancestors())]

def detect_sections(root):
    paras = body_paragraphs(root)
    vocab_hits = [p for p in paras if is_short(p) and role_of(text(p)) is not None]
    sigs = {signature(p) for p in vocab_hits}
    # a heading signature must say something: a heading style, caps, bold or a border
    sigs = {s for s in sigs if any(s)}
    heads = [p for p in paras if is_short(p) and (signature(p) in sigs) and
             (role_of(text(p)) is not None or signature(p) in sigs)]
    # never treat the first line of the document (the name) as a heading unless it's vocabulary
    heads = [p for p in heads if role_of(text(p)) is not None or paras.index(p) > 2]
    idx = [paras.index(p) for p in heads]
    sections = []
    for k, i in enumerate(idx):
        end = idx[k + 1] if k + 1 < len(idx) else len(paras)
        t = text(paras[i]).strip()
        sections.append({"heading": t, "role": role_of(t) or "other", "paras": paras[i + 1:end]})
    return sections

def run_level_breaks(p):
    return [b for r in p.findall(q("r")) for b in r.findall(q("br"))]

def inline_segments(p):
    """Split a paragraph's text at run-level <w:br/>; returns list of segment strings."""
    segs = [""]
    for el in p:
        if el.tag == q("r"):
            for c in el:
                if c.tag == q("br"): segs.append("")
                elif c.tag == q("t"): segs[-1] += c.text or ""
                elif c.tag == q("tab"): segs[-1] += "\t"
        elif el.tag == q("hyperlink"):
            segs[-1] += text(el)
    return segs

def detect_blocks(section, is_bullet):
    """-> list of blocks: {kind: 'paragraph'|'inline', header: [p..], bullets: [p..] or segment groups,
       swappable: bool, reason: str|None}"""
    items = [p for p in section["paras"] if text(p).strip()]
    blocks, cur = [], None
    for p in items:
        if is_bullet(p):
            if cur is None or cur["kind"] == "inline":
                cur = {"kind": "paragraph", "header": [], "bullets": [], "stray": [], "reason": "no_header"}
                blocks.append(cur)
            cur["bullets"].append(p)
            continue
        segs = inline_segments(p)
        if len(segs) > 1 and any(s.lstrip()[:1] in GLYPHS for s in segs[1:]):
            groups = []
            for s in segs[1:]:
                if s.lstrip()[:1] in GLYPHS or not groups: groups.append([s])
                else: groups[-1].append(s)          # continuation of the previous inline bullet
            cur = {"kind": "inline", "header": [p], "header_text": segs[0], "bullets": groups, "stray": [], "reason": None}
            blocks.append(cur); cur = None if False else cur
            cur = None
            continue
        if cur is not None and cur["kind"] == "paragraph" and cur["bullets"]:
            # a non-bullet paragraph after bullets: new header, or a stray (split bullet / description)
            if looks_like_continuation(p):
                cur["stray"].append(p); continue
            cur = None
        if cur is None:
            cur = {"kind": "paragraph", "header": [], "bullets": [], "stray": [], "reason": None}
            blocks.append(cur)
        cur["header"].append(p)
    for b in blocks:
        if b["kind"] == "inline": continue
        if not b["header"]: b["reason"] = b["reason"] or "no_header"
        elif len(b["header"]) > 1: b["reason"] = "multi_paragraph_header"
        elif b["stray"]: b["reason"] = "non_bullet_paragraph_in_block"
        elif not b["bullets"]: b["reason"] = "no_bullets"
    return blocks

def looks_like_continuation(p):
    t = text(p).strip()
    return bool(t) and (t[0].islower() or t[0] in "(,;–-")

# ---- header fields ---------------------------------------------------------
DATE = re.compile(r"(?i)\(?\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s*\d{4}\s*[-–—]\s*"
                  r"(?:(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s*\d{4}|present|current|now)\)?\s*$")
URLISH = re.compile(r"(?i)^(https?://|www\.)?[a-z0-9-]+(\.[a-z0-9-]+)+(/\S*)?$")

def parse_header(p):
    """-> (fields, reason). fields: title, detail, links[{label}], date, date_mode."""
    parts, links, tab_seen, after_tab = [], [], False, ""
    for el in p:
        if el.tag == q("hyperlink"):
            links.append({"label": text(el).strip()})
            parts.append("\x00LINK\x00")
        elif el.tag == q("r"):
            for c in el:
                if c.tag in (q("tab"), q("ptab")): tab_seen = True
                elif c.tag == q("br"): return None, "line_break_in_header"
                elif c.tag == q("t"):
                    if tab_seen: after_tab += c.text or ""
                    else: parts.append(c.text or "")
        elif el.tag in (q("pPr"), q("bookmarkStart"), q("bookmarkEnd"), q("proofErr")):
            continue
        elif el.tag in (q("fldSimple"), q("sdt"), q("smartTag"), q("ins"), q("del")):
            return None, "unsupported_element_in_header"
    body = "".join(parts)
    date, mode = None, None
    if tab_seen:
        if not DATE.search(after_tab.strip()) and after_tab.strip():
            return None, "unrecognized_text_after_tab"
        date, mode = (after_tab.strip() or None), "tab"
    else:
        m = DATE.search(body.replace("\x00LINK\x00", "").rstrip())
        if m and body.rstrip().endswith(m.group(0).strip()):
            date, mode = m.group(0).strip(), "inline"
            body = body.rstrip()[: len(body.rstrip()) - len(m.group(0).strip())]
    # strip link placeholders and separators around them
    core = re.sub(r"\s*[|·]?\s*\x00LINK\x00", "", body).strip()
    core = core.replace("[", "").replace("]", "").strip() if links else core
    pieces = [s.strip() for s in core.split(" | ")]
    title = pieces[0].strip(" |")
    detail = " | ".join(pieces[1:]).strip(" |") or None
    if not title: return None, "no_title"
    if URLISH.match(title.split()[-1] if title.split() else ""):
        # bare URL text at the end of the title (not a hyperlink): treat as a link field
        return None, "bare_url_text"
    return {"title": title, "detail": detail, "detail_is_list": bool(detail and "," in detail),
            "links": links, "date": date, "date_mode": mode}, None

# ---- header templates (section 3.2) ---------------------------------------
import copy as _copy

def _chars_with_rpr(p):
    """Flatten a header paragraph into tokens: ('char', c, rPr) / ('link', el) / ('tab', run)."""
    toks = []
    for el in p:
        if el.tag == q("hyperlink"):
            toks.append(("link", el))
        elif el.tag == q("r"):
            rpr = el.find(q("rPr"))
            for c in el:
                if c.tag in (q("tab"), q("ptab")): toks.append(("tab", el))
                elif c.tag == q("t"):
                    for ch in (c.text or ""): toks.append(("char", ch, rpr))
    return toks

def header_template(p):
    """Ordered template: list of (kind, payload). kinds: TITLE/DETAIL/DATE (payload rPr),
    SEP/LIT (payload (text, rPr)), LINK (element), TAB (run element)."""
    toks = _chars_with_rpr(p)
    out, buf, after_tab = [], [], False
    def flush():
        nonlocal buf
        if not buf: return
        s = "".join(c for _, c, _ in buf)
        if after_tab:
            m = DATE.search(s.strip())
            if m and s.strip():
                i = s.index(s.strip()); j = i + len(s.strip())
                if i: out.append(("LIT", (s[:i], buf[0][2])))
                out.append(("DATE", buf[i][2], s.strip().startswith("(")))
                if j < len(s): out.append(("LIT", (s[j:], buf[j][2])))
            elif s: out.append(("LIT", (s, buf[0][2])))
            buf = []; return
        # before the tab: title, then " | "-separated detail(s); trailing punctuation is literal
        pos = 0
        pieces = s.split(" | ")
        for k, piece in enumerate(pieces):
            if k > 0:
                out.append(("SEP", (" | ", buf[pos][2]))); pos += 3
            core = piece.strip(" [(")
            lead = piece[: piece.index(core)] if core else piece
            trail = piece[len(lead) + len(core):]
            if lead: out.append(("LIT", (lead, buf[pos][2]))); pos += len(lead)
            if core:
                kind = "TITLE" if not any(t[0] == "TITLE" for t in out) else "DETAIL"
                longest = buf[pos][2]
                out.append((kind, longest)); pos += len(core)
            if trail: out.append(("LIT", (trail, buf[pos][2] if pos < len(buf) else buf[-1][2]))); pos += len(trail)
        buf = []
    for t in toks:
        if t[0] == "char": buf.append(t); continue
        flush()
        if t[0] == "link": out.append(("LINK", t[1]))
        else: out.append(("TAB", t[1])); after_tab = True
    flush()
    return out

def _run(rpr, s):
    r = etree.Element(q("r"))
    if rpr is not None: r.append(_copy.deepcopy(rpr))
    t = etree.SubElement(r, q("t")); t.text = s
    t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
    return r

def _word_runs(rpr, s):
    return [_run(rpr, piece) for piece in re.findall(r"\S+|\s+", s)]

def render_header(p, template, fields, add_link_rel):
    """Rewrite header paragraph p from its template with new fields
    {title, detail, links:[{label,url}], date}. add_link_rel(url) -> new r:id."""
    for c in list(p):
        if c.tag != q("pPr"): p.remove(c)
    links = list(fields.get("links") or [])
    kinds = [t[0] for t in template]
    for k, t in enumerate(template):
        kind = t[0]
        if kind == "TITLE":
            p.extend(_word_runs(t[1], fields["title"]))
        elif kind == "DETAIL":
            if fields.get("detail"): p.extend(_word_runs(t[1], fields["detail"]))
        elif kind == "SEP":
            nxt = kinds[k + 1] if k + 1 < len(kinds) else None
            if nxt == "DETAIL" and not fields.get("detail"): continue
            if nxt == "LINK" and not links: continue
            p.append(_run(t[1][1], t[1][0]))
        elif kind == "LIT":
            p.append(_run(t[1][1], t[1][0]))
        elif kind == "LINK":
            if not links: continue
            link = links.pop(0); el = _copy.deepcopy(t[1])
            el.set("{%s}id" % R, add_link_rel(link["url"]))
            runs = [r for r in el.iter(q("r")) if text(r)]
            label = link["url"] if URLISH.match(text(t[1]).strip()) else link["label"]
            if runs:
                ts = runs[0].findall(q("t")); ts[0].text = label
                for extra in ts[1:]: runs[0].remove(extra)
                for r in runs[1:]: r.getparent().remove(r)
            p.append(el)
        elif kind == "TAB":
            r = etree.Element(q("r"))
            rpr = t[1].find(q("rPr"))
            if rpr is not None: r.append(_copy.deepcopy(rpr))
            etree.SubElement(r, q("tab")); p.append(r)
        elif kind == "DATE":
            d = fields.get("date") or ""
            if d and t[2] and not d.startswith("("): d = "(" + d + ")"
            if d: p.extend(_word_runs(t[1], d))

# ---- date tab normalization (section 3.2.1) --------------------------------
def text_width_twips(root, p):
    """Right edge for a right tab: page text width minus the paragraph's right indent."""
    sect = root.find(q("body")).find(q("sectPr"))
    w = int(sect.find(q("pgSz")).get(q("w"))); mar = sect.find(q("pgMar"))
    width = w - int(mar.get(q("left"))) - int(mar.get(q("right")))
    ind = p.find(q("pPr") + "/" + q("ind"))
    right = int(ind.get(q("right"), ind.get(q("end"), "0"))) if ind is not None else 0
    return width - right

def right_align_date_tab(root, p, measured_edge_twips):
    """PHASE3_SPEC 4.1: when header p (TAB ... DATE) is rewritten, replace its tab stops with one right tab at
    the original date's right edge, measured on the normalized render (x1 of the header line's rightmost
    character minus the left margin, in twips), clamped to the text width. Returns True if changed.
    Only call this on headers that are being rewritten."""
    tpl = header_template(p)
    kinds = [t[0] for t in tpl]
    if "DATE" not in kinds or "TAB" not in kinds or kinds.index("TAB") > kinds.index("DATE"): return False
    pos = str(min(int(measured_edge_twips), text_width_twips(root, p)))
    ppr = p.find(q("pPr"))
    if ppr is None: ppr = etree.Element(q("pPr")); p.insert(0, ppr)
    tabs = ppr.find(q("tabs"))
    if tabs is not None: ppr.remove(tabs)
    tabs = etree.Element(q("tabs")); t = etree.SubElement(tabs, q("tab")); t.set(q("val"), "right"); t.set(q("pos"), pos)
    # CT_PPr order: tabs precedes spacing/ind/jc/rPr/sectPr
    anchor = next((c for c in ppr if etree.QName(c).localname in
                   ("suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct", "autoSpaceDE",
                    "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing",
                    "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment", "textboxTightWrap",
                    "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange")), None)
    if anchor is not None: anchor.addprevious(tabs)
    else: ppr.append(tabs)
    return True

# ---- bullet detection with style-chain numbering (matches the Java NumberingResolver) ----
def bullet_detector(zf):
    """-> is_bullet(p) for paragraphs of this package: direct numPr, or pStyle/basedOn chain; numFmt 'bullet'."""
    sty = etree.fromstring(zf.read("word/styles.xml"))
    num = etree.fromstring(zf.read("word/numbering.xml")) if "word/numbering.xml" in zf.namelist() else None
    snum, sbase = {}, {}
    for s in sty.findall(q("style")):
        sid = s.get(q("styleId")); n = s.find(q("pPr") + "/" + q("numPr") + "/" + q("numId"))
        if n is not None: snum[sid] = n.get(q("val"))
        b = s.find(q("basedOn"))
        if b is not None: sbase[sid] = b.get(q("val"))
    amap, fmt = {}, {}
    if num is not None:
        for n in num.findall(q("num")): amap[n.get(q("numId"))] = n.find(q("abstractNumId")).get(q("val"))
        for a in num.findall(q("abstractNum")):
            for l in a.findall(q("lvl")):
                f = l.find(q("numFmt"))
                if f is not None: fmt[(a.get(q("abstractNumId")), l.get(q("ilvl")))] = f.get(q("val"))
    def is_bullet(p):
        if any(a.tag == q("tbl") for a in p.iterancestors()) or not text(p).strip(): return False
        n = p.find(q("pPr") + "/" + q("numPr"))
        if n is not None:
            nid = n.find(q("numId")).get(q("val")); il = n.find(q("ilvl")); il = il.get(q("val")) if il is not None else "0"
        else:
            ps = p.find(q("pPr") + "/" + q("pStyle")); sid = ps.get(q("val")) if ps is not None else None; nid = None
            for _ in range(10):
                if not sid: break
                if sid in snum: nid = snum[sid]; break
                sid = sbase.get(sid)
            il = "0"
        return bool(nid) and nid != "0" and fmt.get((amap.get(nid), il)) == "bullet"
    return is_bullet
