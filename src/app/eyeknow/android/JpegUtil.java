package app.eyeknow.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
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
 * size would OOM the app, so the crop path REGION-decodes only
 * the crop rect (BitmapRegionDecoder) at full source
 * resolution — the full frame is never inflated. The preview
 * decode (decodeCapped) subsamples to the 2560 preview cap
 * instead: display never needs full resolution.
 *
 * Rotation: the capture request sets JPEG_ORIENTATION so the
 * stored JPEG is upright; the crop math therefore works in the
 * JPEG's own pixel space. The crop screen's user rotation is
 * applied to the CROPPED square only, inside cropToFile, so the
 * written JPEG is encoded exactly once, in the orientation the
 * preview showed. The auto path always passes rotation 0.
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
     *  preview cap. Returns null when the file is not a readable
     *  image. Display only — never used for the written crop. */
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
     * capped at 4096). No rotation — the auto path's entry
     * point. See the four-argument overload for the full
     * contract.
     */
    public static File cropToFile(File input, CropMath.Rect fullRect, File output)
            throws IOException {
        return cropToFile(input, fullRect, output, 0);
    }

    /**
     * Crop a JPEG file to a square rect given in the JPEG's FULL
     * pixel space, rotate the cropped square clockwise by
     * {@code rotationDegrees} (0/90/180/270; anything else is
     * treated as 0), and write a fresh JPEG (quality 92, long
     * side capped at 4096 — downscaled only when the crop
     * exceeds the cap, otherwise written at native crop
     * resolution).
     *
     * The decode is a REGION decode: only the crop rect is
     * decoded, at full source resolution, so a 50MP still
     * never inflates into a full bitmap and no detail is lost
     * to subsampling. At most three bitmaps exist at once
     * (region, rotated copy, optional downscale), all recycled.
     * Returns the output file. Throws on unreadable input.
     */
    public static File cropToFile(File input, CropMath.Rect fullRect, File output,
                                  int rotationDegrees) throws IOException {
        int[] d = dimensions(input);
        if (d[0] <= 0 || d[1] <= 0) {
            throw new IOException("Captured photo could not be read.");
        }
        CropMath.Rect r = CropMath.clamp(fullRect, d[0], d[1]);
        BitmapRegionDecoder decoder =
                BitmapRegionDecoder.newInstance(input.getAbsolutePath(), false);
        if (decoder == null) {
            throw new IOException("Captured photo could not be decoded.");
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap cropped = decoder.decodeRegion(
                    new Rect(r.left, r.top, r.left + r.size, r.top + r.size), o);
            if (cropped == null) {
                throw new IOException("Captured photo could not be decoded.");
            }
            try {
                // The region decoder returns the requested rect;
                // trim defensively if a decoder ever returns
                // more (the square invariant must hold).
                Bitmap exact = cropped;
                if (cropped.getWidth() > r.size || cropped.getHeight() > r.size) {
                    exact = Bitmap.createBitmap(cropped, 0, 0,
                            Math.min(cropped.getWidth(), r.size),
                            Math.min(cropped.getHeight(), r.size));
                }
                try {
                    Bitmap oriented = rotate(exact,
                            CropMath.normalizeRotation(rotationDegrees));
                    try {
                        int[] capped = CropMath.cappedSize(
                                oriented.getWidth(), oriented.getHeight());
                        Bitmap out = oriented;
                        if (capped[0] != oriented.getWidth()
                                || capped[1] != oriented.getHeight()) {
                            out = Bitmap.createScaledBitmap(
                                    oriented, capped[0], capped[1], true);
                        }
                        try {
                            writeJpeg(out, output);
                        } finally {
                            if (out != oriented) out.recycle();
                        }
                    } finally {
                        if (oriented != exact) oriented.recycle();
                    }
                } finally {
                    if (exact != cropped) exact.recycle();
                }
            } finally {
                cropped.recycle();
            }
        } finally {
            decoder.recycle();
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

    /**
     * Decode a cut crop into a detection frame for post-crop
     * verification (v1.4). Subsamples to at most 512 on the
     * long side, then converts through RgbFrame, which
     * downsamples to the detector's 320-wide working frame
     * with the same gray/saturation math the YUV path uses —
     * one detection standard, not two. Returns null when the
     * file is not a readable image; the caller treats that as
     * a verification error and falls back. The bitmap is
     * recycled before returning, so no large bitmap survives
     * this call.
     */
    public static IrisFrame frameForVerify(File jpegFile) {
        int[] d = dimensions(jpegFile);
        if (d[0] <= 0 || d[1] <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = verifySampleSize(d[0], d[1]);
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(jpegFile.getAbsolutePath(), o);
        if (bmp == null) return null;
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            return RgbFrame.fromArgb(pixels, w, h);
        } finally {
            bmp.recycle();
        }
    }

    /** Largest power-of-two sample size keeping the long side
     *  at or below 512. Android-only (Bitmap decode), so the
     *  harness cannot exercise it; the conversion it feeds
     *  (RgbFrame) is harness-proven, and the decode itself is
     *  the same subsample pattern as decodeCapped. */
    private static int verifySampleSize(int w, int h) {
        int s = 1;
        while (Math.max(w, h) / s > 512) s *= 2;
        return s;
    }

    /** A JPEG file as the data URL the bridge contract requires. */
    public static String toDataUrl(File jpegFile) throws IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(jpegFile.toPath());
        return BridgeMessages.DISPLAY_PREFIX
                + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
