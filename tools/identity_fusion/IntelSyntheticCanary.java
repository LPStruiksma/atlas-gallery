import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Minimal Java-8/Android-compatible, model-free artificial-image canary.
 *
 * No Android APIs, disk input, assets, network, camera, users, model graphs,
 * or third-party dependencies. A caller injects its own RGB resize callback.
 * Published-OMZ Market1501 evaluation-step goldens are Pillow 12.3.0 only,
 * NOT evidence of actual ONNX/IR input parity or identity accuracy.
 */
public final class IntelSyntheticCanary {
    public static final int TARGET_WIDTH = 128;
    public static final int TARGET_HEIGHT = 256;
    private static final int PIXELS = TARGET_WIDTH * TARGET_HEIGHT;
    private static final String[] NAMES = {"gradient", "checker", "colorbars", "impulse"};
    private static final int[] WIDTH = {341, 199, 128, 65};
    private static final int[] HEIGHT = {173, 397, 256, 83};
    private static final String[] INPUT_HASH = {
        "561d7a8282b1009c32b81725ec1e853ce5137670fbdf98c83ae492f5a4d42d67",
        "f7efebb2a00a063dbb5a10e81526394519f0d328362d66387adacde7c6c34e19",
        "557bcb50a18be07eb21b61e07b1ce31c510d7f7c1f854c244037b00b56bc8a7e",
        "557d233b74c884c3eec07da9fe07c452cfbaa0b1bb6cf3ee39c64881010bdcc0"
    };
    private static final String[] PILLOW_RGB_HASH = {
        "8775971d006d9562e314f5364c35f9a56f66cde265cff8a1f5d2f63f34eabaa5",
        "3e972a66b36bfe40c3c91b749e5f0499e79fa4794b8b4df469f45a695435c980",
        "557bcb50a18be07eb21b61e07b1ce31c510d7f7c1f854c244037b00b56bc8a7e",
        "ee20f8a19b4ae60776dbcb2c85e5942f952bd303cc56091a036c79defc5971a2"
    };
    private static final String[] PILLOW_BGR_TENSOR_HASH = {
        "648d4a04d6f63edb6d60f76b7402fe8f03727c1675456564c52ec4a14a58075c",
        "4cb9600d5ac44d1f949a68b990dc5a8c57fa477454b4d42e426c1959d6fb1b04",
        "4bb93c3050b8595ddf84aacb7e49c5bf5996995d7d7e64ebad641746e9564391",
        "fdc32dff70634041782eeb4254dd9a75bc1ed930b4f95ffb621b65e8593bff31"
    };

    private IntelSyntheticCanary() { }

    /** Callback: resize these GENERATED RGB bytes to exactly 128x256 RGB. */
    public interface RgbResize {
        byte[] resize(int sourceWidth, int sourceHeight, byte[] generatedRgb);
    }

    /** Byte-stable non-photographic stimulus matching benchmark_resize_parity.py. */
    public static byte[] generate(int pattern) {
        if (pattern < 0 || pattern >= NAMES.length) {
            throw new IllegalArgumentException("Unknown synthetic pattern");
        }
        int w = WIDTH[pattern];
        int h = HEIGHT[pattern];
        byte[] out = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r, g, b;
                if (pattern == 0) {
                    r = (13 * x + 7 * y) % 256;
                    g = (19 * x + 5 * y) % 256;
                    b = (3 * x + 23 * y) % 256;
                } else if (pattern == 1) {
                    if ((x / 7 + y / 7) % 2 != 0) {
                        r = 255; g = 7; b = 42;
                    } else {
                        r = 0; g = 203; b = 255;
                    }
                } else if (pattern == 2) {
                    int band = 4 * x / w;
                    if (band == 0) { r = 255; g = 0; b = 0; }
                    else if (band == 1) { r = 0; g = 255; b = 0; }
                    else if (band == 2) { r = 0; g = 0; b = 255; }
                    else { r = 127; g = 127; b = 127; }
                } else if (Math.abs(x - w / 2) <= 2 &&
                           Math.abs(y - h / 2) <= 2) {
                    r = 255; g = 12; b = 128;
                } else {
                    r = 0; g = 0; b = 0;
                }
                int idx = 3 * (y * w + x);
                out[idx] = (byte) r;
                out[idx + 1] = (byte) g;
                out[idx + 2] = (byte) b;
            }
        }
        return out;
    }

    /** Fixed-shape uint8 RGB -> BGR, NCHW, raw float32 0..255, little-endian. */
    public static byte[] bgrTensor(byte[] resizedRgb) {
        if (resizedRgb == null || resizedRgb.length != PIXELS * 3) {
            throw new IllegalArgumentException("Expected generated 128x256 RGB bytes");
        }
        ByteBuffer buffer = ByteBuffer.allocate(PIXELS * 3 * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (int p = 0; p < PIXELS; p++) {
            buffer.putFloat(4 * p, (float) (resizedRgb[3 * p + 2] & 0xff));
            buffer.putFloat(4 * (PIXELS + p), (float) (resizedRgb[3 * p + 1] & 0xff));
            buffer.putFloat(4 * (2 * PIXELS + p), (float) (resizedRgb[3 * p] & 0xff));
        }
        return buffer.array();
    }

    public static String sha256(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Null data");
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(bytes);
            final char[] digits = "0123456789abcdef".toCharArray();
            char[] chars = new char[sum.length * 2];
            for (int i = 0; i < sum.length; i++) {
                chars[i * 2] = digits[(sum[i] & 255) >>> 4];
                chars[i * 2 + 1] = digits[sum[i] & 15];
            }
            return new String(chars);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM SHA-256 missing", ex);
        }
    }

    public static final class Result {
        public final boolean allFourExact;
        public final int passes;
        public final int checked;
        private Result(boolean pass, int n, int total) {
            allFourExact = pass; passes = n; checked = total;
        }
        public String toJson() {
            return "{\"status\":\"synthetic_java_resize_check_only\","
                 + "\"all_four_exact\":" + allFourExact + ","
                 + "\"passing_patterns\":" + passes + ","
                 + "\"checked_patterns\":" + checked + ","
                 + "\"real_model_or_android_runtime_verified\":false,"
                 + "\"private_media_used\":false,"
                 + "\"production_approved\":false,"
                 + "\"auto_confirm_allowed\":false}";
        }
    }

    /** Reject source drift, malformed tensors, and contradicting golden hashes. */
    public static Result verify(RgbResize resize) {
        if (resize == null) throw new IllegalArgumentException("Missing resize callback");
        int exact = 0;
        for (int p = 0; p < NAMES.length; p++) {
            byte[] source = generate(p);
            if (!sha256(source).equals(INPUT_HASH[p])) {
                throw new IllegalStateException("Frozen synthetic stimulus drift");
            }
            byte[] candidate = resize.resize(WIDTH[p], HEIGHT[p], source.clone());
            if (candidate == null || candidate.length != PIXELS * 3) {
                throw new IllegalArgumentException("Wrong synthetic resize byte count");
            }
            boolean rgbMatches = sha256(candidate).equals(PILLOW_RGB_HASH[p]);
            boolean tensorMatches = sha256(bgrTensor(candidate)).equals(PILLOW_BGR_TENSOR_HASH[p]);
            if (rgbMatches != tensorMatches) {
                throw new IllegalStateException("Frozen RGB and tensor references disagree");
            }
            if (rgbMatches) exact++;
        }
        return new Result(exact == NAMES.length, exact, NAMES.length);
    }

    /** Intentionally non-parity negative control: nearest-neighbor resampling. */
    private static byte[] nearestNeighbor(int w, int h, byte[] rgb) {
        byte[] out = new byte[PIXELS * 3];
        for (int y = 0; y < TARGET_HEIGHT; y++) {
            int sourceY = Math.min(h - 1, (int) (((long) y * h) / TARGET_HEIGHT));
            for (int x = 0; x < TARGET_WIDTH; x++) {
                int sourceX = Math.min(w - 1, (int) (((long) x * w) / TARGET_WIDTH));
                int i = 3 * (sourceY * w + sourceX);
                int j = 3 * (y * TARGET_WIDTH + x);
                System.arraycopy(rgb, i, out, j, 3);
            }
        }
        return out;
    }

    /** Headless no-I/O smoke test: nearest matches ONLY the identity pattern. */
    public static void main(String[] args) {
        if (args.length != 1 || !"--self-test".equals(args[0])) {
            System.err.println("Only synthetic --self-test is supported");
            System.exit(2);
            return;
        }
        try {
            Result result = verify(new RgbResize() {
                @Override public byte[] resize(int w, int h, byte[] input) {
                    return nearestNeighbor(w, h, input);
                }
            });
            if (result.allFourExact || result.passes != 1 || result.checked != 4 ||
                !sha256(bgrTensor(generate(2))).equals(PILLOW_BGR_TENSOR_HASH[2])) {
                throw new IllegalStateException("Synthetic negative control inconsistent");
            }
            System.out.println(result.toJson());
        } catch (RuntimeException err) {
            System.err.println("Synthetic Java reference self-test refused");
            System.exit(2);
        }
    }
}
