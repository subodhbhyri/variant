"""Python reference for PHASE5_SPEC.md: job-description parsing, scoring,
assembly of resume #1, alternatives #2/#3, missing skills and the cache
fingerprint. Deterministic. Embeddings are injected: fixtures use
`fake_similarity` (bag-of-stems cosine); production uses MiniLM.
"""
import itertools, json, math, re
from collections import Counter
from guard_ref import load_skills, techs, content_stems, grounding

W_KEYWORD, W_EMBED = 0.7, 0.3
REUSE_FACTOR = 0.5            # alternatives: a reused item scores half, so relevance order survives
MIN_DIFFERENCE = 0.25         # an alternative must differ in >= 25% of slots + positions
SAME_ACHIEVEMENT = 0.5        # two job candidates sharing >= 50% content words are one achievement
DETAIL_WEIGHT = 0.3           # project score = mean bullet score + 0.3 x keyword coverage of its stack

REQUIRED = re.compile(r"\b(requirements?|qualifications?|must[- ]haves?|what you('|’)ll need|you have|minimum)\b", re.I)
PREFERRED = re.compile(r"\b(nice[- ]to[- ]haves?|preferred|bonus|plus|good to have)\b", re.I)
HEADING = re.compile(r"^\s*[A-Z][^.!?]{0,60}:\s*$|^\s*[A-Z][A-Za-z ,'’&/-]{2,40}$")

# --- 5.1 parse ---------------------------------------------------------------
def parse_jd_v1(text, skills):  # revision 1, kept for reference only
    """-> {'title', 'skills': {canon: weight}, 'requirement_text'}"""
    lines = [l.rstrip() for l in text.strip().splitlines()]
    title = next((l.strip() for l in lines if l.strip()), "")
    weight, req_lines, found = 0.3, [], {}
    for l in lines[1:]:
        s = l.strip()
        if not s: continue
        if HEADING.match(s) and (REQUIRED.search(s) or PREFERRED.search(s) or s.endswith(":")):
            weight = 1.0 if REQUIRED.search(s) else 0.5 if PREFERRED.search(s) else 0.3
            continue
        if weight >= 0.5: req_lines.append(s)
        for t in techs(s, skills):
            found[t] = max(found.get(t, 0.0), weight)
    for t in techs(title, skills):               # a skill in the title counts as required
        found[t] = 1.0
    return {"title": title, "skills": dict(sorted(found.items())), "requirement_text": " ".join(req_lines)}

# --- embeddings (injected) -----------------------------------------------------
def fake_similarity(a, b):
    """Fixture stand-in for MiniLM: cosine of content-stem counts, in [0, 1]."""
    ca, cb = Counter(content_stems(a)), Counter(content_stems(b))
    dot = sum(ca[k] * cb[k] for k in ca)
    na, nb = math.sqrt(sum(v * v for v in ca.values())), math.sqrt(sum(v * v for v in cb.values()))
    return 0.0 if na == 0 or nb == 0 else dot / (na * nb)

# --- 5.3 score -----------------------------------------------------------------
def keyword_coverage(text, jd, skills):
    total = sum(jd["skills"].values())
    if total == 0: return 0.0
    return sum(w for t, w in jd["skills"].items() if t in techs(text, skills)) / total

def score(text, jd, skills, sim):
    return round(W_KEYWORD * keyword_coverage(text, jd, skills) + W_EMBED * sim(text, jd["requirement_text"]), 4)

# --- 5.4 assemble --------------------------------------------------------------
def fill_job(slots, candidates, jd, skills, sim, penalty=frozenset()):
    """Best unused candidate per slot; among slots of one length, higher scores go first.
    -> list of candidate ids (None = keep the original bullet)."""
    def val(c, L):
        v = score(c["variants"][str(L)], jd, skills, sim)
        return round(v * (REUSE_FACTOR if c["id"] in penalty else 1.0), 4)
    def same(a, b):   # one achievement written two ways (Phase 4 allows restating)
        ta, tb = " ".join(a["variants"].values()), " ".join(b["variants"].values())
        return grounding(ta, [tb]) >= SAME_ACHIEVEMENT or grounding(tb, [ta]) >= SAME_ACHIEVEMENT
    chosen, taken = [None] * len(slots), []
    for L in sorted(set(slots)):
        for i in [i for i, s in enumerate(slots) if s == L]:
            ranked = sorted((c for c in candidates if str(L) in c["variants"]
                             and not any(same(c, t) for t in taken)),
                            key=lambda c: (-val(c, L), c["id"]))
            if ranked:
                chosen[i] = ranked[0]["id"]; taken.append(ranked[0])
    return chosen

def place_project(project, shape, jd, skills, sim):
    """Best ordering of k of the project's bullets into the position's slots.
    -> (bullet indices, mean bullet score) or None if the project can't fill this shape."""
    bl = project["bullets"]
    if len(bl) < len(shape): return None
    best = None
    for perm in itertools.permutations(range(len(bl)), len(shape)):
        if any(str(L) not in bl[b] for b, L in zip(perm, shape)): continue
        total = sum(score(bl[b][str(L)], jd, skills, sim) for b, L in zip(perm, shape))
        key = (-round(total, 4), perm)
        if best is None or key < best[0]: best = (key, perm, total / len(shape))
    return None if best is None else (list(best[1]), round(best[2], 4))

def project_score(project, position, jd, skills, sim):
    placed = place_project(project, position["shape"], jd, skills, sim)
    if placed is None: return None
    s = placed[1]
    if position.get("shows_detail"):
        s += DETAIL_WEIGHT * keyword_coverage(project.get("detail") or "", jd, skills)
    return round(s, 4), placed[0]

def assign_projects(positions, library, jd, skills, sim, penalty=frozenset(), must_include=None):
    """Best project in the top position (decision 2): among all feasible
    assignments (each project at most once), maximize the first position's
    score, then the second's, and so on. Reused items score x REUSE_FACTOR."""
    best = None
    ids = [p["id"] for p in library]
    for combo in itertools.permutations(range(len(library)), len(positions)):
        scores, detail = [], []
        for pos, lib_i in zip(positions, combo):
            r = project_score(library[lib_i], pos, jd, skills, sim)
            if r is None: break
            eff = round(r[0] * (REUSE_FACTOR if ids[lib_i] in penalty else 1.0), 4)
            scores.append(eff)
            detail.append({"position": pos["id"], "project": ids[lib_i], "bullets": r[1], "score": r[0]})
        else:
            if must_include and must_include not in [d["project"] for d in detail]: continue
            key = (tuple(-x for x in scores), tuple(d["project"] for d in detail))
            if best is None or key < best[0]: best = (key, detail)
    return [] if best is None else best[1]

def order_stack(detail, jd, skills):
    items = [x.strip() for x in (detail or "").split(",") if x.strip()]
    return sorted(items, key=lambda it: -max((jd["skills"].get(t, 0) for t in techs(it, skills)), default=0))

def assemble(shapes, job_cands, library, jd, skills, sim, penalty=frozenset()):
    job = fill_job(shapes["job"]["slots"], job_cands, jd, skills, sim, penalty)
    projects = assign_projects(shapes["positions"], library, jd, skills, sim, penalty)
    by_id = {p["id"]: p for p in library}
    for d in projects:
        d["stack"] = order_stack(by_id[d["project"]].get("detail"), jd, skills)
    return {"job": job, "projects": projects}

def achievement_groups(candidates):
    """Cluster job candidates that restate one achievement -> {candidate id: group id}."""
    def same(a, b):
        ta, tb = " ".join(a["variants"].values()), " ".join(b["variants"].values())
        return grounding(ta, [tb]) >= SAME_ACHIEVEMENT or grounding(tb, [ta]) >= SAME_ACHIEVEMENT
    group = {}
    for c in sorted(candidates, key=lambda c: c["id"]):
        g = next((group[o["id"]] for o in candidates if o["id"] in group and same(c, o)), c["id"])
        group[c["id"]] = g
    return group

def total_score(resume, shapes, job_cands, jd, skills, sim):
    by_id = {c["id"]: c for c in job_cands}
    js = sum(score(by_id[c]["variants"][str(L)], jd, skills, sim)
             for c, L in zip(resume["job"], shapes["job"]["slots"]) if c)
    return round(js + sum(d["score"] for d in resume["projects"]), 4)

def top3(shapes, job_cands, library, jd, skills, sim):
    """#1 = assemble(). Each alternative = the best resume that INCLUDES one item #1
    left out (an unused project, or an unused job achievement), everything else
    re-optimized. Ranked by total score; at most 2; each labelled with what it adds."""
    first = assemble(shapes, job_cands, library, jd, skills, sim)
    groups = achievement_groups(job_cands)
    used_groups = {groups[c] for c in first["job"] if c}
    used_projects = {d["project"] for d in first["projects"]}
    options = []
    for p in library:                                   # force an unused project in, best-first as in #1 (D3)
        if p["id"] in used_projects: continue
        projs = assign_projects(shapes["positions"], library, jd, skills, sim, must_include=p["id"])
        if len(projs) != len(shapes["positions"]): continue
        cand = {"job": first["job"], "projects": projs}
        dropped = sorted(used_projects - {d["project"] for d in projs})
        options.append((total_score(cand, shapes, job_cands, jd, skills, sim),
                        "includes " + p["id"] + (" instead of " + ", ".join(dropped) if dropped else ""), cand))
    for g in sorted(set(groups.values()) - used_groups):   # force an unused achievement in
        members = [c for c in job_cands if groups[c["id"]] == g]
        slots = shapes["job"]["slots"]
        best = None
        for i, L in enumerate(slots):
            m = sorted((c for c in members if str(L) in c["variants"]),
                       key=lambda c: (-score(c["variants"][str(L)], jd, skills, sim), c["id"]))
            if not m: continue
            job = list(first["job"]); job[i] = m[0]["id"]
            cand = {"job": job, "projects": first["projects"]}
            t = total_score(cand, shapes, job_cands, jd, skills, sim)
            if best is None or -t < -best[0]: best = (t, cand)
        if best:
            options.append((best[0], "includes achievement " + g, best[1]))
    options.sort(key=lambda o: (-o[0], o[1]))
    out = [dict(first, label="best match")]
    for t, label, r in options[:2]:
        out.append(dict(r, label=label))
    return out

# --- 5.8 missing skills ----------------------------------------------------------
def missing_skills(jd, material_texts, skills):
    have = set().union(*[techs(t, skills) for t in material_texts]) if material_texts else set()
    return sorted(t for t, w in jd["skills"].items() if w >= 1.0 and t not in have)

# --- 5.7 cache ---------------------------------------------------------------------
def weighted_jaccard(a, b):
    keys = set(a) | set(b)
    num = sum(min(a.get(k, 0), b.get(k, 0)) for k in keys); den = sum(max(a.get(k, 0), b.get(k, 0)) for k in keys)
    return 1.0 if den == 0 else num / den

def cache_decision(jd_a, jd_b, sim):
    j = weighted_jaccard(jd_a["skills"], jd_b["skills"])
    if jd_a["skills"] == jd_b["skills"] or j >= 0.9: return "HIT", round(j, 4)
    if j >= 0.8 and sim(jd_a["requirement_text"], jd_b["requirement_text"]) >= 0.95: return "HIT", round(j, 4)
    return "MISS", round(j, 4)

# --- 7.1 alias suggestion rules (revision 2) ------------------------------------------
# MiniLM cosine can't separate aliases from related-but-different skills (P5-T7:
# React ~ React Native 0.74 > TS ~ TypeScript 0.40). Strict spelling rules can:
# 7/10 fixture aliases, 0/12 hard negatives.
ALIAS_AFFIXES = ("js", "lang", "ful")

def _norm_term(t):
    """Lower-case; drop spaces, hyphens and dots; KEEP + and # (C, C++ and C# stay different)."""
    return re.sub(r"[\s.\-]", "", t.lower())

def _parts(t):
    return [p for p in re.findall(r"[A-Z][a-z]+|[a-z]+|[A-Z]+(?![a-z])|\d+", t) if p]

def alias_rules(a, b):
    """-> sorted list of rule names under which a and b are spellings of one term."""
    na, nb = _norm_term(a), _norm_term(b)
    hits = set()
    if na == nb: hits.add("punctuation")
    for x, y in ((na, nb), (nb, na)):
        for suf in ALIAS_AFFIXES:
            if x.endswith(suf) and len(y) >= 2 and x[: -len(suf)] == y: hits.add("affix")
        m = re.fullmatch(r"([a-z])(\d+)([a-z])", x)          # K8s = k + 8 letters + s
        if m and len(y) == int(m.group(2)) + 2 and y[0] == m.group(1) and y[-1] == m.group(3):
            hits.add("numeronym")
    for s, l in ((a, b), (b, a)):                           # TS = Type + Script
        p = _parts(l)
        if len(p) >= 2 and s.isupper() and len(s) == len(p) and "".join(w[0] for w in p).upper() == s:
            hits.add("initialism")
    return sorted(hits)

# --- 5.1 parser, revision 3 (measured on 20 real postings) -------------------------
BOILERPLATE_TITLE = re.compile(r"^(?:(job description( summary)?|description|overview|general information|"
                               r"company|summary|apply|job details|position description)\s*:?\s*$|about\b)", re.I)
TITLE_LABEL = re.compile(r"^\s*(?:job title|role|position|title)\s*[:\-–]\s*(.+?)\s*$", re.I)
ROLE_NOUN = r"(?:engineer|developer|sdet|programmer|architect|scientist|analyst|tester|assistant)s?\b"
TITLE_PHRASE = re.compile(r"\b(?:as an?|seeking (?:an?\s+)?|hiring (?:an?\s+)?|looking for (?:an?\s+)?|join us as an?)\s*((?:[A-Za-z0-9/+#.&()-]+\s+){0,6}\b" + ROLE_NOUN + r")", re.I)
SECTION_RULES = [   # (kind, pattern) checked in order on heading-like lines
    ("ignore",    re.compile(r"\b(benefits?|perks|salary|compensation|pay (range|scale)|equal opportunity|eeo\b|privacy|disclosures?|"
                             r"physical|work environment|interview process|about (?!the role|you|the job)\w|our (purpose|virtues|stands)|"
                             r"who thrives|why work|why join|e-verify|accommodation)", re.I)),
    ("preferred", re.compile(r"\b(nice[- ]to[- ]haves?|preferred|bonus|desir(ed|able)|additional skills|good to have|a plus)\b", re.I)),
    ("required",  re.compile(r"\b(requirements?|required|qualifications?|must[- ]haves?|minimum|what you('|’)ll need|what you (bring|have)|"
                             r"who you are|about you|what we('|’)re looking for|skills|knowledge|education and experience|you have)\b", re.I)),
    ("duties",    re.compile(r"\b(responsibilit|what you('|’)ll do|what you will (do|work on)|duties|essential (job )?functions|"
                             r"the opportunity|the role|role description|your role|how we work|outcomes|day to day)", re.I)),
]
WEIGHT = {"required": 1.0, "preferred": 0.5, "duties": 0.5, "other": 0.3, "ignore": 0.0}
LINE_PREFERRED = re.compile(r"\b(preferred|a plus|is a plus|bonus|nice to have|desired|desirable|not required)\b", re.I)

def _heading_kind(line):
    s = line.strip().strip("()").strip()
    if not s or len(s) > 70 or s.endswith((".", "!", "?")): return None
    if ":" in s.rstrip(":"): return None                       # "Education: Bachelor's preferred" is a label, not a heading
    if re.fullmatch(r"skills|job details", s.rstrip(":").strip(), re.I): return "other"   # job-board tag lists
    words = s.rstrip(":").split()
    looks = s.endswith(":") or len(words) <= 6 or s.isupper()
    if not looks: return None
    for kind, pat in SECTION_RULES:
        if pat.search(s): return kind
    return "other" if s.endswith(":") else None

def extract_title(lines):
    """Explicit label > first meaningful line naming a role > 'As a X' phrase > first meaningful line."""
    head = [l.strip() for l in lines[:40] if l.strip()]
    for l in head:
        m = TITLE_LABEL.match(l)
        if m and re.search(ROLE_NOUN, m.group(1), re.I): return m.group(1)
    for l in head[:3]:
        if BOILERPLATE_TITLE.match(l): continue
        if (re.search(r"\b" + ROLE_NOUN, l, re.I) and len(l) <= 90 and len(l.split()) <= 10
                and not re.search(r"[.!?]$", l) and not TITLE_PHRASE.search(l)): return l
    for l in head[:8]:                                         # "Job Area: ... > IT Software Developer"
        m = re.search(r"(?:^|[>:,])\s*([^>:,]{2,60}\b" + ROLE_NOUN + r")\s*$", l, re.I)
        if m and not BOILERPLATE_TITLE.match(l): return m.group(1).strip()
    found = []                                                  # "As a Software Engineer at ..." phrases
    for l in head:
        for m in TITLE_PHRASE.finditer(l):
            t = re.sub(r"^(skilled|passionate|talented|motivated)\s+", "", m.group(1), flags=re.I).strip()
            t = re.sub(r"(?i)(engineer|developer|programmer|architect|scientist|analyst|tester)s$", r"\1", t)
            found.append(t[0].upper() + t[1:])
    multi = [t for t in found if len(t.split()) >= 2]           # prefer "Backend Engineer" over a bare "Engineer"
    if multi: return multi[0]
    if found: return found[0]
    return ""                                                   # no recognisable title: none, never a guessed line

def parse_jd_v3(text, skills):
    lines = [l.rstrip() for l in text.strip().splitlines()]
    title = extract_title(lines)
    kind, found, req_lines = "other", {}, []
    for l in lines:
        s = l.strip()
        if not s: continue
        k = _heading_kind(s)
        if k: kind = k; continue
        w = WEIGHT[kind]
        if w == 0: continue
        if kind == "required" and LINE_PREFERRED.search(s): w = WEIGHT["preferred"]
        if w >= 0.5: req_lines.append(s)
        for t in techs(s, skills):
            found[t] = max(found.get(t, 0.0), w)
    for t in techs(title, skills): found[t] = 1.0
    return {"title": title, "skills": dict(sorted(found.items())), "requirement_text": " ".join(req_lines)}


parse_jd = parse_jd_v3   # revision 3 is the parser (PHASE5_SPEC section 1)
