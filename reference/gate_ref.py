"""Python reference for PHASE2_SPEC.md section 2 (upload gate). Reproduces every
expected reason in fixtures/phase2/expected.json. gate(path) -> reason code or None."""
import zipfile, io, json, os, re
from lxml import etree
MAX_FILE=2*1024*1024; MAX_ENTRIES=200; MAX_TOTAL=20*1024*1024; MAX_ENTRY=10*1024*1024; MAX_RATIO=200; MAX_DEPTH=100
OLE=bytes.fromhex("D0CF11E0A1B11AE1")
HYPERLINK="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
def safe_path(n):
    return not (n.startswith(("/","\\")) or "\\" in n or re.match(r"^[A-Za-z]:",n) or any(seg in ("..",".") for seg in n.split("/")) or n=="" )
def depth_ok(xml):
    # streaming depth check, no DTD
    parser=etree.XMLPullParser(events=("start","end"), resolve_entities=False, no_network=True, load_dtd=False)
    d=0
    try:
        if b"<!DOCTYPE" in xml[:2000] or b"<!ENTITY" in xml: return False
        parser.feed(xml); parser.close()
        for ev,_ in parser.read_events():
            d = d+1 if ev=="start" else d-1
            if d>MAX_DEPTH: return False
        return True
    except etree.XMLSyntaxError:
        return False
def gate(path):
    data=open(path,'rb').read()
    if len(data)>MAX_FILE: return "FILE_TOO_LARGE"
    if data[:8]==OLE: return "ENCRYPTED_OR_LEGACY"
    try: z=zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile: return "NOT_A_DOCX"
    infos=z.infolist()
    if len(infos)>MAX_ENTRIES: return "TOO_MANY_ENTRIES"
    names=[i.filename for i in infos]
    if len(set(names))!=len(names): return "DUPLICATE_ENTRY"
    if any(not safe_path(n) for n in names): return "UNSAFE_PATH"
    if sum(i.file_size for i in infos)>MAX_TOTAL or any(i.file_size>MAX_ENTRY or i.file_size/max(i.compress_size,1)>MAX_RATIO for i in infos): return "ZIP_BOMB"
    if "[Content_Types].xml" not in names or "word/document.xml" not in names: return "NOT_A_DOCX"
    ct=z.read("[Content_Types].xml")
    if any(n.lower().endswith("vbaproject.bin") for n in names) or b"macroEnabled" in ct: return "MACROS"
    if any(n.startswith(("word/embeddings/","word/activeX/")) for n in names): return "EMBEDDED_OBJECT"
    for n in names:
        if n.endswith(".rels"):
            for rel in etree.fromstring(z.read(n)):
                if rel.get("TargetMode")=="External" and rel.get("Type")!=HYPERLINK: return "EXTERNAL_RESOURCE"
    for n in names:
        if n.endswith(".xml") or n.endswith(".rels"):
            if not depth_ok(z.read(n)): return "UNSAFE_XML"
    doc=z.read("word/document.xml")
    if re.search(rb"<w:(ins|del|moveFrom|moveTo)[ >]",doc): return "TRACKED_CHANGES"
    return None
