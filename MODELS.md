# Offline models

SnapTranslate runs fully offline. The app has no `INTERNET` permission and no model is bundled in
the APK: at run time it loads whatever model files it finds in a `models` folder on the device.
**Deploying a retrained model means replacing a file** — no code change, no new APK.

| Task | Model | Runtime |
|---|---|---|
| OCR | PaddleOCR **PP-OCRv5** (text detection + text recognition) | ONNX Runtime |
| Translation | **facebook/nllb-200-distilled-600M** | ONNX Runtime |

## Folder layout

```
models/
  ocr/
    det.onnx                         text detection (one model for all languages)
    rec_main.onnx   rec_main.txt     recognition + character dictionary
    rec_eslav.onnx  rec_eslav.txt    (optional, one pair per script family)
    ...
  nllb/
    encoder_model_quantized.onnx
    decoder_model_merged_quantized.onnx
    tokenizer.json
```

The app searches these locations, in order, and uses the first one that has the file:

1. `/sdcard/Android/data/com.falcon.snap/files/models/` — app folder on shared storage
2. the same path on each SD card (`/storage/<card>/Android/data/com.falcon.snap/files/models/`)
3. `/data/data/com.falcon.snap/files/models/` — private internal storage

OCR and NLLB are looked up separately, so the 0.9 GB NLLB files can live on the SD card while the
small OCR files sit in internal storage. **Settings › Offline Models** shows the exact path on the
device and what is installed or missing.

### Recognition models

`rec_<key>` — the key selects which languages use it (`Language.java`):

| key | PP-OCRv5 model | used for |
|---|---|---|
| `main` | `PP-OCRv5_mobile_rec` | English, Chinese, Japanese. **Required.** |
| `latin` | `latin_PP-OCRv5_mobile_rec` | French, German, Spanish, … (falls back to `main`, which loses accents) |
| `eslav` | `eslav_PP-OCRv5_mobile_rec` | Russian, Ukrainian (`cyrillic` is accepted as an alternative) |
| `th` | `th_PP-OCRv5_mobile_rec` | Thai |
| `arabic` | `arabic_PP-OCRv5_mobile_rec` | Arabic |
| `devanagari` | `devanagari_PP-OCRv5_mobile_rec` | Hindi |

A language can be picked as the *source* only if one of its recognizers is installed. Any language
can be a *target*; that only needs NLLB.

### NLLB files

Any of these weight variants is accepted, preferred in this order: `_quantized`, `_int8`, `_uint8`,
`_q4`, `_bnb4`, then plain fp32. **fp16 exports are not supported** (the app feeds float32 tensors).

Either a merged decoder (`decoder_model_merged*.onnx`, preferred: half the memory) or the pair
`decoder_model*.onnx` + `decoder_with_past_model*.onnx`.

Size / memory: int8 is about 0.9 GB on disk and roughly 1 GB of RAM while loaded, so plan for a
phone with 6 GB RAM or more. The model is loaded on the first translation (a few seconds) and
released when the app goes to the background.

## Getting the files

`tools/export_models.py` builds the folder on a PC (it needs Python and an internet connection;
the phone never does):

```
pip install huggingface_hub pyyaml
pip install paddlepaddle paddlex && paddlex --install paddle2onnx      # OCR conversion

python tools/export_models.py --ocr main latin eslav --nllb prebuilt
```

`--nllb prebuilt` downloads the ready-made int8 ONNX export (`Xenova/nllb-200-distilled-600M`).
The OCR models are converted from the official Paddle inference models (`PaddlePaddle/*` on the
Hugging Face hub) with `paddlex --paddle2onnx`, and each recognizer's character dictionary is
extracted from its `inference.yml`.

## Installing on the device

Over USB:

```
adb shell mkdir -p /sdcard/Android/data/com.falcon.snap/files/models
adb push models/. /sdcard/Android/data/com.falcon.snap/files/models/
```

Or copy the `models` folder anywhere on the phone / SD card / USB stick and use
**Settings › Offline Models › Import from folder…**. The importer recognises the files by name, so
both the `ocr/` + `nllb/` tree and a flat folder work.

Replaced files are picked up the next time they are used (the app compares size and modification
time); no restart needed.

## Retraining

The app depends only on the ONNX interface described here, not on the specific weights.

### PP-OCRv5 (PaddleOCR)

Fine-tune with PaddleOCR, export the *inference model* (`inference.json`, `inference.pdiparams`,
`inference.yml`), then:

```
python tools/export_models.py --skip-det --ocr-rec main=./output/my_rec_infer
python tools/export_models.py --ocr-det ./output/my_det_infer        # retrained detector
```

What the app assumes (all of it comes from the stock `inference.yml`):

* **det** — input `[1,3,H,W]` float32, H and W multiples of 32, longest side ≤ 1280; channels in
  **BGR** order, `(x/255 - mean) / std` with mean `0.485,0.456,0.406`, std `0.229,0.224,0.225`.
  Output: probability map `[1,1,H,W]`. Post-processing is DB: `thresh 0.3`, `box_thresh 0.6`,
  `unclip_ratio 1.5` (constants in `DbPostProcessor.java`).
* **rec** — input `[1,3,48,W]` float32, BGR, `(x/255 - 0.5) / 0.5`, W ≥ 320, right-padded with
  zeros. Output `[1,T,C]` softmax scores, decoded with greedy CTC where class 0 is the blank,
  classes 1…N are the lines of `rec_<key>.txt`, and class N+1 (if present) is the space.
* If you **change the character set**, ship the new dictionary as `rec_<key>.txt`. The export
  script does this for you from `inference.yml`. (An ONNX file that carries the list in its
  metadata under `character`, newline-separated, also works without a `.txt`.)

To add a recognizer for a new script, add a key to `Language.java` and install `rec_<key>.*`.

### NLLB-200

Fine-tune `facebook/nllb-200-distilled-600M` with Hugging Face Transformers, save the checkpoint
(model + tokenizer), then:

```
pip install "optimum[onnxruntime]" onnxruntime
python tools/export_models.py --nllb ./my-finetuned-nllb
```

This runs `optimum-cli export onnx --task text2text-generation-with-past` and quantizes the
encoder and the merged decoder to int8. What the app assumes:

* Optimum's tensor names: encoder `input_ids`, `attention_mask` → `last_hidden_state`; decoder
  `input_ids`, `encoder_attention_mask`, `encoder_hidden_states`, `past_key_values.*`,
  `use_cache_branch` → `logits`, `present.*`. Inputs are matched **by name**, and layer count, head
  count and head size are read from the model, so a different size of NLLB (1.3B, 3.3B) works too.
* `tokenizer.json` is the standard fast-tokenizer file: BPE `vocab` + `merges`, with the language
  codes (`eng_Latn`, `zho_Hans`, …) in `added_tokens`. Keep the tokenizer unchanged when
  fine-tuning. A new language needs its NLLB code added to `Language.java`.
* Decoding is greedy (beam size 1) with a KV cache; input is split into sentences and truncated to
  256 tokens per sentence.

## Licenses

PP-OCRv5 models: Apache-2.0. ONNX Runtime: MIT. **NLLB-200 weights are CC-BY-NC 4.0 —
non-commercial use only**, and that carries over to fine-tuned versions of them.
