import zipfile, io, re, os, subprocess, shutil, copy, tempfile, sys, json
from lxml import etree
import pdfplumber
W='http://schemas.openxmlformats.org/wordprocessingml/2006/main'; ns={'w':W}; q=lambda t:'{%s}%s'%(W,t)
XS='{http://www.w3.org/XML/1998/namespace}space'
SOFF=['python','/mnt/skills/public/docx/scripts/office/soffice.py','--headless','--convert-to','pdf']

class Doc:
    def __init__(s,path):
        s.path=path; s.z=zipfile.ZipFile(path)
        s.xml=s.z.read('word/document.xml')
        nx=etree.fromstring(s.z.read('word/numbering.xml'))
        amap={n.get(q('numId')):n.find('w:abstractNumId',ns).get(q('val')) for n in nx.findall('w:num',ns)}
        s.fmt={}
        for an in nx.findall('w:abstractNum',ns):
            for l in an.findall('w:lvl',ns):
                f=l.find('w:numFmt',ns)
                s.fmt[(an.get(q('abstractNumId')),l.get(q('ilvl')))]=f.get(q('val')) if f is not None else None
        s.amap=amap
    def tree(s): return etree.fromstring(s.xml)
    def slots(s,root):
        out=[]
        for p in root.find('w:body',ns).iter(q('p')):
            n=p.find('w:pPr/w:numPr',ns)
            if n is None: continue
            nid=n.find('w:numId',ns).get(q('val')); il=n.find('w:ilvl',ns); il=il.get(q('val')) if il is not None else '0'
            if s.fmt.get((s.amap.get(nid),il))!='bullet': continue
            if any(a.tag==q('tbl') for a in p.iterancestors()): continue
            if not ''.join(t.text or '' for t in p.iter(q('t'))).strip(): continue
            out.append(p)
        return out
    def save(s,root,out):
        with zipfile.ZipFile(s.path) as zi, zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as zo:
            for it in zi.infolist():
                zo.writestr(it, etree.tostring(root,xml_declaration=True,encoding='UTF-8',standalone=True) if it.filename=='word/document.xml' else zi.read(it.filename))

def ptext(p): return ''.join(t.text or '' for t in p.iter(q('t')))
def content_runs(p):  # all runs anywhere under p (incl hyperlinks)
    return [r for r in p.iter(q('r'))]
def rtext(r): return ''.join(t.text or '' for t in r.findall('w:t',ns))
def set_text(p,text):
    runs=content_runs(p)
    tr=[r for r in runs if rtext(r)]
    # template = dominant-length run
    tmpl=max(tr,key=lambda r:len(rtext(r)))
    new=etree.Element(q('r'))
    rp=tmpl.find('w:rPr',ns)
    if rp is not None: new.append(copy.deepcopy(rp))
    t=etree.SubElement(new,q('t')); t.text=text; t.set(XS,'preserve')
    # remove all run-level content (runs, hyperlinks, proofErr) after pPr
    for ch in list(p):
        if ch.tag!=q('pPr'): p.remove(ch)
    p.append(new)
def sentinel_run(tag):
    r=etree.Element(q('r')); rp=etree.SubElement(r,q('rPr'))
    for k in ('sz','szCs'): etree.SubElement(rp,q(k)).set(q('val'),'2')
    t=etree.SubElement(r,q('t')); t.text=tag; return r
def add_sentinels(p,i):
    pPr=p.find('w:pPr',ns)
    idx=list(p).index(pPr)+1 if pPr is not None else 0
    p.insert(idx,sentinel_run(f'QQ{i}AA')); p.append(sentinel_run(f'ZZ{i}BB'))

def render(docx,wd):
    subprocess.run(SOFF+[docx],cwd=wd,capture_output=True,timeout=120)
    return os.path.join(wd,os.path.basename(docx)[:-5]+'.pdf')

def measure_pdf(pdf):
    marks={}; lines=[]
    with pdfplumber.open(pdf) as d:
        npages=len(d.pages)
        for pi,pg in enumerate(d.pages):
            small=[c for c in pg.chars if c['size']<2]
            small.sort(key=lambda c:(round(c['top']),c['x0']))
            s=''.join(c['text'] for c in small)
            # map string positions back to chars
            for m in re.finditer(r'(QQ|ZZ)(\d+)(AA|BB)',s):
                c=small[m.start()]
                marks[(m.group(1),int(m.group(2)))]=(pi,c['bottom'])
            tops=sorted(c['bottom'] for c in pg.chars if c['size']>=4 and c['text'].strip())
            cl=[]
            for t in tops:
                if not cl or t-cl[-1]>3: cl.append(t)
            lines+= [(pi,t) for t in cl]
    counts={}
    for (k,i),(pg,top) in marks.items():
        if k!='QQ': continue
        e=marks.get(('ZZ',i))
        if not e: counts[i]=None; continue
        counts[i]=sum(1 for (lp,lt) in lines if (lp,lt)>=(pg,top-2.5) and (lp,lt)<=(e[0],e[1]+2.5))
    return counts,npages,len(lines)

def run(doc,overrides,wd,name='m',sentinels=True):
    root=doc.tree(); ss=doc.slots(root)
    for i,p in enumerate(ss):
        if i in overrides: set_text(p,overrides[i])
        if sentinels: add_sentinels(p,i)
    out=os.path.join(wd,name+'.docx'); doc.save(root,out)
    return measure_pdf(render(out,wd))
