"""
Phase 3/5 fixture: a variant of projects_synthetic.docx with a headerless bullets
section ("Open-Source Contributions", vocabulary-matched to role "projects", no
header line before its bullets so BlockDetector finds no swappable position
there) inserted before the real "Projects" section. Reproduces the bug where
only the *first* projects-role section's positions were ever seen.

    pip install python-docx
    python tools/make_multi_section_fixture.py fixtures/phase3/projects_synthetic.docx fixtures/phase3/projects_synthetic_multi_section.docx
"""
import sys
import docx

src, out = sys.argv[1], sys.argv[2]
d = docx.Document(src)

projects_heading = None
for p in d.paragraphs:
    if p.text.strip() == "Projects":
        projects_heading = p
        break
if projects_heading is None:
    raise SystemExit("could not find the 'Projects' heading paragraph in " + src)

# Inserted in this order, each landing just before "Projects" -> final order is
# exactly: heading, bullet, bullet (then the untouched "Projects" section).
projects_heading.insert_paragraph_before("Open-Source Contributions", style="Heading 2")
projects_heading.insert_paragraph_before(
    "Reviewed and merged 40+ pull requests for a popular open-source logging library.",
    style="List Bullet")
projects_heading.insert_paragraph_before(
    "Filed and triaged issues for a CLI tool used by several thousand developers.",
    style="List Bullet")

d.save(out)
print("wrote", out)
