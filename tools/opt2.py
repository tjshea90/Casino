import random, itertools
exec(open('tune.py').read().split('#        W S')[0])
# faster line EV using numpy-free enumeration but cached
def metrics(B,F):
    base=[mk(r) for r in B]; fs=[mk(r) for r in F]
    el,hl=eline(base); es,pt=scat(base)
    elf,_=eline(fs); esf,pf=scat(fs)
    if FS_N*pf>=0.5: return None
    nfs=FS_N/(1-FS_N*pf)
    fsv=pt*nfs*FS_MULT*(elf+esf)
    return dict(line=el,scat=es,fs=fsv,rtp=el+es+fsv,trig=1/pt,hit=hl,fsw=nfs*FS_MULT*(elf+esf))
def cost(m):
    if m is None: return 1e9
    return (abs(m['rtp']-0.80)*100)**2 + (abs(m['fs']-0.24)*40)**2 + ((m['trig']-130)/8)**2 + ((m['hit']-0.05)*200)**2 + ((m['fsw']-30)/3)**2
random.seed(int(__import__('sys').argv[1]) if len(__import__('sys').argv)>1 else 1)
B=[[2,1,3,3,4,4,5,5,6,6,7] for _ in range(5)]
F=[[5,1,3,3,4,4,5,5,6,6,7] for _ in range(5)]
cur=metrics(B,F); c=cost(cur)
for it in range(4000):
    nb=[r[:] for r in B]; nf=[r[:] for r in F]
    tgt = nb if random.random()<0.6 else nf
    r=random.randrange(5); s=random.randrange(11); d=random.choice((-1,1))
    tgt[r][s]+=d
    if tgt[r][s]<(1 if s<6 else 3) or (s>=2 and s<6 and tgt[r][s]>min(tgt[r][6:])) or (s==1 and not 1<=tgt[r][s]<=2) or tgt[r][s]>12 or not 28<=sum(tgt[r])<=60: continue
    m=metrics(nb,nf); cc=cost(m)
    if cc<c: B,F,cur,c=nb,nf,m,cc
print(c,{k:round(v,4) for k,v in cur.items()})
print("B=",B); print("F=",F)
