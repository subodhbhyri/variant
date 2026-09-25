"""
Generates the Phase 2 fixtures: a synthetic resume ("Jane Doe", no real
person's data) plus hostile and edge-case files derived from it.

    pip install python-docx
    python tools/make_phase2_fixtures.py fixtures/phase2

Expected outcomes are in fixtures/phase2/expected.json (written by this script).
"""
import io, json, os, sys, zipfile, re
import docx
from docx.shared import Pt

OUT = sys.argv[1] if len(sys.argv) > 1 else "fixtures/phase2"
os.makedirs(OUT, exist_ok=True)

JOBS = [
    ("Senior Software Engineer | Northwind Labs", "Jan 2023 – Present", [
        "Designed an event-driven order pipeline on Kafka and Postgres that cut checkout latency by 38% at 4K requests per second.",
        "Led the migration of twelve services from EC2 to Kubernetes, reducing monthly infrastructure spend by 22%.",
        "Built a feature-flag service used by six teams, enabling same-day rollbacks without redeploys.",
    ]),
    ("Software Engineer | Contoso Health", "Jun 2020 – Dec 2022", [
        "Implemented HL7 FHIR ingestion for 30 hospital partners with end-to-end validation and replay.",
        "Reduced nightly batch runtime from 5 hours to 70 minutes by parallelising the reconciliation step.",
        "Mentored three junior engineers through code review and weekly pairing sessions.",
    ]),
]

def base_document(bullet_count=None, font=None):
    d = docx.Document()
    st = d.styles["Normal"]; st.font.size = Pt(10.5)
    if font:
        st.font.name = font
    d.add_heading("Jane Doe", level=1)
    d.add_paragraph("jane.doe@example.com · (555) 010-0199 · example.com/janedoe")
    d.add_heading("Experience", level=2)
    n = 0
    for title, dates, bullets in JOBS:
        d.add_paragraph(f"{title}    {dates}")
        for b in bullets:
            if bullet_count is not None and n >= bullet_count:
                break
            d.add_paragraph(b, style="List Bullet"); n += 1
    d.add_heading("Education", level=2)
    d.add_paragraph("B.S. Computer Science, State University, 2020")
    return d

def save(d, name):
    buf = io.BytesIO(); d.save(buf); data = buf.getvalue()
    with open(os.path.join(OUT, name), "wb") as f: f.write(data)
    return data

def rewrite(src_bytes, name, edit_parts=None, add=None, drop=None, extra_raw=None):
    """Copy a docx zip, editing parts (name -> fn(str)->str), adding/dropping entries."""
    zin = zipfile.ZipFile(io.BytesIO(src_bytes)); buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        for it in zin.infolist():
            if drop and it.filename in drop: continue
            data = zin.read(it.filename)
            if edit_parts and it.filename in edit_parts:
                data = edit_parts[it.filename](data.decode("utf-8")).encode("utf-8")
            zout.writestr(it.filename, data)
        for n, data in (add or {}).items():
            zout.writestr(n, data)
        for n, data, method in (extra_raw or []):
            zi = zipfile.ZipInfo(n); zi.compress_type = method
            zout.writestr(zi, data)
    with open(os.path.join(OUT, name), "wb") as f: f.write(buf.getvalue())

expected = {}
base = save(base_document(), "ok_synthetic.docx")
expected["ok_synthetic.docx"] = {"accept": True, "editable": 6, "locked": {}}

# --- accepted edge cases ---------------------------------------------------
_uf = save(base_document(font="Constantia"), "unknown_font.docx")
# declare Constantia's family in the font table, as Word does, so the serif candidates are chosen
rewrite(_uf, "unknown_font.docx", edit_parts={"word/fontTable.xml": lambda x: x.replace(
    "</w:fonts>", '<w:font w:name="Constantia"><w:family w:val="roman"/><w:pitch w:val="variable"/></w:font></w:fonts>')})
expected["unknown_font.docx"] = {"accept": True, "editable": 6, "locked": {},
                                 "font_substitution": {"Constantia": "serif candidate"}}

def link_in_first_bullet(x):
    # wrap the first bullet's run in an external hyperlink (relationship added below)
    i = x.index("Designed an event-driven")
    rs = x.rindex("<w:r>", 0, i) if "<w:r>" in x[:i] else x.rindex("<w:r ", 0, i)
    re_ = x.index("</w:r>", i) + len("</w:r>")
    return x[:rs] + '<w:hyperlink r:id="rIdLink1">' + x[rs:re_] + "</w:hyperlink>" + x[re_:]
def add_link_rel(x):
    return x.replace("</Relationships>",
        '<Relationship Id="rIdLink1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" '
        'Target="https://example.com" TargetMode="External"/></Relationships>')
rewrite(base, "link_in_bullet.docx", edit_parts={
    "word/document.xml": link_in_first_bullet, "word/_rels/document.xml.rels": add_link_rel})
expected["link_in_bullet.docx"] = {"accept": True, "editable": 5, "locked": {"0": "hyperlink"}}

# two bullets forced side by side in a two-column section
def side_by_side(x):
    body_sect = re.search(r"<w:sectPr[ >].*?</w:sectPr>", x, re.S).group(0)
    def sect(cols):
        s = re.sub(r"<w:cols[^>]*/>", "", body_sect)
        s = s.replace("<w:sectPr>", "<w:sectPr>", 1)
        s = re.sub(r"(<w:sectPr[^>]*>)", r'\1<w:type w:val="continuous"/>', s, count=1)
        return s.replace("<w:docGrid", f'<w:cols w:num="{cols}" w:space="360"/><w:docGrid', 1)
    paras = [m for m in re.finditer(r"<w:p[ >].*?</w:p>", x, re.S)]
    bullet_idx = [k for k, m in enumerate(paras) if 'w:val="ListBullet"' in m.group(0)]
    b3, b4 = paras[bullet_idx[3]], paras[bullet_idx[4]]
    prev = paras[bullet_idx[3] - 1]
    def add_sect(p, s):
        return p.replace("</w:pPr>", s + "</w:pPr>", 1) if "</w:pPr>" in p else p.replace(">", "><w:pPr>" + s + "</w:pPr>", 1)
    col_break = '<w:p><w:r><w:br w:type="column"/></w:r></w:p>'
    out = (x[:prev.start()] + add_sect(prev.group(0), sect(1)) + x[prev.end():b3.start()]
           + b3.group(0) + col_break + x[b3.end():b4.start()] + add_sect(b4.group(0), sect(2)) + x[b4.end():])
    return out
rewrite(base, "side_by_side.docx", edit_parts={"word/document.xml": side_by_side})
expected["side_by_side.docx"] = {"accept": True, "editable": 6, "locked": {}, "lines": {"3": 3, "4": 3},
                                 "note": ("bullets 3 and 4 render in neighbouring columns with baselines 3.9pt apart, so no "
                                          "line is shared: both stay editable and must be measured at 3 lines each. "
                                          "Page count not asserted (the column break spills 3 lines).")}

# --- rejected: layout / content ---------------------------------------------
save(base_document(bullet_count=2), "too_few_bullets.docx")
expected["too_few_bullets.docx"] = {"accept": False, "reason": "TOO_FEW_EDITABLE"}

def triple_body(x):
    s = x.index("<w:body>") + len("<w:body>"); e = x.index("<w:sectPr")
    return x[:s] + x[s:e] * 7 + x[e:]
rewrite(base, "too_many_pages.docx", edit_parts={"word/document.xml": triple_body})
expected["too_many_pages.docx"] = {"accept": False, "reason": "TOO_MANY_PAGES"}

def tracked(x):
    i = x.index("Reduced nightly batch"); rs = x.rindex("<w:r>", 0, i); re_ = x.index("</w:r>", i) + 6
    return x[:rs] + '<w:ins w:id="901" w:author="Editor" w:date="2026-01-01T00:00:00Z">' + x[rs:re_] + "</w:ins>" + x[re_:]
rewrite(base, "tracked_changes.docx", edit_parts={"word/document.xml": tracked})
expected["tracked_changes.docx"] = {"accept": False, "reason": "TRACKED_CHANGES"}

# --- rejected: unsafe files -------------------------------------------------
open(os.path.join(OUT, "not_a_zip.docx"), "wb").write(b"This is plain text pretending to be a resume.\n" * 20)
expected["not_a_zip.docx"] = {"accept": False, "reason": "NOT_A_DOCX"}

open(os.path.join(OUT, "encrypted.docx"), "wb").write(bytes.fromhex("D0CF11E0A1B11AE1") + b"\x00" * 4088)
expected["encrypted.docx"] = {"accept": False, "reason": "ENCRYPTED_OR_LEGACY"}

rewrite(base, "missing_document.docx", drop={"word/document.xml"})
expected["missing_document.docx"] = {"accept": False, "reason": "NOT_A_DOCX"}

rewrite(base, "too_large.docx", add={"word/media/padding.bin": os.urandom(2_200_000)})
expected["too_large.docx"] = {"accept": False, "reason": "FILE_TOO_LARGE"}

rewrite(base, "too_many_entries.docx", add={f"word/padding/part{i}.xml": b"<a/>" for i in range(300)})
expected["too_many_entries.docx"] = {"accept": False, "reason": "TOO_MANY_ENTRIES"}

rewrite(base, "zip_bomb.docx", extra_raw=[("word/bomb.xml", b" " * 40_000_000, zipfile.ZIP_DEFLATED)])
expected["zip_bomb.docx"] = {"accept": False, "reason": "ZIP_BOMB"}

rewrite(base, "path_traversal.docx", add={"../../evil.xml": b"<x/>"})
expected["path_traversal.docx"] = {"accept": False, "reason": "UNSAFE_PATH"}

rewrite(base, "duplicate_entry.docx", extra_raw=[("word/document.xml", b"<shadow/>", zipfile.ZIP_DEFLATED)])
expected["duplicate_entry.docx"] = {"accept": False, "reason": "DUPLICATE_ENTRY"}

rewrite(base, "macros.docx", add={"word/vbaProject.bin": b"\xd0\xcf\x11\xe0fake-vba"},
        edit_parts={"[Content_Types].xml": lambda x: x.replace("</Types>",
            '<Default Extension="bin" ContentType="application/vnd.ms-office.vbaProject"/></Types>')})
expected["macros.docx"] = {"accept": False, "reason": "MACROS"}

rewrite(base, "macro_content_type.docx", edit_parts={"[Content_Types].xml": lambda x: x.replace(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
    "application/vnd.ms-word.document.macroEnabled.main+xml")})
expected["macro_content_type.docx"] = {"accept": False, "reason": "MACROS"}

rewrite(base, "embedded_object.docx", add={"word/embeddings/oleObject1.bin": b"\xd0\xcf\x11\xe0fake-ole"},
        edit_parts={"[Content_Types].xml": lambda x: x.replace("</Types>",
            '<Default Extension="bin" ContentType="application/vnd.openxmlformats-officedocument.oleObject"/></Types>')})
expected["embedded_object.docx"] = {"accept": False, "reason": "EMBEDDED_OBJECT"}

rewrite(base, "external_image.docx", edit_parts={"word/_rels/document.xml.rels": lambda x: x.replace(
    "</Relationships>",
    '<Relationship Id="rIdExt1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" '
    'Target="http://tracker.example.com/pixel.png" TargetMode="External"/></Relationships>')})
expected["external_image.docx"] = {"accept": False, "reason": "EXTERNAL_RESOURCE"}

rewrite(base, "doctype_entity.docx", edit_parts={"word/document.xml": lambda x: x.replace(
    "?>", '?><!DOCTYPE d [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>', 1)})
expected["doctype_entity.docx"] = {"accept": False, "reason": "UNSAFE_XML"}

rewrite(base, "malformed_xml.docx", edit_parts={"word/document.xml": lambda x: x[: len(x) // 2]})
expected["malformed_xml.docx"] = {"accept": False, "reason": "UNSAFE_XML"}

def deep_tables(x):
    s = x.index("<w:body>") + len("<w:body>")
    nest = "<w:tbl><w:tr><w:tc>" * 300 + "<w:p/>" + "</w:tc></w:tr></w:tbl>" * 300
    return x[:s] + nest + "<w:p/>" + x[s:]
rewrite(base, "deep_nesting.docx", edit_parts={"word/document.xml": deep_tables})
expected["deep_nesting.docx"] = {"accept": False, "reason": "UNSAFE_XML"}

# --- added after the Opus security review (revision 2) ------------------------
def swap(src_bytes, name, parts_fn):
    """Rebuild a zip from a dict of parts that parts_fn may edit (safe: fresh ZipInfo objects)."""
    zin = zipfile.ZipFile(io.BytesIO(src_bytes)); parts = {n: zin.read(n) for n in zin.namelist()}
    parts_fn(parts); buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        for n, b in parts.items(): zout.writestr(n, b)
    with open(os.path.join(OUT, name), "wb") as f: f.write(buf.getvalue())

MAIN_CT = b"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
W_NS = b"http://schemas.openxmlformats.org/wordprocessingml/2006/main"

# R1: package relationships point LibreOffice at a main document the gate never parsed
def redirect(p):
    p["_rels/.rels"] = p["_rels/.rels"].replace(b'Target="word/document.xml"', b'Target="word/main.bin"')
    p["[Content_Types].xml"] = p["[Content_Types].xml"].replace(
        b"</Types>", b'<Override PartName="/word/main.bin" ContentType="' + MAIN_CT + b'"/></Types>')
    p["word/main.bin"] = p["word/document.xml"].replace(b"Jane Doe", b"Unchecked Content")
    p["word/_rels/main.bin.rels"] = p["word/_rels/document.xml.rels"]
swap(base, "main_doc_redirect.docx", redirect)
expected["main_doc_redirect.docx"] = {"accept": False, "reason": "NOT_A_DOCX"}

# R2: tracked change hidden behind a different namespace prefix
_tc = open(os.path.join(OUT, "tracked_changes.docx"), "rb").read()
def prefixed(p):
    d = p["word/document.xml"]
    d = re.sub(rb'<w:ins w:id="901" w:author="Editor" w:date="([^"]+)">',
               b'<x:ins xmlns:x="' + W_NS + rb'" x:id="901" x:author="Editor" x:date="\1">', d, count=1)
    p["word/document.xml"] = d.replace(b"</w:ins>", b"</x:ins>", 1)
swap(_tc, "tracked_changes_prefixed.docx", prefixed)
expected["tracked_changes_prefixed.docx"] = {"accept": False, "reason": "TRACKED_CHANGES"}

# R2b: tracked change in a header, not the body
_hd = base_document(); _hd.sections[0].header.paragraphs[0].text = "Jane Doe — Resume"
_hdb = io.BytesIO(); _hd.save(_hdb)
def tracked_header(p):
    h = next(n for n in p if n.startswith("word/header") and n.endswith(".xml"))
    x = p[h]; i = x.index(b"<w:r>"); j = x.index(b"</w:r>", i) + 6
    p[h] = x[:i] + b'<w:ins w:id="902" w:author="Editor" w:date="2026-01-01T00:00:00Z">' + x[i:j] + b"</w:ins>" + x[j:]
swap(_hdb.getvalue(), "tracked_changes_header.docx", tracked_header)
expected["tracked_changes_header.docx"] = {"accept": False, "reason": "TRACKED_CHANGES"}

# R3: same part name in different case (OPC part names are case-insensitive)
swap(base, "case_duplicate.docx", lambda p: p.__setitem__("Word/Document.xml", p["word/document.xml"]))
expected["case_duplicate.docx"] = {"accept": False, "reason": "DUPLICATE_ENTRY"}

# R4: embedded OLE object stored outside word/embeddings/, found by relationship type
def ole_elsewhere(p):
    p["word/media/object1.bin"] = b"\xd0\xcf\x11\xe0fake-ole"
    p["[Content_Types].xml"] = p["[Content_Types].xml"].replace(
        b"</Types>", b'<Default Extension="bin" ContentType="application/vnd.openxmlformats-officedocument.oleObject"/></Types>')
    p["word/_rels/document.xml.rels"] = p["word/_rels/document.xml.rels"].replace(b"</Relationships>",
        b'<Relationship Id="rIdOle1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/oleObject" '
        b'Target="media/object1.bin"/></Relationships>')
swap(base, "ole_elsewhere.docx", ole_elsewhere)
expected["ole_elsewhere.docx"] = {"accept": False, "reason": "EMBEDDED_OBJECT"}

# R4b: altChunk (imports a foreign document into the body)
def altchunk(p):
    p["word/afchunk.htm"] = b"<html><body><p>Imported content</p></body></html>"
    p["[Content_Types].xml"] = p["[Content_Types].xml"].replace(
        b"</Types>", b'<Default Extension="htm" ContentType="application/xhtml+xml"/></Types>')
    p["word/_rels/document.xml.rels"] = p["word/_rels/document.xml.rels"].replace(b"</Relationships>",
        b'<Relationship Id="rIdAlt1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/aFChunk" '
        b'Target="afchunk.htm"/></Relationships>')
swap(base, "altchunk.docx", altchunk)
expected["altchunk.docx"] = {"accept": False, "reason": "EMBEDDED_OBJECT"}

# R4c: macro content type written in lower case
swap(base, "macro_lowercase_ct.docx", lambda p: p.__setitem__("[Content_Types].xml", p["[Content_Types].xml"].replace(
    MAIN_CT, b"application/vnd.ms-word.document.macroenabled.main+xml")))
expected["macro_lowercase_ct.docx"] = {"accept": False, "reason": "MACROS"}

# R5: linked-content field (LibreOffice 24.2 doesn't resolve it; rejected as defense in depth)
swap(base, "field_includetext.docx", lambda p: p.__setitem__("word/document.xml", p["word/document.xml"].replace(
    b"<w:body>", b'<w:body><w:p><w:fldSimple w:instr=" INCLUDETEXT &quot;/etc/os-release&quot; ">'
                 b"<w:r><w:t>cached</w:t></w:r></w:fldSimple></w:p>", 1)))
expected["field_includetext.docx"] = {"accept": False, "reason": "EXTERNAL_RESOURCE"}

# R5b: a URL target without TargetMode="External"
swap(base, "url_target_internal_mode.docx", lambda p: p.__setitem__("word/_rels/document.xml.rels",
    p["word/_rels/document.xml.rels"].replace(b"</Relationships>",
        b'<Relationship Id="rIdImg9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" '
        b'Target="http://tracker.example.com/pixel.png"/></Relationships>')))
expected["url_target_internal_mode.docx"] = {"accept": False, "reason": "EXTERNAL_RESOURCE"}

# R6: a part with no declared content type
swap(base, "untyped_part.docx", lambda p: p.__setitem__("word/extra.zzz", b"<x/>"))
expected["untyped_part.docx"] = {"accept": False, "reason": "NOT_A_DOCX"}

with open(os.path.join(OUT, "expected.json"), "w") as f:
    json.dump(expected, f, indent=1, sort_keys=True)
print("wrote", len(expected), "fixtures to", OUT)