# Requests to the integrator (CHARS-A: Plague Doctor, Demon, Cursed Skull)

None of these block the three characters: each one is worked around with existing materials. They would make the cast easier
to author and better looking.

## 1. `Mat.FLESH` vein density and `dim`
The demon-skin material (`mat == 7` in `LIT_FS`) lights up about 40 % of the surface with glowing veins
(`smoothstep(0.80, 0.97, noise.b)`), which reads as cooling lava rather than "dark crimson skin with ember veins", and the vein
emission is `(0.9 + 0.7 * emis)`, so it can never go below 0.9 and it ignores `dim` (a dimmed, non-winning demon would keep
glowing). `DemonModel` therefore uses `Mat.MATTE` skin plus sparse, hand-modelled ember cracks (thin `Mat.EMISSIVE` tubes, which
dim correctly). If you want `FLESH` back: expose the vein threshold (for instance `uParam.y`), multiply the vein emission by
`uColor.r` (the dim), and flip the single `SKIN` constant at the bottom of `DemonModel.kt`.

## 2. Emission of `Mat.GLASS` and `Mat.METAL` ignores `dim`
`GLASS` adds `vColor.rgb * uParam.x * 0.6` and `METAL` adds its fake-environment reflection to `emis`, neither multiplied by the
dim tint `uColor`. A dimmed character keeps bright lenses, vials, buckles and rings. `DoctorModel` passes `emis * dim` for its
glass draws as a workaround (so lenses dim twice as fast as the rest, which looks fine); multiplying both terms by `uColor.r` in the
shader would be the clean fix.

## 3. `Sculpt.rag` faces -Z
`rag()` creates +Z normals but `finish()` recomputes them from the winding, so the ribbon ends up facing -Z (back-face culled from
the front). It works for the Reaper because its rags are rotated around a circle; for a curtain facing the camera rotate by
180 degrees (`SkullModel` does). Either fix the winding in `rag()` or document it.

## 4. Flat `Mat.ECTO` ribbons are almost invisible
ECTO's alpha and glow are fresnel driven (`pow(1 - N.V, 2)`), so a flat strip facing the camera is nearly transparent. Good for
tubes and domes, useless for banners. `SkullModel` uses pale `Mat.CLOTH` tatters instead.

## 5. Preview and light API (already handled)
Before the lazy-upload change, lights added inside `CharacterModel.draw()` were never uploaded in `renderPreview`, and `plN` was
never reset in `render()`. With `beginLocalLights()` all three models now add their two local lights before the first `drawV`
and look right in the contact sheets.

## 6. Shader noise scale (technique, no change needed)
The bone/metal noise is sampled at fixed frequencies on the *mesh-local* position, so a small model gets huge pits (golf-ball
bone). `SkullModel` builds its bone meshes 3x larger and draws them at 1/3 scale (`KitA.xform` + a scale in the matrix), which makes
the pits and cracks 3x finer at zero cost. Other artists may want the same trick for small props.

## Budget table (triangles / mesh draws / glow sprites / local lights)
| Model | Triangles | Mesh draws | Glow sprites | Local lights |
| --- | --- | --- | --- | --- |
| Plague Doctor | 4,262 | 5 | 4 (+1 breath puff while excited) | 2 |
| Demon | 4,338 | 6 | 4 | 2 |
| Cursed Skull | 4,152 | 5 | 3 | 2 |
