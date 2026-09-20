# Offline models

SnapTranslate runs fully offline: the app has no `INTERNET` permission and never downloads a model.
**Deploying a retrained model means replacing a file** — no code change.

| Task | Model | Runtime | Where it lives |
|---|---|---|---|
| OCR | PaddleOCR **PP-OCRv6** small (text detection + text recognition) | ONNX Runtime | bundled in the APK (`assets`) |
| Translation | **facebook/nllb-200-distilled-600M** | ONNX Runtime | device storage (about 1 GB, far too big for an APK) |

## Bundled OCR models (`app/src/main/assets`)

```
app/src/main/assets/
  PP-OCRv6_small_det.onnx      text detection                     <- you add this
  PP-OCRv6_small_rec.onnx      text recognition                   <- you add this
  PP-OCRv6_small_rec.txt       the recognizer's character list    (in the repo)
```

* The two `.onnx` files are not in git (`*.onnx` is ignored, like the other model binaries): copy
  them into the folder before building. Without them the app builds and runs, but OCR reports
  "Models not installed".
* **The `.txt` must belong to the `.onnx`.** It is the `character_dict` of `PP-OCRv6_small_rec`'s
  `inference.yml`, one character per line (18,708 of them; PP-OCRv5's list has 18,383 and is *not*
  interchangeable). It is found by name: same file name as the recognizer, with `.txt`. The app
  checks the count against the model and refuses a mismatch with a clear message, because the
  alternative is silently wrong characters. If you retrain with a different character set, replace
  this file.
* File names are matched by suffix, so other PaddleOCR models work the same way: `*_det.onnx` is
  the detector, `*_rec.onnx` the main recognizer, and a script-specific recognizer keeps its key
  as a prefix (`eslav_PP-OCRv5_mobile_rec.onnx` + `.txt`). The app's own names (`det.onnx`,
  `rec_<key>.onnx`) work too.
* `.onnx` assets are stored uncompressed (`noCompress` in `app/build.gradle`).

**What PP-OCRv6_small_rec can read** (checked against its character list): Latin including
accented letters, Greek, Chinese, Japanese. **Not** Cyrillic, Korean, Thai, Arabic or Devanagari —
so with only these two models, Russian and Ukrainian work as a *target* language but not as the
*source* (the text being photographed). To read Russian, add a Cyrillic recognizer, for example
PP-OCRv5's `eslav` model, to the assets or to storage (see below).

## Models on storage

A model on storage **takes priority over the bundled one**, so a retrained OCR model can be tried
by copying a file to the phone, without rebuilding the APK. NLLB is always loaded from storage.

```
models/
  ocr/                               optional: overrides / adds to the bundled OCR models
    det.onnx                         text detection (one model for all languages)
    rec_main.onnx   rec_main.txt     recognition + character dictionary
    rec_eslav.onnx  rec_eslav.txt    one pair per script family
    ...
  nllb/                              required for translation
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

| key | model | used for |
|---|---|---|
| `main` | `PP-OCRv6_small_rec` (bundled) | English, Chinese, Japanese, and the fallback for Latin-script languages. **Required.** |
| `latin` | `latin_PP-OCRv5_mobile_rec` | French, German, Spanish, … Optional: the bundled v6 `main` model already knows accented letters |
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
conda create -n paddle2onnx python=3.10 -y          # a dedicated environment for conversion
conda activate paddle2onnx
python -m pip install huggingface_hub pyyaml
python -m pip install paddlepaddle==3.1.1 -i https://www.paddlepaddle.org.cn/packages/stable/cpu/
python -m pip install paddlex
python -m paddlex --install paddle2onnx             # PaddleX's Paddle -> ONNX plugin

python tools/export_models.py --ocr main eslav --nllb prebuilt
```

**Pin PaddlePaddle to 3.1.1 for conversion.** paddle2onnx is a compiled extension linked against
PaddlePaddle's own libraries. The version PaddleX installs (2.0.2rc3) loads under PaddlePaddle 3.1.x
but not under 3.2.x, where it dies on import with `DLL load failed while importing
paddle2onnx_cpp2py_export: The specified procedure could not be found`
([Paddle2ONNX#1607](https://github.com/PaddlePaddle/Paddle2ONNX/issues/1607),
[PaddleOCR#17083](https://github.com/PaddlePaddle/PaddleOCR/issues/17083)). Conversion runs on the
CPU and happens once per model, so keep it in its own environment and train in whatever
PaddlePaddle version you need (the second report above does exactly that: trains on 3.2, converts
in a 3.1.1 environment).

Use `python -m pip`, not a bare `pip`: the packages must land in the same interpreter that runs the
script (with several Pythons / conda environments on one PC, a bare `pip` often installs into a
different one, and the script then fails with `No module named paddlex`). The script checks its
requirements before downloading anything — including that paddle2onnx really loads — and prints the
exact commands for whatever is wrong.

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

### PaddleOCR (PP-OCRv6 / PP-OCRv5)

Fine-tune with PaddleOCR and export to ONNX. Then either replace the file in
`app/src/main/assets` and rebuild, or copy it to `models/ocr/` on the phone to override the bundled
one. If you export through Paddle's *inference model* (`inference.json`, `inference.pdiparams`,
`inference.yml`), the script converts it and extracts the dictionary:

```
python tools/export_models.py --skip-det --ocr-rec main=./output/my_rec_infer
python tools/export_models.py --ocr-det ./output/my_det_infer        # retrained detector
```

What the app assumes (from the stock `inference.yml`; identical for v5 and v6 unless noted):

* **det** — input `[1,3,H,W]` float32, H and W multiples of 32, longest side ≤ 1280; channels in
  **BGR** order, `(x/255 - mean) / std` with mean `0.485,0.456,0.406`, std `0.229,0.224,0.225`.
  Output: text map `[1,1,H,W]`, as probabilities or as logits (a sigmoid is applied if values fall
  outside 0…1). Post-processing is DB with PP-OCRv6's values: `thresh 0.2`, `box_thresh 0.45`,
  `unclip_ratio 1.4`, `max_candidates 3000` — constants in `DbPostProcessor.java`. (PP-OCRv5:
  0.3 / 0.6 / 1.5 / 1000.)
* **rec** — input `[1,3,48,W]` float32, BGR, `(x/255 - 0.5) / 0.5`, W ≥ 320, right-padded with
  zeros. Output `[1,T,C]`, softmax scores or logits, decoded with greedy CTC where class 0 is the
  blank, classes 1…N are the lines of the dictionary, and class N+1 (if present) is the space.
  So `C` must be N+1 or N+2; anything else is rejected as a model/dictionary mismatch.
* The first input and first output of each model are used, whatever they are named.
* If you **change the character set**, ship the new dictionary next to the model (`.txt`, one
  character per line, UTF-8, LF line endings, no BOM). The export script writes it from
  `inference.yml`. (An ONNX file that carries the list in its metadata under `character`,
  newline-separated, also works without a `.txt`.)

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
