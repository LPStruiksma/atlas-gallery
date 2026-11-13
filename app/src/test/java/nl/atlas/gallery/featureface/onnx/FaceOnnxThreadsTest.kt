package nl.atlas.gallery.featureface.onnx

import org.junit.Assert.assertEquals
import org.junit.Test

/** #552: ORT thread budget by core count. No model is loaded. */
class FaceOnnxThreadsTest {
    @Test fun twoCoreDevicesUseOneThreadAndBigPhonesAtMostFour() {
        assertEquals(1, FaceOnnxThreads.intraOpFor(1))
        assertEquals(1, FaceOnnxThreads.intraOpFor(2))
        assertEquals(2, FaceOnnxThreads.intraOpFor(4))
        assertEquals(3, FaceOnnxThreads.intraOpFor(6))
        assertEquals(4, FaceOnnxThreads.intraOpFor(8))
        assertEquals(4, FaceOnnxThreads.intraOpFor(10))
    }
}
