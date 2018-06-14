package com.android.camera2demo.utils;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by lenovo on 18/1/6.
 */

public class CameraUtils {

    //从屏幕旋转转换为JPEG方向。
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * 假设摄像机支持{@code choices}的{@code Size}，则选择与相应纹理视图大小至少一样大的最小大小，
     * 并且该大小最多与相应的最大大小一样大，并且其纵横比与指定值匹配。
     * 如果这种尺寸不存在，请选择最大尺寸与相应最大尺寸一样大，且其纵横比与指定值匹配的最大尺寸。
     *
     * @param choices           相机支持的预期输出类别的尺寸列表
     * @param textureViewWidth  纹理视图相对于传感器坐标的宽度
     * @param textureViewHeight 纹理视图相对于传感器坐标的高度
     * @param maxWidth          可以选择的最大宽度
     * @param maxHeight         可以选择的最大高度
     * @param aspectRatio       纵横比
     * @return 最佳的{@code Size}，或者任何一个都不够大的任意一个
     */
    public static Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // 收集至少与预览表面一样大的支持的分辨率
        List<Size> bigEnough = new ArrayList<>();
        // 收集小于预览表面的支持的分辨率
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // 选择那些足够大的最小的。 如果没有足够大的人，挑选那些不够大的人。
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e("", "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * 将必要的{@link Matrix}转换配置为`mTextureView`。
     * 在setUpCameraOutputs中确定相机预览大小并且“mTextureView”的大小已修复之后，应该调用此方法。
     *
     * @param viewWidth  `mTextureView`的宽度
     * @param viewHeight `mTextureView`的高度
     */
    public static void configureTransform(TextureView textureView, Size previewSize, int rotation, int viewWidth, int viewHeight) {
        if (null == textureView || null == previewSize) {
            return;
        }
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    /**
     * 从指定的屏幕旋转中检索JPEG方向。
     *
     * @param rotation 屏幕旋转。
     * @return JPEG方向（0,90,270和360中的一个）
     */
    public static int getOrientation(int rotation, int sensorOrientation) {
        // 大多数设备的传感器方向为90°，某些设备的传感器方向为270°（例如Nexus 5X）。我们必须考虑到这一点并正确旋转JPEG。
        // 对于方向为90的设备，我们只需返回ORIENTATIONS的映射。
        // 对于方向为270的设备，我们需要将JPEG旋转180度。
        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
    }

    /**
     * 根据他们的区域比较两个{@code Size}。
     */
    public static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // 我们在这里投入，以确保乘法不会溢出
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
