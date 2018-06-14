package com.android.camera2demo.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.camera2demo.R;
import com.android.camera2demo.utils.CameraUtils;
import com.android.camera2demo.utils.ToastMaster;
import com.android.camera2demo.widget.AutoFitTextureView;
import com.android.camera2demo.widget.ImageSaver;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 自己整理的Camera2
 */

public class MyCameraActivity extends AppCompatActivity {

    private static final int STATE_PREVIEW = 0;//相机状态：显示相机预览。
    private static final int STATE_WAITING_LOCK = 1;//相机状态：等待焦点被锁定。
    private static final int STATE_WAITING_PRECAPTURE = 2;//相机状态：等待曝光为预拍状态。
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;//相机状态：等待曝光状态不是预捕获。
    private static final int STATE_PICTURE_TAKEN = 4;//相机状态：拍摄照片。

    private static final int MAX_PREVIEW_WIDTH = 1920;//Camera2 API保证的最大预览宽度
    private static final int MAX_PREVIEW_HEIGHT = 1080;//Camera2 API保证的最大预览高度

    private ImageView ivFlash;
    private AutoFitTextureView mTextureView;
    private TextView tvCancel;
    private ImageView ivTakePhoto;
    private ImageView ivChangeCamera;

    /**
     * 当前摄像头ID {@link CameraDevice}.
     */
    private String mCameraId;
    private Integer mFacing = CameraCharacteristics.LENS_FACING_BACK;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);//信号量在关闭相机之前阻止应用程序退出。
    private boolean mFlashSupported;//当前相机设备是否支持Flash。
    private Integer mSensorOrientation;//相机传感器的方向
    private int mState = STATE_PREVIEW;//照相机状态的当前状态

    private String mSavePath;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;//相机捕捉会话
    private ImageReader mImageReader;//处理静止图像捕捉
    private Size mPreviewSize;
    private CaptureRequest.Builder mPreviewRequestBuilder;//捕获请求
    private CaptureRequest mPreviewRequest;//CaptureRequest 由 mPreviewRequestBuilder 生成

    public static void show(Activity activity) {
        Intent intent = new Intent();
        intent.setClass(activity, MyCameraActivity.class);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_camera);

        initData();
        initView();

    }

    @Override
    protected void onResume() {
        super.onResume();
        // RUNNING STEP 1 Start thread
        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void initData() {
        mSavePath = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                .getAbsolutePath() + File.separator + "Camera2demo" + File.separator;
    }

    private void initView() {
        ivFlash = findViewById(R.id.iv_flash);
        mTextureView = findViewById(R.id.textureview);
        tvCancel = findViewById(R.id.tv_cancel);
        ivTakePhoto = findViewById(R.id.iv_take_photo);
        ivChangeCamera = findViewById(R.id.iv_change_camera);

        ivFlash.setOnClickListener(mClick);
        tvCancel.setOnClickListener(mClick);
        ivTakePhoto.setOnClickListener(mClick);
        ivChangeCamera.setOnClickListener(mClick);
    }

    ///////////////////////////////////////////////////////////////////////////
    // 相机相关方法
    ///////////////////////////////////////////////////////////////////////////

    /**
     * 对于静态图像捕捉，我们使用最大的可用尺寸。
     *
     * @param largest 图片最大尺寸
     */
    private void setPhotoSize(final Size largest) {
        Log.d("ImageReader", "ImageReader.newInstance");
        mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                ImageFormat.JPEG, /*maxImages*/2);
        mImageReader.setOnImageAvailableListener(
                mOnImageAvailableListener, mBackgroundHandler);
    }

    /**
     * 设置PreviewSize 和 TextureView 的宽高
     *
     * @param width        宽度
     * @param height       高度
     * @param largest      图片最大尺寸
     * @param surfaceSizes SurfaceTexture尺寸列表
     */
    private void setOrientation(int width, int height, Size largest, Size[] surfaceSizes) {
        // 了解我们是否需要交换尺寸以获取相对于传感器坐标的预览尺寸。
        final int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        // noinspection ConstantConditions

        boolean swappedDimensions = false;
        switch (displayRotation) {
            case Surface.ROTATION_0:
            case Surface.ROTATION_180:
                swappedDimensions = mSensorOrientation == 90 || mSensorOrientation == 270;
                break;
            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                swappedDimensions = mSensorOrientation == 0 || mSensorOrientation == 180;
                break;
            default:
                Log.e("", "显示旋转无效：" + displayRotation);
        }

        final Point displaySize = new Point();
        getWindowManager().getDefaultDisplay().getSize(displaySize);
        final int rotatedPreviewWidth = swappedDimensions ? height : width;
        final int rotatedPreviewHeight = swappedDimensions ? width : height;
        int maxPreviewWidth = swappedDimensions ? displaySize.y : displaySize.x;
        int maxPreviewHeight = swappedDimensions ? displaySize.x : displaySize.y;

        maxPreviewWidth = maxPreviewWidth > MAX_PREVIEW_WIDTH ? MAX_PREVIEW_WIDTH : maxPreviewWidth;
        maxPreviewHeight = maxPreviewHeight > MAX_PREVIEW_HEIGHT ? MAX_PREVIEW_HEIGHT : maxPreviewHeight;

        // 危险，W.R.！ 试图使用太大的预览尺寸可能会超过摄像头总线的带宽限制，导致非常漂亮的预览，但存储垃圾捕获数据。
        mPreviewSize = CameraUtils.chooseOptimalSize(surfaceSizes,
                rotatedPreviewWidth, rotatedPreviewHeight,
                maxPreviewWidth, maxPreviewHeight, largest);

        // 我们将TextureView的高宽比与我们选择的预览大小进行匹配。
        final int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        } else {
            mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
        }
    }

    /**
     * 设置与相机相关的成员变量。
     *
     * @param width  相机预览的可用尺寸的宽度
     * @param height 相机预览的可用尺寸的高度
     */
    @SuppressWarnings("SuspiciousNameCombination")
    private void setUpCameraOutputs(int width, int height) {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && !facing.equals(mFacing)) {
                    continue;
                }

                final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                final Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                final Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                final Size[] surfaceSizes = map.getOutputSizes(SurfaceTexture.class);

                final Size largest = Collections.max(Arrays.asList(jpegSizes), new CameraUtils.CompareSizesByArea());

                // RUNNING STEP 2 Set camera id
                mCameraId = cameraId;

                // RUNNING STEP 3 Set ImageReader
                setPhotoSize(largest);

                setOrientation(width, height, largest, surfaceSizes);

                // 检查闪存是否受支持。
                mFlashSupported = available == null ? false : available;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // 当使用Camera2API时，当前会引发NPE，但此代码运行的设备不支持该NPE。
            showToast("此设备不支持Android最新相机模块Camera2！");
            finish();
        }
    }

    /**
     * 打开指定的相机 {@link MyCameraActivity#mCameraId}.
     */
    @SuppressLint("MissingPermission")
    private void openCamera(int width, int height) {
        setUpCameraOutputs(width, height);

        final int rotation = getWindowManager().getDefaultDisplay().getRotation();
        CameraUtils.configureTransform(mTextureView, mPreviewSize, rotation, width, height);

        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("超时等待锁定相机开启。");
            }

            // RUNNING STEP 4 openCamera
            assert manager != null;
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("尝试锁定相机开启时中断。", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("试图锁定相机关闭时中断。", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 为相机预览创建一个新的 {@link CameraCaptureSession}
     */
    private void createCameraPreviewSession() {
        try {
            final SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // 我们将默认缓冲区的大小配置为我们想要的相机预览的大小。
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // 这是我们需要开始预览的输出Surface。
            final Surface surface = new Surface(texture);

            // 我们用输出Surface建立了一个CaptureRequest.Builder。
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // RUNNING STEP 6 createCaptureSession
            // 在这里，我们为相机预览创建一个CameraCaptureSession。
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // RUNNING STEP 7 onConfigured
                            // 相机已经关闭
                            if (null == mCameraDevice) {
                                return;
                            }

                            // 会话准备就绪后，我们开始显示预览。
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // 相机预览应该连续自动对焦。
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // 闪光灯在必要时自动启用。
                                setAutoFlash(mPreviewRequestBuilder);

                                // 最后，我们开始显示相机预览。
                                mPreviewRequest = mPreviewRequestBuilder.build();

                                // RUNNING STEP 8 setRepeatingRequest
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("onConfigureFailed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 启动静态图像捕捉。
     */
    private void takePicture() {
        lockFocus();
    }

    /**
     * 锁定焦点作为静态图像捕获的第一步。
     */
    private void lockFocus() {
        try {
            // 这是如何告诉相机锁定焦点。
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // 告诉 mCaptureCallback 等待锁定。
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 解锁焦点。 当静止图像捕捉序列完成时应调用此方法。
     */
    private void unlockFocus() {
        try {
            // 重置自动对焦触发
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // 之后，相机将回到正常的预览状态。
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 运行预捕获序列以捕获静止图像。 当我们在 {@link #mCaptureCallback} from {@link #lockFocus()}
     * 中得到响应时，应该调用这个方法
     */
    private void runPrecaptureSequence() {
        try {
            // 这是如何告诉相机触发。
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // 告诉 mCaptureCallback 等待precapture序列被设置。
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 拍摄静态照片。 当我们从{@link #lockFocus()}得到{@link #mCaptureCallback}的响应时，应该调用这个方法
     */
    private void captureStillPicture() {
        mState = STATE_PICTURE_TAKEN;
        try {
            if (null == mCameraDevice) {
                return;
            }
            // 这是我们用来拍摄照片的CaptureRequest.Builder。
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // 使用与预览相同的AE和AF模式。
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(captureBuilder);

            // 方向
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            rotation = CameraUtils.getOrientation(rotation, mSensorOrientation);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotation);

            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    unlockFocus();
                }
            };

            mCaptureSession.stopRepeating();
            mCaptureSession.abortCaptures();
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    private void process(CaptureResult result) {
        switch (mState) {
            case STATE_PREVIEW: {
                // 当相机预览正常工作时，我们无事可做。
                break;
            }
            case STATE_WAITING_LOCK: {
                final Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                if (afState == null) {
                    captureStillPicture();
                } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                    // 某些设备上的CONTROL_AE_STATE可以为空
                    final Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        captureStillPicture();
                    } else {
                        runPrecaptureSequence();
                    }
                }
                break;
            }
            case STATE_WAITING_PRECAPTURE: {
                // 某些设备上的CONTROL_AE_STATE可以为空
                final Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                    mState = STATE_WAITING_NON_PRECAPTURE;
                }
                break;
            }
            case STATE_WAITING_NON_PRECAPTURE: {
                // 某些设备上的CONTROL_AE_STATE可以为空
                final Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    captureStillPicture();
                }
                break;
            }
        }
    }

    private void showToast(final String text){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ToastMaster.toast(text);
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////
    // 回调
    ///////////////////////////////////////////////////////////////////////////
    private View.OnClickListener mClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.iv_flash:
//                    changeFlashMode();
                    break;
                case R.id.tv_cancel:
                    onBackPressed();
                    break;
                case R.id.iv_take_photo:
                    takePicture();
                    break;
                case R.id.iv_change_camera:
                    if (mFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                        mFacing = CameraCharacteristics.LENS_FACING_BACK;
                    } else {
                        mFacing = CameraCharacteristics.LENS_FACING_FRONT;
                    }

                    closeCamera();
                    stopBackgroundThread();
                    startBackgroundThread();
                    if (mTextureView.isAvailable()) {
                        openCamera(mTextureView.getWidth(), mTextureView.getHeight());
                    } else {
                        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
                    }
                    break;
            }
        }
    };

    /**
     * {@link TextureView.SurfaceTextureListener}在{@link TextureView}上处理几个生命周期事件
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            final int rotation = getWindowManager().getDefaultDisplay().getRotation();
            CameraUtils.configureTransform(mTextureView, mPreviewSize, rotation, width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }
    };

    /**
     * {@link CameraDevice.StateCallback}在{@link CameraDevice}改变其状态时被调用。
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // RUNNING STEP 5 Camera onOpened
            // 当相机打开时调用此方法。 我们在这里开始相机预览。
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            finish();
        }
    };

    /**
     * 这个{@link ImageReader}的“onImageAvailable”的回调对象将在静止图像准备好保存时被调用。
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d("ImageReader", "ImageReader is null? " + (reader == null));
            final ImageSaver imageSaver = new ImageSaver(reader.acquireNextImage(), mSavePath);
            imageSaver.setCreateListener(new ImageSaver.OnCreatePhotoListener() {
                @Override
                public void onSuccess(File file) {
                    showToast("Saved: " + file);
                    final Uri uri = Uri.fromFile(file);
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
                }

                @Override
                public void onError(String errorMessage) {
                    showToast(errorMessage);
                }
            });

            mBackgroundHandler.post(imageSaver);
        }

    };

    /**
     * 处理与JPEG捕获相关的事件的xxx。{@link CameraCaptureSession.CaptureCallback}
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            // RUNNING STEP 9 onCaptureCompleted
            process(result);
        }

    };
}
