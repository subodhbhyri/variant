"""Python reference for PHASE2_SPEC.md section 2 (upload gate), revision 2 after
the Opus security review. Reproduces every expected reason in
fixtures/phase2/expected.json. gate(path) -> reason code or None."""
import zipfile, io, re
from lxml import etree

MAX_FILE = 2 * 1024 * 1024; MAX_ENTRIES = 200; MAX_TOTAL = 20 * 1024 * 1024
MAX_ENTRY = 10 * 1024 * 1024; MAX_RATIO = 200; MAX_DEPTH = 100
OLE = bytes.fromhex("D0CF11E0A1B11AE1")
W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
CT = "{http://schemas.openxmlformats.org/package/2006/content-types}"
REL_HYPERLINK = "hyperlink"
REL_MACRO = {"vbaproject", "wordvbadata"}
REL_EMBED = {"oleobject", "package", "control", "activexcontrol", "activexcontrolbinary", "afchunk"}
# Word main-document types, compared case-insensitively. The macro-enabled ones count as
# "a Word document" here so that check 9 can reject them with the clearer reason MACROS.
MAIN_TYPES = {t.lower() for t in (
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
    "application/vnd.ms-word.document.macroEnabled.main+xml",
    "application/vnd.ms-word.template.macroEnabledTemplate.main+xml")}
LINKED_FIELDS = {"INCLUDETEXT", "INCLUDEPICTURE", "LINK", "DDE", "DDEAUTO", "IMPORT"}
SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*:")

def safe_path(n):
    return not (n == "" or n.startswith(("/", "\\")) or "\\" in n or re.match(r"^[A-Za-z]:", n)
                or any(seg in ("..", ".") for seg in n.split("/")))

def parse_safe(xml):
    """Return a parsed tree, or None if it has a DOCTYPE/entity, is malformed, or is too deep."""
    if b"<!DOCTYPE" in xml or b"<!ENTITY" in xml:
        return None
    try:
        parser = etree.XMLParser(resolve_entities=False, no_network=True, load_dtd=False, huge_tree=False)
        root = etree.fromstring(xml, parser)
    except etree.XMLSyntaxError:
        return None
    stack = [(root, 1)]
    while stack:
        el, d = stack.pop()
        if d > MAX_DEPTH:
            return None
        stack.extend((c, d + 1) for c in el if isinstance(c.tag, str))
    return root

def gate(path):
    data = open(path, "rb").read()
    try:
        return _gate(data)
    except Exception:          # the gate never crashes: anything unexpected is "not a docx"
        return "NOT_A_DOCX"

def _gate(data):
    if len(data) > MAX_FILE: return "FILE_TOO_LARGE"                                   # 1
    if data[:8] == OLE: return "ENCRYPTED_OR_LEGACY"                                   # 2
    try: z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile: return "NOT_A_DOCX"                                     # 3
    infos = [i for i in z.infolist() if not i.filename.endswith("/")]
    if len(z.infolist()) > MAX_ENTRIES: return "TOO_MANY_ENTRIES"                      # 4
    names = [i.filename for i in infos]
    if len({n.lower() for n in names}) != len(names): return "DUPLICATE_ENTRY"        # 5 (case-insensitive)
    if any(not safe_path(n) for n in names): return "UNSAFE_PATH"                      # 6
    if (sum(i.file_size for i in infos) > MAX_TOTAL
            or any(i.file_size > MAX_ENTRY or i.file_size / max(i.compress_size, 1) > MAX_RATIO for i in infos)):
        return "ZIP_BOMB"                                                               # 7
    parts = {n: z.read(n) for n in names}
    # 8. a well-formed Word package
    if "[Content_Types].xml" not in parts or "word/document.xml" not in parts or "_rels/.rels" not in parts:
        return "NOT_A_DOCX"
    ctroot = parse_safe(parts["[Content_Types].xml"])
    if ctroot is None: return "UNSAFE_XML"
    defaults = {e.get("Extension", "").lower(): e.get("ContentType", "") for e in ctroot.findall(CT + "Default")}
    overrides = {e.get("PartName", "").lstrip("/").lower(): e.get("ContentType", "") for e in ctroot.findall(CT + "Override")}
    def ctype(n):
        return overrides.get(n.lower()) or defaults.get(n.rsplit(".", 1)[-1].lower() if "." in n else "")
    if any(ctype(n) is None for n in names if n != "[Content_Types].xml"): return "NOT_A_DOCX"
    pkg_rels = parse_safe(parts["_rels/.rels"])
    if pkg_rels is None: return "UNSAFE_XML"
    mains = [r.get("Target", "").lstrip("/") for r in pkg_rels if r.get("Type", "").endswith("/officeDocument")]
    if mains != ["word/document.xml"] or (ctype("word/document.xml") or "").lower() not in MAIN_TYPES: return "NOT_A_DOCX"
    # 9. macros (by name, content type, and relationship type)
    all_ct = " ".join(list(defaults.values()) + list(overrides.values())).lower()
    rel_roots = {n: parse_safe(parts[n]) for n in names if n.endswith(".rels")}
    rel_types = [r.get("Type", "").rsplit("/", 1)[-1].lower() for root in rel_roots.values() if root is not None for r in root]
    if (any(n.lower().endswith("vbaproject.bin") for n in names) or "macroenabled" in all_ct
            or "vbaproject" in all_ct or REL_MACRO & set(rel_types)): return "MACROS"
    # 10. embedded objects (by path and relationship type)
    if any(n.startswith(("word/embeddings/", "word/activeX/")) for n in names) or REL_EMBED & set(rel_types):
        return "EMBEDDED_OBJECT"
    # 11. external resources: external mode, or a URI/UNC target, on anything but a hyperlink
    for root in rel_roots.values():
        if root is None: continue
        for r in root:
            if r.get("Type", "").rsplit("/", 1)[-1].lower() == REL_HYPERLINK: continue
            t = r.get("Target", "")
            if r.get("TargetMode") == "External" or SCHEME.match(t) or t.startswith(("//", "\\\\")):
                return "EXTERNAL_RESOURCE"
    # 12. every XML part (by content type) and every .rels part parses safely
    trees = {}
    for n in names:
        if n.endswith(".rels") or (ctype(n) or "").endswith("xml"):
            t = parse_safe(parts[n])
            if t is None: return "UNSAFE_XML"
            trees[n] = t
    # 13. story parts, namespace-aware: linked fields, then tracked changes
    stories = {n: t for n, t in trees.items() if n.startswith("word/") and not n.endswith(".rels")}
    for t in stories.values():
        instr = [e.text or "" for e in t.iter("{%s}instrText" % W)] + \
                [e.get("{%s}instr" % W, "") for e in t.iter("{%s}fldSimple" % W)]
        if any(s.split() and s.split()[0].upper() in LINKED_FIELDS for s in instr): return "EXTERNAL_RESOURCE"
    for t in stories.values():
        for tag in ("ins", "del", "moveFrom", "moveTo"):
            if next(t.iter("{%s}%s" % (W, tag)), None) is not None: return "TRACKED_CHANGES"
    return None
