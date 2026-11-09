/*
 * Independent Monte-Carlo components of the Neon Reaper audit (C, xoshiro256**, config from config_snapshot.txt).
 *
 *   audit_mc CFG mini   TYPE N SEED      TYPE 1 Reaper's Touch, 2 Murder of Crows, 3 Demon's Bargain, 4 Skull's Curse
 *                                         prints the histogram of (total multiple of the stake, coffins on the final grid)
 *   audit_mc CFG seance FS_HIST K0 N SEED THE SEANCE started by K0 coffins (3/4/5): histogram of the session total multiple.
 *                                         Each free spin is drawn from the EXACT joint (line multiple, coffin count) distribution
 *                                         produced by enum_grid on the free-spin reels (no approximation), then the multiplier
 *                                         ramp min(cap, spin number) and the retrigger rule are applied spin by spin.
 *   audit_mc CFG vault  N SEED           Soul Vault hold & spin (total multiple of the bonus bet), simulated from the rules.
 *   audit_mc CFG plain  N SEED           plain base-game spins (cross-check of the exhaustive enumeration)
 *
 * Output: "N <samples>" then one line per non-empty bin ("Y count" or "Y k count"). Bin Y>=HBINS-1 collects overflow.
 */
#include "audit_common.h"

#define HBINS (1 << 21)

/* ------------------------------------------------------------------ plain / mini spins */
static void draw_grid(const Cfg *c, Rng *r, int g[REELS][ROWS]) {
    for (int reel = 0; reel < REELS; reel++) {
        int st = (int)rng_below(r, (uint32_t)c->len[reel]);
        for (int row = 0; row < ROWS; row++) g[reel][row] = c->strip[reel][(st + row) % c->len[reel]];
    }
}
static int coffins(const Cfg *c, const int g[REELS][ROWS]) {
    int k = 0;
    for (int a = 0; a < REELS; a++) for (int b = 0; b < ROWS; b++) if (g[a][b] == c->scat) k++;
    return k;
}
/* choose n distinct cells out of 15 and make them wild */
static void wild_cells(const Cfg *c, Rng *r, int g[REELS][ROWS], int n) {
    int cell[REELS * ROWS];
    for (int i = 0; i < REELS * ROWS; i++) cell[i] = i;
    for (int i = 0; i < n; i++) {
        int j = i + (int)rng_below(r, (uint32_t)(REELS * ROWS - i));
        int t = cell[i]; cell[i] = cell[j]; cell[j] = t;
        g[cell[i] / ROWS][cell[i] % ROWS] = c->wild;
    }
}

static void run_mini(const Cfg *c, int type, uint64_t n, uint64_t seed) {
    Rng r; rng_seed(&r, seed);
    static uint64_t h[1 << 15][6];
    const int YB = 1 << 15;
    double sraven = sum_d(c->raven_w, c->nraven), spat = sum_d(c->pat_w, c->npat), scc = sum_d(c->coin_cnt_w, c->ncc);
    double scv = sum_d(c->coin_w, c->ncv), ssk = sum_d(c->skull_w, c->nsk);
    for (uint64_t i = 0; i < n; i++) {
        int g[REELS][ROWS];
        draw_grid(c, &r, g);
        int mult = 1, coins = 0;
        if (type == 1) {
            int p = pick_w(&r, c->pat_w, c->npat, spat);
            for (int j = 0; j < c->pat_n[p]; j++) for (int row = 0; row < ROWS; row++) g[c->pat_reels[p][j]][row] = c->wild;
        } else if (type == 2) {
            wild_cells(c, &r, g, c->raven_n[pick_w(&r, c->raven_w, c->nraven, sraven)]);
        } else if (type == 3) {
            int cnt = c->coin_cnt[pick_w(&r, c->coin_cnt_w, c->ncc, scc)];
            for (int j = 0; j < cnt; j++) coins += c->coin_val[pick_w(&r, c->coin_w, c->ncv, scv)];
        } else if (type == 4) {
            wild_cells(c, &r, g, c->skull_wilds);
            mult = c->skull_m[pick_w(&r, c->skull_w, c->nsk, ssk)];
        } else die("mini type 1..4");
        int k = coffins(c, g); if (k > 5) k = 5;
        int y = grid_lines(c, g) * mult + coins + c->scatter_pay[k];
        if (y >= YB) y = YB - 1;
        h[y][k]++;
    }
    printf("N %llu\n", (unsigned long long)n);
    for (int y = 0; y < YB; y++) for (int k = 0; k < 6; k++) if (h[y][k]) printf("%d %d %llu\n", y, k, (unsigned long long)h[y][k]);
}

static void run_plain(const Cfg *c, uint64_t n, uint64_t seed) {
    Rng r; rng_seed(&r, seed);
    static uint64_t h[1 << 12][6];
    for (uint64_t i = 0; i < n; i++) {
        int g[REELS][ROWS];
        draw_grid(c, &r, g);
        int k = coffins(c, g); if (k > 5) k = 5;
        int y = grid_lines(c, g) + c->scatter_pay[k];
        if (y >= (1 << 12)) y = (1 << 12) - 1;
        h[y][k]++;
    }
    printf("N %llu\n", (unsigned long long)n);
    for (int y = 0; y < (1 << 12); y++) for (int k = 0; k < 6; k++) if (h[y][k]) printf("%d %d %llu\n", y, k, (unsigned long long)h[y][k]);
}

/* ------------------------------------------------------------------ THE SEANCE from the exact free-spin pmf */
typedef struct { int n; int *x, *k; double *prob; int *alias; double *cut; } Alias;

static void alias_build(Alias *a, const char *fs_hist) {
    FILE *f = fopen(fs_hist, "r"); if (!f) die("cannot open fs histogram");
    char key[32]; unsigned long long total = 0, cnt; int x, k;
    if (fscanf(f, "%31s %llu", key, &total) != 2 || strcmp(key, "total")) die("fs histogram header");
    int cap = 4096; a->x = malloc(cap * sizeof(int)); a->k = malloc(cap * sizeof(int)); a->prob = malloc(cap * sizeof(double)); a->n = 0;
    while (fscanf(f, "%d %d %llu", &x, &k, &cnt) == 3) { a->x[a->n] = x; a->k[a->n] = k; a->prob[a->n] = (double)cnt / (double)total; a->n++; }
    fclose(f);
    int n = a->n; a->alias = malloc(n * sizeof(int)); a->cut = malloc(n * sizeof(double));
    double *sc = malloc(n * sizeof(double)); int *small = malloc(n * sizeof(int)), *large = malloc(n * sizeof(int)); int ns = 0, nl = 0;
    double tot = 0; for (int i = 0; i < n; i++) tot += a->prob[i];
    for (int i = 0; i < n; i++) { sc[i] = a->prob[i] / tot * n; if (sc[i] < 1.0) small[ns++] = i; else large[nl++] = i; }
    while (ns && nl) {
        int s = small[--ns], l = large[--nl];
        a->cut[s] = sc[s]; a->alias[s] = l; sc[l] = (sc[l] + sc[s]) - 1.0;
        if (sc[l] < 1.0) small[ns++] = l; else large[nl++] = l;
    }
    while (nl) { int l = large[--nl]; a->cut[l] = 1.0; a->alias[l] = l; }
    while (ns) { int s = small[--ns]; a->cut[s] = 1.0; a->alias[s] = s; }
    free(sc); free(small); free(large);
}
static inline int alias_draw(const Alias *a, Rng *r) {
    int i = (int)rng_below(r, (uint32_t)a->n);
    return rng_dbl(r) < a->cut[i] ? i : a->alias[i];
}

static void run_seance(const Cfg *c, const char *fs_hist, int k0, uint64_t n, uint64_t seed) {
    Alias al; alias_build(&al, fs_hist);
    Rng r; rng_seed(&r, seed);
    uint64_t *h = calloc(HBINS, sizeof(uint64_t));
    double spins_sum = 0;
    for (uint64_t i = 0; i < n; i++) {
        int64_t total = 0; int left = c->fs_spins[k0], played = 0;
        while (left > 0 && played < c->max_seance_spins) {
            played++;
            int d = alias_draw(&al, &r);
            int m = played < c->ramp_cap ? played : c->ramp_cap;
            total += (int64_t)al.x[d] * m;
            left += c->fs_extra[al.k[d]] - 1;
        }
        spins_sum += played;
        h[total >= HBINS - 1 ? HBINS - 1 : total]++;
    }
    printf("N %llu\nSPINS %.6f\n", (unsigned long long)n, spins_sum / (double)n);
    for (int y = 0; y < HBINS; y++) if (h[y]) printf("%d %llu\n", y, (unsigned long long)h[y]);
}

/* ------------------------------------------------------------------ Soul Vault from the rules */
static void run_vault(const Cfg *c, uint64_t n, uint64_t seed) {
    Rng r; rng_seed(&r, seed);
    uint64_t *h = calloc(HBINS, sizeof(uint64_t));
    int cells = c->vcols * c->vrows;
    double sw = 0; for (int i = 0; i < c->norb; i++) sw += c->orb_w[i];
    for (uint64_t it = 0; it < n; it++) {
        int kind[64], val[64];            /* kind: 0 empty, 1 soul, 2 jackpot, 3 reaper */
        memset(kind, 0, sizeof kind); memset(val, 0, sizeof val);
        int filled = 0;
        while (filled < c->vstart) {
            int cell = (int)rng_below(&r, (uint32_t)cells);
            if (kind[cell]) continue;
            double x = rng_dbl(&r) * sw; int v = 0;
            for (int i = 0; i < c->norb; i++) { x -= c->orb_w[i]; if (x < 0) { v = c->orb_val[i]; break; } }
            kind[cell] = 1; val[cell] = v ? v : c->orb_val[0]; filled++;
        }
        int respins = c->vrespins;
        while (respins > 0 && filled < cells) {
            respins--;
            int landed = 0;
            for (int col = 0; col < c->vcols; col++) for (int row = 0; row < c->vrows; row++) {
                int cell = row * c->vcols + col;
                if (kind[cell] || rng_dbl(&r) >= c->vorb) continue;
                double x = rng_dbl(&r);
                if (x < c->p_major) { kind[cell] = 2; val[cell] = c->v_major; }
                else if ((x -= c->p_major) < c->p_minor) { kind[cell] = 2; val[cell] = c->v_minor; }
                else if ((x -= c->p_minor) < c->p_mini) { kind[cell] = 2; val[cell] = c->v_mini; }
                else if ((x -= c->p_mini) < c->p_reaper) {
                    int s = 0; for (int j = 0; j < cells; j++) if (kind[j] == 1 || kind[j] == 3) s += val[j];
                    kind[cell] = 3; val[cell] = s;
                } else {
                    double y = rng_dbl(&r) * sw; int v = c->orb_val[0];
                    for (int i = 0; i < c->norb; i++) { y -= c->orb_w[i]; if (y < 0) { v = c->orb_val[i]; break; } }
                    kind[cell] = 1; val[cell] = v;
                }
                filled++; landed++;
            }
            if (landed) respins = c->vrespins;
        }
        int64_t total = 0; for (int j = 0; j < cells; j++) total += val[j];
        if (filled == cells) total += c->v_grand;
        h[total >= HBINS - 1 ? HBINS - 1 : total]++;
    }
    printf("N %llu\n", (unsigned long long)n);
    for (int y = 0; y < HBINS; y++) if (h[y]) printf("%d %llu\n", y, (unsigned long long)h[y]);
}

int main(int argc, char **argv) {
    if (argc < 4) die("usage: audit_mc CFG mini|seance|vault|plain ...");
    Cfg c; load_cfg(&c, argv[1]); build_line_table(&c);
    if (!strcmp(argv[2], "mini") && argc == 6) run_mini(&c, atoi(argv[3]), strtoull(argv[4], 0, 10), strtoull(argv[5], 0, 10));
    else if (!strcmp(argv[2], "plain") && argc == 5) run_plain(&c, strtoull(argv[3], 0, 10), strtoull(argv[4], 0, 10));
    else if (!strcmp(argv[2], "seance") && argc == 7) run_seance(&c, argv[3], atoi(argv[4]), strtoull(argv[5], 0, 10), strtoull(argv[6], 0, 10));
    else if (!strcmp(argv[2], "vault") && argc == 5) run_vault(&c, strtoull(argv[3], 0, 10), strtoull(argv[4], 0, 10));
    else die("bad arguments");
    return 0;
}
