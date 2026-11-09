import random, sys
VALS=[1,2,3,4,5,8,10,15,25]; VW=[25,22,16,12,9,7,5,3,1]
JP={'MINI':15,'MINOR':40,'MAJOR':150}; GRAND=1000
def orb(rng,P):
    x=rng.random()
    if x<P['major']: return ('J',JP['MAJOR'])
    x-=P['major']
    if x<P['minor']: return ('J',JP['MINOR'])
    x-=P['minor']
    if x<P['mini']: return ('J',JP['MINI'])
    x-=P['mini']
    if x<P['reaper']: return ('R',0)
    return ('V',rng.choices(VALS,VW)[0])
def play(rng,P):
    cells=[None]*20
    for i in rng.sample(range(20),P['start']):
        o=orb(rng,P)
        while o[0]!='V': o=orb(rng,P)
        cells[i]=list(o)
    spins=3
    while spins>0 and any(c is None for c in cells):
        spins-=1; landed=False
        for i in range(20):
            if cells[i] is None and rng.random()<P['rho']:
                o=list(orb(rng,P)); landed=True
                if o[0]=='R':
                    o[1]=sum(c[1] for c in cells if c and c[0] in 'VR')
                cells[i]=o
        if landed: spins=3
    tot=sum(c[1] for c in cells if c)
    full=all(cells)
    if full: tot+=GRAND
    return tot,full,sum(1 for c in cells if c)
P=dict(start=5,rho=0.075,mini=0.03,minor=0.012,major=0.003,reaper=0.02)
if __name__=='__main__':
    rng=random.Random(1); N=200000
    res=[play(rng,P) for _ in range(N)]
    m=[r[0] for r in res]
    import statistics
    print('mean',sum(m)/N,'median',statistics.median(m),'grand 1/',N/max(1,sum(r[1] for r in res)),
          'p>100',sum(x>100 for x in m)/N,'p>50',sum(x>50 for x in m)/N,'avg orbs',sum(r[2] for r in res)/N,
          'capped@40 mean',sum(min(x,50) for x in m)/N)
