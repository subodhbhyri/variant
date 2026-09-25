# Phase 1 Spec — Core Engine CLI (bullets)

Written by Opus for the implementing model. Read this whole file before writing code.
The roadmap doc ("Resume Tailoring Tool — Build Roadmap") is the source of truth for
*what* and *why*; this file is the source of truth for *how* in Phase 1.

Every rule below was measured on the 9-resume corpus. When a rule says "measured",
the Python in `reference/` reproduces the measurement. Treat `reference/` as an
executable example, not code to port line by line.

---

## 0. Non-negotiable rules

These exist because the obvious approach was tried and measured to fail.

1. **Never insert marker/sentinel text to measure.** Even 1pt markers moved words
   in 7 of 9 resumes and changed a line count in one. Measure the real file by
   locating each bullet's own text in the rendered PDF (section 6).
2. **Never accept a bullet text because its character count is under a limit.**
   The same slot fits 12% (median) to 22% (p90) more or fewer characters depending
   on the words. At a filler-calibrated limit, 101 of 130 real bullets overflowed.
   A render is the only acceptance test (section 7). Character limits are UI hints.
3. **Never collapse a bullet to its first run's formatting.** 45 of 130 bullets mix
   bold/italic inside. Emphasis spans are part of the text model (section 5).
4. **A paragraph with `numPr` is not automatically a bullet.** Resolve the level's
   `numFmt`; only `bullet` counts. (Subodh's phone area code "(352)" is a decimal list.)
5. **Never mutate through `XWPFRun.setText` / `XWPFParagraph` run helpers.**
   `setText(text, 0)` replaces only the first `<w:t>`; extra `<w:t>`, `<w:tab/>`,
   `<w:br/>` survive. Edit the paragraph's XML directly (section 5).
6. **Every render starts from the normalized source bytes.** Never edit a file that
   was already edited for a previous probe.
7. **Nothing on the page may move.** Deleted bullets become blanks of equal height;
   shorter bullets are padded to their original line count (section 5.3, 5.4).
   An edit that needs more lines than the slot has is rejected.

---

## 1. Scope

In: normalize fonts, detect bullet slots, substitute/blank/pad bullets, render,
measure per-bullet line counts, calibrate hint budgets, render-validate candidate
texts in batches, verify, run all of it against the 9 corpus resumes.

Out (later phases): tables/sidebars/text-box policy, upload security, project
blocks and one-paragraph project sections, bullets containing hyperlinks, line
breaks, tabs, fields, or tracked changes (detect and mark **unsupported**, don't
edit), LLM, database, web, payments.

---

## 2. Environment

Build and run everything inside one Docker image so fonts and LibreOffice are
pinned. Calibration numbers depend on both.

- Base: Ubuntu 24.04. LibreOffice from the Ubuntu archive. Golden files were made
  with **LibreOffice 24.2.7.2**; record whatever version you get (`soffice --version`)
  in every calibration result.
- Fonts (apt): `fonts-crosextra-carlito fonts-crosextra-caladea fonts-liberation2
  fonts-texgyre fonts-lmodern fonts-dejavu`.
- Gelasio: download the static TTFs from github.com/SorkinType/Gelasio
  (`fonts/ttf/Gelasio-{Regular,Bold,Italic,BoldItalic}.ttf`) at image build time,
  run `tools/patch_gelasio.py` (needs `pip install fonttools`), install the output
  (`GelasioRT-*.ttf`, family **"Gelasio RT"**), then `fc-cache -f`.
  Why: stock Gelasio matches Georgia's widths but its lines are ~46% taller;
  that alone pushed a native 1-page resume to 2 pages.
- Java 21, Gradle (Kotlin DSL). Libraries: Apache POI `poi-ooxml` 5.x (package
  I/O only), Apache PDFBox 3.x, Jackson, picocli, JUnit 5. No Spring in Phase 1.

LibreOffice invocation (one process per render, never shared):

```
soffice --headless --norestore --nolockcheck \
  -env:UserInstallation=file:///tmp/lo-<uuid> \
  --convert-to pdf --outdir <dir> <file.docx>
```

- A unique `UserInstallation` per call is required; parallel calls sharing a
  profile hang or fail.
- Timeout 60 s, then `destroyForcibly()` and delete the profile dir. Timeout is
  an error, never a retry loop without limit (max 1 retry).
- Measured cost: ~1.2 s per render warm.

---

## 3. Font normalization (step 1.2)

Runs once per resume before anything else. Its output bytes are "the source"
for every later step.

### 3.1 Font map

Apply to `w:ascii`, `w:hAnsi`, `w:cs`, `w:eastAsia` attributes in
`word/document.xml`, `word/styles.xml`, headers and footers; to
`<a:latin typeface=…>` in `word/theme/*.xml`; and in `word/numbering.xml` **except**
glyph fonts (`Symbol`, `Wingdings*`, `Courier New` used for `o` bullets).

| In file | Render with |
|---|---|
| Calibri, Calibri Light, Aptos, Aptos Display | Carlito |
| Cambria | Caladea |
| Times New Roman | Liberation Serif |
| Arial, Helvetica | Liberation Sans |
| Courier New (text) | Liberation Mono |
| Georgia | Gelasio RT |
| Book Antiqua, Palatino Linotype | TeX Gyre Pagella |
| Bookman Old Style | TeX Gyre Bonum |
| Verdana, Lucida Sans Unicode | DejaVu Sans |

Keep the map in `fonts/font-map.json`, not in code.

### 3.2 Remove converter letter-squeezing

Delete every run-level `<w:spacing w:val="-N"/>` (negative character spacing)
in `word/document.xml`. PDF→Word converters add it to force a wider substitute
font into the original line positions (measured: 30–86% of bullet text in the 4
converted resumes). Leave positive values alone.

### 3.3 Page-fit fallback

After 3.1–3.2, render. If the page count is above the target (Phase 1 target is
**1 page for all 9**), reduce every `w:sz`/`w:szCs` in `document.xml` and
`styles.xml` by 1 half-point (0.5 pt) and render again; then by 2. Still over →
return `NEEDS_USER` (Phase 2 turns this into a question in the preview).
Measured: 6 resumes need no shrink, 3 need 0.5 pt (My_resume1, Rakesh, ajitesh).

### 3.4 Font audit

After every render, list the font used for each character (PDFBox
`TextPosition.getFont().getName()`, strip the `ABCDEF+` subset prefix). Fail if
any ASCII letter or digit is rendered in a font that is not in the map's right
column. Symbols falling back to DejaVu Sans are allowed (e.g. "⋄").

---

## 4. Document model and bullet detection (step 1.3)

### 4.1 Walking the body

Walk `w:body` children **in document order**, recursing into:
`w:tbl → w:tr → w:tc`, `w:sdt → w:sdtContent`, `w:customXml`.
Yield every `w:p` with:

- `locator`: list of element-child indices from `w:body` down to the paragraph,
  counting **every element child** (any tag). Example `[12]` = 13th child of
  body; `[5,1,0,2]` = body child 5 (tbl) → its child 1 → child 0 → child 2.
  Golden files use exactly this scheme.
- `inTable`: true if any ancestor is `w:tbl`.

### 4.2 Effective numbering

1. Direct `w:pPr/w:numPr` (`numId`, `ilvl` default 0). If absent, follow
   `w:pStyle` → `w:basedOn` chain in `styles.xml` for a style `numPr`.
2. `numId == 0` → not numbered.
3. `w:num[@numId]` → `abstractNumId`; apply `w:lvlOverride` if present;
   → `w:lvl[@ilvl]/w:numFmt/@w:val`.
4. **Slot** iff `numFmt == "bullet"`, not `inTable`, and paragraph text is not blank.

### 4.3 Paragraph text

Concatenate `w:t` text under the paragraph in document order (including inside
`w:hyperlink`). Ignore `w:delText`.

### 4.4 Unsupported slots (detect, report, never edit)

Mark `supported=false` with reasons if the paragraph contains: `w:hyperlink`
(`hyperlink`), run-level `w:br` (`line_break`), run-level `w:tab` (`tab_in_text`),
`w:fldChar`/`w:fldSimple` (`field`), `w:ins`/`w:del` (`tracked_change`).
Do not confuse `w:pPr/w:tabs/w:tab` (tab stops) with run-level tabs.
Corpus: 130 slots, 2 unsupported (My_resume1 #12 hyperlink, Rakesh #0 tab).

### 4.5 Emphasis spans

For each slot, record spans `{start, end, bold, italic}` over the paragraph text
where a run has `w:b`/`w:i` on (absent `w:val`, or not `0`/`false`).

---

## 5. Editing a bullet (step 1.4)

All edits operate on the `w:p` DOM node. Use POI's `OPCPackage` to read and write
parts; parse `word/document.xml` with a hardened `DocumentBuilderFactory`
(namespace-aware, `disallow-doctype-decl`, no external entities), edit the DOM,
serialize, write the part back. POI's `XWPF*` object model is not used for edits.

### 5.1 Text model

A bullet's content is `BulletText { String text; List<Span> emphasis; }`.
Emphasis spans are half-open `[start,end)` over `text`, non-overlapping.

### 5.2 Substitute

1. **Base run properties**: among runs with non-empty text, pick the one with the
   most characters **that is not bold or italic**; if all are emphasized, pick the
   longest. Deep-copy its `w:rPr` and remove `w:b`, `w:bCs`, `w:i`, `w:iCs` from the
   copy.
2. Remove every child of `w:p` except `w:pPr`.
3. Split `text` at span boundaries. For each piece append
   `<w:r>{copy of base rPr (+ w:b / w:i if in a span)}<w:t xml:space="preserve">piece</w:t></w:r>`.
4. Numbering (`w:pPr/w:numPr`) is untouched, so the bullet dot stays.

Property test: substituting each supported bullet with its own text and emphasis
must give the same rendered line count for every slot and the same page count
(section 9, test T2).

### 5.3 Blank (user deleted the bullet)

Measured to keep the page layout exactly (last-line position unchanged to 0.01 pt):

1. Keep `w:numPr`. Set the paragraph mark color to white:
   `w:pPr/w:rPr/w:color w:val="FFFFFF"` (create `w:rPr` if missing). This paints the
   bullet dot white while keeping its font metrics. Removing numbering instead
   shifted the page by 1.25 pt because the dot's font sets the first line's height.
2. Content: one run with base rPr containing `\u00A0`, then `(L−1)` times
   `<w:br/>` + `<w:t>\u00A0</w:t>`, where `L` is the slot's original line count.

### 5.4 Pad (text needs fewer lines than the slot)

After a render shows the new text uses `k < L` lines, append `(L−k)` times
`<w:br/>` + `<w:t>\u00A0</w:t>` to the last run and re-render to confirm `L`.
Measured: exact.

### 5.5 Reject (text needs more lines than the slot)

Never shrink font or spacing to make it fit. Return `TOO_LONG`.

---

## 6. Rendering and measurement (step 1.5, part 1)

### 6.1 Line extraction (PDFBox)

Subclass `PDFTextStripper`, override `writeString(String, List<TextPosition>)`
to collect every `TextPosition` with non-blank unicode: page index, `getYDirAdj()`
(baseline), `getXDirAdj()`, font size, font name.

Per page: sort by baseline; a new line starts when the baseline differs from the
current line's by **more than 3 pt**. Within a line sort by x. Line text =
concatenation, then normalize: Unicode NFKC, remove all whitespace.

### 6.2 Anchoring slots to lines

Input: the slot texts in document order (as they are in the file being rendered).
Keep a cursor at line 0. For each slot:

1. `t` = normalized slot text (NFKC, no whitespace). Blank slots (5.3) are skipped
   and keep their known `L`.
2. `a` = first line index ≥ cursor whose text contains `t[0:14]` (or all of `t` if
   shorter).
3. `b` = smallest index in `[a, a+8)` such that the concatenation of lines `a..b`
   contains `t`.
4. `lines = b − a + 1`; cursor = `b`.
5. Not found → `null` (treated as a failure, never as a pass).

Measured: agreed with an independent method on 131 of 132 slots; the one
disagreement was the other method disturbing the layout.

### 6.3 Page count

From the PDF (`PDDocument.getNumberOfPages()`).

---

## 7. Calibration and validation (step 1.5, part 2)

### 7.1 Why both exist

- **Calibration** produces a *hint* per slot (`maxCharsHint`) for the editor's
  counter and for generating candidate lengths. It is not a limit.
- **Validation** decides whether a specific text fits. It always renders.

### 7.2 Batch principle

A bullet's line count depends only on its own paragraph. So one render can test
one candidate per slot, for every slot at once. Never render one slot at a time.

### 7.3 Calibrate (hint budget)

For each supported slot, binary-search the largest word-prefix length of a
reference text (the realistic prose text in `reference/calib.py`, `PROSE`) that
keeps the slot at `L` lines. All slots search in parallel: each render sets every
unfinished slot to its own midpoint. Range 30–600 chars; ~9–10 renders per resume.
Build candidates as word prefixes (never cut inside a word), each prefixed with
`"Slot{i} "` so texts are unique for anchoring.

Record per slot: `L`, `maxCharsHint` (prose), plus the LibreOffice version and
font-map hash. Recalibrate when either changes.

Expected, not a bug: the user's own original text is *longer* than its prose hint
in 13 of 130 slots. Hints are text-dependent; that is why validation renders.

### 7.4 Validate candidates (the real gate)

Input: for each slot, a list of candidate `BulletText`s.

1. Cheap pre-filter, no render: reject if any whitespace-separated token is
   longer than 25 characters (`UNBREAKABLE_TOKEN`), or if length > 1.15 × hint
   (`FAR_TOO_LONG`). Measured: the users' own texts reach at most 1.10 × hint, so
   1.15 never rejects real content that fits.
2. Round r: put candidate r of every slot that still has one into a copy of the
   source, render once, measure.
3. `lines == L` → `FITS`; `lines < L` → `FITS_WITH_PADDING` (5.4);
   `lines > L` → `TOO_LONG`.

A slot with V candidates costs V renders, shared across all slots.

---

## 8. Verification

For every assembled output (any mix of substituted, blanked, padded slots):

- page count equals the source's,
- every slot's measured line count equals its `L` (blanks: skip text check, but
  run the layout check below),
- layout check: the bottom baseline of the last text line on the last page equals
  the source's within 0.5 pt,
- font audit passes (3.4).

Any failure → the output is not delivered.

---

## 9. Code layout

```
settings.gradle.kts
engine/                      pure Java library, no Spring
  src/main/java/.../engine/
    docx/      DocxPackage (load/save bytes, part access), SafeXml, BodyWalker, Locator
    numbering/ NumberingResolver
    fonts/     FontMap, FontNormalizer, FontAudit
    slots/     Slot, BulletText, Span, BulletDetector
    edit/      Substituter, Blanker, Padder
    render/    Renderer (interface), LibreOfficeRenderer, RenderResult
    measure/   PdfLines, AnchorMeasurer
    calibrate/ BatchCalibrator, BatchValidator
    verify/    Verifier, VerifyReport
  src/main/resources/fonts/font-map.json
  src/test/...  unit tests + corpus tests
cli/                         picocli app "tailor"
corpus/                      the 9 .docx files (gitignored if they are private)
golden/                      the 9 golden JSON files from this package
docker/Dockerfile
```

`Renderer` is an interface so Phase 2 can swap in a sandboxed pool without
touching callers.

## 10. CLI

| Command | Does |
|---|---|
| `tailor normalize in.docx out.docx` | Section 3; prints map applied, squeeze removed, shrink used, pages |
| `tailor render in.docx` | PDF + page count + font audit |
| `tailor detect in.docx` | Slots as JSON (golden format) |
| `tailor measure in.docx` | Line count per slot |
| `tailor calibrate in.docx` | Adds `L`, `maxCharsHint`, versions |
| `tailor validate in.docx candidates.json` | Section 7.4 results per candidate |
| `tailor assemble in.docx edits.json out.pdf` | Apply substitutes/blanks, pad, verify, write PDF |
| `tailor corpus-check corpus/ golden/` | Runs every test in section 11, prints a pass/fail table |

## 11. Tests (all 9 resumes, all must pass)

| Id | Test | Pass condition |
|---|---|---|
| T1 | Normalize | 1 page each; shrink used matches golden `shrink_pt`; font audit passes |
| T2 | Detect | Slot count, locators, texts, `supported` flags equal golden |
| T3 | Measure | Every slot's `lines` equals golden `lines` exactly |
| T4 | Round-trip | Substitute each supported slot with its own text+emphasis → T3 still exact, pages unchanged |
| T5 | Emphasis | After T4, emphasis spans re-detected from the output equal golden `emphasis` |
| T6 | Fill real prose | For every supported slot, build a real-prose candidate at its hint, validate; all `FITS`/`FITS_WITH_PADDING` candidates assembled at once → verification passes |
| T7 | Too long | Hint + 60 chars of prose in every slot → every slot `TOO_LONG`; nothing assembled |
| T8 | Token | A candidate with a 40-char token → `UNBREAKABLE_TOKEN`, and the renderer call counter did not increase |
| T9 | Blank | Blank every 2nd supported slot → verification passes (layout check within 0.5 pt) |
| T10 | Pad | Replace every 2-line slot with a 1-line sentence → padded, verification passes |
| T11 | Determinism | Calibrate twice → identical `L`; hints identical |
| T12 | Hints | Each `maxCharsHint` within ±5 chars of golden `max_chars_prose` (LibreOffice version may differ) |

Golden JSON fields per bullet: `index, locator, text, chars, lines, emphasis,
max_chars_prose, max_chars_tech, supported, unsupported_reasons`. File-level:
`squeeze_removed, shrink_pt, pages, environment`.

## 12. Build order

Build and pass in this order; don't start a step until the previous step's tests
pass.

1. **1.1 Setup**: Dockerfile, Gradle, `Renderer`, `tailor render`. Done when all 9
   produce PDFs in the container.
2. **1.2 Fonts**: `FontNormalizer`, `FontAudit`, `tailor normalize`. → T1.
3. **1.3 Detect**: `BodyWalker`, `NumberingResolver`, `BulletDetector`. → T2.
4. **1.4 Edit**: `Substituter`, `Blanker`, `Padder`.
5. **1.5 Measure/calibrate/validate**: `PdfLines`, `AnchorMeasurer`,
   `BatchCalibrator`, `BatchValidator`, `Verifier`. → T3–T12.
6. **1.6** `tailor corpus-check` prints the full table.

## 13. When to stop and escalate to Opus

- A test fails and the cause isn't a plain coding bug (e.g. line counts differ
  from golden on a correct-looking implementation).
- You're tempted to relax a rule in section 0, add sentinels, loosen a tolerance,
  or special-case one resume.
- LibreOffice version differs and more than 2 slots' `lines` differ from golden.
Record the observation and stop; don't work around it.
