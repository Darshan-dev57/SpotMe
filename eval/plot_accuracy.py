"""Draws docs/images/accuracy.png from eval/out/accuracy.json (precision and recall by threshold)."""
import json
import os

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "out", "accuracy.json")) as f:
    data = json.load(f)

INK, SAFE, MUTED = "#2a221c", "#c8361d", "#9a8f80"
fig, ax = plt.subplots(figsize=(8, 4.2), dpi=160)
for split, style in (("tune", "--"), ("test", "-")):
    rows = data[split]
    t = [r["threshold"] for r in rows]
    ax.plot(t, [r["precision"] for r in rows], style, color=INK, label=f"precision ({split})")
    ax.plot(t, [r["recall"] for r in rows], style, color=SAFE, label=f"recall ({split})")
# 0.55 was picked on this event; the app now uses 0.51, picked on the 11,251-photo scale test (MEASUREMENTS.md)
ax.axvline(0.55, color=MUTED, lw=1, ls=":")
ax.text(0.553, 0.32, "picked here: 0.55", color=MUTED, fontsize=9)
ax.axvline(0.51, color=MUTED, lw=1)
ax.text(0.513, 0.26, "used now: 0.51", color=MUTED, fontsize=9)
ax.set_xlabel("maximum cosine distance (lower = stricter)")
ax.set_ylim(0, 1.03)
ax.set_xlim(0.4, 0.8)
ax.set_title("Per-photo precision and recall, LFW evaluation event (tune and test splits)", fontsize=11, color=INK)
ax.grid(alpha=0.25)
ax.legend(fontsize=8, loc="lower left", frameon=False)
for side in ("top", "right"):
    ax.spines[side].set_visible(False)
fig.tight_layout()
out = os.path.join(HERE, "..", "docs", "images", "accuracy.png")
fig.savefig(out)
print("wrote", os.path.normpath(out))
