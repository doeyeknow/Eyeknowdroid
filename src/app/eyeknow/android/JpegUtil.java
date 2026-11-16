package app.eyeknow.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * JPEG decode / crop / encode for the capture flow. All sizing
 * math lives in CropMath (pure, harness-proven); this class only
 * drives BitmapFactory/Bitmap.
 *
 * Memory safety: phone stills can be 50MP+. Decoding one at full
 * size would OOM the app, so every decode subsamples to the
 * 2560 long-side cap FIRST (CropMath.sampleSize), and the crop
 * rect is scaled into the subsampled space before cropping.
 *
 * Rotation: the capture request sets JPEG_ORIENTATION so the
 * stored JPEG is upright; the crop math therefore works in the
 * JPEG's own pixel space with no extra rotation step.
 */
public final class JpegUtil {

    private JpegUtil() {}

    /** Dimensions of a JPEG file without decoding pixels. */
    public static int[] dimensions(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        return new int[] { o.outWidth, o.outHeight };
    }

    /** Decode a JPEG subsampled so its long side is at most the
     *  cap. Returns null when the file is not a readable image. */
    public static Bitmap decodeCapped(File file) {
        int[] d = dimensions(file);
        if (d[0] <= 0 || d[1] <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = CropMath.sampleSize(d[0], d[1]);
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), o);
    }

    /**
     * Crop a JPEG file to a square rect given in the JPEG's FULL
     * pixel space, writing a fresh JPEG (quality 92, long side
     * capped at 2560). The decode is subsampled and the rect is
     * scaled down to match, so a 50MP still never inflates.
     * Returns the output file. Throws on unreadable input.
     */
    public static File cropToFile(File input, CropMath.Rect fullRect, File output)
            throws IOException {
        int[] d = dimensions(input);
        if (d[0] <= 0 || d[1] <= 0) {
            throw new IOException("Captured photo could not be read.");
        }
        int sample = CropMath.sampleSize(d[0], d[1]);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(input.getAbsolutePath(), o);
        if (bmp == null) throw new IOException("Captured photo could not be decoded.");
        try {
            CropMath.Rect r = CropMath.clamp(
                    new CropMath.Rect(fullRect.left / sample, fullRect.top / sample,
                            fullRect.size / sample),
                    bmp.getWidth(), bmp.getHeight());
            Bitmap cropped = Bitmap.createBitmap(bmp, r.left, r.top, r.size, r.size);
            try {
                int[] capped = CropMath.cappedSize(cropped.getWidth(), cropped.getHeight());
                Bitmap out = cropped;
                if (capped[0] != cropped.getWidth() || capped[1] != cropped.getHeight()) {
                    out = Bitmap.createScaledBitmap(cropped, capped[0], capped[1], true);
                }
                try {
                    writeJpeg(out, output);
                } finally {
                    if (out != cropped) out.recycle();
                }
            } finally {
                cropped.recycle();
            }
        } finally {
            bmp.recycle();
        }
        return output;
    }

    public static void writeJpeg(Bitmap bmp, File output) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, CropMath.JPEG_QUALITY, baos)) {
            throw new IOException("Could not encode the photo.");
        }
        try (FileOutputStream fos = new FileOutputStream(output)) {
            fos.write(baos.toByteArray());
        }
    }

    /** Downscale a bitmap to the long-side cap (no-op when it fits).
     *  The caller owns recycling. */
    public static Bitmap capped(Bitmap bmp) {
        int[] capped = CropMath.cappedSize(bmp.getWidth(), bmp.getHeight());
        if (capped[0] == bmp.getWidth() && capped[1] == bmp.getHeight()) return bmp;
        return Bitmap.createScaledBitmap(bmp, capped[0], capped[1], true);
    }

    /** Rotate a bitmap by a multiple of 90 degrees. No-op for 0. */
    public static Bitmap rotate(Bitmap bmp, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        if (d == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(d);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    /** A JPEG file as the data URL the bridge contract requires. */
    public static String toDataUrl(File jpegFile) throws IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(jpegFile.toPath());
        return BridgeMessages.DISPLAY_PREFIX
                + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
