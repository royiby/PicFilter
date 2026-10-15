# MobileFaceNet.tflite

- Source: https://github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing
- Path in repo: `app/src/main/assets/MobileFaceNet.tflite`
- Repo license: MIT (284 stars, 84 forks)
- Downloaded: 2026-10-15T10:57:57Z
- Size: 5233396 bytes
- SHA-256: d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b

## Caveats
- **Third-party artifact.** No official Google/Kaggle TFLite face embedder exists;
  every available option is a community build. Verified here only as a well-formed
  TFLite flatbuffer with the expected tensor shapes.
- **Weight provenance is separate from the repo license.** MobileFaceNet is
  typically trained with InsightFace losses on MS1M (MS-Celeb-1M), which Microsoft
  withdrew. MIT covers the repo's code; the weights' training-data terms are murky.
  Fine for personal use; **get this cleared before any commercial release.**
- Must use the *same* model on Mac (evaluation) and Android (runtime), or the
  enrolled centroids will not transfer.
