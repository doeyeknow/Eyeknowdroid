package app.eyeknow.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The referral stamp embedded in a downloaded APK (v1.5).
 *
 * When someone downloads the eyeKnow app through a referral
 * link, the download endpoint stamps the referral code into
 * the APK Signing Block as an ID-value pair with an ID no
 * verifier knows. The app reads its own APK file on its first
 * plain launch and, when a valid stamp is present, loads the
 * home page with {@code ?ref=<code>} once — so the page's
 * existing ReferralCapture stores the referral (cookie +
 * localStorage + one click, an honest click: a real install
 * from the link) exactly as if the link had opened in the app.
 *
 * Why the signing block is safe to extend: the APK Signature
 * Scheme v2 spec protects sections 1 (ZIP entry contents), 3
 * (central directory) and 4 (EOCD) — the signing block itself
 * (section 2) is outside the signed digest — and "ID-value
 * pairs with unknown IDs should be ignored when interpreting
 * the block." The stamper inserts one pair, updates the
 * block's two size fields and the EOCD's central-directory
 * offset, and nothing else moves: apksigner still verifies
 * v2 and v3 on the stamped file (proven empirically on the
 * real APK before this class was written). The v3 scheme
 * uses the same block and the same digest sections, so the
 * same argument covers it.
 *
 * The stamp format — ONE format, shared with the web
 * endpoint's TypeScript stamper (lib/apkStamp.ts), pinned by
 * shared test vectors run both ways:
 *
 *   pair ID    0x45594b4e ('EYKN' as big-endian ASCII)
 *   pair value ASCII "EYEKNOW-REF:" + <code>
 *   code       the web rule, exactly: lib/affiliates-client.ts
 *              isValidRefValue — /^[a-z0-9-]{3,30}$/ and at
 *              least one [a-z0-9]. The patterns below mirror
 *              that file character-for-character.
 *
 * Reader rules, all fail-safe (any doubt returns null, never
 * a guess, never an exception out of the public methods):
 * malformed/truncated structures, a missing signing block, a
 * ZIP64 APK, and an unreadable file all yield null. When
 * several stamp pairs are present (a stamped APK stamped
 * again — the endpoint never does this, it always stamps the
 * pristine bundled APK), the FIRST valid stamp wins.
 *
 * Pure Java — no Android types — so the harness proves the
 * exact shipping parse over synthetic blocks and the real
 * stamped APK bytes.
 */
public final class ApkStamp {

    /** Signing-block pair ID for the referral stamp. */
    public static final int STAMP_ID = 0x45594b4e;

    /** Value prefix; the code follows it directly. */
    public static final String VALUE_MAGIC = "EYEKNOW-REF:";

    /** Largest APK file this reader will load whole (64 MiB).
     *  The eyeKnow APK is ~62 KiB; the cap exists so a corrupt
     *  path can never OOM the shell. */
    public static final long MAX_APK_BYTES = 64L * 1024 * 1024;

    private static final byte[] EOCD_SIG = {0x50, 0x4b, 0x05, 0x06};
    private static final byte[] BLOCK_MAGIC =
            "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);
    private static final int EOCD_LEN = 22;
    private static final int MAX_COMMENT = 0xFFFF;

    // The web rule, mirrored character-for-character from
    // lib/affiliates-client.ts isValidRefValue.
    private static final Pattern REF_PATTERN = Pattern.compile("^[a-z0-9-]{3,30}$");
    private static final Pattern REF_ALNUM = Pattern.compile("[a-z0-9]");

    private ApkStamp() {}

    /** The referral-code rule, exactly as the web defines it:
     *  lowercase alphanumerics and hyphens, 3-30 chars, at
     *  least one alphanumeric. Null is invalid. */
    public static boolean isValidRefValue(String s) {
        if (s == null) return false;
        return REF_PATTERN.matcher(s).matches() && REF_ALNUM.matcher(s).find();
    }

    /**
     * The referral code stamped into an APK's bytes, or null
     * when the APK carries no valid stamp or cannot be parsed
     * safely. Never throws on malformed input.
     */
    public static String readStamp(byte[] apk) {
        try {
            return readStampChecked(apk);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The referral code stamped into an APK file, or null when
     * the file carries no valid stamp, cannot be read, or is
     * over the size cap. Never throws.
     */
    public static String readStampFromFile(String path) {
        if (path == null) return null;
        try {
            File f = new File(path);
            long len = f.length();
            if (len <= 0 || len > MAX_APK_BYTES) return null;
            byte[] bytes = new byte[(int) len];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) return null;
                    off += n;
                }
            }
            return readStamp(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The home URL with a stamped referral attached, for the
     * one first-launch load: {@code homeUrl + "?ref=" + code}.
     * A null code yields the home URL unchanged. The code is
     * stamped only after validation, so it needs no encoding:
     * the rule allows only [a-z0-9-], all URL-safe.
     */
    public static String stampedHomeUrl(String homeUrl, String code) {
        if (code == null) return homeUrl;
        return homeUrl + "?ref=" + code;
    }

    private static String readStampChecked(byte[] apk) {
        if (apk == null) return null;
        int eocd = findEocd(apk);
        if (eocd < 0) return null;
        long cdSize = u32(apk, eocd + 12);
        long cdOffset = u32(apk, eocd + 16);
        if (cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return null; // ZIP64
        if (cdOffset < 24 || cdOffset > eocd) return null;
        int footer = (int) cdOffset - 24;
        long size2 = u64(apk, footer);
        if (!matchesAt(apk, footer + 8, BLOCK_MAGIC)) return null;
        long blockStart = cdOffset - 8 - size2;
        if (blockStart < 0 || blockStart > footer) return null;
        long size1 = u64(apk, (int) blockStart);
        if (size1 != size2) return null;
        // Walk the pairs. A malformed pair ends the scan: the
        // offsets after it cannot be trusted.
        long p = blockStart + 8;
        while (p + 8 <= footer) {
            long pairLen = u64(apk, (int) p);
            if (pairLen < 4) return null;
            long pairEnd = p + 8 + pairLen;
            if (pairEnd > footer) return null;
            long id = u32(apk, (int) p + 8);
            if (id == (STAMP_ID & 0xFFFFFFFFL)) {
                int valueOff = (int) p + 12;
                int valueLen = (int) pairLen - 4;
                String code = codeFromValue(apk, valueOff, valueLen);
                if (code != null) return code;
                // An invalid stamp pair does not end the scan:
                // a later pair may hold a valid stamp (a
                // stamped APK stamped again keeps the first
                // VALID one, which is the original).
            }
            p = pairEnd;
        }
        return null;
    }

    /** The code carried by one stamp pair's value, or null
     *  when the value lacks the magic or the code is invalid. */
    private static String codeFromValue(byte[] apk, int off, int len) {
        byte[] magic = VALUE_MAGIC.getBytes(StandardCharsets.US_ASCII);
        if (len < magic.length) return null;
        for (int i = 0; i < magic.length; i++) {
            if (apk[off + i] != magic[i]) return null;
        }
        int codeLen = len - magic.length;
        if (codeLen < 3 || codeLen > 30) return null;
        String code = new String(apk, off + magic.length, codeLen, StandardCharsets.US_ASCII);
        return isValidRefValue(code) ? code : null;
    }

    /** The EOCD record: the LAST PK\\005\\006 signature in the
     *  tail whose comment length reaches exactly the end of
     *  the file, or -1. Scanning from the end matters: the
     *  signature bytes can occur inside file contents. */
    private static int findEocd(byte[] apk) {
        int tail = Math.min(apk.length, EOCD_LEN + MAX_COMMENT);
        int start = apk.length - tail;
        for (int i = apk.length - EOCD_LEN; i >= start; i--) {
            if (!matchesAt(apk, i, EOCD_SIG)) continue;
            int commentLen = (int) u16(apk, i + 20);
            if (i + EOCD_LEN + commentLen == apk.length) return i;
        }
        return -1;
    }

    private static boolean matchesAt(byte[] b, int off, byte[] pat) {
        if (off < 0 || off + pat.length > b.length) return false;
        for (int i = 0; i < pat.length; i++) {
            if (b[off + i] != pat[i]) return false;
        }
        return true;
    }

    private static long u16(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static long u64(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24)
                | ((b[off + 4] & 0xFFL) << 32) | ((b[off + 5] & 0xFFL) << 40)
                | ((b[off + 6] & 0xFFL) << 48) | ((b[off + 7] & 0xFFL) << 56);
    }
}
