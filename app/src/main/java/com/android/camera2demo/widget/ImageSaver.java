package com.android.camera2demo.widget;

import android.media.Image;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 将JPEG {@link Image}保存到指定的{@link File}中。
 */
public class ImageSaver implements Runnable {
    private final Image mImage;
    private final String mSavePath;

    private OnCreatePhotoListener mCreateListener;

    public ImageSaver(Image image, String savePath) {
        mImage = image;
        mSavePath = savePath;
    }

    @Override
    public void run() {
        final String fileName = System.currentTimeMillis() + ".jpg";
        final File directory = new File(mSavePath);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        final File image = new File(directory, fileName);

        ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(image);
            output.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            mImage.close();
            if (null != output) {
                try {
                    output.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (mCreateListener != null) {
                if (image.exists()) {
                    mCreateListener.onSuccess(image);
                } else {
                    mCreateListener.onError("保存失败");
                }
            }
        }
    }

    public void setCreateListener(OnCreatePhotoListener createListener) {
        this.mCreateListener = createListener;
    }

    public interface OnCreatePhotoListener {
        void onSuccess(File file);

        void onError(String errorMessage);
    }
}
