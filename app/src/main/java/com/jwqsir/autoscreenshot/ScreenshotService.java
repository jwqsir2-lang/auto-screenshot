package com.jwqsir.autoscreenshot;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 前台服务：每隔 intervalMs 截图一次，保存为 JPEG。
 * - START_STICKY：被系统回收后自动重启。
 * - 设备管理器激活后，普通方式无法卸载本应用。
 * - 退出/停止只能在 MainActivity 里输入密码。
 */
public class ScreenshotService extends Service {

    private static final String TAG = "ScreenshotService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "auto_screenshot_channel";
    private static final long MIN_INTERVAL = 60_000L;
    private static final long MAX_INTERVAL = 15 * 60_000L;
    private static final int MAX_FILES = 2000;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_INTERVAL = "interval_ms";

    private int resultCode;
    private Intent resultData;
    private long intervalMs = 5 * 60_000L;

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long savedCount = 0;
    private String lastSavedName = "";

    private File outputDir;
    private boolean publicDirFailed = false;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.w(TAG, "MediaProjection stopped");
            mediaProjection = null;
            updateNotification("录屏授权已失效，请重新打开本应用重新授权");
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        createNotificationChannel();
        startForegroundCompat(getString(R.string.notif_running, intervalMs / 60000L));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            long iv = intent.getLongExtra(EXTRA_INTERVAL, intervalMs);
            if (iv >= MIN_INTERVAL && iv <= MAX_INTERVAL) intervalMs = iv;
        }
        if (mediaProjection == null && resultCode != 0 && resultData != null) {
            startProjection();
        }
        handler.removeCallbacks(captureRunnable);
        handler.post(captureRunnable);
        return START_STICKY;
    }

    private void startProjection() {
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, resultData);
            mediaProjection.registerCallback(projectionCallback, handler);

            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int width = dm.widthPixels;
            int height = dm.heightPixels;
            int dpi = dm.densityDpi;

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "auto_screenshot", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, handler);
            Log.i(TAG, "projection ready: " + width + "x" + height);
            updateNotification(getString(R.string.notif_running, intervalMs / 60000L));
        } catch (Exception e) {
            Log.e(TAG, "startProjection failed", e);
            updateNotification("授权失败，请重试");
        }
    }

    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                captureNow();
            } catch (Exception e) {
                Log.e(TAG, "capture error", e);
            }
            handler.postDelayed(this, intervalMs);
        }
    };

    private void captureNow() {
        if (imageReader == null) return;
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int width = imageReader.getWidth();
            int height = imageReader.getHeight();
            buffer.rewind();
            int rowPixels = rowStride / pixelStride;
            Bitmap full = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);
            Bitmap bmp = (rowPixels == width) ? full
                    : Bitmap.createBitmap(full, 0, 0, width, height);
            if (bmp != full) full.recycle();
            saveBitmap(bmp);
            bmp.recycle();
        } finally {
            if (image != null) image.close();
        }
    }

    private void saveBitmap(Bitmap bmp) {
        File dir = getOutputDir();
        if (dir == null) return;
        String name = "shot_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                + ".jpg";
        if (writeBitmap(dir, name, bmp)) {
            savedCount++;
            lastSavedName = name;
            trimFiles(dir);
            updateNotification("已保存 " + savedCount + " 张，最近: " + name);
            scanFile(new File(dir, name));
        }
    }

    private boolean writeBitmap(File dir, String name, Bitmap bmp) {
        File out = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "save failed: " + out, e);
            // 公共目录不可写时（Android10+ 分区存储）退回应用专属目录，并记住不再尝试公共目录
            if (!publicDirFailed && dir == publicDir()) {
                publicDirFailed = true;
                outputDir = null;
                File fallback = getOutputDir();
                return writeBitmap(fallback, name, bmp);
            }
            updateNotification("保存失败: " + e.getMessage());
            return false;
        }
    }

    private File publicDir() {
        return new File(Environment.getExternalStorageDirectory(), "auto_screenshot");
    }

    private File getOutputDir() {
        if (outputDir != null) return outputDir;
        if (!publicDirFailed) {
            File pub = publicDir();
            try {
                if (!pub.exists()) pub.mkdirs();
                if (pub.canWrite()) {
                    outputDir = pub;
                    return pub;
                }
            } catch (Exception ignore) {
            }
            publicDirFailed = true;
        }
        File app = new File(getExternalFilesDir(null), "auto_screenshot");
        if (!app.exists()) app.mkdirs();
        outputDir = app;
        return outputDir;
    }

    private void trimFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        int extra = files.length - MAX_FILES;
        for (int i = 0; i < extra; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    private void scanFile(File file) {
        try {
            MediaScannerConnection.scanFile(this,
                    new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        } catch (Exception ignore) {
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "自动截图服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持后台运行并定时截图");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name) + " · 后台运行中")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 从最近任务划掉时，3 秒后自动重启前台服务（Android 不允许后台重新申请录屏，
        // 重启后会停留在"需重新授权"通知，用户点通知打开应用即可恢复）
        Intent restart = new Intent(this, ScreenshotService.class);
        restart.putExtra(EXTRA_INTERVAL, intervalMs);
        PendingIntent pi = PendingIntent.getService(this, 1, restart,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3000, pi);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "onDestroy");
        handler.removeCallbacks(captureRunnable);
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignore) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignore) {}
        try { if (mediaProjection != null) mediaProjection.stop(); } catch (Exception ignore) {}
    }
}
