package app.couchmouse.tv;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** QR code bitmap (black on white) for the setup screen. */
final class Qr {
    private Qr() {
    }

    static Bitmap make(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF0B1220 : 0xFFFFFFFF;
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }
}
