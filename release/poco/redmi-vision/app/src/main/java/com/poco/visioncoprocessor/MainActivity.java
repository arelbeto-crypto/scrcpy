package com.poco.visioncoprocessor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private EditText host;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (status != null) {
                status.setText(VisionService.getStatus());
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 84);
        }

        int pad = Math.round(18 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Redmi Vision Coprocessor V8.5.1");
        title.setTextSize(22);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(this);
        info.setText("\nProcesa segmentacion y tracking en este Redmi. "
                + "El POCO conserva Camera2, render OpenGL, bokeh, AR y codificacion. "
                + "Usa la misma red Wi-Fi/5 GHz.\n");
        info.setTextSize(15);
        root.addView(info);

        host = new EditText(this);
        host.setHint("IP del POCO (opcional; vacio = autodetectar)");
        host.setSingleLine(true);
        root.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button auto = new Button(this);
        auto.setText("Iniciar / autodetectar POCO");
        auto.setOnClickListener(v -> startVision(""));
        root.addView(auto, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button manual = new Button(this);
        manual.setText("Conectar a IP");
        manual.setOnClickListener(v -> startVision(host.getText().toString().trim()));
        root.addView(manual, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button stop = new Button(this);
        stop.setText("Detener");
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, VisionService.class);
            intent.setAction(VisionService.ACTION_STOP);
            startService(intent);
        });
        root.addView(stop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView shortcuts = new TextView(this);
        shortcuts.setText("\nEn la ventana scrcpy de la Acer:\n"
                + "Alt+V  Bokeh ON/OFF\n"
                + "Alt+Shift+V  Intensidad bokeh\n"
                + "Alt+Y  AR siguiente\n"
                + "Alt+Shift+1..9  AR directo\n"
                + "Alt+J  Estado del coprocesador en consola\n");
        shortcuts.setTextSize(14);
        root.addView(shortcuts);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Color.LTGRAY);
        status.setPadding(0, pad, 0, pad);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void startVision(String address) {
        Intent intent = new Intent(this, VisionService.class);
        intent.setAction(VisionService.ACTION_START);
        if (!address.isEmpty()) {
            intent.putExtra(VisionService.EXTRA_HOST, address);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
