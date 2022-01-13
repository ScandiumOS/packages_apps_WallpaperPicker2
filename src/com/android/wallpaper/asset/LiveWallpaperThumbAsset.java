/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.wallpaper.asset;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.WorkerThread;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asset wrapping a drawable for a live wallpaper thumbnail.
 */
public class LiveWallpaperThumbAsset extends Asset {
    private static final String TAG = "LiveWallpaperThumbAsset";
    private static final int LOW_RES_THUMB_TIMEOUT_SECONDS = 2;

    protected final Context mContext;
    protected final android.app.WallpaperInfo mInfo;
    // The content Uri of thumbnail
    protected Uri mUri;
    protected Drawable mThumbnailDrawable;

    public LiveWallpaperThumbAsset(Context context, android.app.WallpaperInfo info) {
        mContext = context.getApplicationContext();
        mInfo = info;
    }

    public LiveWallpaperThumbAsset(Context context, android.app.WallpaperInfo info, Uri uri) {
        this(context, info);
        mUri = uri;
    }

    @Override
    public void decodeBitmap(int targetWidth, int targetHeight,
                             BitmapReceiver receiver) {
        // No scaling is needed, as the thumbnail is already a thumbnail.
        LoadThumbnailTask task = new LoadThumbnailTask(mContext, mInfo, receiver);
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void decodeBitmapRegion(Rect rect, int targetWidth, int targetHeight,
            boolean shouldAdjustForRtl, BitmapReceiver receiver) {
        receiver.onBitmapDecoded(null);
    }

    @Override
    public void decodeRawDimensions(Activity unused, DimensionsReceiver receiver) {
        receiver.onDimensionsDecoded(null);
    }

    @Override
    public boolean supportsTiling() {
        return false;
    }

    @Override
    public void loadDrawable(Context context, ImageView imageView,
                             int placeholderColor) {
        RequestOptions reqOptions;
        if (mUri != null) {
            reqOptions = RequestOptions.centerCropTransform().apply(RequestOptions
                    .diskCacheStrategyOf(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true))
                    .placeholder(new ColorDrawable(placeholderColor));
        } else {
            reqOptions = RequestOptions.centerCropTransform()
                    .placeholder(new ColorDrawable(placeholderColor));
        }
        imageView.setBackgroundColor(placeholderColor);
        Glide.with(context)
                .asDrawable()
                .load(LiveWallpaperThumbAsset.this)
                .apply(reqOptions)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(imageView);
    }

    @Override
    public void loadLowResDrawable(Activity activity, ImageView imageView, int placeholderColor,
            BitmapTransformation transformation) {
        Transformation<Bitmap> finalTransformation = (transformation == null)
                ? new FitCenter()
                : new MultiTransformation<>(new FitCenter(), transformation);
        Glide.with(activity)
                .asDrawable()
                .load(LiveWallpaperThumbAsset.this)
                .apply(RequestOptions.bitmapTransform(finalTransformation)
                        .placeholder(new ColorDrawable(placeholderColor)))
                .into(imageView);
    }

    @Override
    @WorkerThread
    public Bitmap getLowResBitmap(Context context) {
        try {
            Drawable drawable = Glide.with(context)
                    .asDrawable()
                    .load(this)
                    .submit()
                    .get(LOW_RES_THUMB_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (drawable instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
                Bitmap bitmap = bitmapDrawable.getBitmap();
                if (bitmap != null) {
                    return bitmap;
                }
            }
            Bitmap bitmap;
            // If not a bitmap, draw the drawable into a bitmap
            if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
                return null;
            } else {
                bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                        drawable.getIntrinsicHeight(), Bitmap.Config.RGB_565);
            }

            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.w(TAG, "Couldn't obtain low res bitmap", e);
        }
        return null;
    }

    /**
     * Returns a Glide cache key.
     */
    Key getKey() {
        return new LiveWallpaperThumbKey(mInfo);
    }

    /**
     * Returns the thumbnail drawable for the live wallpaper synchronously. Should not be called on
     * the main UI thread.
     */
    protected Drawable getThumbnailDrawable() {
        if (mThumbnailDrawable != null) {
            return mThumbnailDrawable;
        }
        if (mUri != null) {
            try (AssetFileDescriptor assetFileDescriptor =
                         mContext.getContentResolver().openAssetFileDescriptor(mUri, "r")) {
                if (assetFileDescriptor != null) {
                    mThumbnailDrawable = new BitmapDrawable(mContext.getResources(),
                            BitmapFactory.decodeStream(assetFileDescriptor.createInputStream()));
                    return mThumbnailDrawable;
                }
            } catch (IOException e) {
                Log.w(TAG, "Not found thumbnail from URI.");
            }
        }
        mThumbnailDrawable = mInfo.loadThumbnail(mContext.getPackageManager());
        return mThumbnailDrawable;
    }

    /**
     * Glide caching key for resources from any arbitrary package.
     */
    private static final class LiveWallpaperThumbKey implements Key {
        private android.app.WallpaperInfo mInfo;

        public LiveWallpaperThumbKey(android.app.WallpaperInfo info) {
            mInfo = info;
        }

        @Override
        public String toString() {
            return getCacheKey();
        }

        @Override
        public int hashCode() {
            return getCacheKey().hashCode();
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof LiveWallpaperThumbKey)) {
                return false;
            }

            LiveWallpaperThumbKey otherKey = (LiveWallpaperThumbKey) object;
            return getCacheKey().equals(otherKey.getCacheKey());
        }

        @Override
        public void updateDiskCacheKey(MessageDigest messageDigest) {
            messageDigest.update(getCacheKey().getBytes(CHARSET));
        }

        /**
         * Returns an inexpensively calculated {@link String} suitable for use as a disk cache key,
         * based on the live wallpaper's package name and service name, which is enough to uniquely
         * identify a live wallpaper.
         */
        private String getCacheKey() {
            return "LiveWallpaperThumbKey{"
                    + "packageName=" + mInfo.getPackageName() + ","
                    + "serviceName=" + mInfo.getServiceName()
                    + '}';
        }
    }

    /**
     * AsyncTask subclass which loads the live wallpaper's thumbnail bitmap off the main UI thread.
     * Resolves with null if live wallpaper thumbnail is not a bitmap.
     */
    private static class LoadThumbnailTask extends AsyncTask<Void, Void, Bitmap> {
        private final PackageManager mPackageManager;
        private android.app.WallpaperInfo mInfo;
        private BitmapReceiver mReceiver;

        public LoadThumbnailTask(Context context, android.app.WallpaperInfo info,
                BitmapReceiver receiver) {
            mInfo = info;
            mReceiver = receiver;
            mPackageManager = context.getPackageManager();
        }

        @Override
        protected Bitmap doInBackground(Void... unused) {
            Drawable thumb = mInfo.loadThumbnail(mPackageManager);

            // Live wallpaper components may or may not specify a thumbnail drawable.
            if (thumb instanceof BitmapDrawable) {
                return ((BitmapDrawable) thumb).getBitmap();
            } else if (thumb != null) {
                Bitmap bitmap;
                if (thumb.getIntrinsicWidth() > 0 && thumb.getIntrinsicHeight() > 0) {
                    bitmap = Bitmap.createBitmap(thumb.getIntrinsicWidth(),
                            thumb.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                } else {
                    return null;
                }

                Canvas canvas = new Canvas(bitmap);
                thumb.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                thumb.draw(canvas);
                return bitmap;
            }

            // If no thumbnail was specified, return a null bitmap.
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            mReceiver.onBitmapDecoded(bitmap);
        }
    }

    /**
     * Frees the drawable.
     */
    @Override
    public void release() {
        if (mThumbnailDrawable != null) {
            if (mThumbnailDrawable instanceof BitmapDrawable) {
                ((BitmapDrawable) mThumbnailDrawable).getBitmap().recycle();
            }
            mThumbnailDrawable = null;
        }
    }
}
