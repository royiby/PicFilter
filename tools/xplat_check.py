"""
Cross-platform embedding consistency check.

Thresholds tuned on the Mac are worthless if the Mac and Android produce
different embeddings from the same bytes. This reproduces EXACTLY the synthetic
image and normalisation that Embed.modelCheck() uses on Android, so the two
embeddings can be compared numerically without needing any photo.

Android reference (Embed.kt):
    v = (x*7 + y*13 + seed*53) % 256
    pixel = ARGB(255, R=v, G=255-v, B=(v*2)%256)
    input  = ((channel) - 127.5) / 128   in R,G,B order
    output = L2-normalised
"""
import numpy as np
import tensorflow as tf

IN = 112
MODEL = "models/MobileFaceNet.tflite"


def synth(seed: int) -> np.ndarray:
    x = np.arange(IN)[None, :]
    y = np.arange(IN)[:, None]
    v = (x * 7 + y * 13 + seed * 53) % 256
    r = v
    g = 255 - v
    b = (v * 2) % 256
    return np.stack([r, g, b], axis=-1).astype(np.float32)


def l2(v):
    return v / max(float(np.sqrt((v * v).sum())), 1e-9)


def embed(interp, img_rgb: np.ndarray) -> np.ndarray:
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    batch = int(inp["shape"][0])
    norm = (img_rgb - 127.5) / 128.0
    # model's input batch is 2 (published for pairwise comparison): fill both slots
    x = np.repeat(norm[None, ...], batch, axis=0).astype(np.float32)
    interp.set_tensor(inp["index"], x)
    interp.invoke()
    return l2(interp.get_tensor(out["index"])[0])


def main():
    interp = tf.lite.Interpreter(model_path=MODEL)
    interp.allocate_tensors()
    a = embed(interp, synth(1))
    b = embed(interp, synth(99))
    print(f"CHECK dim={a.shape[0]}")
    print(f"CHECK vecA l2={float((a*a).sum()):.4f} "
          f"min={a.min():.4f} max={a.max():.4f} mean={a.mean():.4f}")
    print(f"CHECK cos(A,A)={float(a@a):.4f} cos(A,B)={float(a@b):.4f}")
    print("CHECK first8_A=" + ",".join(f"{v:.4f}" for v in a[:8]))
    print("CHECK first8_B=" + ",".join(f"{v:.4f}" for v in b[:8]))


if __name__ == "__main__":
    main()
