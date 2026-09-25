import sys,glob,random,json,tempfile; sys.path.insert(0,'/home/claude/w'); from anchor import *
FILLER=("Engineered a resilient distributed service layer with structured observability, automated rollback, and cache invalidation. ")*8
TECH=("AWS EC2 S3 RDS IAM VPC CI/CD GraphQL PostgreSQL gRPC REST APIs p99 latency cut 42% at 12K QPS; SLA 99.95% SLO, JWT OAuth2 RBAC, K8s HPA, Redis TTL, Kafka ETL, ONNX INT8, GPT-4o RAG, 3.2x TPS, TypeScript/React/Node.js, 1.5M+ DAU. ")*6
LONG=("Containerized microservices orchestration using Kubernetes, implementing comprehensive observability, infrastructure provisioning, authentication, authorization, internationalization, PostgreSQL replication, asynchronous synchronization, and transformation pipelines. ")*5
def corpus_prose():
    t=[]
    for f in sorted(glob.glob('/home/claude/w/real/*.docx')):
        d=Doc(f); t+= [ptext(p).strip() for p in d.slots(d.tree())]
    random.Random(7).shuffle(t); return ' '.join(t)*2
PROSE=corpus_prose()
def mk(src,n,i):
    head=f"Slot{i} "; words=(head+src).split(' '); out=''
    for w in words:
        c=(out+' '+w) if out else w
        if len(c)>n: break
        out=c
    return out
def measure_texts(d,texts_by_slot,wd,name):
    root=d.tree(); ss=d.slots(root); texts=[]
    for i,p in enumerate(ss):
        if i in texts_by_slot: set_text(p,texts_by_slot[i])
        texts.append(ptext(p))
    out=f'{wd}/{name}.docx'; d.save(root,out); pdf=render(out,wd)
    return measure_anchor(pdf,texts)
def calibrate(d,wd,src,L,lo=30,hi=600):
    n=len(L); LO={i:lo for i in range(n)}; HI={i:hi for i in range(n)}; best={i:None for i in range(n)}; k=0
    while any(LO[i]<=HI[i] for i in range(n)):
        mids={i:(LO[i]+HI[i])//2 for i in range(n) if LO[i]<=HI[i]}
        texts={i:mk(src,m,i) for i,m in mids.items()}
        c,_=measure_texts(d,texts,wd,f'cal{k}'); k+=1
        for i,m in mids.items():
            if c.get(i) is not None and c[i]<=L[i]: best[i]=len(texts[i]); LO[i]=m+1
            else: HI[i]=m-1
    return best,k
