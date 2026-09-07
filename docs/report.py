"""Summarise how far bliss and Essentia disagree, and what it would cost."""
import json
import statistics
import sys


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            e, b = row.get("essentia") or {}, row.get("bliss") or {}
            if "bpm" in e and "bpm" in b and e["bpm"] > 0 and b["bpm"] > 0:
                rows.append(row)
    return rows


def clamp(v):
    return max(0.0, min(1.0, v))


def moods(bpm, onset_rate, centroid_hz):
    """The formulas acoustic.py ships today."""
    energy = clamp((onset_rate / 6.0) * 0.6 + (bpm / 180.0) * 0.4)
    brightness = clamp(centroid_hz / 4000.0)
    return {
        "energy": energy,
        "brightness": brightness,
        "aggressive": clamp(energy * 0.7 + brightness * 0.3),
        "relaxed": clamp(1.0 - energy),
        "danceable": clamp((bpm / 140.0) * 0.6 + (onset_rate / 6.0) * 0.4),
    }


def pct(n, total):
    return f"{n} ({100.0 * n / total:.0f}%)"


def main():
    rows = load(sys.argv[1] if len(sys.argv) > 1 else "pairs.jsonl")
    n = len(rows)
    if not n:
        print("no usable rows")
        return

    print(f"Tracks compared: {n}\n")

    # --- BPM -------------------------------------------------------------
    octave, agree, off = [], [], []
    ratios = []
    for r in rows:
        e, b = r["essentia"]["bpm"], r["bliss"]["bpm"]
        ratio = b / e
        ratios.append(ratio)
        # An octave error is the classic tempo failure: half or double.
        if abs(ratio - 2.0) < 0.06 or abs(ratio - 0.5) < 0.03:
            octave.append(r)
        elif abs(ratio - 1.0) <= 0.05:
            agree.append(r)
        else:
            off.append(r)

    print("== BPM ==")
    print(f"  within 5%      {pct(len(agree), n)}")
    print(f"  octave error   {pct(len(octave), n)}   (half or double tempo)")
    print(f"  other mismatch {pct(len(off), n)}")

    close = [r for r in agree]
    if close:
        errs = [
            100.0 * (r["bliss"]["bpm"] - r["essentia"]["bpm"]) / r["essentia"]["bpm"]
            for r in close
        ]
        print(f"\n  On the {len(close)} that agree, bliss reads:")
        print(f"    median  {statistics.median(errs):+.2f}%")
        print(f"    mean    {statistics.fmean(errs):+.2f}%")
        print(f"    stdev    {statistics.pstdev(errs):.2f}%")
        signed = sum(1 for x in errs if x > 0)
        print(f"    higher than Essentia on {pct(signed, len(errs))} of them")

    # Does Essentia's own confidence flag the octave errors?
    if octave:
        conf_bad = [r["essentia"].get("bpm_confidence", 0) for r in octave]
        conf_ok = [r["essentia"].get("bpm_confidence", 0) for r in agree]
        print(f"\n  Essentia beat confidence:")
        print(f"    where they agree      median {statistics.median(conf_ok):.2f}")
        print(f"    where octave-flipped  median {statistics.median(conf_bad):.2f}")

    print("\n  Worst disagreements:")
    for r in sorted(rows, key=lambda r: -abs(r["bliss"]["bpm"] / r["essentia"]["bpm"] - 1))[:8]:
        e, b = r["essentia"]["bpm"], r["bliss"]["bpm"]
        print(f"    {e:6.1f} -> {b:6.1f}  (x{b / e:.2f})  {r['path'][:56]}")

    # --- Brightness ------------------------------------------------------
    print("\n== Spectral centroid (feeds brightness) ==")
    head, mean_, bl = [], [], []
    for r in rows:
        e, b = r["essentia"], r["bliss"]
        if "centroid_head_hz" in e and "centroid_hz" in b:
            head.append(e["centroid_head_hz"])
            mean_.append(e["centroid_mean_hz"])
            bl.append(b["centroid_hz"])
    if head:
        print(f"  Essentia, first 0.74 s only (what ships)  median {statistics.median(head):7.0f} Hz")
        print("    ^ one window from the start of the track, usually the intro")
        print(f"  Essentia, whole-track mean                median {statistics.median(mean_):7.0f} Hz")
        print(f"  bliss,    whole-track mean                median {statistics.median(bl):7.0f} Hz")
        try:
            print(f"\n  correlation, bliss vs Essentia whole-track  r = {statistics.correlation(bl, mean_):.3f}")
            print(f"  correlation, bliss vs Essentia first-window  r = {statistics.correlation(bl, head):.3f}")
        except Exception:
            pass

    # --- Downstream mood effect -----------------------------------------
    print("\n== What this does to the mood axes ==")
    print("  (bliss has no onset-rate equivalent, so Essentia's is held constant;")
    print("   this isolates the effect of the BPM and centroid differences alone)")
    axes = ("energy", "brightness", "aggressive", "relaxed", "danceable")
    octave_set = {id(r) for r in octave}

    def mood_table(subset, label):
        deltas = {k: [] for k in axes}
        flips = {k: 0 for k in axes}
        for r in subset:
            e, b = r["essentia"], r["bliss"]
            if "onset_rate" not in e:
                continue
            me = moods(e["bpm"], e["onset_rate"], e["centroid_head_hz"])
            mb = moods(b["bpm"], e["onset_rate"], b["centroid_hz"])
            for k in axes:
                deltas[k].append(abs(mb[k] - me[k]))
                # Would a "> 0.5" style rule put the track on the other side?
                if (me[k] >= 0.5) != (mb[k] >= 0.5):
                    flips[k] += 1
        total = len(deltas["energy"])
        if not total:
            return
        print(f"\n  {label} (n={total})")
        print(f"  {'axis':<12}{'median':>10}{'worst':>10}{'crosses 0.5':>14}")
        for k in axes:
            vals = sorted(deltas[k])
            print(f"  {k:<12}{statistics.median(vals):>10.3f}{vals[-1]:>10.3f}"
                  f"{pct(flips[k], total):>14}")

    # Split, because the octave errors are the whole story and a median over
    # everything hides them.
    mood_table([r for r in rows if id(r) not in octave_set], "where BPM agrees")
    mood_table(octave, "where BPM is octave-flipped")

    # --- Can the disagreement be cheaply corrected? ----------------------
    print("\n== Mitigations, tested on this data ==")

    def score(transform, label):
        """How many tracks land within 5% of Essentia after [transform]."""
        good = 0
        for r in rows:
            e = r["essentia"]["bpm"]
            if abs(transform(r["bliss"]["bpm"]) / e - 1.0) <= 0.05:
                good += 1
        print(f"  {label:<44}{pct(good, n)}")

    score(lambda b: b, "as-is")

    # The agreeing tracks were all biased the same way, so try removing it.
    if close:
        bias = statistics.median(
            [r["bliss"]["bpm"] / r["essentia"]["bpm"] for r in close]
        )
        score(lambda b: b / bias, f"bias-corrected (divide by {bias:.4f})")

    # Octave folding: the standard fix, but it cuts genuinely fast tracks too.
    def fold(lo):
        def f(b):
            while b >= lo * 2:
                b /= 2
            while b < lo:
                b *= 2
            return b
        return f

    for lo in (60, 70, 80, 90):
        score(fold(lo), f"folded into [{lo}, {lo * 2})")

    # --- Speed -----------------------------------------------------------
    es_t = [r["essentia"]["seconds"] for r in rows if "seconds" in r["essentia"]]
    bl_t = [r["bliss"]["seconds"] for r in rows if "seconds" in r["bliss"]]
    if es_t and bl_t:
        print("\n== Speed ==")
        print(f"  Essentia  median {statistics.median(es_t):.2f} s/track")
        print(f"  bliss     median {statistics.median(bl_t):.2f} s/track"
              f"   ({statistics.median(es_t) / statistics.median(bl_t):.0f}x faster)")


if __name__ == "__main__":
    main()
