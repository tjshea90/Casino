/*
 * Shared helpers of the independent Neon Reaper audit tools (C): config loader, RNG, payline rule.
 * The config comes from tools/audit/config_snapshot.txt, which AuditTest.configSnapshotIsCurrent regenerates from the REAL
 * ReaperConfig (fails if it is stale), so these tools always audit the shipped numbers.
 */
#ifndef AUDIT_COMMON_H
#define AUDIT_COMMON_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>

#define NS 12
#define REELS 5
#define ROWS 3
#define LINES 20
#define MAXLEN 128

typedef struct {
    int wild, scat;
    int pays[NS][3];
    int lines[LINES][REELS]; int nlines;
    int len[REELS], strip[REELS][MAXLEN];
    int fslen[REELS], fsstrip[REELS][MAXLEN];
    int scatter_pay[6], fs_spins[6], fs_extra[6];
    int ramp_cap, max_seance_spins, max_win;
    int stakes[16]; int nst;
    int buy_seance, buy_vault;
    double feature_rate, mini_w[5];
    int npat; double pat_w[8]; int pat_n[8]; int pat_reels[8][5];
    int nraven; int raven_n[8]; double raven_w[8];
    int ncc; int coin_cnt[8]; double coin_cnt_w[8];
    int ncv; int coin_val[16]; double coin_w[16];
    int nsk; int skull_m[8]; double skull_w[8]; int skull_wilds;
    int relic_count; double relic_chance;
    int vcols, vrows, vstart, vrespins; double vorb;
    int norb; int orb_val[16]; int orb_w[16];
    double p_major, p_minor, p_mini, p_reaper; int v_mini, v_minor, v_major, v_grand;
    double rtp_claimed[16];
} Cfg;

static void die(const char *m) { fprintf(stderr, "audit: %s\n", m); exit(2); }

static int parse_ints(char *s, int *out, int max) { int n = 0; for (char *t = strtok(s, " \n"); t; t = strtok(NULL, " \n")) { if (n >= max) die("too many values"); out[n++] = atoi(t); } return n; }
static int parse_dbls(char *s, double *out, int max) { int n = 0; for (char *t = strtok(s, " \n"); t; t = strtok(NULL, " \n")) { if (n >= max) die("too many values"); out[n++] = atof(t); } return n; }

static void load_cfg(Cfg *c, const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) die("cannot open config snapshot");
    memset(c, 0, sizeof(*c));
    static char buf[8192];
    while (fgets(buf, sizeof buf, f)) {
        if (buf[0] == '#' || buf[0] == '\n') continue;
        char *rest = strchr(buf, ' '); char key[64];
        if (!rest) continue;
        size_t kl = (size_t)(rest - buf); if (kl > 63) die("key too long");
        memcpy(key, buf, kl); key[kl] = 0; rest++;
        int v[160]; double d[160]; int n;
        if (!strcmp(key, "dims")) { n = parse_ints(rest, v, 8); if (n != 5 || v[0] != REELS || v[1] != ROWS || v[2] != NS) die("dims"); c->wild = v[3]; c->scat = v[4]; }
        else if (!strcmp(key, "pay")) { n = parse_ints(rest, v, 8); if (n != 4) die("pay"); for (int i = 0; i < 3; i++) c->pays[v[0]][i] = v[1 + i]; }
        else if (!strcmp(key, "payline")) { n = parse_ints(rest, v, 8); if (n != 5 || c->nlines >= LINES) die("payline"); for (int i = 0; i < 5; i++) c->lines[c->nlines][i] = v[i]; c->nlines++; }
        else if (!strcmp(key, "strip") || !strcmp(key, "fsstrip")) {
            n = parse_ints(rest, v, 150); int r = v[0];
            if (r < 0 || r >= REELS || n - 1 > MAXLEN) die("strip");
            if (key[0] == 'f') { c->fslen[r] = n - 1; memcpy(c->fsstrip[r], v + 1, (n - 1) * sizeof(int)); }
            else { c->len[r] = n - 1; memcpy(c->strip[r], v + 1, (n - 1) * sizeof(int)); }
        }
        else if (!strcmp(key, "scatter_pay")) { n = parse_ints(rest, v, 8); for (int i = 0; i < n; i++) c->scatter_pay[i] = v[i]; }
        else if (!strcmp(key, "fs_spins")) { n = parse_ints(rest, v, 8); for (int i = 0; i < n; i++) c->fs_spins[i] = v[i]; }
        else if (!strcmp(key, "fs_extra")) { n = parse_ints(rest, v, 8); for (int i = 0; i < n; i++) c->fs_extra[i] = v[i]; }
        else if (!strcmp(key, "fs_ramp_cap")) c->ramp_cap = atoi(rest);
        else if (!strcmp(key, "max_seance_spins")) c->max_seance_spins = atoi(rest);
        else if (!strcmp(key, "max_win_tokens")) c->max_win = atoi(rest);
        else if (!strcmp(key, "stakes")) c->nst = parse_ints(rest, c->stakes, 16);
        else if (!strcmp(key, "buy_x")) { n = parse_ints(rest, v, 2); c->buy_seance = v[0]; c->buy_vault = v[1]; }
        else if (!strcmp(key, "feature_rate")) c->feature_rate = atof(rest);
        else if (!strcmp(key, "mini_weights")) { n = parse_dbls(rest, d, 8); for (int i = 0; i < n; i++) c->mini_w[i] = d[i]; }
        else if (!strcmp(key, "reaper_pattern")) {
            n = parse_dbls(rest, d, 8); if (c->npat >= 8) die("patterns");
            c->pat_w[c->npat] = d[0]; c->pat_n[c->npat] = n - 1; for (int i = 1; i < n; i++) c->pat_reels[c->npat][i - 1] = (int)d[i];
            c->npat++;
        }
        else if (!strcmp(key, "raven_wilds")) c->nraven = parse_ints(rest, c->raven_n, 8);
        else if (!strcmp(key, "raven_weights")) parse_dbls(rest, c->raven_w, 8);
        else if (!strcmp(key, "coin_counts")) c->ncc = parse_ints(rest, c->coin_cnt, 8);
        else if (!strcmp(key, "coin_count_weights")) parse_dbls(rest, c->coin_cnt_w, 8);
        else if (!strcmp(key, "coin_values")) c->ncv = parse_ints(rest, c->coin_val, 16);
        else if (!strcmp(key, "coin_weights")) parse_dbls(rest, c->coin_w, 16);
        else if (!strcmp(key, "skull_mults")) c->nsk = parse_ints(rest, c->skull_m, 8);
        else if (!strcmp(key, "skull_weights")) parse_dbls(rest, c->skull_w, 8);
        else if (!strcmp(key, "skull_wilds")) c->skull_wilds = atoi(rest);
        else if (!strcmp(key, "relic")) { n = parse_dbls(rest, d, 2); c->relic_count = (int)d[0]; c->relic_chance = d[1]; }
        else if (!strcmp(key, "vault_dims")) { n = parse_dbls(rest, d, 5); c->vcols = (int)d[0]; c->vrows = (int)d[1]; c->vstart = (int)d[2]; c->vrespins = (int)d[3]; c->vorb = d[4]; }
        else if (!strcmp(key, "orb_values")) c->norb = parse_ints(rest, c->orb_val, 16);
        else if (!strcmp(key, "orb_weights")) parse_ints(rest, c->orb_w, 16);
        else if (!strcmp(key, "vault_special")) { n = parse_dbls(rest, d, 4); c->p_major = d[0]; c->p_minor = d[1]; c->p_mini = d[2]; c->p_reaper = d[3]; }
        else if (!strcmp(key, "vault_values")) { n = parse_ints(rest, v, 4); c->v_mini = v[0]; c->v_minor = v[1]; c->v_major = v[2]; c->v_grand = v[3]; }
        else if (!strcmp(key, "rtp_by_stake_claimed")) parse_dbls(rest, c->rtp_claimed, 16);
    }
    fclose(f);
    if (c->nlines != LINES) die("expected 20 paylines");
}

/* pay (x stake) of ONE payline given its five symbols: written from the rules, not copied from the engine */
static int line_pay(const Cfg *c, const int *a) {
    int w = 0;
    while (w < REELS && a[w] == c->wild) w++;
    int best = 0;
    if (w >= 3) best = c->pays[c->wild][w - 3];
    if (w < REELS) {
        int t = a[w], n = w;
        while (n < REELS && (a[n] == t || a[n] == c->wild)) n++;
        if (n >= 3 && c->pays[t][n - 3] > best) best = c->pays[t][n - 3];
    }
    return best;
}

static uint16_t LT[NS * NS * NS * NS * NS];
static void build_line_table(const Cfg *c) {
    for (int i = 0; i < NS * NS * NS * NS * NS; i++) {
        int a[REELS], x = i;
        for (int r = REELS - 1; r >= 0; r--) { a[r] = x % NS; x /= NS; }
        LT[i] = (uint16_t)line_pay(c, a);
    }
}
/* total line multiple of a 5x3 grid g[reel][row] */
static inline int grid_lines(const Cfg *c, const int g[REELS][ROWS]) {
    int sum = 0;
    for (int l = 0; l < LINES; l++) {
        const int *ln = c->lines[l];
        int idx = (((g[0][ln[0]] * NS + g[1][ln[1]]) * NS + g[2][ln[2]]) * NS + g[3][ln[3]]) * NS + g[4][ln[4]];
        sum += LT[idx];
    }
    return sum;
}

/* xoshiro256** */
typedef struct { uint64_t s[4]; } Rng;
static inline uint64_t rotl(uint64_t x, int k) { return (x << k) | (x >> (64 - k)); }
static inline uint64_t rng_next(Rng *r) {
    uint64_t *s = r->s, res = rotl(s[1] * 5, 7) * 9, t = s[1] << 17;
    s[2] ^= s[0]; s[3] ^= s[1]; s[1] ^= s[2]; s[0] ^= s[3]; s[2] ^= t; s[3] = rotl(s[3], 45);
    return res;
}
static void rng_seed(Rng *r, uint64_t seed) {
    for (int i = 0; i < 4; i++) { seed += 0x9e3779b97f4a7c15ULL; uint64_t z = seed; z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL; z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL; r->s[i] = z ^ (z >> 31); }
}
static inline double rng_dbl(Rng *r) { return (rng_next(r) >> 11) * (1.0 / 9007199254740992.0); }
/* unbiased integer in [0, n) (Lemire) */
static inline uint32_t rng_below(Rng *r, uint32_t n) {
    uint64_t m = (rng_next(r) >> 32) * (uint64_t)n; uint32_t l = (uint32_t)m;
    if (l < n) { uint32_t t = (uint32_t)(-n) % n; while (l < t) { m = (rng_next(r) >> 32) * (uint64_t)n; l = (uint32_t)m; } }
    return (uint32_t)(m >> 32);
}
/* weighted pick over w[0..n) (weights need not sum to 1) */
static inline int pick_w(Rng *r, const double *w, int n, double total) {
    double x = rng_dbl(r) * total;
    for (int i = 0; i < n; i++) { x -= w[i]; if (x < 0) return i; }
    return n - 1;
}
static double sum_d(const double *w, int n) { double s = 0; for (int i = 0; i < n; i++) s += w[i]; return s; }
#endif
