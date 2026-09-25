import sys,glob,os,json,re,zipfile,subprocess,tempfile,shutil; sys.path.insert(0,'/home/claude/w')
from normalize import normalize
from calib import *
def shrink(src,dst,halfpts):
    zi=zipfile.ZipFile(src); zo=zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED)
    for it in zi.infolist():
        b=zi.read(it.filename)
        if it.filename in('word/document.xml','word/styles.xml'):
            b=re.sub(r'<w:(sz|szCs) w:val="(\d+)"',lambda m:f'<w:{m.group(1)} w:val="{int(m.group(2))-halfpts}"',b.decode()).encode()
        zo.writestr(it,b)
    zo.close()
def pages(pdf): return int(re.search(r'Pages:\s+(\d+)',subprocess.run(['pdfinfo',pdf],capture_output=True,text=True).stdout).group(1))
def locator(p):
    path=[]; e=p
    while e.getparent() is not None and e.tag!=q('body'):
        par=e.getparent(); path.append([c for c in par if isinstance(c.tag,str)].index(e)); e=par
    return list(reversed(path))
def emphasis(p):
    spans=[]; pos=0
    for r in p.iter(q('r')):
        t=rtext(r)
        if not t: continue
        rp=r.find('w:rPr',ns)
        def on(tag):
            el=rp.find('w:'+tag,ns) if rp is not None else None
            return el is not None and el.get(q('val')) not in ('0','false')
        if on('b') or on('i'): spans.append({'start':pos,'end':pos+len(t),'bold':on('b'),'italic':on('i')})
        pos+=len(t)
    return spans
f=sys.argv[1]; name=os.path.basename(f); wd=tempfile.mkdtemp()
out=f'{wd}/n.docx'; st=normalize(f,out); shrunk=0
for hp in (0,1,2):
    cand=out if hp==0 else f'{wd}/s{hp}.docx'
    if hp: shrink(out,cand,hp)
    if pages(render(cand,wd))==1: shrunk=hp; final=cand; break
shutil.copy(final,f'/home/claude/w/golden/normalized/{name}')
d=Doc(final); root=d.tree(); ss=d.slots(root); texts=[ptext(p) for p in ss]
L,pg=measure_anchor(render(final,wd),texts)
mp,_=calibrate(d,wd,PROSE,L); mt,_=calibrate(d,wd,TECH,L)
R={'file':name,'squeeze_removed':st['squeeze_removed'],'shrink_pt':shrunk*0.5,'pages':pg,
   'bullets':[{'index':i,'locator':locator(p),'text':texts[i],'chars':len(texts[i]),'lines':L[i],
               'emphasis':emphasis(p),'max_chars_prose':mp[i],'max_chars_tech':mt[i]} for i,p in enumerate(ss)]}
json.dump(R,open(f'/home/claude/w/golden/{name[:-5]}.json','w'),indent=1,ensure_ascii=False)
print(name, 'bullets',len(ss),'shrink',shrunk*0.5,'pages',pg)
