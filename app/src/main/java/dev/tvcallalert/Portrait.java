package dev.tvcallalert;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;

/**
 * Optional caller picture. Copy an image named {@code portrait.png} or {@code portrait.jpg}
 * into the app's external files folder and the card shows it instead of the phone icon.
 */
final class Portrait {
    private static final int TARGET_PIXELS = 512;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private Portrait() {
    }

    static Bitmap load(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            return null;
        }
        for (String name : new String[] {"portrait.png", "portrait.jpg"}) {
            File f = new File(dir, name);
            if (f.isFile() && f.length() > 0 && f.length() <= MAX_FILE_BYTES) {
                Bitmap b = decode(f);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (opts.inSampleSize * 2)
                    >= TARGET_PIXELS) {
                opts.inSampleSize *= 2;
            }
            return BitmapFactory.decodeFile(f.getPath(), opts);
        } catch (RuntimeException | OutOfMemoryError e) {
            L.w("portrait decode failed", e);
            return null;
        }
    }
}
