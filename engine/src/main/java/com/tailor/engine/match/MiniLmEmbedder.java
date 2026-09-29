package com.tailor.engine.match;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PHASE5_SPEC.md section 7 (step 5.2): {@code all-MiniLM-L6-v2} exported to ONNX, run in-process
 * with ONNX Runtime's Java bindings, CPU only. Tokenizer loaded straight from the model's own
 * {@code tokenizer.json} (HuggingFace format — not hand-reimplemented, so WordPiece tokenization
 * matches the model exactly); mean pooling over token embeddings with the attention mask, then L2
 * normalization (the model's standard sentence embedding). Model files live outside git; {@link
 * #loadFromEnv} reads their directory from {@code VARIANT_MODEL_DIR} (the Docker image mounts it
 * read-only). A SHA-256 mismatch against the pinned files, or an unpinned runtime version, is a
 * startup error — never silently tolerated, since a swapped model would change every score without
 * changing any test.
 */
public final class MiniLmEmbedder implements Embedder, AutoCloseable {

    /** Pinned SHA-256 of the operator-supplied model.onnx (compared case-insensitively). */
    static final String MODEL_SHA256 = "6FD5D72FE4589F189F8EBC006442DBB529BB7CE38F8082112682524616046452";
    /** Pinned SHA-256 of the operator-supplied tokenizer.json. */
    static final String TOKENIZER_SHA256 = "BE50C3628F2BF5BB5E3A7F17B1F74611B2561A3A27EEAB05E5AA30F411572037";
    /** Pinned ONNX Runtime artifact version (engine/build.gradle.kts' own dependency line). */
    static final String PINNED_RUNTIME_VERSION = "1.19.2";

    private static final int EMBEDDING_DIM = 384; // all-MiniLM-L6-v2's own hidden size

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final Set<String> modelInputNames;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();

    private MiniLmEmbedder(OrtEnvironment env, OrtSession session, HuggingFaceTokenizer tokenizer) throws Exception {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
        this.modelInputNames = session.getInputNames();
    }

    public static MiniLmEmbedder loadFromEnv() throws Exception {
        String dir = System.getenv("VARIANT_MODEL_DIR");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("VARIANT_MODEL_DIR is not set (PHASE5_SPEC.md section 7: "
                    + "model files live outside git; the runtime image mounts them read-only)");
        }
        return load(Path.of(dir));
    }

    public static MiniLmEmbedder load(Path modelDir) throws Exception {
        Path modelOnnx = modelDir.resolve("model.onnx");
        Path tokenizerJson = modelDir.resolve("tokenizer.json");
        verifyPin("model.onnx", modelOnnx, MODEL_SHA256);
        verifyPin("tokenizer.json", tokenizerJson, TOKENIZER_SHA256);
        verifyRuntimeVersion();

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        OrtSession session = env.createSession(modelOnnx.toString(), options);
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerJson);
        return new MiniLmEmbedder(env, session, tokenizer);
    }

    @Override
    public double similarity(String a, String b) {
        float[] va = embed(a);
        float[] vb = embed(b);
        double dot = 0;
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            dot += va[i] * vb[i];
        }
        // both vectors are already L2-normalized, so the dot product is the cosine directly;
        // clip anyway (PHASE5_SPEC.md section 2: "clipped to [0, 1]").
        return Math.max(0.0, Math.min(1.0, dot));
    }

    /** Embeds and L2-normalizes one text, memoized (a JD's requirement text is scored against
     * every candidate bullet, so this avoids re-running inference on the same text repeatedly). */
    float[] embed(String text) {
        return cache.computeIfAbsent(text, this::embedUncached);
    }

    private float[] embedUncached(String text) {
        try {
            Encoding encoding = tokenizer.encode(text);
            long[] ids = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] tokenTypeIds = encoding.getTypeIds();
            int seqLen = ids.length;

            Map<String, OnnxTensor> inputs = new java.util.LinkedHashMap<>();
            try {
                inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[] {1, seqLen}));
                if (modelInputNames.contains("attention_mask")) {
                    inputs.put("attention_mask",
                            OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), new long[] {1, seqLen}));
                }
                if (modelInputNames.contains("token_type_ids")) {
                    inputs.put("token_type_ids",
                            OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), new long[] {1, seqLen}));
                }

                try (OrtSession.Result result = session.run(inputs)) {
                    float[][][] tokenEmbeddings = (float[][][]) result.get(0).getValue();
                    return meanPoolAndNormalize(tokenEmbeddings[0], attentionMask);
                }
            } finally {
                for (OnnxTensor t : inputs.values()) {
                    t.close();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("MiniLM embedding failed for text of length " + text.length(), e);
        }
    }

    private static float[] meanPoolAndNormalize(float[][] tokenEmbeddings, long[] attentionMask) {
        float[] pooled = new float[EMBEDDING_DIM];
        double maskSum = 0;
        for (int t = 0; t < tokenEmbeddings.length; t++) {
            if (attentionMask[t] == 0) {
                continue;
            }
            maskSum += 1;
            float[] tok = tokenEmbeddings[t];
            for (int d = 0; d < EMBEDDING_DIM; d++) {
                pooled[d] += tok[d];
            }
        }
        if (maskSum == 0) {
            maskSum = 1; // degenerate (empty text): avoid dividing by zero, embedding stays zero
        }
        for (int d = 0; d < EMBEDDING_DIM; d++) {
            pooled[d] = (float) (pooled[d] / maskSum);
        }
        double norm = 0;
        for (float v : pooled) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < EMBEDDING_DIM; d++) {
                pooled[d] = (float) (pooled[d] / norm);
            }
        }
        return pooled;
    }

    private static void verifyPin(String label, Path file, String pinnedSha256) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(label + " not found at " + file.toAbsolutePath());
        }
        String actual = sha256Hex(file);
        if (!actual.equalsIgnoreCase(pinnedSha256)) {
            throw new IllegalStateException(label + " SHA-256 mismatch: expected " + pinnedSha256 + ", got "
                    + actual + " (" + file.toAbsolutePath() + ") — refusing to start with an unpinned model file");
        }
    }

    private static void verifyRuntimeVersion() {
        String actual = OrtEnvironment.class.getPackage().getImplementationVersion();
        if (actual == null) {
            return; // no manifest metadata available (e.g. an unusual packaging); nothing to compare
        }
        if (!actual.equals(PINNED_RUNTIME_VERSION)) {
            throw new IllegalStateException("ONNX Runtime version mismatch: pinned " + PINNED_RUNTIME_VERSION
                    + ", classpath has " + actual);
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException("failed to close the ONNX Runtime session", e);
        }
        tokenizer.close();
    }
}
