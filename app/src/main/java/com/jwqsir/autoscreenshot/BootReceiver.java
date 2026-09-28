package com.jwqsir.autoscreenshot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机/应用更新后自动打开主界面，提示用户重新授权录屏。
 * Android 不允许后台重新申请录屏授权，必须由用户在界面里点一次。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent activity = new Intent(context, MainActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(activity);
        } catch (Exception ignore) {
        }
    }
}
