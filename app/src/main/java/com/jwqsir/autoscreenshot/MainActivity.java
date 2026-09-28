package com.jwqsir.autoscreenshot;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

import android.os.Environment;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "cfg";
    private static final String KEY_PWD = "pwd";
    private static final String KEY_INTERVAL = "interval_min";
    private static final String DEFAULT_PWD = "1234";

    private MediaProjectionManager projectionManager;
    private ActivityResultLauncher<Intent> projectionLauncher;

    private TextView statusText;
    private EditText intervalEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        buildUi();

        projectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        startScreenshotService(result.getResultCode(), result.getData());
                    } else {
                        toast("未授权录屏，无法截图");
                    }
                });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
        updateStatus();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setLineSpacing(0, 1.4f);
        statusText.setPadding(0, 0, 0, pad);
        root.addView(statusText);

        intervalEdit = new EditText(this);
        intervalEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        intervalEdit.setHint("截图间隔（1~15 分钟，默认 5）");
        long saved = getPrefs().getLong(KEY_INTERVAL, 5);
        intervalEdit.setText(String.valueOf(saved));
        intervalEdit.setSingleLine();
        root.addView(intervalEdit);

        Button btnStart = new Button(this);
        btnStart.setText("授权并开始后台截图");
        btnStart.setOnClickListener(v -> {
            hideIme(intervalEdit);
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent());
        });
        root.addView(btnStart);

        Button btnAdmin = new Button(this);
        btnAdmin.setText("激活设备管理器（防卸载）");
        btnAdmin.setOnClickListener(v -> activateAdmin());
        root.addView(btnAdmin);

        Button btnOpenDir = new Button(this);
        btnOpenDir.setText("打开截图文件夹");
        btnOpenDir.setOnClickListener(v -> openDir());
        root.addView(btnOpenDir);

        Button btnChangePwd = new Button(this);
        btnChangePwd.setText("修改密码");
        btnChangePwd.setOnClickListener(v -> showChangePwdDialog());
        root.addView(btnChangePwd);

        Button btnStop = new Button(this);
        btnStop.setText("停止并退出（需密码）");
        btnStop.setOnClickListener(v -> showStopDialog());
        root.addView(btnStop);

        setContentView(root);
    }

    private void startScreenshotService(int code, Intent data) {
        long minutes = readInterval();
        Intent svc = new Intent(this, ScreenshotService.class);
        svc.putExtra(ScreenshotService.EXTRA_RESULT_CODE, code);
        svc.putExtra(ScreenshotService.EXTRA_RESULT_DATA, data);
        svc.putExtra(ScreenshotService.EXTRA_INTERVAL, minutes * 60_000L);
        ContextCompat.startForegroundService(this, svc);
        toast("已开始：每 " + minutes + " 分钟保存一张");
        updateStatus();
    }

    private long readInterval() {
        long minutes = 5;
        try {
            minutes = Long.parseLong(intervalEdit.getText().toString().trim());
        } catch (Exception ignore) {
        }
        if (minutes < 1) minutes = 1;
        if (minutes > 15) minutes = 15;
        getPrefs().edit().putLong(KEY_INTERVAL, minutes).apply();
        return minutes;
    }

    private void activateAdmin() {
        ComponentName admin = new ComponentName(this, MyDeviceAdminReceiver.class);
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm.isAdminActive(admin)) {
            toast("设备管理器已激活，本应用无法被卸载");
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        startActivity(intent);
    }

    private void openDir() {
        File pub = new File(Environment.getExternalStorageDirectory(), "auto_screenshot");
        File app = new File(getExternalFilesDir(null), "auto_screenshot");
        File dir = pub.exists() ? pub : (app.exists() ? app : pub);
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(Uri.fromFile(dir), "resource/folder");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            toast("没有文件管理器，截图位于: " + dir.getAbsolutePath());
        }
    }

    private void showStopDialog() {
        final EditText et = pwdField("密码");
        new AlertDialog.Builder(this)
                .setTitle("停止并退出")
                .setMessage("请输入密码")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    if (et.getText().toString().equals(getPwd())) {
                        stopService(new Intent(this, ScreenshotService.class));
                        toast("已停止");
                        finishAffinity();
                    } else {
                        toast("密码错误");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showChangePwdDialog() {
        final EditText oldE = pwdField("原密码");
        final EditText newE = pwdField("新密码");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(oldE);
        box.addView(newE);
        new AlertDialog.Builder(this)
                .setTitle("修改密码")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    if (!oldE.getText().toString().equals(getPwd())) {
                        toast("原密码错误");
                        return;
                    }
                    String nw = newE.getText().toString().trim();
                    if (nw.isEmpty()) {
                        toast("新密码不能为空");
                        return;
                    }
                    getPrefs().edit().putString(KEY_PWD, nw).apply();
                    toast("密码已修改");
                    updateStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText pwdField(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setSingleLine();
        return et;
    }

    private void updateStatus() {
        ComponentName admin = new ComponentName(this, MyDeviceAdminReceiver.class);
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        StringBuilder sb = new StringBuilder();
        sb.append("使用说明\n");
        sb.append("1. 填写间隔（1~15 分钟），点「授权并开始后台截图」\n");
        sb.append("2. 在系统弹窗里点「开始投屏/立即开始」\n");
        sb.append("3. 点「激活设备管理器」可防止本应用被卸载\n\n");
        sb.append("截图保存位置：/sdcard/auto_screenshot/\n");
        sb.append("（Android10 以上若公共目录不可写，自动改存应用私有目录，连接电脑可见）\n\n");
        sb.append("设备管理器：").append(dpm.isAdminActive(admin) ? "已激活（防卸载生效）" : "未激活").append("\n");
        sb.append("默认密码：").append(getPwd().equals(DEFAULT_PWD) ? "1234（请尽快修改）" : "已修改").append("\n");
        sb.append("通知栏有一条常驻通知，系统不会轻易回收本服务。");
        statusText.setText(sb.toString());
    }

    private String getPwd() {
        return getPrefs().getString(KEY_PWD, DEFAULT_PWD);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void hideIme(View view) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        } catch (Exception ignore) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
