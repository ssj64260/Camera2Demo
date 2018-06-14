package com.android.camera2demo.utils;

import android.annotation.SuppressLint;
import android.util.Log;
import android.widget.Toast;

import com.android.camera2demo.app.APP;


/**
 * toast优化工具
 */

public class ToastMaster {

    private static Toast sToast = null;

    private ToastMaster() {

    }

    @SuppressLint("ShowToast")
    public static void toast(String content) {
        Log.d("toast", content);
        showToast(Toast.makeText(APP.getInstance(), content, Toast.LENGTH_SHORT));
    }

    private static void showToast(Toast toast) {
        cancelToast();
        sToast = toast;
        sToast.show();
    }

    private static void cancelToast() {
        if (sToast != null)
            sToast.cancel();
        sToast = null;
    }
}
