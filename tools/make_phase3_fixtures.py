"""
Phase 3 fixtures: a synthetic resume ("Jane Doe") whose Projects section has one
position of every header shape seen in the corpus, plus a project library.

    pip install python-docx
    python tools/make_phase3_fixtures.py fixtures/phase3
"""
import io, json, os, sys, zipfile, copy
import docx
from docx.shared import Pt, Inches
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

OUT = sys.argv[1] if len(sys.argv) > 1 else "fixtures/phase3"
os.makedirs(OUT, exist_ok=True)
REL_HL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"

d = docx.Document()
sec = d.sections[0]; sec.left_margin = sec.right_margin = Inches(0.75)
d.styles["Normal"].font.size = Pt(10.5)

def run(p, text, bold=False, size=None):
    r = p.add_run(text); r.bold = bold
    if size: r.font.size = Pt(size)
    return r

def tab(p): p.add_run().add_tab()

def tab_stop(p, kind, twips):
    ppr = p._p.get_or_add_pPr(); tabs = OxmlElement("w:tabs"); t = OxmlElement("w:tab")
    t.set(qn("w:val"), kind); t.set(qn("w:pos"), str(twips)); tabs.append(t); ppr.append(tabs)

def hyperlink(p, label, url):
    rid = d.part.relate_to(url, REL_HL, is_external=True)
    h = OxmlElement("w:hyperlink"); h.set(qn("r:id"), rid)
    r = OxmlElement("w:r"); rpr = OxmlElement("w:rPr"); u = OxmlElement("w:u"); u.set(qn("w:val"), "single")
    rpr.append(u); r.append(rpr); t = OxmlElement("w:t"); t.text = label; r.append(t); h.append(r)
    p._p.append(h)

def br(p):
    """a line break inside a run that carries the paragraph's body text size (see spec 3.4)"""
    r = p.add_run(); r.font.size = Pt(10); r.add_break()

d.add_heading("Jane Doe", level=1)
d.add_paragraph("jane.doe@example.com · (555) 010-0199")
d.add_heading("Experience", level=2)
p = d.add_paragraph(); run(p, "Senior Software Engineer | Northwind Labs", bold=True); tab(p); run(p, "Jan 2023 – Present")
tab_stop(p, "right", 10080)
for b in ["Designed an event-driven order pipeline on Kafka and Postgres that cut checkout latency by 38%.",
          "Led the migration of twelve services to Kubernetes, reducing infrastructure spend by 22%."]:
    d.add_paragraph(b, style="List Bullet")

d.add_heading("Projects", level=2)
# P0: Title | stack <tab> date  (converter-style LEFT tab tuned so the date ends at the margin)
p = d.add_paragraph(); run(p, "Beacon Analytics", bold=True); run(p, " | Kotlin, Spark, Delta Lake, Airflow")
tab(p); run(p, "Jan 2024 – Jun 2024"); tab_stop(p, "left", 8250)
for b in ["Built a streaming analytics service that aggregates 2M events per minute into hourly rollups.",
          "Cut storage cost by 40% by compacting small Delta files on a nightly schedule, pruning stale partitions and moving cold tables to cheaper storage tiers automatically.",
          "Added data-quality checks that block bad batches before they reach dashboards."]:
    d.add_paragraph(b, style="List Bullet")
# P1: Title | stack | Link <tab> date  (proper right tab)
p = d.add_paragraph(); run(p, "Ledger Sync", bold=True); run(p, " | Go, Postgres, gRPC | ")
hyperlink(p, "GitHub", "https://github.com/example/ledger-sync"); tab(p); run(p, "Aug 2023 – Dec 2023")
tab_stop(p, "right", 10080)
for b in ["Implemented idempotent ledger replication between three regions with exactly-once delivery guarantees, ordered replay and automatic catch-up after network partitions.",
          "Reduced reconciliation time from 6 hours to 25 minutes."]:
    d.add_paragraph(b, style="List Bullet")
# P2: Title — subtitle (no stack, no date)
p = d.add_paragraph(); run(p, "Atlas — Offline Maps for Field Teams", bold=True)
for b in ["Shipped an offline-first map app that syncs tiles and notes when connectivity returns.",
          "Designed a conflict-resolution scheme for edits made by several people while offline, with a side-by-side merge view and an undo history for every change."]:
    d.add_paragraph(b, style="List Bullet")
# P3: inline block (one paragraph: title + URL link, then typed bullets; bullet 2 has a manual break)
p = d.add_paragraph(); run(p, "Pulse: Real-Time Incident Dashboard ", bold=True, size=10.5)
hyperlink(p, "https://pulse.example.com", "https://pulse.example.com")
br(p); run(p, "• Built a live incident timeline that merges alerts, deploys and chat messages into one view.", size=10)
br(p); run(p, "• Cut alert noise by 55% by grouping related alerts with a similarity model trained on", size=10)
br(p); run(p, "two years of incident history and on-call feedback.", size=10)
br(p); run(p, "• Added a status page that updates itself from incident state.", size=10)
# P4: not swappable: two-paragraph header
p = d.add_paragraph(); run(p, "Orion — Research Prototype", bold=True)
d.add_paragraph("[Advisor: Dr. A. Example, State University]")
for b in ["Prototyped a scheduling algorithm for shared lab equipment that raised utilisation by 18%."]:
    d.add_paragraph(b, style="List Bullet")

d.add_heading("Education", level=2)
d.add_paragraph("B.S. Computer Science, State University, 2020")

buf = io.BytesIO(); d.save(buf)
open(os.path.join(OUT, "projects_synthetic.docx"), "wb").write(buf.getvalue())

library = {"projects": [
  {"id": "quill", "title": "Quill", "detail": "TypeScript, Next.js, Postgres, Redis",
   "links": [{"label": "GitHub", "url": "https://github.com/example/quill"}], "date": "Feb 2024 – May 2024",
   "bullets": [
     {"1": "Built a collaborative editor with presence and comments.",
      "2": "Built a collaborative markdown editor with live presence, threaded comments, version history and offline drafts, used daily by four product teams at a 60-person startup."},
     {"1": "Cut page load time by 45% with edge caching.",
      "2": "Cut median page load time by 45% by moving server rendering to the edge, caching document snapshots in Redis and streaming large documents in chunks."},
     {"1": "Added role-based sharing with audit logs.",
      "2": "Added role-based sharing with per-document permissions, expiring share links and an audit log that records every access and change for compliance reviews."}]},
  {"id": "harbor", "title": "Harbor", "detail": "Rust, Tokio, SQLite",
   "links": [{"label": "GitHub", "url": "https://github.com/example/harbor"}], "date": "Sep 2022 – Dec 2022",
   "bullets": [
     {"1": "Wrote a container registry cache in Rust.",
      "2": "Wrote a pull-through container registry cache in Rust that serves repeat image pulls from local disk, verifies layer digests and evicts by least-recent use."},
     {"1": "Cut CI image pulls by 70% across 40 runners.",
      "2": "Cut CI image pull time by 70% across 40 build runners by deduplicating shared layers, prefetching popular tags overnight and pinning base images per branch."}]},
  {"id": "sprout", "title": "Sprout — Habit Tracker with Smart Nudges", "detail": None, "links": [], "date": None,
   "bullets": [
     {"1": "Designed a habit tracker with adaptive reminders.",
      "2": "Designed a habit tracker that adapts reminder timing to when each user actually completes their habits, learning a per-habit schedule from two weeks of history."},
     {"1": "Grew weekly retention from 31% to 48%.",
      "2": "Grew four-week retention from 31% to 48% by testing nudge timing and wording with a simple bandit model that shifts traffic toward the best variant each day."},
     {"1": "Ran A/B tests on onboarding with 3K users.",
      "2": "Ran A/B tests on onboarding flows with 3,000 beta users, instrumented every step with funnels and shipped the variant that doubled first-week completion rates."}]},
  {"id": "forge", "title": "Forge Build Orchestrator",
   "detail": "Go, Rust, Bazel, Kubernetes, Argo Workflows, Redis, PostgreSQL, gRPC, Prometheus, Grafana, Terraform, AWS",
   "links": [{"label": "GitHub", "url": "https://github.com/example/forge"}], "date": "Mar 2023 – Nov 2023",
   "bullets": [
     {"1": "Built a remote build cache that cut CI time by 60%.",
      "2": "Built a remote build cache and scheduler for a 300-service monorepo that cut median CI time by 60% and removed most flaky rebuilds."},
     {"1": "Scheduled 5K builds a day across 120 workers.",
      "2": "Scheduled 5,000 builds a day across 120 autoscaled workers, packing jobs by cache locality so most artifacts never left their node."},
     {"1": "Added per-team cost reports for build minutes.",
      "2": "Added per-team cost reports for build minutes and storage, which let two teams cut their monthly CI spend by a third within a quarter."}]},
  {"id": "relay", "title": "Relay", "detail": "Python, FastAPI, Kafka, ClickHouse, Grafana, Terraform, AWS",
   "links": [{"label": "Demo", "url": "https://relay.example.com"}], "date": "October 2021 – February 2022",
   "bullets": [
     {"1": "Built a webhook relay with retries and replay.",
      "2": "Built a webhook relay that retries failed deliveries with exponential backoff, signs every payload and lets customers replay any event from the last thirty days."},
     {"1": "Handled 12K events per second at p99 90ms.",
      "2": "Handled 12,000 events per second at a p99 latency of 90ms by batching writes to ClickHouse partitions and backpressuring producers when queues grew too deep."},
     {"1": "Wrote dashboards for delivery health.",
      "2": "Wrote Grafana dashboards for delivery health per customer and paged the owning team automatically whenever a customer endpoint degraded for more than five minutes."}]},
]}
json.dump(library, open(os.path.join(OUT, "library.json"), "w"), indent=1)
print("wrote", OUT)
