"""Python reference for PHASE4_SPEC.md section 5 (truthfulness guard).
guard(variant, source_texts, budget_chars, skills) -> list of reason codes ([] = pass).
Deterministic, no network, runs before any render."""
import json, re

WORD_NUMS = {w: i for i, w in enumerate(
    "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen "
    "sixteen seventeen eighteen nineteen twenty".split())}
# Number words: not "one" (too ambiguous: "one of the"), and not inside a hyphenated word ("zero-downtime", "two-phase").
_WORDS = [w for w in WORD_NUMS if w != "one"]
NUM = re.compile(r"(?<![\w.])(\d+(?:[.,]\d+)*)\s*([kKmMbB])?(?![\w])|(?<![\w-])(" + "|".join(_WORDS) + r")(?![\w-])", re.I)
URL = re.compile(r"https?://|www\.|\b[\w.+-]+@[\w-]+\.\w|\b[\w-]+\.(?:com|io|dev|org|net|ai|app|co|me|xyz)\b(?:/\S*)?", re.I)
FIRST_PERSON = re.compile(r"(?<![\w'])(I|me|my|mine|we|our|ours|us)(?![\w'])")

def numbers(text):
    out = set()
    for m in NUM.finditer(text):
        if m.group(3):
            out.add(float(WORD_NUMS[m.group(3).lower()])); continue
        v = float(m.group(1).replace(",", ""))
        mult = {"k": 1e3, "m": 1e6, "b": 1e9}.get((m.group(2) or "").lower(), 1)
        out.add(v * mult)
    return out

def _alias_re(alias):
    flags = 0 if len(alias) <= 2 else re.I          # short aliases (Go, JS, TS, S3) are case-sensitive
    return re.compile(r"(?<![\w+#.])" + re.escape(alias) + r"(?![\w+#])", flags)

def load_skills(path):
    d = json.load(open(path)); d.pop("_note", None)
    return {canon: [_alias_re(a) for a in aliases] for canon, aliases in d.items()}

def techs(text, skills):
    found = set()
    for canon, pats in skills.items():
        if any(p.search(text) for p in pats):
            found.add(canon)
    # a longer canonical term containing a shorter one ("React Native" vs "React") keeps both; fine for a subset check
    return found

# ---------------------------------------------------------------------------
# Grounding (revision 4): catches wholesale invented sentences, which contain no
# checkable number or technology. Share of a variant's content words that also
# occur in the sources, with a crude stem so "reducing"/"reduced" match.
STOP = set("""a an the and or but of in on at to for from by with without via into onto over under
as is are was were be been being this that these those it its their them they he she his her
our we i my me you your up down out off than then so such very more most less least same across
while during after before about per each all any both either neither not no nor only own also""".split())
_WORD = re.compile(r"[A-Za-z][A-Za-z'\-]+")

def _stem(w):
    w = w.lower().strip("'-")
    for suf in ("ations", "ation", "ising", "izing", "ised", "ized", "ings", "ing", "edly", "ed", "es", "s", "ly"):
        if w.endswith(suf) and len(w) - len(suf) >= 4:
            return w[: -len(suf)]
    return w

def content_stems(text):
    return [_stem(w) for w in _WORD.findall(text) if w.lower() not in STOP and len(w) > 2]

def _grounded(stem, source_stems):
    if stem in source_stems: return True
    if len(stem) >= 5:                        # prefix match: "parallel" ~ "parallelis", "cut" is too short to use
        return any(s.startswith(stem[:5]) for s in source_stems if len(s) >= 5)
    return False

def grounding(variant, source_texts):
    src = set(s for t in source_texts for s in content_stems(t))
    words = content_stems(variant)
    if not words: return 1.0
    return sum(_grounded(w, src) for w in words) / len(words)

GROUNDING_MIN = 0.40   # measured: fabricated sentences 0.00-0.08, faithful live variants >= 0.57

def guard(variant, source_texts, budget_chars, skills):
    reasons = []
    v = variant.strip()
    if not v: return ["EMPTY"]
    if "\n" in variant or "\r" in variant: reasons.append("MULTILINE")
    if URL.search(v): reasons.append("URL")
    if FIRST_PERSON.search(v): reasons.append("FIRST_PERSON")
    if budget_chars is not None and len(v) > budget_chars: reasons.append("OVER_BUDGET")
    source = "\n".join(source_texts)
    missing_nums = sorted(n for n in numbers(v) if n not in numbers(source))
    for n in missing_nums: reasons.append("UNSUPPORTED_NUMBER:" + (str(int(n)) if n == int(n) else str(n)))
    for t in sorted(techs(v, skills) - techs(source, skills)): reasons.append("UNSUPPORTED_TECH:" + t)
    if grounding(v, source_texts) < GROUNDING_MIN: reasons.append("UNGROUNDED")
    return reasons


def consistent(shorter, longer):
    """A candidate's versions must describe the same facts: the shorter version's
    content words must be grounded in the longer version."""
    return grounding(shorter, [longer]) >= GROUNDING_MIN