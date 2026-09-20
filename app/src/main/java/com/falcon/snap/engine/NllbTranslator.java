package com.falcon.snap.engine;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * facebook/nllb-200-distilled-600M on ONNX Runtime, in the layout produced by HuggingFace Optimum
 * (and used by the Xenova/transformers.js exports): an encoder plus either a merged decoder or a
 * decoder / decoder-with-past pair. Greedy decoding with a key/value cache.
 *
 * Inputs are fed by name from whatever the decoder declares, so a fine-tuned NLLB re-exported the
 * same way (any layer count or width) drops in without code changes. Not thread-safe.
 */
final class NllbTranslator implements AutoCloseable {
    private static final int MAX_SOURCE_TOKENS = 256;
    private static final int MAX_NEW_TOKENS = 256;
    private static final int MAX_REPEATS = 8;
    private static final String PAST_PREFIX = "past_key_values";
    private static final String PRESENT_PREFIX = "present";

    private NllbTokenizer tokenizer;
    private OrtSession encoder;
    /** The merged decoder, or the no-cache decoder used for the first step. */
    private OrtSession decoder;
    /** Null when {@link #decoder} is a merged decoder that handles both cases itself. */
    private OrtSession decoderWithPast;
    private long heads = 16;
    private long headDim = 64;

    NllbTranslator(ModelStore.NllbFiles files) throws OrtException, IOException {
        try {
            tokenizer = NllbTokenizer.load(files.tokenizer);
            encoder = Onnx.open(files.encoder);
            decoder = Onnx.open(files.decoder);
            decoderWithPast = files.decoderWithPast == null ? null : Onnx.open(files.decoderWithPast);
            readCacheShape();
        } catch (OrtException | IOException | RuntimeException e) {
            // Sessions hold hundreds of MB of native memory; never leave a half-built translator behind.
            close();
            throw e;
        }
    }

    /** One cache entry is [batch, heads, length, headDim]; the two fixed dims come from the model itself. */
    private void readCacheShape() throws OrtException, IOException {
        long foundHeads = heads;
        long foundHeadDim = headDim;
        OrtSession cached = decoderWithPast != null ? decoderWithPast : decoder;
        for (Map.Entry<String, NodeInfo> input : cached.getInputInfo().entrySet()) {
            if (input.getKey().startsWith(PAST_PREFIX) && input.getValue().getInfo() instanceof TensorInfo) {
                TensorInfo info = (TensorInfo) input.getValue().getInfo();
                if (info.type != OnnxJavaType.FLOAT) {
                    throw new IOException("This NLLB export uses " + info.type
                            + " tensors; use the fp32, int8 (\"quantized\") or q4 export instead");
                }
                long[] shape = info.getShape();
                if (shape.length == 4 && shape[1] > 0 && shape[3] > 0) {
                    foundHeads = shape[1];
                    foundHeadDim = shape[3];
                }
                break;
            }
        }
        heads = foundHeads;
        headDim = foundHeadDim;
    }

    boolean supports(String nllbCode) {
        return tokenizer.languageId(nllbCode) >= 0;
    }

    @Override
    public void close() {
        for (OrtSession session : new OrtSession[]{encoder, decoder, decoderWithPast}) {
            if (session != null) {
                try {
                    session.close();
                } catch (OrtException ignored) {
                    // Best effort.
                }
            }
        }
    }

    /** Translates one sentence or short passage. */
    String translate(String text, String sourceCode, String targetCode) throws OrtException {
        int sourceLanguage = tokenizer.languageId(sourceCode);
        int targetLanguage = tokenizer.languageId(targetCode);
        if (sourceLanguage < 0 || targetLanguage < 0) {
            throw new IllegalArgumentException("Language not in tokenizer: " + sourceCode + " / " + targetCode);
        }
        // NLLB source format: <language> tokens... </s>
        int[] body = tokenizer.encode(text);
        int bodyLength = Math.min(body.length, MAX_SOURCE_TOKENS - 2);
        long[] source = new long[bodyLength + 2];
        source[0] = sourceLanguage;
        for (int i = 0; i < bodyLength; i++) {
            source[i + 1] = body[i];
        }
        source[source.length - 1] = NllbTokenizer.EOS;
        long[] mask = new long[source.length];
        Arrays.fill(mask, 1L);
        long[] sourceShape = {1, source.length};

        try (OnnxTensor inputIds = OnnxTensor.createTensor(Onnx.env(), LongBuffer.wrap(source), sourceShape);
             OnnxTensor attentionMask = OnnxTensor.createTensor(Onnx.env(), LongBuffer.wrap(mask), sourceShape)) {
            Map<String, OnnxTensor> encoderInputs = new HashMap<>();
            encoderInputs.put("input_ids", inputIds);
            encoderInputs.put("attention_mask", attentionMask);
            try (OrtSession.Result encoded = encoder.run(encoderInputs)) {
                OnnxTensor hiddenStates = (OnnxTensor) encoded.get(0);
                int maxNew = Math.min(MAX_NEW_TOKENS, source.length * 3 + 10);
                List<Integer> output = generate(hiddenStates, attentionMask, targetLanguage, maxNew);
                return tokenizer.decode(output);
            }
        }
    }

    private List<Integer> generate(OnnxTensor hiddenStates, OnnxTensor encoderMask, int targetLanguage, int maxNew)
            throws OrtException {
        List<Integer> generated = new ArrayList<>();
        // The cache: "present.N.decoder.key" etc. -> tensor. Encoder entries come from the first
        // step and never change; decoder entries are replaced on every step.
        Map<String, OnnxTensor> cache = new HashMap<>();
        OrtSession.Result firstStep = null;
        OrtSession.Result lastStep = null;
        OnnxTensor emptyPast = null;
        try {
            emptyPast = OnnxTensor.createTensor(Onnx.env(),
                    FloatBuffer.allocate((int) (heads * headDim)), new long[]{1, heads, 1, headDim});

            // Decoder start: </s> followed by the target language token, which NLLB is "forced" to emit first.
            long[] next = {NllbTokenizer.EOS, targetLanguage};
            int repeats = 0;
            for (int step = 0; step <= maxNew; step++) {
                boolean first = step == 0;
                OrtSession session = first || decoderWithPast == null ? decoder : decoderWithPast;
                OrtSession.Result result = runDecoder(session, next, first, hiddenStates, encoderMask, cache, emptyPast);

                for (Map.Entry<String, OnnxValue> out : result) {
                    String name = out.getKey();
                    // After the first step a merged decoder returns placeholders for the encoder entries.
                    if (name.startsWith(PRESENT_PREFIX) && (first || name.contains(".decoder."))) {
                        cache.put(name, (OnnxTensor) out.getValue());
                    }
                }
                if (first) {
                    firstStep = result;
                } else {
                    if (lastStep != null) {
                        lastStep.close();
                    }
                    lastStep = result;
                }

                int token = argmaxLast((OnnxTensor) result.get("logits").get());
                if (token == NllbTokenizer.EOS) {
                    break;
                }
                repeats = !generated.isEmpty() && generated.get(generated.size() - 1) == token ? repeats + 1 : 0;
                if (repeats >= MAX_REPEATS) {
                    // The model got stuck; drop the stutter rather than emit it.
                    generated.subList(generated.size() - repeats, generated.size()).clear();
                    break;
                }
                generated.add(token);
                next = new long[]{token};
            }
        } finally {
            if (firstStep != null) {
                firstStep.close();
            }
            if (lastStep != null) {
                lastStep.close();
            }
            if (emptyPast != null) {
                emptyPast.close();
            }
        }
        return generated;
    }

    /** Feeds every input the decoder declares, by name. */
    private OrtSession.Result runDecoder(OrtSession session, long[] tokens, boolean first, OnnxTensor hiddenStates,
                                         OnnxTensor encoderMask, Map<String, OnnxTensor> cache, OnnxTensor emptyPast)
            throws OrtException {
        List<OnnxTensor> temporary = new ArrayList<>();
        try {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            for (String name : session.getInputNames()) {
                if (name.equals("input_ids")) {
                    OnnxTensor ids = OnnxTensor.createTensor(Onnx.env(), LongBuffer.wrap(tokens), new long[]{1, tokens.length});
                    temporary.add(ids);
                    inputs.put(name, ids);
                } else if (name.equals("encoder_hidden_states")) {
                    inputs.put(name, hiddenStates);
                } else if (name.equals("encoder_attention_mask")) {
                    inputs.put(name, encoderMask);
                } else if (name.equals("use_cache_branch")) {
                    OnnxTensor flag = OnnxTensor.createTensor(Onnx.env(), new boolean[]{!first});
                    temporary.add(flag);
                    inputs.put(name, flag);
                } else if (name.startsWith(PAST_PREFIX)) {
                    // On the first step a merged decoder ignores the cache inputs, but still needs them fed.
                    OnnxTensor past = first ? emptyPast : cache.get(PRESENT_PREFIX + name.substring(PAST_PREFIX.length()));
                    if (past == null) {
                        throw new IllegalStateException("Decoder input " + name + " has no matching output from the previous step");
                    }
                    inputs.put(name, past);
                }
            }
            return session.run(inputs);
        } finally {
            for (OnnxTensor tensor : temporary) {
                tensor.close();
            }
        }
    }

    /** Index of the largest logit at the last sequence position of a [1, length, vocabulary] tensor. */
    private static int argmaxLast(OnnxTensor logits) {
        long[] shape = logits.getInfo().getShape();
        int length = (int) shape[1];
        int vocabulary = (int) shape[2];
        FloatBuffer values = logits.getFloatBuffer();
        int offset = (length - 1) * vocabulary;
        int best = 0;
        float bestValue = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vocabulary; i++) {
            float value = values.get(offset + i);
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }
}
