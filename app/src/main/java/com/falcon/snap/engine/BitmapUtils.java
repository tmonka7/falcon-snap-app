package com.falcon.snap.engine;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Bitmap loading and saving helpers. Everything here does I/O: call off the UI thread. */
public final class BitmapUtils {
    private BitmapUtils() {
    }

    /**
     * Decodes an image upright (EXIF rotation applied) with its longest side at most maxDim.
     * Returns null if the image cannot be read.
     */
    public static Bitmap decodeScaled(Context context, Uri uri, int maxDim) {
        ContentResolver resolver = context.getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (options.inSampleSize * 2) >= maxDim) {
                options.inSampleSize *= 2;
            }
            Bitmap bitmap;
            try (InputStream in = resolver.openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) {
                return null;
            }

            Matrix matrix = new Matrix();
            float scale = maxDim / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (scale < 1f) {
                matrix.postScale(scale, scale);
            }
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in != null) {
                    ExifInterface exif = new ExifInterface(in);
                    if (exif.isFlipped()) {
                        matrix.postScale(-1f, 1f);
                    }
                    matrix.postRotate(exif.getRotationDegrees());
                }
            } catch (IOException ignored) {
                // No readable EXIF (PNG, WebP...): the image is already upright.
            }
            if (matrix.isIdentity()) {
                return bitmap;
            }
            Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (result != bitmap) {
                bitmap.recycle();
            }
            return result;
        } catch (IOException | SecurityException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Small decode for list thumbnails. Returns null if the file is missing or unreadable. */
    public static Bitmap decodeThumbnail(String path, int targetSize) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        int shortest = Math.min(bounds.outWidth, bounds.outHeight);
        while (shortest / (options.inSampleSize * 2) >= targetSize) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(path, options);
    }

    public static void saveJpeg(Bitmap bitmap, File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new IOException("Could not encode " + file.getName());
            }
        }
    }

    /**
     * Adds the image to the device gallery under Pictures/SnapTranslate.
     * API 29+ only: scoped storage lets us do this without a storage permission.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    public static void exportToGallery(Context context, Bitmap bitmap, String displayName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SnapTranslate");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) {
            throw new IOException("MediaStore rejected the image");
        }
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new IOException("Could not write the image");
            }
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }
}
