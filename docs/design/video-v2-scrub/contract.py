#!/usr/bin/env python3
"""VIDEO-V2P2 #1067: synthetic reference model and contract check for the #961 seeker.

This is NOT app code and NOT an Android run. It is a small, deterministic Python
model of the PROPOSED per-scrub lifecycle and preview-timestamp contract written
in README.md, plus concrete cases that assert the contract. A later Kotlin
implementation must pass equivalent JVM tests; this file only proves the written
contract is self-consistent and gives the reviewer executable examples.

Run (Python 3, no dependencies; exit code 1 on any failed assertion):

    python3 docs/design/video-v2-scrub/contract.py

It rewrites contract-cases.md (the recorded output) next to this file.
generate.py imports it to draw svg/lifecycle.svg and svg/timestamps.svg from the
same traces, so the boards cannot drift from the checked contract.
"""
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent

# ---- OBSERVED (VIEW:V16 static analysis; see README) ----
OBS_TAIL_MS = 100      # nominal frame offsets i*(d-100)/n
OBS_LEAD_MS = 200      # strip leads playback by 200 ms while playing

# ---- PROPOSED (ATLAS choices) ----
LEAD_RAMP_MS = 400     # lead grows 0 -> 200 ms over the first 400 ms of real playback advance
END_FALLBACK_MS = 100  # end clamp when the last decodable PTS is unknown: d - 100 ms, marked approximate


def obs_frame_count(duration_s):
    """VIEW:V16 max(3, 7*log10(d_s)); floor is the proposed rounding (P5)."""
    if duration_s <= 0:
        return 3
    return max(3, math.floor(7 * math.log10(duration_s)))


def nominal_offsets(d_ms, n):
    """V16 extraction grid: REQUEST times for tile i, not frame timestamps."""
    usable = max(0.0, d_ms - OBS_TAIL_MS)
    return [i * usable / n for i in range(n)]


def tile_interval(d_ms, n):
    return max(0.0, d_ms - OBS_TAIL_MS) / n


def end_clamp(d_ms, last_pts_ms):
    """Largest selectable T. Uses the actual last decodable PTS when known."""
    if last_pts_ms is not None:
        return min(float(d_ms), float(last_pts_ms)), "last decodable PTS"
    return max(0.0, d_ms - END_FALLBACK_MS), "fallback d - 100 ms (approximate)"


def select_preview(t_req, tiles, d_ms, n, extra=()):
    """Choose the moving/held preview for requested T.

    tiles: list of decoded tile PTS (ms) or None when the extractor did not report it.
    extra: iterable of (pts, source) for matching-generation decoder or endpoint frames.
    Returns a dict that never presents a nominal offset as a PTS.
    """
    nom = nominal_offsets(d_ms, n)
    half = tile_interval(d_ms, n) / 2
    known = [(abs(p - t_req), p, f"tile {i}") for i, p in enumerate(tiles) if p is not None]
    known += [(abs(p - t_req), p, src) for p, src in extra if p is not None]
    if known:
        err, pts, src = min(known, key=lambda k: (k[0], k[1]))
        res = {"source": src, "pts": pts, "error_ms": round(err, 2), "status": "approximate, PTS known"}
        if err == 0:
            res["status"] = "PTS equals requested T"
    else:
        i = min(range(n), key=lambda k: (abs(nom[k] - t_req), k))
        err = None
        res = {"source": f"tile {i}", "pts": None, "error_ms": None, "status": "approximate, PTS unknown",
               "nominal_ms": round(nom[i], 2)}
    # endpoint fallback (README "Endpoint fallback"): T lies in an end region AND the best
    # preview is farther than half a tile interval from T, or its PTS is unknown.
    #   end region:   T > last tile's PTS (its nominal offset when the PTS is unknown)
    #   start region: T < tile 0's PTS; when tile 0's PTS is unknown, T < half a tile interval
    last_ref = tiles[-1] if tiles and tiles[-1] is not None else nom[-1]
    if tiles and tiles[0] is not None:
        in_start = t_req < tiles[0]
    else:
        in_start = t_req < half
    in_end = t_req > last_ref
    res["endpoint_region"] = "end" if in_end else ("start" if in_start else None)
    res["endpoint_decode"] = bool((in_end or in_start) and (err is None or err > half))
    return res


class Seeker:
    """Reference model of one viewer page's seeker. Times in ms of media time.

    Events mirror the README contract. Every async request returns a token
    (gen, kind, seq, target); results are accepted only when they match the live
    generation and request, so late or out-of-order callbacks are dropped.
    """

    def __init__(self, d_ms, playing=True, pos=0.0, tiles=None, last_pts=None):
        self.d = d_ms
        self.n = obs_frame_count(d_ms / 1000)
        self.tiles = tiles if tiles is not None else nominal_offsets(d_ms, self.n)
        self.t_max, self.t_max_basis = end_clamp(d_ms, last_pts)
        self.gen = 0
        self.phase = "follow"          # follow | dragging | flinging | settling | cancelled | error
        self.playing = playing
        self.pos = float(pos)
        self.anchor = (self.pos, self.pos, 0.0)  # (T_c, p_c, L0); None while a scrub owns the strip
        self.resume = False
        self.t_sel = None
        self.exact = None
        self.seq = 0
        self.live_preview = None       # the one preview request allowed to update the main view
        self.collapsed = False
        self.preview = {"source": "player", "pts": self.pos, "status": "playback frame"}
        self.extra = []
        self.clock = 0.0
        self.log = []
        self.samples = []
        self._sample("start")

    # ---- derived ----
    def strip_t(self):
        if self.anchor is None:
            return self.t_sel
        t_c, p_c, l0 = self.anchor
        adv = max(0.0, self.pos - p_c)
        lead = l0 + (OBS_LEAD_MS - l0) * min(1.0, adv / LEAD_RAMP_MS) if self.playing or adv > 0 else l0
        return min(self.t_max, t_c + adv + lead)

    def _sample(self, note):
        self.samples.append({"clock": self.clock, "strip_t": round(self.strip_t(), 3), "pos": round(self.pos, 3),
                             "phase": self.phase, "gen": self.gen, "playing": self.playing,
                             "resume": self.resume, "follow": self.anchor is not None, "note": note})

    def _log(self, event, outcome):
        self.log.append({"clock": self.clock, "event": event, "outcome": outcome, "gen": self.gen,
                         "phase": self.phase, "playing": self.playing, "resume": self.resume,
                         "strip_t": round(self.strip_t(), 2), "pos": round(self.pos, 2)})
        self._sample(event)

    def clamp(self, t):
        return min(self.t_max, max(0.0, float(t)))

    # ---- pointer / scrub ----
    def down(self):
        """Begin a scrub: initial hold (180 ms reached), re-drag of the open seeker, or interrupt."""
        prev = self.phase
        if prev in ("flinging", "settling"):
            kind = "interrupt " + prev
            resume = self.resume              # transfer the ORIGINAL intent; the player is already paused by us
        else:
            kind = "begin from " + ("playing" if self.playing else "paused")
            resume = self.playing             # snapshot of the user's play state
        t = self.strip_t()
        self.gen += 1
        self.resume = resume
        self.playing = False                  # pause synchronously
        self.anchor = None                    # follow stops for the whole scrub
        self.t_sel = self.clamp(t)            # visual target = where the strip already is (no jump)
        self.exact = None
        self.extra = []
        self.live_preview = None              # older generation's requests are superseded
        self.collapsed = False
        self.phase = "dragging"
        self._log("down", f"{kind}; gen {self.gen}; resume intent {resume}; T held at {round(self.t_sel, 2)}")

    def move(self, t):
        assert self.phase in ("dragging", "flinging")
        self.t_sel = self.clamp(t)
        self.seq += 1
        tok = (self.gen, "sync", self.seq, self.t_sel)
        self.live_preview = tok               # issuing a request supersedes every earlier one
        self._log("move" if self.phase == "dragging" else "fling step", f"T {round(self.t_sel, 2)}; CLOSEST_SYNC seq {self.seq}")
        return tok

    def release(self, fling=False):
        assert self.phase == "dragging"
        if fling:
            self.phase = "flinging"
            self._log("up (fling)", "fling owns the strip; still paused")
            return None
        return self._settle("up")

    def fling_end(self):
        assert self.phase == "flinging"
        return self._settle("fling rest")

    def _settle(self, why):
        self.phase = "settling"
        self.exact = (self.gen, "exact", 0, self.t_sel)
        self._log(why, f"settling: EXACT seek to T_r {round(self.t_sel, 2)}; offset and T held; follow off")
        return self.exact

    # ---- async results ----
    def preview_result(self, tok, pts, source="decoder"):
        gen, kind, seq, target = tok
        if tok != self.live_preview or self.phase not in ("dragging", "flinging", "settling"):
            live = "none" if self.live_preview is None else f"gen {self.live_preview[0]} seq {self.live_preview[2]}"
            self._log("preview result", f"DROPPED stale (gen {gen} seq {seq}; live request {live}, phase {self.phase})")
            return False
        self.live_preview = None              # one result per request
        self.extra.append((pts, f"{source} gen {gen} seq {seq}"))
        self.preview = select_preview(self.t_sel, self.tiles, self.d, self.n, self.extra)
        self._log("preview result", f"accepted {source} PTS {pts}; preview now {self.preview['source']} ({self.preview['status']})")
        return True

    def exact_result(self, tok, ok=True, pts=None):
        if tok != self.exact or self.phase not in ("settling", "cancelled"):
            self._log("exact result", f"DROPPED stale (token gen {tok[0]} T {round(tok[3], 2)}; live gen {self.gen}, phase {self.phase})")
            return False
        self.live_preview = None              # commit, cancel and error invalidate every preview request
        if not ok:
            self.phase = "error"
            self.collapsed = True
            self.resume = False
            self.exact = None
            self.preview = dict(select_preview(self.t_sel, self.tiles, self.d, self.n, self.extra))
            self._log("exact result", f"FAILED: stay paused at T {round(self.t_sel, 2)}; keep approximate preview; no resume")
            return True
        self.pos = self.t_sel                 # player position reports the requested seek position
        self.preview = {"source": "exact decode", "pts": pts,
                        "status": "decoded PTS reported" if pts is not None else "decoded PTS unknown",
                        "error_ms": None if pts is None else round(abs(pts - self.t_sel), 2)}
        self.exact = None
        if self.phase == "cancelled":
            # the page becomes an ordinary paused page at T (seeker collapsed); normal Play works again
            self.anchor = (self.t_sel, self.pos, 0.0)
            self.phase = "follow"
            self.playing = self.resume        # only an explicit Play tap made after the cancel sets this
            self.resume = False
            self._log("exact result", "cancel's paused frame applied; paused page owns playback again" +
                      ("; Play tapped meanwhile: play" if self.playing else ""))
            return True
        self.anchor = (self.t_sel, self.pos, 0.0)   # follow re-anchors at the committed T, lead 0
        self.phase = "follow"
        self.playing = self.resume
        self.resume = False
        self._log("exact result", f"confirmed; follow anchored at T_c {round(self.t_sel, 2)}; " +
                  ("resume playback" if self.playing else "stay paused"))
        return True

    # ---- player / lifecycle ----
    def advance(self, ms):
        self.clock += ms
        if self.playing:
            self.pos = min(self.t_max, self.pos + ms)
        self._sample("tick")

    def play(self):
        if self.phase == "error":
            self._log("play", "ignored: #142 error state owns recovery (retry reloads the player)")
        elif self.phase == "cancelled":
            self.resume = True
            self._log("play", "Play tapped while the cancel's exact seek is pending: plays from T once it lands")
        elif self.phase in ("settling", "flinging"):
            self.resume = True
            self._log("play", "resume intent set; playback starts only after exact confirmation")
        elif self.phase == "follow" and not self.playing:
            self.anchor = (self.pos, self.pos, self.strip_t() - self.pos)
            self.playing = True
            self._log("play", "playing; lead continues from where it stood")
        else:
            self._log("play", "ignored (second pointer during drag, or already playing)")

    def pause(self):
        if self.phase in ("settling", "flinging", "cancelled"):
            self.resume = False
            self._log("pause", "resume intent cleared; commit still completes, stays paused")
        elif self.phase == "follow" and self.playing:
            s = self.strip_t()
            self.anchor = (self.pos, self.pos, s - self.pos)  # freeze the strip where it is; no backward jump
            self.playing = False
            self._log("pause", "paused; strip frozen in place")
        else:
            self._log("pause", "ignored")

    def cancel(self, reason):
        if self.phase in ("dragging", "flinging", "settling"):
            self.phase = "cancelled"
            self.collapsed = True
            self.live_preview = None
            self.resume = False
            self.exact = (self.gen, "exact", 1, self.t_sel)   # exact frame for the paused page, never a resume
            self._log("cancel", f"{reason}: stay paused at last selected T {round(self.t_sel, 2)}; collapse")
            return self.exact
        if self.playing:
            s = self.strip_t()
            self.anchor = (self.pos, self.pos, s - self.pos)
            self.playing = False
        self.collapsed = True
        self._log("cancel", f"{reason}: not scrubbing; playback paused (V17), position kept")
        return None


# ---------------------------------------------------------------------------
# Contract cases
# ---------------------------------------------------------------------------
class Case:
    def __init__(self, name, desc):
        self.name, self.desc, self.checks, self.trace = name, desc, [], []

    def check(self, label, cond, detail=""):
        self.checks.append((label, bool(cond), detail))

    @property
    def ok(self):
        return all(c[1] for c in self.checks)


def max_step(samples, a, b):
    """Largest change of strip_t between consecutive samples in [a, b) of the sample list."""
    s = samples[a:b]
    return max((abs(s[i + 1]["strip_t"] - s[i]["strip_t"]) for i in range(len(s) - 1)), default=0.0)


def run_repeat_scrub():
    c = Case("C1 repeat scrub", "playing -> hold -> drag -> release -> confirm -> playing -> second scrub while playing -> confirm")
    s = Seeker(30_000, playing=True, pos=10_000)
    for _ in range(10):
        s.advance(16)
    t0 = s.strip_t()
    c.check("following leads playback by at most 200 ms", abs(t0 - s.pos) <= OBS_LEAD_MS + 1e-9, f"strip {t0} pos {s.pos}")
    s.down()
    c.check("scrub 1 begin pauses and snapshots playing", (not s.playing) and s.resume and s.gen == 1)
    c.check("begin does not move the strip", abs(s.strip_t() - t0) < 1e-9, f"{s.strip_t()} vs {t0}")
    tok_a = s.move(14_000)
    tok = s.move(15_000)
    c.check("superseded request A dropped on its FIRST arrival after B was issued", not s.preview_result(tok_a, 13_900))
    s.advance(60)
    c.check("newest request B accepted", s.preview_result(tok, 14_000))
    c.check("a second result for B is dropped", not s.preview_result(tok, 14_000))
    c.check("no follow while dragging", s.anchor is None)
    ex = s.release()
    t_r = s.t_sel
    s.advance(16)
    s.advance(16)
    c.check("settling: T and offset held, still paused", s.strip_t() == t_r and not s.playing)
    i_confirm = len(s.samples)
    s.exact_result(ex, True, 15_000)
    c.check("resume only after exact confirmation", s.playing and s.phase == "follow")
    c.check("late preview after exact confirmation cannot replace the committed frame",
            not s.preview_result(tok, 14_000) and s.preview["source"] == "exact decode")
    before = s.strip_t()
    c.check("no +200 ms jump at resume", abs(before - t_r) < 1e-9, f"strip {before} T_r {t_r}")
    for _ in range(40):
        s.advance(16)
    c.check("lead reintroduced continuously (max step per 16 ms frame <= 16*1.5 ms)",
            max_step(s.samples, i_confirm, len(s.samples)) <= 16 * (1 + OBS_LEAD_MS / LEAD_RAMP_MS) + 1e-9,
            f"max step {round(max_step(s.samples, i_confirm, len(s.samples)), 3)} ms")
    c.check("lead reaches 200 ms after 400 ms of playback", abs(s.strip_t() - s.pos - OBS_LEAD_MS) < 1e-9,
            f"lead {round(s.strip_t() - s.pos, 3)}")
    t1 = s.strip_t()
    s.down()
    c.check("scrub 2 begin (playing) pauses again with a fresh snapshot", s.gen == 2 and s.resume and not s.playing)
    c.check("scrub 2 begin holds the strip in place", abs(s.strip_t() - t1) < 1e-9)
    s.move(5_000)
    ex2 = s.release()
    s.exact_result(ex, True, 15_000)  # late duplicate of scrub 1's confirmation
    c.check("scrub 1 confirmation arriving again is dropped", s.phase == "settling" and not s.playing)
    s.exact_result(ex2, True, 5_000)
    c.check("scrub 2 commits at its own T and resumes", s.playing and s.pos == 5_000 and s.strip_t() == 5_000)
    c.trace = s.log
    return c, s


def run_interrupted_fling():
    c = Case("C2 interrupted fling", "playing -> scrub A -> fling -> finger down mid-fling -> scrub B -> release -> confirm")
    s = Seeker(30_000, playing=True, pos=8_000)
    s.down()
    tok_a = s.move(9_000)
    s.release(fling=True)
    tok_f = s.move(11_000)
    c.check("fling: still paused, no exact commit yet", not s.playing and s.exact is None)
    s.down()
    c.check("interrupt transfers the ORIGINAL resume intent (true)", s.resume and s.gen == 2)
    c.check("interrupt holds T where the fling stopped", s.t_sel == 11_000)
    c.check("player was never resumed between A and B", all(not x["playing"] for x in s.samples[1:]))
    c.check("A's late preview callback is dropped", not s.preview_result(tok_f, 11_000))
    c.check("A's older preview callback is dropped", not s.preview_result(tok_a, 9_000))
    s.move(12_500)
    ex = s.release()
    s.exact_result(ex, True, 12_500)
    c.check("resume after B's confirmation only", s.playing and s.strip_t() == 12_500)
    # paused variant: intent false is also transferred, never upgraded
    p = Seeker(30_000, playing=False, pos=8_000)
    p.down(); p.move(9_000); p.release(fling=True); p.move(10_000); p.down()
    c.check("paused variant transfers resume intent false", p.resume is False)
    ex = p.release()
    p.exact_result(ex, True, 10_000)
    c.check("paused variant stays paused after commit", not p.playing)
    c.trace = s.log
    return c, s


def run_interrupted_settle():
    c = Case("C3 down during commit", "release -> EXACT pending -> finger down again -> old confirmation arrives late")
    s = Seeker(30_000, playing=True, pos=3_000)
    s.down()
    t1 = s.move(3_500)
    t2 = s.move(4_000)
    c.check("same-gen out of order: newer seq 2 accepted, then older seq 1 dropped",
            s.preview_result(t2, 4_000) and not s.preview_result(t1, 3_500))
    ex_a = s.release()
    s.down()
    c.check("interrupting a pending commit keeps resume intent", s.resume and s.gen == 2 and not s.playing)
    c.check("late confirmation for gen 1 is dropped, no premature resume", not s.exact_result(ex_a, True, 4_000) and not s.playing)
    s.move(6_000)
    ex_b = s.release()
    s.exact_result(ex_b, True, 6_000)
    c.check("gen 2 commit resumes", s.playing and s.pos == 6_000)
    c.trace = s.log
    return c, s


def run_pause_resume_cancel():
    c = Case("C4 pause, resume, cancel, background", "play/pause during commit; cancel mid-drag; backgrounded while following")
    s = Seeker(30_000, playing=True, pos=1_000)
    s.down(); s.move(2_000)
    ex = s.release()
    s.pause()
    s.exact_result(ex, True, 2_000)
    c.check("pause during settling: commit completes, stays paused", not s.playing and s.phase == "follow")
    t = s.strip_t()
    s.play()
    s.advance(16)
    c.check("play from paused follow is continuous", abs(s.strip_t() - t) <= 16 * 1.5 + 1e-9)
    s.down(); s.move(7_000)
    ex2 = s.release()
    s.pause(); s.play()
    s.exact_result(ex2, True, 7_000)
    c.check("play during settling resumes only after confirmation", s.playing and s.pos == 7_000)
    s.down()
    tp = s.move(9_500)
    cx = s.cancel("audio focus loss")
    c.check("cancel stays paused with last selected T", (not s.playing) and s.t_sel == 9_500 and not s.resume)
    c.check("late preview after cancel is dropped", not s.preview_result(tp, 9_400))
    s.exact_result(cx, True, 9_500)
    c.check("cancel's exact frame never resumes", not s.playing and s.pos == 9_500)
    c.check("a late duplicate of the cancel's exact result is dropped", not s.exact_result(cx, True, 9_500))
    s.play()
    s.advance(16)
    c.check("after cancel, ordinary Play works without a new scrub (seeker collapsed)",
            s.playing and s.collapsed and s.pos == 9_516, f"pos {s.pos}")
    s.down(); s.move(13_000)
    cy = s.cancel("ACTION_CANCEL (back swipe)")
    s.play()
    c.check("Play tapped while the cancel's exact seek is pending does not play early", not s.playing)
    s.exact_result(cy, True, 13_000)
    c.check("... and plays from the cancelled T once it lands", s.playing and s.pos == 13_000)
    s.down(); s.move(12_000)
    ex3 = s.release()
    s.exact_result(ex3, True, 12_000)
    for _ in range(30):
        s.advance(16)
    t = s.strip_t()
    s.pause()
    s.advance(16)
    c.check("pause while following: strip frozen in place (no backward jump)", abs(s.strip_t() - t) < 1e-9, f"{s.strip_t()} vs {t}")
    s.play()
    s.advance(16)
    c.check("resume after pause: continuous, lead keeps ramping", 0 < s.strip_t() - t <= 16 * 1.5 + 1e-9, f"step {round(s.strip_t() - t, 3)}")
    b = Seeker(30_000, playing=True, pos=4_000)
    for _ in range(5):
        b.advance(16)
    t = b.strip_t()
    b.cancel("lifecycle stop (backgrounded)")
    c.check("backgrounded while following: paused, strip frozen in place", (not b.playing) and abs(b.strip_t() - t) < 1e-9)
    pos = b.pos
    b.play()
    b.advance(16)
    c.check("on return, Play resumes from the remembered position", b.playing and b.pos == pos + 16)
    g = Seeker(30_000, playing=True, pos=4_000)
    g.down(); g.move(6_000)
    gx = g.release()
    g.cancel("lifecycle stop (backgrounded mid-commit)")
    g.exact_result(gx, True, 6_000)
    c.check("backgrounded mid-commit: the scrub's own confirmation is dropped, no resume", not g.playing and g.phase == "cancelled")
    c.trace = s.log
    return c, s


def run_decode_failure():
    c = Case("C5 exact decode fails", "release -> EXACT seek reports an error")
    s = Seeker(30_000, playing=True, pos=1_000)
    s.down()
    tok = s.move(20_000)
    s.preview_result(tok, 19_000)
    ex = s.release()
    s.exact_result(ex, ok=False)
    c.check("stays paused, T retained, no resume", (not s.playing) and s.t_sel == 20_000 and s.phase == "error")
    c.check("preview stays approximate, never claimed exact", s.preview["status"].startswith("approximate"),
            s.preview["status"])
    s.play()
    c.check("unlike cancel, Play does not recover from an exact failure: #142 error state owns retry", not s.playing)
    c.check("late preview after the failure is dropped", not s.preview_result(tok, 19_500))
    c.trace = s.log
    return c, s


def run_endpoints():
    c = Case("C6 endpoints d=30 s, N=10", "nominal grid vs requested T at both ends; endpoint-specific decode")
    d, n = 30_000, obs_frame_count(30)
    nom = nominal_offsets(d, n)
    half = tile_interval(d, n) / 2
    t_end = d - 1000 / 30          # last decodable PTS of a 30 fps synthetic fixture
    c.check("last nominal tile is 26.91 s", abs(nom[-1] - 26_910) < 1e-9, f"{nom[-1]}")
    gap = t_end - nom[-1]
    c.check("end gap 29.97 s - 26.91 s = 3.06 s exceeds half a tile interval (1.495 s)",
            round(gap) == 3057 and gap > half, f"gap {round(gap, 2)} ms, half interval {half}")
    pv = select_preview(t_end, nom, d, n)
    c.check("near end: preview is tile 9, approximate with 3,057 ms error, endpoint decode requested",
            pv["source"] == "tile 9" and round(pv["error_ms"]) == 3057 and pv["endpoint_decode"], str(pv))
    s = Seeker(d, playing=False, pos=25_000, last_pts=t_end)
    s.down()
    old = s.move(29_000)
    tok = s.move(40_000)
    c.check("end clamp uses the last decodable PTS", s.t_sel == t_end, f"T {s.t_sel}")
    before = dict(s.preview)
    c.check("superseded request dropped on first arrival after the endpoint request was issued", not s.preview_result(old, 28_000))
    s.preview_result(tok, t_end, source="endpoint decode")
    c.check("matching-generation endpoint frame replaces the approximate preview", s.preview["source"].startswith("endpoint decode")
            and s.preview["error_ms"] == 0, f"before {before['source']}, after {s.preview['source']}")
    c.check("requested T never changed by preview results", s.t_sel == t_end)
    pv0 = select_preview(0, nom, d, n)
    c.check("start: tile 0 at PTS 0 serves T=0 with zero error, no endpoint decode", pv0["error_ms"] == 0 and not pv0["endpoint_decode"])
    pv28 = select_preview(28_000, nom, d, n)
    c.check("end region starts right after the last tile: T=28.0 s (1,090 ms from tile 9, < half) needs no decode",
            pv28["endpoint_region"] == "end" and not pv28["endpoint_decode"], str(pv28))
    pv29 = select_preview(28_500, nom, d, n)
    c.check("T=28.5 s (1,590 ms from tile 9, > half) requests an end decode", pv29["endpoint_decode"], str(pv29))
    tiles_sync = [400.0] + nom[1:]          # first tile decoded at a sync frame 0.4 s in
    pv0s = select_preview(0, tiles_sync, d, n)
    c.check("start with tile 0 at a later sync PTS: approximate 400 ms error, no false exact", pv0s["error_ms"] == 400 and
            pv0s["status"] == "approximate, PTS known", str(pv0s))
    tiles_far = [2_000.0] + nom[1:]         # tile 0 decoded 2 s in (long GOP)
    pvf = select_preview(0, tiles_far, d, n)
    c.check("start fallback: T=0 with tile 0 at PTS 2.0 s (> half interval) requests a start decode",
            pvf["endpoint_region"] == "start" and pvf["endpoint_decode"], str(pvf))
    u = Seeker(d, playing=False, pos=0, last_pts=None)
    c.check("unknown last PTS: clamp falls back to d - 100 ms and says so", u.t_max == d - 100 and "approximate" in u.t_max_basis)
    c.trace = s.log
    return c, s


def run_pts_truth():
    c = Case("C7 VFR, unknown PTS, short and long clips", "select by decoded PTS; never infer PTS from the grid")
    d, n = 30_000, 10
    nom = nominal_offsets(d, n)
    vfr = list(nom)
    vfr[3], vfr[4] = 9_100.0, 11_300.0       # VFR: decoded PTS differ from nominal 8,970 / 11,960
    pv = select_preview(10_300, vfr, d, n)
    pn = select_preview(10_300, nom, d, n)
    c.check("VFR: decoded PTS picks tile 4 (1,000 ms away), not nominal-nearest tile 3", pv["source"] == "tile 4" and pn["source"] == "tile 3",
            f"by PTS {pv['source']} err {pv['error_ms']}; by nominal {pn['source']}")
    unk = [None] * n
    pu = select_preview(10_300, unk, d, n)
    c.check("unknown PTS: preview marked approximate/unknown, no PTS reported", pu["pts"] is None and pu["status"] == "approximate, PTS unknown")
    pue = select_preview(29_900, unk, d, 10)
    c.check("unknown PTS at the end still requests an endpoint decode", pue["endpoint_decode"])
    pu28 = select_preview(28_000, unk, d, 10)
    c.check("unknown PTS: T=28.0 s, just past the last nominal tile (26.91 s), requests an end decode", pu28["endpoint_decode"], str(pu28))
    pu0 = select_preview(0, unk, d, 10)
    c.check("unknown PTS: T=0 requests a start decode (tile 0's sync PTS unknown)", pu0["endpoint_region"] == "start" and pu0["endpoint_decode"], str(pu0))
    pum = select_preview(10_300, unk, d, 10)
    c.check("unknown PTS mid-clip: no endpoint decode (60 ms sync frame is the path)", not pum["endpoint_decode"])
    ds, ns = 300, obs_frame_count(0.3)
    gs = end_clamp(ds, 300 - 1000 / 30)[0] - nominal_offsets(ds, ns)[-1]
    c.check("short clip 300 ms: N=3, last nominal 133 ms, end gap 133 ms", ns == 3 and round(gs) == 133, f"gap {round(gs, 2)}")
    dl = 5_025_000
    nl = obs_frame_count(dl / 1000)
    gl = (dl - 1000 / 30) - nominal_offsets(dl, nl)[-1]
    c.check("long clip 1:23:45: N=25, end gap about 201 s", nl == 25 and round(gl / 1000) == 201, f"gap {round(gl)} ms")
    c.trace = []
    return c, None


CASES = [run_repeat_scrub, run_interrupted_fling, run_interrupted_settle, run_pause_resume_cancel,
         run_decode_failure, run_endpoints, run_pts_truth]


def endpoint_table():
    rows = []
    for d in (300, 1_000, 4_000, 30_000, 120_000, 5_025_000):
        n = obs_frame_count(d / 1000)
        nom = nominal_offsets(d, n)
        t_end = d - 1000 / 30
        rows.append((d, n, round(tile_interval(d, n), 2), round(tile_interval(d, n) / 2, 2), round(nom[-1], 2),
                     round(t_end, 2), round(t_end - nom[-1], 2)))
    return rows


def run_all():
    results = [f() for f in CASES]
    lines = ["# Contract check output (generated by contract.py; do not edit)", "",
             "Synthetic reference model only: no Android, Media3 or emulator run. "
             "Each case drives the proposed contract with explicit events and asserts it.", ""]
    total = sum(len(c.checks) for c, _ in results)
    passed = sum(sum(1 for ch in c.checks if ch[1]) for c, _ in results)
    lines += [f"**Result: {passed}/{total} assertions pass across {len(results)} cases.**", ""]
    lines += ["## E1 Endpoint gap of the nominal grid (30 fps fixture, last PTS = d − 33.33 ms)", "",
              "| d ms | N | Tile interval ms | Half interval ms | Last nominal tile ms | Last PTS ms | End gap ms |",
              "|---|---|---|---|---|---|---|"]
    for r in endpoint_table():
        lines.append("| " + " | ".join(str(v) for v in r) + " |")
    lines += ["", "Every row's end gap exceeds half a tile interval, so the old ‘at most half a tile interval’ bound was false at the end.", ""]
    for c, _ in results:
        lines += [f"## {c.name}: {'PASS' if c.ok else 'FAIL'}", "", c.desc, ""]
        for label, ok, detail in c.checks:
            lines.append(f"- {'PASS' if ok else 'FAIL'}: {label}" + (f" ({detail})" if detail else ""))
        if c.trace:
            lines += ["", "| # | Event | Outcome | gen | phase | playing | resume intent | strip T | player pos |", "|---|---|---|---|---|---|---|---|---|"]
            for i, e in enumerate(c.trace, 1):
                lines.append(f"| {i} | {e['event']} | {e['outcome']} | {e['gen']} | {e['phase']} | {e['playing']} | {e['resume']} | {e['strip_t']} | {e['pos']} |")
        lines.append("")
    (HERE / "contract-cases.md").write_text("\n".join(lines))
    return results, passed, total


if __name__ == "__main__":
    res, passed, total = run_all()
    for c, _ in res:
        print(("PASS " if c.ok else "FAIL ") + c.name)
        for label, ok, detail in c.checks:
            if not ok:
                print("   FAIL:", label, detail)
    print(f"{passed}/{total} assertions pass")
    sys.exit(0 if passed == total else 1)
