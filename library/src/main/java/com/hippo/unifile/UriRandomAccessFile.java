/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.unifile;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class UriRandomAccessFile extends RandomAccessFile {

    private static final String TAG = UriRandomAccessFile.class.getSimpleName();

    private static final Field FIELD_FD;
    private static final Method METHOD_CLOSE;

    static {
        Field field;
        try {
            field = RandomAccessFile.class.getDeclaredField("fd");
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            Log.e(TAG, "Can't get field RandomAccessFile.fd : " + e);
            field = null;
        }
        FIELD_FD = field;

        Method method;
        try {
            Class<?> clazz = Class.forName("libcore.io.IoUtils");
            method = clazz.getMethod("close", FileDescriptor.class);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Can't get class libcore.io.IoUtils: " + e);
            method = null;
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Can't get method libcore.io.IoUtils.close(FileDescriptor): " + e);
            method = null;
        }
        METHOD_CLOSE = method;
    }

    private ParcelFileDescriptor mPfd;
    private AssetFileDescriptor mAfd;

    private UriRandomAccessFile(String mode) throws FileNotFoundException {
        // /dev/random is only a temp file to create UriRandomAccessFile object
        super("/dev/random", mode);
    }

    @Override
    public void close() throws IOException {
        if (mPfd != null) {
            mPfd.close();
            mPfd = null;
        }
        if (mAfd != null) {
            mAfd.close();
            mAfd = null;
        }
        super.close();
    }

    private static void checkReflection() throws IOException {
        // Check reflection stuff
        if (FIELD_FD == null || METHOD_CLOSE == null) {
            throw new IOException("Can't get reflection stuff");
        }
    }

    @NonNull
    static RandomAccessFile create(Context context, Uri uri, String mode) throws IOException {
        checkReflection();

        ParcelFileDescriptor pfd = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, mode);
            if (pfd == null) {
                throw new IOException("Can't get ParcelFileDescriptor");
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null) {
                throw new IOException("Can't get FileDescriptor");
            }

            UriRandomAccessFile file = create(fd, mode);
            file.mPfd = pfd;

            return file;
        } catch (IOException e) {
            // Close ParcelFileDescriptor if failed
            if (pfd != null) {
                pfd.close();
            }
            throw e;
        }
    }

    @NonNull
    static RandomAccessFile create(AssetManager assetManager, String path, String mode) throws IOException {
        // Check mode
        if (!"r".equals(mode)) {
            throw new IOException("Non-supported mode for asset file: " + mode);
        }

        checkReflection();

        AssetFileDescriptor afd = null;
        try {
            afd = assetManager.openFd(path);
            if (afd == null) {
                throw new IOException("Can't get AssetFileDescriptor");
            }
            FileDescriptor fd = afd.getFileDescriptor();
            if (fd == null) {
                throw new IOException("Can't get FileDescriptor");
            }

            UriRandomAccessFile file = create(fd, mode);
            file.mAfd = afd;

            return file;
        } catch (IOException e) {
            // Close AssetFileDescriptor if failed
            if (afd != null) {
                afd.close();
            }
            throw e;
        }
    }

    @NonNull
    private static UriRandomAccessFile create(FileDescriptor fd, String mode) throws IOException {
        // Create UriRandomAccessFile object
        UriRandomAccessFile file;
        try {
            file = new UriRandomAccessFile(mode);
        } catch (FileNotFoundException e) {
            throw new IOException("Can't create UriRandomAccessFile");
        }

        // Close old FileDescriptor
        try {
            Object obj = FIELD_FD.get(file);
            if (obj instanceof FileDescriptor) {
                METHOD_CLOSE.invoke(null, (FileDescriptor) obj);
            }
        } catch (IllegalAccessException e) {
            Log.e(TAG, "Failed to invoke libcore.io.IoUtils.close(FileDescriptor): " + e);
            file.close();
            throw new IOException(e.getMessage());
        } catch (InvocationTargetException e) {
            Log.e(TAG, "Failed to invoke libcore.io.IoUtils.close(FileDescriptor): " + e);
            file.close();
            throw new IOException(e.getMessage());
        }

        // Set new FileDescriptor
        try {
            FIELD_FD.set(file, fd);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            file.close();
            throw new IOException(e.getMessage());
        }

        return file;
    }
}
