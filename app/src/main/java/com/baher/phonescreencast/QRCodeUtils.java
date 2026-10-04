package com.baher.phonescreencast;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** Offline QR code generation (ZXing core, pure Java). */
public final class QRCodeUtils {

    private QRCodeUtils() {}

    /**
     * Renders {@code text} as a crisp black-on-white QR bitmap of roughly {@code targetPx} pixels,
     * using whole pixels per module so it stays sharp when scaled without filtering.
     */
    public static Bitmap generate(String text, int targetPx) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 2);

        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        int modules = matrix.getWidth();
        int scale = Math.max(1, targetPx / modules);
        int size = modules * scale;

        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int row = y * size;
            for (int x = 0; x < size; x++) {
                pixels[row + x] = matrix.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }
}
