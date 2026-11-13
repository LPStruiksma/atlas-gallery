/** Portable, bounded Java 8 reference resampler for four synthetic canary probes ONLY. */
public final class IntelSyntheticPillowBilinear implements IntelSyntheticCanary.RgbResize {
    private static final int WIDTH = 128;
    private static final int HEIGHT = 256;
    private static final long ONE = 1L << 22;
    private static final long ROUND = ONE >>> 1;
    private static final int[] SOURCE_WIDTHS = {341, 199, 128, 65};
    private static final int[] SOURCE_HEIGHTS = {173, 397, 256, 83};
    private static final String[] SOURCE_HASHES = {
        "561d7a8282b1009c32b81725ec1e853ce5137670fbdf98c83ae492f5a4d42d67",
        "f7efebb2a00a063dbb5a10e81526394519f0d328362d66387adacde7c6c34e19",
        "557bcb50a18be07eb21b61e07b1ce31c510d7f7c1f854c244037b00b56bc8a7e",
        "557d233b74c884c3eec07da9fe07c452cfbaa0b1bb6cf3ee39c64881010bdcc0"
    };
    public IntelSyntheticPillowBilinear() { }

    /** Fixed 22-bit Pillow-12.3-style weight table; only approved synthetic geometry. */
    private static Weights[] weights(int source, int destination) {
        double scale = ((double) source) / destination;
        double support = Math.max(scale, 1.0);
        Weights[] result = new Weights[destination];
        for (int i = 0; i < destination; i++) {
            double center = (i + .5) * scale;
            int low = Math.max(0, (int) (center - support + .5));
            int high = Math.min(source, (int) (center + support + .5));
            double[] coeff = new double[high - low];
            double total = 0.0;
            for (int j = 0; j < coeff.length; j++) {
                coeff[j] = Math.max(0.0, 1.0 - Math.abs(((low + j) - center + .5) / support));
                total += coeff[j];
            }
            if (!(total > 0.0)) throw new IllegalStateException("Empty synthetic filter");
            int[] fixed = new int[coeff.length];
            for (int j = 0; j < coeff.length; j++) {
                fixed[j] = (int) (coeff[j] / total * ONE + .5);
            }
            result[i] = new Weights(low, fixed);
        }
        return result;
    }

    private static final class Weights {
        final int start;
        final int[] coeff;
        Weights(int start, int[] coeff) { this.start = start; this.coeff = coeff; }
    }

    private static int clamp(long value) {
        return (int) Math.min(255L, Math.max(0L, value >> 22));
    }

    /** Reject any non-canary source or geometry; not a general-purpose photo processor. */
    @Override
    public byte[] resize(int sourceWidth, int sourceHeight, byte[] rgb) {
        if (rgb == null || sourceWidth < 1 || sourceHeight < 1) {
            throw new IllegalArgumentException("Only generated synthetic probe buffers are supported");
        }
        int chosen = -1;
        for (int i = 0; i < SOURCE_WIDTHS.length; i++) {
            if (sourceWidth == SOURCE_WIDTHS[i] && sourceHeight == SOURCE_HEIGHTS[i]) {
                chosen = i;
                break;
            }
        }
        if (chosen == -1 || rgb.length != sourceWidth * sourceHeight * 3 ||
            !IntelSyntheticCanary.sha256(rgb).equals(SOURCE_HASHES[chosen])) {
            throw new IllegalArgumentException("Unexpected non-synthetic RGB or dimensions");
        }
        byte[] rowResult = rgb;
        if (sourceWidth != WIDTH) {
            rowResult = new byte[WIDTH * sourceHeight * 3];
            Weights[] wx = weights(sourceWidth, WIDTH);
            for (int y = 0; y < sourceHeight; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    Weights filter = wx[x];
                    for (int c = 0; c < 3; c++) {
                        long accum = ROUND;
                        for (int j = 0; j < filter.coeff.length; j++) {
                            int at = 3 * (y * sourceWidth + filter.start + j) + c;
                            accum += (rgb[at] & 255L) * filter.coeff[j];
                        }
                        rowResult[3 * (y * WIDTH + x) + c] = (byte) clamp(accum);
                    }
                }
            }
        }
        if (sourceHeight == HEIGHT) return rowResult.clone();
        byte[] out = new byte[WIDTH * HEIGHT * 3];
        Weights[] wy = weights(sourceHeight, HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            Weights filter = wy[y];
            for (int x = 0; x < WIDTH; x++) {
                for (int c = 0; c < 3; c++) {
                    long accum = ROUND;
                    for (int j = 0; j < filter.coeff.length; j++) {
                        int at = 3 * ((filter.start + j) * WIDTH + x) + c;
                        accum += (rowResult[at] & 255L) * filter.coeff[j];
                    }
                    out[3 * (y * WIDTH + x) + c] = (byte) clamp(accum);
                }
            }
        }
        return out;
    }

    /** Exact supplied synthetic inputs only; no source file decoding. */
    public static void main(String[] args) {
        if (args.length != 1 || !"--self-test".equals(args[0])) {
            System.err.println("Only the synthetic self-test is supported");
            System.exit(2);
            return;
        }
        try {
            // All four RGB and BGR float32 tensor SHA256s are independently pinned
            // by IntelSyntheticCanary; no generated test data reaches disk.
            IntelSyntheticCanary.Result result = IntelSyntheticCanary.verify(
                new IntelSyntheticPillowBilinear());
            if (!result.allFourExact || result.passes != 4 || result.checked != 4) {
                throw new IllegalStateException("Synthetic reference mismatch");
            }
            System.out.println(result.toJson());
        } catch (RuntimeException ex) {
            System.err.println("Synthetic resize self-test failed");
            System.exit(2);
        }
    }
}
