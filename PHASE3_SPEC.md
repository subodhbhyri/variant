# Phase 3 Spec — Project Blocks

Written by Opus for the implementing model. Read it all before writing code.
Phases 1 and 2 still apply. Every rule below was measured on the 9-resume
corpus and on `fixtures/phase3/`, using the Python reference
`reference/blocks_ref.py`.

---

## 0. Decisions (made; don't revisit)

| # | Decision |
|---|---|
| D1 | **Jobs never move.** In experience sections only bullets are tailored. Only sections the user marks as project-like swap whole blocks. |
| D2 | **A project must match the position's exact bullet count.** A 3-bullet project in a 2-bullet position shows its first 2 (Phase 5 will rank them); a 2-bullet project can't fill a 3-bullet position → `INSUFFICIENT_BULLETS`. |
| D3 | **Headers that don't parse** keep bullet tailoring but can't be swapped; the onboarding report says why. No rejection. |
| D4 | **Blocks with a non-bullet paragraph inside** (a second header line, a split-off bullet tail) aren't swappable in v1; their bullets still tailor. |

**The model: a position is a fixed shape.** Its header's line count plus its
exact bullets with their line counts, e.g. `header 1 · bullets [1,2,1]`. A
project fills a position by supplying header fields and exactly that many
bullets, each at its slot's line count. **Paragraphs are never added or
removed**, so height matches by construction and the Verifier confirms it.
This replaces the handoff's "variant sets per height"; paragraph spacing
makes height-in-lines unreliable whenever bullet counts change.

---

## 1. What the corpus looks like (measured)

- Project-like sections are named "Projects" (7), **"Research Papers"**
  (Durga) and **"Open-Source Contributions"** (Subodh). Names can't be relied on.
- Jobs have the same header + bullets shape as projects.
- Six header shapes: `Title | stack <tab> date`, `Title | stack | link <tab> date`,
  `Title [link] [link] <tab> (date)`, `Title <tab> date`, `Title — subtitle`,
  `Title | venue`. Only 3 of 9 put a tech stack in the header.
- Subodh's projects are **one paragraph each**: title + URL link, then lines
  starting with a typed "•", separated by `<w:br/>`; one bullet continues onto
  a second segment after a manual break (a PDF-converter artifact).
- Position shapes differ within one resume (Abhinav: `[1,1,2]`, `[1,1,1]`, `[2,1,1]`).

---

## 2. Sections and roles (step 3.1)

**Heading detection** (reference: `detect_sections`):

1. Body paragraphs outside tables and text boxes.
2. A paragraph is a *vocabulary heading* if it's short (≤ 40 chars, ≤ 5 words)
   and its text contains a section word (`VOCAB` in the reference:
   project, research paper, publication, open-source, contribution, portfolio,
   experience, employment, internship, education, skill, certification, …).
3. Collect the **formatting signatures** of vocabulary headings:
   (heading style name if it starts with "Heading", all-caps, all-bold,
   has a paragraph border). Drop the empty signature.
4. Headings = short paragraphs that are vocabulary headings, or share one of
   those signatures and aren't among the first 3 paragraphs (the name/contact
   block).

**Suggested role** from the heading text: `projects` (project, research paper,
publication, open-source, contribution, portfolio), `experience` (experience,
employment, internship, work history), else `other`. The user confirms or
changes roles at onboarding (`tailor onboard … --section-role "HEADING=role"`,
repeatable). Measured: the project-like section is found and correctly
suggested in **all 9** resumes. Two resumes keep their Experience heading
inside a table, so their first job lands under the previous section; roles are
user-confirmed, and bullets still tailor either way.

---

## 3. Blocks and positions (step 3.1)

Within a section (reference: `detect_blocks`):

- **Paragraph block**: one or more consecutive non-bullet paragraphs (the
  header) followed by bullet paragraphs (Phase 1 detection, including
  style-chain numbering).
- **Inline block**: a single non-bullet paragraph with run-level `<w:br/>`
  where at least one segment after the first starts with a bullet glyph. Segment
  0 is the header; each glyph segment starts an inline bullet; a segment with no
  glyph continues the previous inline bullet.
- A non-bullet paragraph after bullets that starts with a lowercase letter or
  `( , ; – -` is a **stray** (a split bullet tail), not a new header.

A position is **swappable** unless:

| Reason | When |
|---|---|
| `multi_paragraph_header` | header is more than one paragraph |
| `non_bullet_paragraph_in_block` | a stray paragraph sits among the bullets |
| `no_header` / `no_bullets` | the block lacks one |
| header parse reasons (3.2) | `line_break_in_header`, `unsupported_element_in_header`, `unrecognized_text_after_tab`, `no_title`, `bare_url_text` |
| any Phase 1/2 lock on one of its bullets | e.g. `hyperlink`, `shared_lines` |

**Shape** = header line count + each bullet's line count, measured on the
normalized render (inline bullets: the sum of their segments' lines).

Expected results: `fixtures/phase3/expected.json` (positions) and
`golden/phase3_blocks.json` (all 9 corpus resumes).

---

## 4. Header templates (step 3.2)

A header paragraph becomes an **ordered template of tokens**, each keeping its
original formatting (reference: `header_template`):

| Token | What |
|---|---|
| `TITLE` | text before the first ` \| ` (rPr of its first character) |
| `DETAIL` | text after a ` \| ` (stack, venue, subtitle); `detail_is_list` if it contains commas |
| `SEP` | the literal ` \| ` and its rPr |
| `LINK` | a `w:hyperlink` element (deep copy) |
| `TAB` | the run holding the tab |
| `DATE` | text after the tab matching the date pattern; remembers whether it was in parentheses |
| `LIT` | anything else (brackets, spaces), emitted as-is |

Note ` — `, ` – ` and `: ` are **not** separators; "Sage.AI — Natural Language
to SQL" is one TITLE.

**Rendering** a template with new fields `{title, detail, links[{label,url}], date}`
(reference: `render_header`):

- Replace everything in the paragraph except `pPr`; emit tokens in order.
- `TITLE`/`DETAIL`/`DATE` text is written as **one run per word** with the
  token's rPr (Phase 1 rule).
- Skip a `SEP` whose next token is a `DETAIL` or `LINK` the new project doesn't
  have. Skip `DETAIL`/`LINK`/`DATE` tokens with no value.
- `LINK`: copy the element; point it at a **new** relationship (fresh id such
  as `rIdVariant7`, `TargetMode="External"`) written into
  `word/_rels/document.xml.rels`. Never modify or reuse existing relationships.
  Label: the project's label, except when the original label looks like a URL,
  in which case the label is the new URL.
- `DATE`: add parentheses if the original had them.

### 4.1 Date tabs (measured)

Several converted resumes don't right-align dates. They put the date after a
**left** tab stop tuned per header so that *that* date ends at the margin.
A longer date then runs past the margin and wraps: Mohan's header grew to 2
lines, and trimming the stack couldn't fix it.

Rule: when (and only when) a header with `TAB … DATE` is **rewritten**, replace
its tab stops with one **right** tab at the original date's right edge,
measured on the normalized render and **clamped to the text width** (page
width − left/right margins − the paragraph's right indent). Store the measured
edge per header at onboarding (`dateEdgeTwips`) so swaps don't re-measure.
Tab positions are measured from the left margin.

Measured: converting tab-date headers moves nothing vertically and leaves every
other line in place. On the converted header line itself, characters may move
horizontally by a few twips (LibreOffice positions text in whole twips; the
measured edge is converted to twips for the tab stop): 2 twips = 0.10pt on
Abhinav. With it, Mohan's rotation succeeds in one render. Untouched headers are
never converted.

---

## 5. Tech-stack fit (step 3.3)

Only when the position's header has a `DETAIL` token and the project's detail
is a comma list:

1. Order the items (library order for now; Phase 5 supplies relevance order).
2. Binary-search the largest prefix `k` (1…n) whose rendered header keeps the
   original header line count.
3. If even `k = 1` doesn't fit, try with no detail at all (its `SEP` skipped).
   If that doesn't fit either → `HEADER_TOO_LONG`.

Measured: a 12-item stack keeps 7 items in one position and 6 in another; a
position with no `DETAIL` token simply doesn't show the stack.

---

## 6. Inline bullets (step 3.4)

Inline bullets become slots too, both for ordinary bullet tailoring and for
swaps. Slot address = (paragraph locator, inline bullet index).

Rewriting an inline block (header segment and/or bullets):

1. **Each `<w:br/>` goes inside the last run of the text it ends** (append the
   break to that run, or to a new run copying the hyperlink's run properties
   when the text ends in a link). Measured: a break in an unformatted run takes
   the paragraph's default size and grew each line, 3.9pt in total on Subodh's
   resume; with this rule the shift is **0.0pt**.
2. **Bullet text formatting comes from the bullet's first real text run**, not
   the glyph run or the break run.
3. A rewritten bullet is `• ` + text as one run per word, and **drops manual
   breaks inside the bullet** (the continuation segments). Its line count is
   the sum its segments had; padding (Phase 1 5.4) adds `<w:br/>` + nbsp in the
   bullet's last run.

Measured: rewriting a bullet of Subodh's LLM Eval block (with its own text and
with new text, removing the manual break) keeps it at 2 lines and moves no
other line on the page (0.00pt).

---

## 7. Block swap (step 3.5)

**Library project** (the Phase 4 contract; fixture: `fixtures/phase3/library.json`):

```json
{"id": "quill", "title": "Quill", "detail": "TypeScript, Next.js, Postgres, Redis",
 "links": [{"label": "GitHub", "url": "https://github.com/example/quill"}],
 "date": "Feb 2024 – May 2024",
 "bullets": [{"1": "one-line version", "2": "two-line version"}, …]}
```

Each bullet has one variant per line count. Putting project P into position Q:

1. `len(P.bullets) < len(Q.bullets)` → `INSUFFICIENT_BULLETS`.
2. Header: render Q's template with P's fields (4, 4.1, 5).
3. Bullet j of Q (L lines) gets P's bullet j variant `"L"`. Missing → `MISSING_VARIANT`.
   Render-check: more lines than L → `BULLET_TOO_LONG`; fewer → pad (Phase 1 5.4 /
   section 6).
4. Verify (Phase 1 section 8, extended): the position counts as edited
   (header `SUBSTITUTED`; bullets `SUBSTITUTED` or `PADDED`; an inline position
   is one edited paragraph whose total line count must equal the original).
   Every other line must stay within 0.5pt.

---

## 8. CLI

```
tailor blocks <onboarded.docx>                        # sections, roles, positions, shapes, reasons (JSON)
tailor swap <onboarded.docx> <library.json> <outDir> --place P3=quill [--place P0=relay …]
tailor project-check fixtures/phase3                   # runs P3-T3 and prints the swap table
```

`tailor onboard` additionally reports sections (with suggested roles), positions
(shape, swappable, reason, header token list, `dateEdgeTwips`).

---

## 9. Tests

| Id | Test | Pass condition |
|---|---|---|
| P3-T1 | Fixture detection | Sections and the 5 positions equal `fixtures/phase3/expected.json` `positions` |
| P3-T2 | Corpus detection | All 9 resumes equal `golden/phase3_blocks.json` (projects section, positions, swappable/reason, header token list) |
| P3-T3 | Fixture swaps | Every library project into every swappable position gives the `swaps` outcome in `expected.json`; for `OK`, `stack_items_kept` and `padded_bullets` match, and the Verifier passes |
| P3-T4 | Corpus rotation | In each corpus project section, content of position i+1 → position i with bullets fitted (library built from the resume's own blocks, shortened/padded to fit); skips exactly as `golden/phase3_blocks.json` `_rotation.skipped`; Verifier passes (measured 0.00pt on all) |
| P3-T5 | Date-tab conversion | Converting every tab-date header in the corpus: (1) no character moves vertically by more than 0.05pt; (2) no character on any line other than a converted header line moves by more than 0.05pt; (3) characters on converted header lines move horizontally by at most 0.25pt (5 twips). Report the three maxima per resume. |
| P3-T6 | Inline break rule | Unit test: rebuilding Subodh's LLM Eval block with its own content moves no line; the same rebuild with breaks in unformatted runs moves lines (proves the test can fail) |
| P3-T7 | No regressions | All Phase 1 and Phase 2 tests, `corpus-check` ALL PASS |

For P3-T4 the fitting step may shorten a bullet (drop trailing words) until it
fits, or pad it; that only exists to build test content. Real content comes from
Phase 4 variants.

**Duplicate content:** a rotation that skips a position leaves the same project
on the page twice (the original and the rotated copy). Anchor every slot of
every position in **one pass, in document order**, as `AnchorMeasurer` already
does. Anchoring each position separately from the top of the page matches the
wrong copy (this produced two false failures while building the reference).

---

## 10. Build order

1. **3.1** Sections, roles, blocks, positions, shapes; `tailor blocks`; P3-T1, P3-T2.
2. **3.2** Header templates and rendering, link relationships; date-tab
   conversion; P3-T5.
3. **3.4** Inline slots and rewrite rules; P3-T6. Inline bullets also become
   ordinary tailoring slots.
4. **3.3** Stack fit.
5. **3.5** Swap; `tailor swap`, `tailor project-check`; P3-T3, P3-T4, P3-T7.

Stop for an **Opus review after step 2** (header templates touch relationship
parts and every header shape).

## 11. When to stop and escalate to Opus

- A corpus position's detection differs from `golden/phase3_blocks.json`.
- Any swap moves a fixed line, or a fixture swap's outcome differs from `expected.json`.
- A rule here seems to need an exception for one resume.
- Anything that would add or remove paragraphs to make a shape fit.
