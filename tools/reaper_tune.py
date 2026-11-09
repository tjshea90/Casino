"""Tuner for Neon Reaper base game (no-rounding reference stake = 10 tokens)."""
import numpy as np, itertools, sys
W,HOOD,SKULL,RAVEN,LANT,TOMB,A,K,Q,J,TEN = range(11)
NS=11
PAY=np.zeros((NS,6))
for s,(a,b,c) in {W:(5,25,250),HOOD:(4,15,100),SKULL:(3,10,60),RAVEN:(2,8,40),LANT:(2,6,30),TOMB:(2,5,25),
                  A:(1,4,15),K:(1,3,12),Q:(1,2,10),J:(1,2,8),TEN:(1,2,6)}.items():
    PAY[s,3],PAY[s,4],PAY[s,5]=a,b,c
LINES=np.array([[1,1,1,1,1],[0,0,0,0,0],[2,2,2,2,2],[0,1,2,1,0],[2,1,0,1,2],[0,0,1,2,2],[2,2,1,0,0],
 [1,0,0,0,1],[1,2,2,2,1],[1,0,1,2,1],[1,2,1,0,1],[0,1,1,1,0],[2,1,1,1,2],[0,1,0,1,0],[2,1,2,1,2],
 [1,1,0,1,1],[1,1,2,1,1],[0,0,2,0,0],[2,2,0,2,2],[0,2,2,2,0]])
def build(counts,seed):
    L=sum(counts); strip=[-1]*L; placed=[0]*NS
    order=sorted(range(NS),key=lambda s:(s*7+seed*3)%NS); filled=0; prev=-1
    for pos in range(L):
        filled+=1; best=-1; bs=-1e9
        for s in order:
            if placed[s]>=counts[s]: continue
            sc=counts[s]*filled/L-placed[s]-(0.75 if s==prev else 0)
            if sc>bs: bs=sc; best=s
        strip[pos]=best; placed[best]+=1; prev=best
    return np.array(strip)
def evaluate(grid):
    # grid: (N,5,3) -> total multiplier per spin (N,)
    N=grid.shape[0]; tot=np.zeros(N)
    for ln in LINES:
        s=grid[:,np.arange(5),ln]  # (N,5)
        isw=(s==W)
        # leading wilds
        lw=np.zeros(N,int); run=np.ones(N,bool)
        for r in range(5):
            run&=isw[:,r]; lw+=run
        wp=np.where(lw>=3,PAY[W][np.minimum(lw,5)],0)
        idx=np.minimum(lw,4)
        tgt=s[np.arange(N),idx]
        tgt=np.where(lw==5,W,tgt)
        n=np.zeros(N,int); run=np.ones(N,bool)
        for r in range(5):
            run&=(s[:,r]==tgt)|isw[:,r]; n+=run
        sp=PAY[tgt,np.minimum(n,5)]*(n>=3)
        tot+=np.maximum(wp,sp)
    return tot
def sample(strips,N,rng,wildreels=None):
    grid=np.empty((N,5,3),int)
    for r in range(5):
        st=strips[r]; stops=rng.integers(0,len(st),N)
        for row in range(3): grid[:,r,row]=st[(stops+row)%len(st)]
    if wildreels is not None:
        for r in wildreels: grid[:,r,:]=W
    return grid
def stats(counts,N=400000,seed=1):
    rng=np.random.default_rng(seed)
    strips=[build(c,i) for i,c in enumerate(counts)]
    m=evaluate(sample(strips,N,rng))
    out=dict(rtp=m.mean(),hit=(m>0).mean(),sd=m.std())
    feats=[]
    for wr,pw in (([1],.4),([2],.2),([3],.1),([1,3],.15),([1,2,3],.15)):
        mf=evaluate(sample(strips,N//4,rng,wr)); feats.append(pw*mf.mean())
    out['feat']=sum(feats)
    return out
if __name__=='__main__':
    #          W HOOD SKULL RAVEN LANT TOMB A K Q J T
    C=[[1,3,4,5,5,6,7,7,8,8,8]]*5
    print(stats(C))
