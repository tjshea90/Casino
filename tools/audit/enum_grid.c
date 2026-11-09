/*
 * Independent audit tool for Neon Reaper 3.0: EXHAUSTIVE enumeration of every reel-stop combination.
 *
 *   enum_grid config_snapshot.txt base            > base.hist    (74^5 = 2.2e9 stop combinations, ~1-2 minutes)
 *   enum_grid config_snapshot.txt fs              > fs.hist      (58^5 = 6.6e8, ~30 s)
 *   enum_grid config_snapshot.txt base 1 3        > reaper13.hist  (reels 1 and 3 replaced by wilds: Reaper's Touch pattern)
 *
 * For every combination it evaluates all 20 paylines and counts coffins on the 3x5 window, and prints the exact histogram of
 * (total line multiple X, coffin count k) with 64-bit integer counts, so no sampling error exists anywhere in the output.
 * The line rule below is written from the rules (leading wild run pays as the wild; otherwise the first non-wild symbol
 * extends over itself and wilds; the better of the two pays; the coffin has no line pay), NOT copied from the engine; the
 * Kotlin audit test cross-checks it against ReaperEngine.evaluateLine on all 12^5 symbol combinations.
 * The numbers (strips, pays, paylines) come from tools/audit/config_snapshot.txt, a dump of the real ReaperConfig.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define MAXLEN 128
#define NS 12
#define REELS 5
#define LINES 20

static int WILD = 0, SCAT = 11;
static int pays[NS][3];
static int lines[LINES][REELS];
static int nlines = 0;
static int len_base[REELS], strip_base[REELS][MAXLEN];
static int len_fs[REELS], strip_fs[REELS][MAXLEN];

static void die(const char *m) { fprintf(stderr, "enum_grid: %s\n", m); exit(2); }

static void load(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) die("cannot open config snapshot");
    char key[64];
    while (fscanf(f, "%63s", key) == 1) {
        if (key[0] == '#') { int c; while ((c = fgetc(f)) != '\n' && c != EOF) {} continue; }
        if (!strcmp(key, "dims")) {
            int a, b, c, d, e; if (fscanf(f, "%d %d %d %d %d", &a, &b, &c, &d, &e) != 5) die("dims");
            if (a != REELS || b != 3 || c != NS) die("unexpected dims");
            WILD = d; SCAT = e;
        } else if (!strcmp(key, "pay")) {
            int s; if (fscanf(f, "%d", &s) != 1) die("pay");
            for (int i = 0; i < 3; i++) if (fscanf(f, "%d", &pays[s][i]) != 1) die("pay");
        } else if (!strcmp(key, "payline")) {
            if (nlines >= LINES) die("too many paylines");
            for (int i = 0; i < REELS; i++) if (fscanf(f, "%d", &lines[nlines][i]) != 1) die("payline");
            nlines++;
        } else if (!strcmp(key, "strip") || !strcmp(key, "fsstrip")) {
            int fs = key[0] == 'f', r; if (fscanf(f, "%d", &r) != 1) die("strip");
            int *len = fs ? len_fs : len_base; int (*st)[MAXLEN] = fs ? strip_fs : strip_base;
            int n = 0, c;
            for (;;) {   /* read ints until end of line */
                while ((c = fgetc(f)) == ' ') {}
                if (c == '\n' || c == EOF) break;
                ungetc(c, f);
                if (fscanf(f, "%d", &st[r][n]) != 1) die("strip value");
                n++;
            }
            len[r] = n;
        } else {   /* every other line is for the other tools: skip it */
            int c; while ((c = fgetc(f)) != '\n' && c != EOF) {}
        }
    }
    fclose(f);
    if (nlines != LINES) die("expected 20 paylines");
}

/* pay (x stake) of ONE payline given the five symbols */
static int line_pay(const int *a) {
    int w = 0;
    while (w < REELS && a[w] == WILD) w++;
    int best = 0;
    if (w >= 3) best = pays[WILD][w - 3];
    if (w < REELS) {
        int t = a[w], n = w;
        while (n < REELS && (a[n] == t || a[n] == WILD)) n++;
        if (n >= 3 && pays[t][n - 3] > best) best = pays[t][n - 3];
    }
    return best;
}

static uint16_t T[NS * NS * NS * NS * NS];
static uint64_t hist[4096][REELS + 1];

int main(int argc, char **argv) {
    if (argc < 3) die("usage: enum_grid config_snapshot.txt base|fs [wild reels ...]");
    load(argv[1]);
    int fs = !strcmp(argv[2], "fs");
    int *len = fs ? len_fs : len_base; int (*strip)[MAXLEN] = fs ? strip_fs : strip_base;
    int wildreel[REELS] = {0};
    for (int i = 3; i < argc; i++) { int r = atoi(argv[i]); if (r < 0 || r >= REELS) die("bad wild reel"); wildreel[r] = 1; }

    /* line table over all 12^5 symbol combinations */
    for (int i = 0; i < NS * NS * NS * NS * NS; i++) {
        int a[REELS], x = i;
        for (int r = REELS - 1; r >= 0; r--) { a[r] = x % NS; x /= NS; }
        T[i] = (uint16_t)line_pay(a);
    }

    /* windows: win[r][stop][row]; a wild reel is a single all-wild window (no coffin) */
    static int win[REELS][MAXLEN][3], cof[REELS][MAXLEN], n[REELS];
    for (int r = 0; r < REELS; r++) {
        if (wildreel[r]) {
            n[r] = 1; for (int row = 0; row < 3; row++) win[r][0][row] = WILD; cof[r][0] = 0;
        } else {
            n[r] = len[r];
            for (int i = 0; i < n[r]; i++) {
                cof[r][i] = 0;
                for (int row = 0; row < 3; row++) {
                    int s = strip[r][(i + row) % len[r]];
                    win[r][i][row] = s; if (s == SCAT) cof[r][i]++;
                }
            }
        }
    }
    /* last-reel symbol per line, so the innermost loop is 20 table lookups */
    static int w4[MAXLEN][LINES];
    for (int i = 0; i < n[4]; i++) for (int l = 0; l < LINES; l++) w4[i][l] = win[4][i][lines[l][4]];

    int mul[REELS] = {NS * NS * NS * NS, NS * NS * NS, NS * NS, NS, 1};
    int p0[LINES], p1[LINES], p2[LINES], p3[LINES];
    uint64_t total = 0;
    for (int i0 = 0; i0 < n[0]; i0++) {
        for (int l = 0; l < LINES; l++) p0[l] = win[0][i0][lines[l][0]] * mul[0];
        int c0 = cof[0][i0];
        for (int i1 = 0; i1 < n[1]; i1++) {
            for (int l = 0; l < LINES; l++) p1[l] = p0[l] + win[1][i1][lines[l][1]] * mul[1];
            int c1 = c0 + cof[1][i1];
            for (int i2 = 0; i2 < n[2]; i2++) {
                for (int l = 0; l < LINES; l++) p2[l] = p1[l] + win[2][i2][lines[l][2]] * mul[2];
                int c2 = c1 + cof[2][i2];
                for (int i3 = 0; i3 < n[3]; i3++) {
                    for (int l = 0; l < LINES; l++) p3[l] = p2[l] + win[3][i3][lines[l][3]] * mul[3];
                    int c3 = c2 + cof[3][i3];
                    for (int i4 = 0; i4 < n[4]; i4++) {
                        int sum = 0;
                        const int *w = w4[i4];
                        for (int l = 0; l < LINES; l++) sum += T[p3[l] + w[l]];
                        hist[sum][c3 + cof[4][i4]]++;
                    }
                    total += n[4];
                }
            }
        }
    }
    printf("total %llu\n", (unsigned long long)total);
    for (int x = 0; x < 4096; x++) for (int k = 0; k <= REELS; k++)
        if (hist[x][k]) printf("%d %d %llu\n", x, k, (unsigned long long)hist[x][k]);
    return 0;
}
