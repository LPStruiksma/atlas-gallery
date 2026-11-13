/**
 * Java-8-compatible orientation and half-open crop geometry.
 * Research-only in-memory helper: tests use invented pixels exclusively.
 * This API cannot authenticate a caller's image rights. It never decodes
 * files/EXIF, uses Android APIs, accesses media, or runs models.
 */
public final class ExifOrientationCropCanary {
    private static final int MAX_SIDE = 512;
    private static final int MAX_PIXELS = MAX_SIDE * MAX_SIDE;

    private ExifOrientationCropCanary() { }

    public static final class Rect {
        public final int left, top, right, bottom;
        public Rect(int left, int top, int right, int bottom) {
            this.left = left; this.top = top;
            this.right = right; this.bottom = bottom;
        }
    }

    public static final class Result {
        public final int orientedWidth, orientedHeight, cropWidth, cropHeight;
        public final byte[] cropRgb;
        private Result(int width, int height, int cw, int ch, byte[] rgb) {
            orientedWidth = width; orientedHeight = height;
            cropWidth = cw; cropHeight = ch; cropRgb = rgb;
        }
    }

    /**
     * Input ARGB pixels must be UNORIENTED; rect is in ORIENTED pixel edges.
     * No silent clipping, fractional rounding, or premultiplied alpha.
     * Do not pass EXIF correction twice after an orientation-aware decoder.
     * This is independent of source authorization, which callers must verify.
     */
    public static Result orientAndCrop(int[] argb, int rawWidth, int rawHeight,
                                       int orientation, Rect rect) {
        if (rawWidth < 1 || rawWidth > MAX_SIDE ||
            rawHeight < 1 || rawHeight > MAX_SIDE ||
            (long) rawWidth * rawHeight > MAX_PIXELS ||
            orientation < 1 || orientation > 8 ||
            argb == null || argb.length != rawWidth * rawHeight || rect == null) {
            throw new IllegalArgumentException("Invalid geometry or EXIF orientation");
        }
        int orientedWidth = orientation >= 5 ? rawHeight : rawWidth;
        int orientedHeight = orientation >= 5 ? rawWidth : rawHeight;
        if (rect.left < 0 || rect.top < 0 ||
            rect.right <= rect.left || rect.bottom <= rect.top ||
            rect.right > orientedWidth || rect.bottom > orientedHeight) {
            throw new IllegalArgumentException("Crop outside oriented pixel-edge space");
        }
        int cw = rect.right - rect.left;
        int ch = rect.bottom - rect.top;
        byte[] rgb = new byte[cw * ch * 3];
        for (int dy = rect.top; dy < rect.bottom; dy++) {
            for (int dx = rect.left; dx < rect.right; dx++) {
                final int sx, sy;
                switch (orientation) {
                    case 1: sx = dx; sy = dy; break;
                    case 2: sx = rawWidth-1-dx; sy = dy; break;
                    case 3: sx = rawWidth-1-dx; sy = rawHeight-1-dy; break;
                    case 4: sx = dx; sy = rawHeight-1-dy; break;
                    case 5: sx = dy; sy = dx; break;
                    case 6: sx = dy; sy = rawHeight-1-dx; break;
                    case 7: sx = rawWidth-1-dy; sy = rawHeight-1-dx; break;
                    case 8: sx = rawWidth-1-dy; sy = dx; break;
                    default: throw new AssertionError("EXIF already checked");
                }
                int argbPixel = argb[sy * rawWidth + sx];
                if ((argbPixel >>> 24) != 255)
                    throw new IllegalArgumentException("Nonopaque pixel; alpha policy required");
                int offset = 3 * ((dy-rect.top)*cw + dx-rect.left);
                rgb[offset] = (byte)((argbPixel >>> 16) & 255);
                rgb[offset+1] = (byte)((argbPixel >>> 8) & 255);
                rgb[offset+2] = (byte)(argbPixel & 255);
            }
        }
        return new Result(orientedWidth, orientedHeight, cw, ch, rgb);
    }

    /** Invented ARGB-only 3x2 structural sentinel, no file or real input. */
    public static void main(String[] args) {
        if (args.length != 1 || !"--self-test".equals(args[0])) {
            System.err.println("Synthetic --self-test only");
            System.exit(2);
            return;
        }
        try {
            int[] pixels = {0xff010203,0xff040506,0xff070809,
                            0xff0a0b0c,0xff0d0e0f,0xff101112};
            for (int o = 1; o <= 8; o++) {
                int w = o>=5 ? 2:3, h = o>=5 ? 3:2;
                Result r = orientAndCrop(pixels,3,2,o,new Rect(0,0,w,h));
                if (r.cropWidth != w || r.cropHeight != h || r.cropRgb.length != 18)
                    throw new IllegalStateException("Synthetic geometry wrong");
            }
            System.out.println("{\"eight_exif_modes_structurally_verified\":true,"
              + "\"real_decoder_or_exif_parsing_verified\":false,"
              + "\"photos_or_models_used\":false,"
              + "\"production_approved\":false,"
              + "\"auto_confirm_allowed\":false}");
        } catch (RuntimeException err) {
            System.err.println("Synthetic EXIF geometry check failed");
            System.exit(2);
        }
    }
}
