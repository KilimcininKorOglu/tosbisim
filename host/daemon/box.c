// simrelayd box mode helpers. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
//
// Root-only pieces of "box mode" (the host as a silent, screen-off box). Everything phone-specific is
// probed, not hard-coded:
//   SCREEN_OFF / WAKE      KEYCODE_SLEEP / KEYCODE_WAKEUP through /system/bin/input (the app may not hold
//                          DEVICE_POWER)
//   ROLE <role> <package>  cmd role add-role-holder (the app may not hold MANAGE_ROLE_HOLDERS)
//   CHARGE <target>        battery-care target; 60 -> charge below 57, hold at 60, drain above 62
//   escape gesture         Volume Up x3 then Volume Down x3 within 5 s, read from /dev/input without grabbing
//                          (volume keys keep working; works with the screen off) -> wake + tell the app

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/netlink.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include "box.h"

#define TAG "simrelayd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern char **environ;

#define ESCAPE_WINDOW_NS 5000000000LL
#define ESCAPE_ACTION "dev.simrelay.host.BOX_ESCAPE"
#define ESCAPE_RECEIVER "dev.simrelay.host/.DaemonEventReceiver"

static int g_keys[BOX_MAX_KEYDEVS];
static int g_power[BOX_MAX_KEYDEVS], g_grabbed[BOX_MAX_KEYDEVS], g_volup[BOX_MAX_KEYDEVS], g_voldown[BOX_MAX_KEYDEVS];
static struct timespec g_grab_until;            // dead-man: the block lapses unless the app confirms it again
static int g_nkeys = 0;
static int g_seq[6];          // last six volume presses: +1 up, -1 down
static long long g_seq_t[6];

static long long now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000000000LL + t.tv_nsec;
}

// Every child starts with no signal blocked and SIGPIPE at its default: the daemon blocks SIGTERM & co. outside its
// ppoll and ignores SIGPIPE, and both would be inherited (a logcat that ignores the SIGTERM log_stop sends).
static int spawn_pid(char *const argv[], pid_t *pid) {
    posix_spawnattr_t at;
    sigset_t none, def;
    sigemptyset(&none);
    sigemptyset(&def);
    sigaddset(&def, SIGPIPE);
    posix_spawnattr_init(&at);
    posix_spawnattr_setsigmask(&at, &none);
    posix_spawnattr_setsigdefault(&at, &def);
    posix_spawnattr_setflags(&at, POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF);
    int rc = posix_spawn(pid, argv[0], NULL, &at, argv, environ);
    posix_spawnattr_destroy(&at);
    if (rc != 0) { LOGW("spawn %s: %s", argv[0], strerror(rc)); return -1; }
    return 0;
}

// Fire-and-forget: the main loop reaps it (box_reap).
static int spawn(char *const argv[]) {
    pid_t pid;
    return spawn_pid(argv, &pid);
}

int box_screen(int on) {
    char *argv[] = { "/system/bin/input", "keyevent", on ? "KEYCODE_WAKEUP" : "KEYCODE_SLEEP", NULL };
    return spawn(argv);
}

static int valid_pkg(const char *s) {
    if (!s || !*s || strlen(s) > 100) return 0;
    for (const char *c = s; *c; c++)
        if (!((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') || *c == '.' || *c == '_'))
            return 0;
    return 1;
}

int box_role(const char *role, const char *pkg) {
    // Only the roles the host needs (box mode's dialer, the SMS role it guards); the package name is checked so
    // nothing reaches cmd unescaped.
    if (!role || (strcmp(role, "android.app.role.DIALER") != 0 && strcmp(role, "android.app.role.SMS") != 0) ||
        !valid_pkg(pkg)) return -1;
    char *argv[] = { "/system/bin/cmd", "role", "add-role-holder", (char *)role, (char *)pkg, "0", NULL };
    LOGI("role %s -> %s", role, pkg);
    return spawn(argv);
}

// ------------------------------------------------------------------ battery care
//
// Universal soft charge control. No charger chip or kernel is assumed: the daemon probes the power_supply
// class and uses the first safe control this phone exposes.
//
//   1. charge_behaviour=inhibit-charge (Linux power_supply ABI): USB input stays on, the battery stops
//      charging. This is the closest thing to true bypass the kernel can offer.
//   2. A cap node plus a cut node: stop charging at the target, cut the charger input above the drain
//      threshold, release it again at the target. Without a cap node the cut cycles between resume and target.
//   3. Cap or cut alone, when that is all the phone has.
//
// The target is the user's percentage. The band is derived: 60% -> charge below 57%, hold at 60%, drain
// above 62%. 100% turns the feature off. Driven by power_supply uevents and a periodic check; the target is
// persisted so it survives the app and a reboot. Fail-safe: all controls are released at start, at exit,
// below 15% and whenever no limit is set.

static const struct { const char *path; int stop; } CUT_NODES[] = {
    { "/sys/class/power_supply/battery/batt_slate_mode", 1 },           // Samsung: run from the battery
    { "/sys/class/power_supply/battery/charging_enabled", 0 },          // may keep input (bypass) or cut it
    { "/sys/class/power_supply/battery/battery_charging_enabled", 0 },  // Samsung/OnePlus spelling
    { "/sys/class/power_supply/battery/charge_disable", 1 },            // some Xiaomi/MediaTek kernels
    { "/sys/class/power_supply/battery/input_suspend", 1 },             // Qualcomm/MediaTek: input suspended
    { "/sys/class/qcom-battery/input_suspend", 1 },                     // newer Qualcomm qti_battery_charger
    { "/sys/class/power_supply/usb/input_suspend", 1 },                 // some vendors put it on the USB supply
    { "/sys/class/power_supply/main/input_suspend", 1 },
    { "/sys/class/power_supply/ac/input_suspend", 1 },
    { "/sys/class/power_supply/dc/input_suspend", 1 },
};
static const char *CAP_NODES[] = {
    "/sys/class/power_supply/battery/batt_full_capacity",              // Samsung "Protect battery"
    "/sys/class/power_supply/battery/charge_control_end_threshold",    // Linux power_supply ABI
    "/sys/class/power_supply/battery/charge_stop_level",               // some Xiaomi/Google kernels
    "/sys/class/power_supply/main/charge_control_end_threshold",
    "/sys/class/power_supply/bms/charge_control_end_threshold",
    "/sys/class/power_supply/lenovo_battery/charge_control_end_threshold",
};
static const char *START_NODES[] = {
    "/sys/class/power_supply/battery/charge_control_start_threshold",
    "/sys/class/power_supply/main/charge_control_start_threshold",
    "/sys/class/power_supply/bms/charge_control_start_threshold",
    "/sys/class/power_supply/lenovo_battery/charge_control_start_threshold",
};
#define BATT_CAPACITY "/sys/class/power_supply/battery/capacity"
#define LIMIT_DIR "/data/adb/tosbisim"
#define LIMIT_FILE LIMIT_DIR "/charge_limit"
#define ORIGINAL_FILE LIMIT_DIR "/charge_original"
#define MIN_ON_BATTERY 15
#define CHARGE_MIN 50
#define CHARGE_RESUME_MARGIN 3
#define CHARGE_DRAIN_MARGIN 2

static int g_limit = 0;                // target percent; 0 or 100 = no limit
static int g_resume = 0;               // charge again at or below this level
static int g_drain = 0;                // above this level, drain toward the target
static int g_cutting = 0;
static int g_cut_failed = 0;
static int g_cap_original = -1;        // OEM/user threshold before battery care touched it
static int g_start_original = -1;
static int g_originals_loaded = 0;
static char g_behaviour_path[256];
static int g_behaviour_force = 0;
static int g_behaviour_scanned = 0;
static int g_behaviour_failed = 0;
// Re-cuts after something reset the node: at most RECUT_MAX an hour. A kernel that undoes every write would
// otherwise turn each write's power event into the next write, for ever; past the budget the limit is left
// alone until the hour is over (the battery charges, which is the safe direction).
#define RECUT_MAX 6
static long g_recut_window = -1;       // CLOCK_BOOTTIME s the hour began, -1 = none yet (0 is a real time: boot)
static int g_recuts = 0;
static long g_recut_paused_until = 0;  // budget spent: the limit is left alone until then (CLOCK_BOOTTIME s)

static long boot_s(void) {
    struct timespec t;
    clock_gettime(CLOCK_BOOTTIME, &t);                    // counts deep sleep too
    return (long)t.tv_sec;
}

// access(W_OK) always passes for root: a read-only sysfs node would be picked, hide a working one, and report charge
// control that isn't there (audit, 19 Oct). The node's own write bit says what the kernel allows.
static int node_writable(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && (st.st_mode & S_IWUSR);
}
static const char *first_writable(const char *const *nodes, size_t n) {
    for (size_t i = 0; i < n; i++) if (node_writable(nodes[i])) return nodes[i];
    return NULL;
}
static const char *cap_node(void) { return first_writable(CAP_NODES, sizeof(CAP_NODES) / sizeof(CAP_NODES[0])); }
/** The first writable cut node (index into CUT_NODES), or -1. */
static int cut_index(void) {
    for (size_t i = 0; i < sizeof(CUT_NODES) / sizeof(CUT_NODES[0]); i++) if (node_writable(CUT_NODES[i].path)) return (int)i;
    return -1;
}
static const char *cut_node(void) { int i = cut_index(); return i < 0 ? NULL : CUT_NODES[i].path; }

static int read_int(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char b[16] = { 0 };
    ssize_t n = read(fd, b, sizeof(b) - 1);
    close(fd);
    return n > 0 ? atoi(b) : -1;
}

static int write_int(const char *path, int v) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char b[16];
    int n = snprintf(b, sizeof(b), "%d", v);
    int ok = write(fd, b, (size_t)n) == n;
    close(fd);
    return ok ? 0 : -1;
}

static int read_text(const char *path, char *out, size_t size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, out, size - 1);
    close(fd);
    if (n <= 0) return -1;
    out[n] = 0;
    while (n > 0 && (out[n - 1] == '\n' || out[n - 1] == '\r' || out[n - 1] == ' ')) out[--n] = 0;
    return 0;
}

static int write_text(const char *path, const char *value) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    size_t n = strlen(value);
    int ok = write(fd, value, n) == (ssize_t)n;
    close(fd);
    return ok ? 0 : -1;
}

/** Locate a writable charge_behaviour that offers inhibit-charge (and optionally force-discharge). */
static void find_behaviour(void) {
    if (g_behaviour_scanned) return;
    g_behaviour_scanned = 1;
    g_behaviour_path[0] = 0;
    g_behaviour_force = 0;
    DIR *dir = opendir("/sys/class/power_supply");
    if (!dir) return;
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        if (entry->d_name[0] == '.') continue;
        char path[256], value[256];
        snprintf(path, sizeof(path), "/sys/class/power_supply/%s/charge_behaviour", entry->d_name);
        if (!node_writable(path) || read_text(path, value, sizeof(value)) < 0) continue;
        if (!strstr(value, "inhibit-charge")) continue;
        snprintf(g_behaviour_path, sizeof(g_behaviour_path), "%s", path);
        g_behaviour_force = strstr(value, "force-discharge") != NULL;
        LOGI("battery care: %s offers %s", path, g_behaviour_force ? "inhibit-charge + force-discharge" : "inhibit-charge");
        break;
    }
    closedir(dir);
}

/** The value inside [...] of a power_supply enum, e.g. "[auto] inhibit-charge force-discharge". */
static int behaviour_active(const char *wanted) {
    char value[256];
    if (read_text(g_behaviour_path, value, sizeof(value)) < 0) return 0;
    char *left = strchr(value, '['), *right = left ? strchr(left, ']') : NULL;
    if (left && right && right > left + 1) {
        *right = 0;
        return strcmp(left + 1, wanted) == 0;
    }
    return strcmp(value, wanted) == 0;
}

static int set_behaviour(const char *value) {
    if (!g_behaviour_path[0]) return 0;
    if (behaviour_active(value)) return 0;
    if (write_text(g_behaviour_path, value) < 0) {
        if (!g_behaviour_failed) LOGW("battery care: writing %s=%s failed: %s", g_behaviour_path, value, strerror(errno));
        g_behaviour_failed = 1;
        return -1;
    }
    g_behaviour_failed = 0;
    LOGI("battery care: charge_behaviour=%s (%s)", value, g_behaviour_path);
    return 0;
}

static int charger_online(void) {
    static const char *nodes[] = {
        "/sys/class/power_supply/usb/online",
        "/sys/class/power_supply/ac/online",
        "/sys/class/power_supply/dc/online",
        "/sys/class/power_supply/pc_port/online",
    };
    int known = 0;
    for (size_t i = 0; i < sizeof(nodes) / sizeof(nodes[0]); i++) {
        int value = read_int(nodes[i]);
        if (value < 0) continue;
        known = 1;
        if (value > 0) return 1;
    }
    return known ? 0 : -1;
}

static void update_band(void) {
    if (g_limit <= 0 || g_limit >= 100) { g_resume = g_drain = 0; return; }
    g_resume = g_limit - CHARGE_RESUME_MARGIN;
    if (g_resume < 30) g_resume = 30;
    g_drain = g_limit + CHARGE_DRAIN_MARGIN;
    if (g_drain > 95) g_drain = 95;
}

static void set_cut(int on) {
    int i = cut_index();
    if (i < 0) return;
    const char *node = CUT_NODES[i].path;
    int want = on ? CUT_NODES[i].stop : !CUT_NODES[i].stop;
    if (read_int(node) == want) { g_cutting = on; return; }       // already there: no needless uevent
    if (write_int(node, want) < 0) {
        if (!g_cut_failed) LOGW("battery care: writing %s failed: %s", node, strerror(errno));   // once until one works
        g_cut_failed = 1;
        return;
    }
    g_cut_failed = 0;
    if (g_cutting != on) LOGI("battery care: %s (%s)", on ? "charging stopped" : "charging allowed", node);
    g_cutting = on;
}

static void save_originals(void) {
    mkdir(LIMIT_DIR, 0700);
    FILE *f = fopen(ORIGINAL_FILE, "we");
    if (f) { fprintf(f, "%d %d\n", g_cap_original, g_start_original); fclose(f); }
}

static void load_originals(void) {
    if (g_originals_loaded) return;
    g_originals_loaded = 1;
    FILE *f = fopen(ORIGINAL_FILE, "re");
    if (!f) return;
    int cap = -1, start = -1;
    if (fscanf(f, "%d %d", &cap, &start) == 2 && cap >= 0 && cap <= 100 && start >= 0 && start <= 100) {
        g_cap_original = cap;
        g_start_original = start;
    }
    fclose(f);
}

/** Remember the OEM/user values the first time battery care is applied, so turning it off restores them. */
static void capture_originals(void) {
    load_originals();
    int changed = 0;
    if (g_cap_original < 0) {
        const char *cap = cap_node();
        int value = cap ? read_int(cap) : -1;
        if (value >= 0 && value <= 100) { g_cap_original = value; changed = 1; }
    }
    if (g_start_original < 0) {
        const char *start = first_writable(START_NODES, sizeof(START_NODES) / sizeof(START_NODES[0]));
        int value = start ? read_int(start) : -1;
        if (value >= 0 && value <= 100) { g_start_original = value; changed = 1; }
    }
    if (changed) save_originals();
}

/** Allow charging again without losing the target cap (low battery, or a paused hold). */
static void release_cut(void) {
    find_behaviour();
    set_behaviour("auto");
    if (cut_node()) set_cut(0);
    g_cutting = 0;
}

/** Release every control to normal charging, restoring the thresholds the phone had before battery care. */
static void release_charge(void) {
    release_cut();
    const char *cap = cap_node();
    const char *start = first_writable(START_NODES, sizeof(START_NODES) / sizeof(START_NODES[0]));
    // Restore end first: an original start (e.g. 80) may be above the target end we are replacing.
    if (cap && g_cap_original >= 0 && read_int(cap) != g_cap_original) write_int(cap, g_cap_original);
    if (start && g_start_original >= 0 && read_int(start) != g_start_original) write_int(start, g_start_original);
}

/** "behaviour+force", "behaviour", "cap+cut", "cap", "cut" or NULL: what this phone offers. */
const char *box_charge_node(void) {
    find_behaviour();
    if (g_behaviour_path[0]) return g_behaviour_force ? "behaviour+force" : "behaviour";
    const char *cap = cap_node(), *cut = cut_node();
    return cap && cut ? "cap+cut" : cap ? "cap" : cut ? "cut" : NULL;
}

void box_charge_status(char *out, size_t size) {
    snprintf(out, size, "target=%d resume=%d drain=%d cutting=%d", g_limit, g_resume, g_drain, g_cutting);
}

void box_charge_check(void) {
    find_behaviour();
    int level = read_int(BATT_CAPACITY);
    if (level < 0) return;
    if (g_limit <= 0 || g_limit >= 100) return;                 // off: leave the OEM/user thresholds alone
    if (level <= MIN_ON_BATTERY) { release_cut(); return; }     // charge, but the cap still stops at the target

    int online = charger_online();
    int plugged = online != 0;                              // unknown counts as plugged: release only on a clear 0
    int cut = cut_index();

    if (g_behaviour_path[0]) {
        if (level > g_drain && plugged && g_behaviour_force) {
            set_cut(0);
            if (set_behaviour("force-discharge") == 0) return;
        }
        if (level > g_drain && plugged && cut >= 0) {       // no force-discharge: use a cut while draining
            set_behaviour("auto");
            set_cut(1);
            return;
        }
        if (g_cutting) set_cut(0);
        if (level >= g_limit) set_behaviour("inhibit-charge");
        else if (level <= g_resume) set_behaviour("auto");
        return;
    }

    const char *cap = cap_node();
    if (cap) {
        int current = read_int(cap);
        if (current != g_limit) write_int(cap, g_limit);
    }
    if (cut < 0) return;
    long now = boot_s();
    if (g_recut_paused_until) {
        if (now < g_recut_paused_until) return;                  // still in the hour we gave up
        g_recut_paused_until = 0;
    }
    int with_cap = cap != NULL;
    int release_at = with_cap ? g_limit : g_resume;
    int should_cut = with_cap ? (plugged && level > g_drain) : (plugged && level >= g_limit);
    // The kernel can undo the cut behind our back: the Redmi Note 8 Pro's charger driver clears input_suspend when a
    // charger is plugged in, and charged past the limit (95 -> 96 %, 20 Oct 2026). Read it back on every power event
    // and put it back. Only a re-cut that is really written counts against the hour's budget (audit, 20 Oct 2026).
    if (g_cutting && read_int(CUT_NODES[cut].path) != CUT_NODES[cut].stop) {
        g_cutting = 0;                                          // honest state: we are not cutting
        if (level <= release_at) return;
        if (g_recut_window < 0 || now - g_recut_window >= 3600) { g_recut_window = now; g_recuts = 0; }
        if (g_recuts >= RECUT_MAX) {
            LOGW("battery care: %s keeps being reset, leaving it this hour", CUT_NODES[cut].path);
            g_recut_paused_until = g_recut_window + 3600;
            return;
        }
        g_recuts++;
        LOGW("battery care: the kernel reset %s, cutting again (%d this hour)", CUT_NODES[cut].path, g_recuts);
        set_cut(1);
        return;
    }
    if (!g_cutting && should_cut) set_cut(1);
    else if (g_cutting && level <= release_at) set_cut(0);
}

int box_charge(int percent) {
    if (percent < CHARGE_MIN || percent > 100) return -1;
    g_behaviour_scanned = 0;                            // a driver may have appeared since the last scan
    find_behaviour();
    if (!box_charge_node()) return -2;
    g_limit = percent >= 100 ? 0 : percent;
    update_band();
    capture_originals();
    if (g_limit == 0) {
        release_charge();
        mkdir(LIMIT_DIR, 0700);
        FILE *f = fopen(LIMIT_FILE, "we");
        if (f) { fprintf(f, "100\n"); fclose(f); }
        LOGI("battery care: off");
        return 0;
    }
    const char *start = first_writable(START_NODES, sizeof(START_NODES) / sizeof(START_NODES[0]));
    const char *cap = cap_node();
    int new_end = g_limit > 0 ? g_limit : 100;
    int new_start = g_limit > 0 ? g_resume : 0;
    if (cap) {
        int old_end = read_int(cap);
        // start must stay below end: when raising the target, write end first; when lowering it, start first.
        if (start && old_end >= 0 && new_start > old_end) {
            write_int(cap, new_end);
            write_int(start, new_start);
        } else {
            if (start) write_int(start, new_start);
            write_int(cap, new_end);
        }
    } else if (start) {
        write_int(start, new_start);
    }
    mkdir(LIMIT_DIR, 0700);
    FILE *f = fopen(LIMIT_FILE, "we");
    if (f) { fprintf(f, "%d\n", percent); fclose(f); } else LOGW("save %s: %s", LIMIT_FILE, strerror(errno));
    LOGI("battery care: target %d%% (band %d-%d, %s)", percent, g_resume, g_drain, box_charge_node());
    box_charge_check();
    return 0;
}

void box_charge_init(void) {
    capture_originals();                                // read before we touch anything
    release_charge();                                   // a previous run may have died while cutting
    int saved = read_int(LIMIT_FILE);
    if (saved >= CHARGE_MIN && saved <= 100) box_charge(saved);
}

void box_charge_shutdown(void) { release_charge(); }

int box_uevent_open(void) {
    int fd = socket(AF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_KOBJECT_UEVENT);
    if (fd < 0) { LOGW("uevent socket: %s", strerror(errno)); return -1; }
    struct sockaddr_nl a = { .nl_family = AF_NETLINK, .nl_groups = 1 };
    if (bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0) { LOGW("uevent bind: %s", strerror(errno)); close(fd); return -1; }
    return fd;
}

void box_uevent(int fd) {
    char buf[4096];
    ssize_t n;
    int power = 0;
    while ((n = recv(fd, buf, sizeof(buf) - 1, 0)) > 0) {
        buf[n] = 0;
        for (char *p = buf; p < buf + n; p += strlen(p) + 1)      // NUL-separated KEY=VALUE pairs
            if (strcmp(p, "SUBSYSTEM=power_supply") == 0) power = 1;
    }
    if (power) box_charge_check();
}

// ------------------------------------------------------------------ network block (box mode)
//
// Box mode cuts the network of apps a SIM box never needs (Google Play services: ~7 % of the overnight standby drain
// on r8q, all background data). Android 13 has no per-package switch reachable from shell, and "restrict background
// data" doesn't touch Play services' foreground-service processes, so: our own iptables/ip6tables chain matched on
// the app's uid. Not persistent across reboot by design: box mode re-applies it at boot, and box-off removes it.

#define NB_CHAIN "tosbisim_block"

// The rules change runs as a detached script: iptables waits for the system's xtables lock (-w 5 per call, a dozen
// calls), which inside the main loop could stall the socket, the keys and the escape gesture for up to a minute
// (audit, 19 Oct). One script at a time, and the last state asked for each uid wins: two scripts running at once
// could finish out of order, and box on -> off in quick succession left Play services blocked (audit, 20 Oct 2026).
#define NB_MAX 16
typedef struct { int uid, want, done; } nb_t;          // done: the state last applied, -1 = none
static nb_t g_nb[NB_MAX];
static int g_nnb = 0;
static pid_t g_nb_pid = 0;                              // the running script
static int g_nb_running = -1;                           // its g_nb slot, and the state it applies
static int g_nb_running_on = 0;

static void nb_next(void) {
    if (g_nb_pid > 0) return;
    for (int k = 0; k < g_nnb; k++) {
        if (g_nb[k].want == g_nb[k].done) continue;
        int uid = g_nb[k].uid, on = g_nb[k].want;
        char script[1024];
        snprintf(script, sizeof(script),
            "for b in /system/bin/iptables /system/bin/ip6tables; do "
            "$b -w 5 -N " NB_CHAIN " 2>/dev/null; "                        // chain + jump from OUTPUT, created once
            "$b -w 5 -C OUTPUT -j " NB_CHAIN " 2>/dev/null || $b -w 5 -I OUTPUT 1 -j " NB_CHAIN "; "
            "%s; done",
            on ? "$b -w 5 -C " NB_CHAIN " -m owner --uid-owner %1$d -j REJECT 2>/dev/null || $b -w 5 -A " NB_CHAIN " -m owner --uid-owner %1$d -j REJECT"
               : "while $b -w 5 -D " NB_CHAIN " -m owner --uid-owner %1$d -j REJECT 2>/dev/null; do :; done");
        char cmd[1200];
        snprintf(cmd, sizeof(cmd), script, uid);
        char *argv[] = { "/system/bin/sh", "-c", cmd, NULL };
        pid_t pid;
        if (spawn_pid(argv, &pid) != 0) {
            LOGW("network block: spawn failed");
            g_nb[k].done = on;                                  // not retried in a loop; the next request tries again
            continue;
        }
        g_nb_pid = pid;
        g_nb_running = k;
        g_nb_running_on = on;
        LOGI("network %s for uid %d", on ? "blocked" : "unblocked", uid);
        return;
    }
}

// Collect finished fire-and-forget children (same thread as everything that spawns them), then start the next
// network-block script if one is waiting. SIGCHLD wakes the main loop, so that happens at once.
void box_reap(void) {
    pid_t pid;
    int st;
    while ((pid = waitpid(-1, &st, WNOHANG)) > 0) {
        if (pid != g_nb_pid) continue;
        if (!WIFEXITED(st) || WEXITSTATUS(st) != 0) LOGW("network block script for uid %d ended with status %d", g_nb[g_nb_running].uid, st);
        g_nb[g_nb_running].done = g_nb_running_on;
        g_nb_pid = 0;
        g_nb_running = -1;
    }
    nb_next();
}

int box_netblock(int uid, int on) {
    if (uid < 10000 || uid > 99999) return -1;                     // app uids only
    on = on ? 1 : 0;
    int k = 0;
    while (k < g_nnb && g_nb[k].uid != uid) k++;
    if (k == g_nnb) {
        if (g_nnb == NB_MAX) { LOGW("network block: more than %d uids", NB_MAX); return -1; }
        g_nb[g_nnb++] = (nb_t){ .uid = uid, .want = on, .done = -1 };
    }
    g_nb[k].want = on;
    // Asked again with the same state: applied again (the rules don't survive a reboot or an iptables flush)
    if (!(g_nb_pid > 0 && g_nb_running == k)) g_nb[k].done = -1;
    nb_next();
    return 0;
}

// ------------------------------------------------------------------ escape gesture

static int has_bit(const unsigned long *bits, int bit) {
    return (bits[bit / (8 * sizeof(long))] >> (bit % (8 * sizeof(long)))) & 1UL;
}

int box_keys_open(void) {
    DIR *d = opendir("/dev/input");
    if (!d) return 0;
    struct dirent *e;
    while ((e = readdir(d)) && g_nkeys < BOX_MAX_KEYDEVS) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        unsigned long keys[(KEY_MAX + 1) / (8 * sizeof(long)) + 1], types[EV_MAX / (8 * sizeof(long)) + 1];
        memset(keys, 0, sizeof(keys));
        memset(types, 0, sizeof(types));
        ioctl(fd, EVIOCGBIT(0, sizeof(types)), types);
        // Touch panels (absolute axes) can report a "power" key for wake gestures: never watched, never grabbed
        if (!has_bit(types, EV_ABS) && ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) >= 0 &&
            (has_bit(keys, KEY_VOLUMEUP) || has_bit(keys, KEY_VOLUMEDOWN) || has_bit(keys, KEY_POWER))) {
            char name[64] = "?";
            ioctl(fd, EVIOCGNAME(sizeof(name)), name);
            g_power[g_nkeys] = has_bit(keys, KEY_POWER);
            g_volup[g_nkeys] = has_bit(keys, KEY_VOLUMEUP);
            g_voldown[g_nkeys] = has_bit(keys, KEY_VOLUMEDOWN);
            LOGI("box keys: watching %s (%s)%s", path, name, g_power[g_nkeys] ? " with the power key" : "");
            g_keys[g_nkeys++] = fd;
        } else {
            close(fd);
        }
    }
    closedir(d);
    return g_nkeys;
}

int box_key_fd(int i) { return i < g_nkeys ? g_keys[i] : -1; }

/** A watched input device went away: stop polling it (its slot keeps the others' order; -1 is ignored by poll). */
void box_key_drop(int i) {
    if (i >= g_nkeys || g_keys[i] < 0) return;
    LOGW("box keys: input device %d gone", i);
    close(g_keys[i]);
    g_keys[i] = -1;
    g_grabbed[i] = 0;
}

// Box mode: the power button must not wake the screen (19 Oct: on the Redmi each press lit the screen for a
// moment before box mode turned it off). Grabbing the key devices gives their events to us alone: Android never sees
// the power key, the escape gesture still works (we read the volume keys from the same fd), and the hardware
// long-press reset bypasses Android anyway. Released by POWERKEY 0, by the escape gesture, and by the kernel if we die.
//
// Only when the escape gesture can work (both volume keys watched): otherwise blocking the power key, with the screen
// kept off, would lock the owner out (audit, 19 Oct). And a dead-man: the block lapses after 15 min unless the app
// confirms it again (it does every minute while box mode is on), so a stuck app can't leave the phone sealed.
int box_power_block(int on) {
    if (on) {
        int up = 0, down = 0;
        for (int i = 0; i < g_nkeys; i++) if (g_keys[i] >= 0) { up |= g_volup[i]; down |= g_voldown[i]; }
        if (!up || !down) { LOGW("power key not blocked: the volume keys aren't both watched (no escape gesture)"); return -1; }
        clock_gettime(CLOCK_MONOTONIC, &g_grab_until);
        g_grab_until.tv_sec += 15 * 60;
    } else {
        g_grab_until.tv_sec = 0;
    }
    int n = 0;
    for (int i = 0; i < g_nkeys; i++) {
        if (g_keys[i] < 0 || !g_power[i] || g_grabbed[i] == on) continue;
        if (ioctl(g_keys[i], EVIOCGRAB, on ? 1 : 0) == 0) { g_grabbed[i] = on; n++; }
        else LOGW("power key %s: %s", on ? "grab" : "release", strerror(errno));
    }
    if (n) LOGI("power key %s (%d device%s)", on ? "blocked" : "released", n, n == 1 ? "" : "s");
    return n;
}
int box_key_count(void) { return g_nkeys; }

/** The dead-man check (main loop): an unconfirmed block is released. */
void box_power_check(void) {
    if (g_grab_until.tv_sec == 0) return;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec < g_grab_until.tv_sec) return;
    LOGW("power key block not confirmed for 15 min: released");
    box_power_block(0);
}

static void broadcast(const char *action) {
    char *argv[] = { "/system/bin/am", "broadcast", "--user", "0", "-a", (char *)action, "-n", ESCAPE_RECEIVER, NULL };
    spawn(argv);
}

// Persistent log: logcat's ring buffer (5 MiB) rolls over within hours on a busy day, losing the host's side of
// a trip (16 Oct 2026). logcat itself writes a rotated copy (2 MiB x 5) of our tags + call/voice/hotspot tags.
#define LOG_FILE "/data/adb/tosbisim/logs/host.log"
#define LOG_PIDF "/data/adb/tosbisim/logs/logcat.pid"

// The capture's logcat (pid file; a pattern match would find itself), or 0 if it no longer runs.
static int log_pid(void) {
    int pid = read_int(LOG_PIDF);
    if (pid <= 1) return 0;
    char comm[64] = "", path[64];
    snprintf(path, sizeof(path), "/proc/%d/comm", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { ssize_t n = read(fd, comm, sizeof(comm) - 1); close(fd); if (n > 0) comm[n] = 0; }
    return strncmp(comm, "logcat", 6) == 0 ? pid : 0;
}

static void log_stop(void) {
    int pid = log_pid();                 // one capture at a time
    if (pid) kill(pid, SIGTERM);
}

/** since: "MM-DD hh:mm:ss.mmm" to resume after the file's last line (no copy of what it already holds), or NULL. */
static struct timespec g_log_started;            // the running capture's start (monotonic)

static void log_spawn(const char *since) {
    char *argv[24];
    int a = 0;
    const char *head[] = { "/system/bin/logcat", "-b", "main,system,crash", "-v", "time", "-f", LOG_FILE, "-r", "2048", "-n", "5" };
    for (size_t i = 0; i < sizeof(head) / sizeof(head[0]); i++) argv[a++] = (char *)head[i];
    if (since) { argv[a++] = "-T"; argv[a++] = (char *)since; }
    const char *tags[] = { "-s", "SimRelayHost:I", "simrelayd:I", "voice:D", "Telecom:I", "SemWifiApTimeOutImpl:I", "Tethering:W",
        "AndroidRuntime:E" };
    for (size_t i = 0; i < sizeof(tags) / sizeof(tags[0]); i++) argv[a++] = (char *)tags[i];
    argv[a] = NULL;
    pid_t pid;
    if (spawn_pid(argv, &pid) != 0) { LOGW("log capture: spawn failed"); return; }
    FILE *f = fopen(LOG_PIDF, "we");
    if (f) { fprintf(f, "%d\n", pid); fclose(f); }
    clock_gettime(CLOCK_MONOTONIC, &g_log_started);
    LOGI("log capture -> " LOG_FILE " (pid %d%s%s)", pid, since ? ", from " : "", since ? since : "");
}

void box_log_capture(void) {
    mkdir("/data/adb/tosbisim", 0700);
    mkdir("/data/adb/tosbisim/logs", 0700);
    log_stop();
    log_spawn(NULL);
}

// A capture that ended (logcat killed or crashed) starts again from the file's last line. Judged by whether logcat
// runs, not by the file's age: logcat writes the file in whole 4 KiB blocks (Android 13), so a quiet host adds nothing
// for 10-25 min. Judged by age it was killed every ~10 min, which threw the unwritten block away and left cut lines and
// duplicates (519 restarts in 13 days; the "logd stopped feeding it" of 19 Oct was most likely this too; audit, 1 Nov
// 2026). Problem reports take the newest lines from logcat itself (Diag.recentLog).
void box_log_watch(void) {
    static struct timespec last;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (last.tv_sec && now.tv_sec - last.tv_sec < 60) return;
    last = now;
    // A capture just started may not be "logcat" in /proc yet (judged in its first millisecond, a second one started)
    if (now.tv_sec - g_log_started.tv_sec < 60 || log_pid()) return;
    struct stat st;
    if (stat(LOG_FILE, &st) != 0) st.st_size = 0;
    char tail[2048] = "", since[19] = "";
    int fd = open(LOG_FILE, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        off_t off = st.st_size > (off_t)sizeof(tail) - 1 ? st.st_size - (off_t)sizeof(tail) + 1 : 0;
        ssize_t n = pread(fd, tail, sizeof(tail) - 1, off);
        close(fd);
        if (n > 0) {
            tail[n] = 0;
            // The newest whole line with a "-v time" stamp ("MM-DD hh:mm:ss.mmm"); the last one may be cut short.
            for (char *p = tail + n; p > tail; ) {
                *p = 0;
                char *line = p;
                while (line > tail && line[-1] != '\n') line--;
                if (line > tail && strlen(line) >= 18 && line[2] == '-' && line[5] == ' ' && line[8] == ':' && line[14] == '.') {
                    memcpy(since, line, 18); since[18] = 0; break;
                }
                p = line > tail ? line - 1 : tail;
            }
        }
    }
    LOGW("log capture: logcat is gone, restarting it");
    log_spawn(since[0] ? since : NULL);
}

void box_announce(void) { broadcast("dev.simrelay.host.DAEMON_UP"); }

static void escape(void) {
    box_power_block(0);                  // the person at the phone gets their buttons back at once
    LOGI("escape gesture: waking the screen and telling the app");
    box_screen(1);
    broadcast(ESCAPE_ACTION);
}

void box_key_event(int fd) {
    struct input_event ev[16];
    ssize_t n;
    while ((n = read(fd, ev, sizeof(ev))) > 0) {
        for (size_t i = 0; i < (size_t)n / sizeof(ev[0]); i++) {
            if (ev[i].type != EV_KEY || ev[i].value != 1) continue;           // key down only
            if (ev[i].code != KEY_VOLUMEUP && ev[i].code != KEY_VOLUMEDOWN) continue;
            memmove(g_seq, g_seq + 1, 5 * sizeof(g_seq[0]));
            memmove(g_seq_t, g_seq_t + 1, 5 * sizeof(g_seq_t[0]));
            g_seq[5] = ev[i].code == KEY_VOLUMEUP ? 1 : -1;
            g_seq_t[5] = now_ns();
            static const int want[6] = { 1, 1, 1, -1, -1, -1 };
            if (memcmp(g_seq, want, sizeof(want)) == 0 && g_seq_t[5] - g_seq_t[0] <= ESCAPE_WINDOW_NS) {
                memset(g_seq, 0, sizeof(g_seq));
                escape();
            }
        }
    }
}
