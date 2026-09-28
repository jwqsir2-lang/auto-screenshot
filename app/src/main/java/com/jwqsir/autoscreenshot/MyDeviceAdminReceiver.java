package com.jwqsir.autoscreenshot;

import android.content.Context;
import android.content.Intent;

/**
 * 设备管理器接收器：激活后系统会阻止卸载本应用，禁用时弹出二次确认。
 */
public class MyDeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "禁用设备管理器后将可以卸载本应用，后台自动截图会停止。确定继续？";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        // BroadcastReceiver 里弹 Dialog 不可靠，用通知提示
        try {
            android.widget.Toast.makeText(context,
                    "设备管理器已禁用，本应用现在可以被卸载",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignore) {
        }
    }
}
