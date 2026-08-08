package com.guardianshield.child.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class LockOverlayService extends Service {
    private WindowManager windowManager;
    private View overlayView;
    private TextView titleView;
    private TextView subtitleView;
    private static boolean isShowing = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "SHOW_OVERLAY".equals(intent.getAction())) {
            String title = intent.getStringExtra("title");
            String message = intent.getStringExtra("message");
            showOverlay(
                title != null ? title : "🔒 DISPOSITIVO BLOQUEADO",
                message != null ? message : "Pausa Geral Ativa ou Tempo Limite Esgotado.\nFale com seus pais para liberar o uso."
            );
        } else if (intent != null && "HIDE_OVERLAY".equals(intent.getAction())) {
            hideOverlay();
        }
        return START_STICKY;
    }

    private void showOverlay(String titleText, String subtitleText) {
        try {
            if (isShowing && overlayView != null) {
                if (titleView != null) titleView.setText(titleText);
                if (subtitleView != null) subtitleView.setText(subtitleText);
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.e("GuardianShield", "Não é possível exibir o bloqueio: permissão SYSTEM_ALERT_WINDOW não concedida.");
                return;
            }

            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            layout.setBackgroundColor(Color.parseColor("#0f172a")); // Fundo escuro igual ao tema do app

            titleView = new TextView(this);
            titleView.setText(titleText);
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(24);
            titleView.setGravity(Gravity.CENTER);
            titleView.setPadding(32, 0, 32, 20);

            subtitleView = new TextView(this);
            subtitleView.setText(subtitleText);
            subtitleView.setTextColor(Color.parseColor("#94a3b8"));
            subtitleView.setTextSize(16);
            subtitleView.setGravity(Gravity.CENTER);
            subtitleView.setPadding(32, 0, 32, 0);

            layout.addView(titleView);
            layout.addView(subtitleView);

            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_PHONE;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL 
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN 
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            );

            windowManager.addView(layout, params);
            overlayView = layout;
            isShowing = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void hideOverlay() {
        if (!isShowing || overlayView == null) return;
        try {
            if (windowManager != null) {
                windowManager.removeView(overlayView);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            overlayView = null;
            titleView = null;
            subtitleView = null;
            isShowing = false;
        }
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        super.onDestroy();
    }
}
