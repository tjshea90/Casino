import itertools, sys
W,S,TIG,KOI,LAN,ENV,A,K,Q,J,T = range(11)
PAY = {W:(25,200,1000),TIG:(20,75,300),KOI:(15,50,200),LAN:(10,40,150),ENV:(8,25,100),
       A:(4,12,50),K:(4,10,40),Q:(2,8,30),J:(2,6,25),T:(1,5,20)}
SCAT = {3:2,4:10,5:50}  # x total bet
FS_N=10; FS_MULT=3
def linewin(s):
    w=0
    while w<5 and s[w]==W: w+=1
    wp = PAY[W][w-3] if w>=3 else 0
    if w==5: return wp
    t=s[w]
    if t==S: return wp
    n=w
    while n<5 and (s[n]==t or s[n]==W): n+=1
    sp = PAY[t][n-3] if n>=3 else 0
    return max(wp,sp)
def eline(reels):
    # reels: list of dict sym->count
    tot=[sum(r.values()) for r in reels]
    ev=0.0; hit=0.0
    items=[list(r.items()) for r in reels]
    for combo in itertools.product(*items):
        p=1.0; syms=[]
        for (s,c),L in zip(combo,tot): p*=c/L; syms.append(s)
        v=linewin(syms)
        if v: ev+=p*v; hit+=p
    return ev,hit
def scat(reels):
    ps=[3*r.get(S,0)/sum(r.values()) for r in reels]
    dist=[1.0]
    for p in ps:
        nd=[0.0]*(len(dist)+1)
        for k,v in enumerate(dist):
            nd[k]+=v*(1-p); nd[k+1]+=v*p
        dist=nd
    ev=sum(dist[k]*SCAT[k] for k in SCAT)
    trig=sum(dist[k] for k in (3,4,5))
    return ev,trig
def rtp(base,fs,show=True):
    el,hl=eline(base); es,pt=scat(base)
    elf,_=eline(fs); esf,pf=scat(fs)
    nfs=FS_N/(1-FS_N*pf)
    fsv=pt*nfs*FS_MULT*(elf+esf)
    r=el+es+fsv
    if show:
        print(f"line {el:.4f} scat {es:.4f} fs {fsv:.4f} RTP {r:.4f} trig 1/{1/pt:.0f} hitline {hl:.4f} fsAvgSpins {nfs:.2f} fsAvgWin(xTB) {nfs*FS_MULT*(elf+esf):.1f}")
    return r
def mk(counts): return {i:c for i,c in enumerate(counts) if c}
#        W S TIG KOI LAN ENV A K Q J T
base=[mk([2,2,3,3,4,4,5,5,6,6,6]),
      mk([2,2,3,3,4,4,5,5,6,6,6]),
      mk([2,2,3,3,4,4,5,5,6,6,6]),
      mk([2,2,3,3,4,4,5,5,6,6,6]),
      mk([2,2,3,3,4,4,5,5,6,6,6])]
fs=base
rtp(base,fs)
