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
    return reasons
