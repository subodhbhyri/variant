import zipfile,re,sys,os
FONT_MAP={"Calibri":"Carlito","Calibri Light":"Carlito","Cambria":"Caladea","Times New Roman":"Liberation Serif",
 "Arial":"Liberation Sans","Courier New":"Liberation Mono","Georgia":"Gelasio RT","Book Antiqua":"TeX Gyre Pagella",
 "Palatino Linotype":"TeX Gyre Pagella","Bookman Old Style":"TeX Gyre Bonum","Verdana":"DejaVu Sans",
 "Lucida Sans Unicode":"DejaVu Sans","Aptos":"Carlito","Aptos Display":"Carlito","Helvetica":"Liberation Sans"}
KEEP={"Symbol","Wingdings","Wingdings 2","Wingdings 3","Courier New"}  # bullet glyph fonts in numbering stay
def remap_attrs(x, glyph_part=False):
    def rep(m):
        k,v=m.group(1),m.group(2)
        if glyph_part and v in KEEP: return m.group(0)
        return f'{k}="{FONT_MAP.get(v,v)}"'
    return re.sub(r'(w:(?:ascii|hAnsi|cs|eastAsia))="([^"]+)"',rep,x)
def normalize(src,dst):
    zi=zipfile.ZipFile(src); zo=zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED); stats={'squeeze_removed':0}
    for it in zi.infolist():
        b=zi.read(it.filename)
        n=it.filename
        if n in('word/document.xml','word/styles.xml') or n.startswith('word/header') or n.startswith('word/footer'):
            x=remap_attrs(b.decode('utf8'))
            if n=='word/document.xml':
                x,c=re.subn(r'<w:spacing w:val="-\d+"\s*/>','',x); stats['squeeze_removed']=c
            b=x.encode('utf8')
        elif n=='word/numbering.xml':
            b=remap_attrs(b.decode('utf8'),glyph_part=True).encode('utf8')
        elif n.startswith('word/theme/'):
            x=b.decode('utf8')
            x=re.sub(r'(<a:latin typeface=")([^"]+)(")',lambda m:m.group(1)+FONT_MAP.get(m.group(2),m.group(2))+m.group(3),x)
            b=x.encode('utf8')
        zo.writestr(it,b)
    zo.close(); return stats
if __name__=='__main__': print(normalize(sys.argv[1],sys.argv[2]))
