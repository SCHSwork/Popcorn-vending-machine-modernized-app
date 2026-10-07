import numpy as np, sys
from PIL import Image
from scipy.ndimage import gaussian_filter, distance_transform_edt, zoom

def fbm(shape, rng, scales=(4,8,16,32), amps=(1,.5,.25,.12)):
    out = np.zeros(shape)
    for s,a in zip(scales,amps):
        n = rng.random((max(2,shape[0]//s+2), max(2,shape[1]//s+2)))
        out += a*zoom(n, (shape[0]/n.shape[0], shape[1]/n.shape[1]), order=3)[:shape[0],:shape[1]]
    out -= out.min(); out /= out.max()+1e-9
    return out

def normals(H, k=3.0):
    gy, gx = np.gradient(H)
    nx, ny, nz = -gx*k*H.shape[0]/8, -gy*k*H.shape[0]/8, np.ones_like(H)
    l = np.sqrt(nx**2+ny**2+nz**2); return nx/l, ny/l, nz/l

def lerp(a,b,t): return a+(b-a)*t[...,None]

def popcorn(seed, S=192):
    rng = np.random.default_rng(seed)
    yy,xx = np.mgrid[0:S,0:S].astype(float)
    x = (xx/S-0.5)*2; y=(yy/S-0.5)*2
    n = int(rng.integers(8,13))
    lobes=[(0,0,0.34)]
    for k in range(n):
        a = rng.random()*6.283; d = 0.18+0.38*rng.random()**0.8
        lobes.append((np.cos(a)*d, np.sin(a)*d*0.9, 0.18+0.17*rng.random()))
    # smooth-max of hemispherical caps -> lumpy heights with creases
    acc = np.zeros((S,S)); mask_acc=np.zeros((S,S))
    nz = fbm((S,S), rng, (16,32,64), (1,.5,.25))
    for (cx,cy,r) in lobes:
        r2 = r*(1+0.18*(nz-0.5))
        d2 = (x-cx)**2+(y-cy)**2
        cap = np.sqrt(np.clip(r2**2-d2,0,None))/r
        acc += np.exp(cap*9.0)-1
        mask_acc = np.maximum(mask_acc, (d2<r2**2).astype(float))
    H = np.log(acc+1)/9.0
    H = H + 0.016*fbm((S,S), rng, (4,8,16), (1,.6,.3))
    mask = gaussian_filter(mask_acc, 1.1)
    mask = np.clip((mask-0.35)/0.5,0,1)
    H = H*(mask>0.05)
    Hs = gaussian_filter(H,1.0)
    nx,ny,nzv = normals(Hs, 2.2)
    L = np.array([-0.55,-0.62,0.56]); L/=np.linalg.norm(L)
    lam = np.clip(nx*L[0]+ny*L[1]+nzv*L[2],0,1)
    ao = np.clip(gaussian_filter(H,7)-H,0,1); ao = np.clip(ao*3.2,0,0.85)
    shade = np.clip(0.30+0.78*lam,0,1)
    light = np.array([255,251,238.]); dark=np.array([214,168,98.])
    col = lerp(np.broadcast_to(dark,(S,S,3)).copy(), np.broadcast_to(light,(S,S,3)).copy(), shade)
    gold = fbm((S,S), rng, (24,48), (1,.5))
    gold = np.clip((gold-0.45)*2.2,0,1)*0.55
    col = lerp(col, np.broadcast_to(np.array([250,214,128.]),(S,S,3)).copy(), gold*(1-shade*0.5))
    col *= (1-ao*0.55)[...,None]
    # soft translucent edge: darker, warmer rim
    edge = distance_transform_edt(mask>0.3); rim = np.clip(edge/9.0,0,1)
    col = lerp(col, col*np.array([0.86,0.78,0.62]), (1-rim)*0.8)
    rgba = np.dstack([np.clip(col,0,255), mask*255])
    # hull (the golden-brown base bit), on most pieces
    if seed%4!=0:
        hs = 96
        hy,hx = np.mgrid[0:hs,0:hs].astype(float)
        u=(hx/hs-0.5)*2; v=(hy/hs-0.5)*2
        ex = (u/0.62)**2+(v/0.42)**2 + 0.18*(fbm((hs,hs), rng, (6,12), (1,.5))-0.5)
        hm = np.clip((1-ex)*6,0,1)
        hh = np.sqrt(np.clip(1-ex,0,None))
        gx_,gy_ = np.gradient(gaussian_filter(hh,1.2))
        lm = np.clip(-gx_*7*0.55-gy_*7*0.62+0.5,0,1)
        c1=np.array([104,62,18.]); c2=np.array([196,140,52.])
        hc = lerp(np.broadcast_to(c1,(hs,hs,3)).copy(), np.broadcast_to(c2,(hs,hs,3)).copy(), lm)
        hw = int(S*0.26)
        himg = Image.fromarray(np.dstack([hc,hm*255]).astype(np.uint8)).resize((hw,int(hw*0.7)), Image.LANCZOS)
        ys,xs = np.nonzero(mask>0.5)
        cols_ok = np.abs(xs-S/2)<S*0.14
        by_bottom = ys[cols_ok].max() if cols_ok.any() else ys.max()
        bx = int(S*0.5 - hw/2 + (rng.random()-0.5)*S*0.1); by = int(by_bottom - hw*0.35)
        out = Image.new('RGBA',(S,S),(0,0,0,0))
        out.alpha_composite(himg,(bx,by))                      # hull sits behind, only the tip peeks out
        out.alpha_composite(Image.fromarray(rgba.astype(np.uint8)))
        return out
    return Image.fromarray(rgba.astype(np.uint8))

def kernel(seed, W=96, Hh=104):
    rng = np.random.default_rng(1000+seed)
    yy,xx = np.mgrid[0:Hh,0:W].astype(float)
    u=(xx/W-0.5)*2; v=yy/Hh        # v: 0 top .. 1 tip
    def ell(cx,cy,rx,ry): return ((u-cx)/rx)**2+((v-cy)/ry)**2<=1
    jit = 1+0.06*(rng.random()-0.5)
    mask = ell(0,0.40,0.90*jit,0.36)|ell(0,0.62,0.68*jit,0.34)|ell(0,0.80,0.40,0.18)|ell(0,0.90,0.24,0.08)
    mask = gaussian_filter(mask.astype(float),1.6)>0.5
    m = gaussian_filter(mask.astype(float),1.0)
    d = gaussian_filter(distance_transform_edt(mask),1.6)
    t_ = np.clip(d/(W*0.20),0,1); Hh_ = 1-(1-t_)**2.2
    # dimple on the crown
    dim = np.exp(-(((u+0.05)/0.38)**2+((v-0.20)/0.12)**2))
    Hh_ = Hh_ - 0.22*dim*(Hh_>0.05)
    Hs = gaussian_filter(Hh_,1.2)
    nx,ny,nzv = normals(Hs, 1.5)
    L = np.array([-0.5,-0.65,0.58]); L/=np.linalg.norm(L)
    lam = np.clip(nx*L[0]+ny*L[1]+nzv*L[2],0,1)
    shade = np.clip(0.55+0.55*lam,0,1)
    top = np.array([255,222,92.]); tip=np.array([232,150,30.])
    base = lerp(np.broadcast_to(top,(Hh,W,3)).copy(), np.broadcast_to(tip,(Hh,W,3)).copy(), np.clip(v*1.15,0,1))
    col = base*shade[...,None]*1.12
    # specular
    Hv = np.array([0,0,1.]); hv = (L+Hv); hv/=np.linalg.norm(hv)
    spec = np.clip(nx*hv[0]+ny*hv[1]+nzv*hv[2],0,1)**22
    col = np.clip(col+spec[...,None]*70,0,255)
    edge = np.clip(d/6.0,0,1)
    col = lerp(col, col*np.array([0.86,0.74,0.50]), (1-edge)*0.6)
    return Image.fromarray(np.dstack([col, m*255]).astype(np.uint8))

if __name__=="__main__":
    out = sys.argv[1]
    for i in range(12): popcorn(i+1).save(f"{out}/pc{i}.png")
    for i in range(4): kernel(i).save(f"{out}/k{i}.png")
