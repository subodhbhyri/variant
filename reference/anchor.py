import sys,unicodedata; sys.path.insert(0,'/home/claude/w'); from lab import *
def norm(s): return re.sub(r'\s+','',unicodedata.normalize('NFKC',s))
def pdf_lines(pdf):
    out=[]
    with pdfplumber.open(pdf) as d:
        n=len(d.pages)
        for pi,pg in enumerate(d.pages):
            cs=sorted((c for c in pg.chars if c['text'].strip()),key=lambda c:c['bottom']); cl=[]
            for c in cs:
                if cl and c['bottom']-cl[-1][0]<=3: cl[-1][1].append(c)
                else: cl.append([c['bottom'],[c]])
            for b,chs in cl: out.append(norm(''.join(c['text'] for c in sorted(chs,key=lambda c:c['x0']))))
    return out,n
def measure_anchor(pdf,slot_texts):
    lines,n=pdf_lines(pdf); cur=0; res={}
    for i,t in enumerate(slot_texts):
        t=norm(t); pre,suf=t[:14],t[-14:]
        a=next((k for k in range(cur,len(lines)) if pre in lines[k]),None)
        if a is None: res[i]=None; continue
        b=next((k for k in range(a,min(a+8,len(lines))) if t in ''.join(lines[a:k+1])),None)
        res[i]=None if b is None else b-a+1; cur=(b or a)
    return res,n
