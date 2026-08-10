package com.guardianshield.child.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.guardianshield.child.LauncherHomeActivity;
import com.guardianshield.child.util.GuardianPrefs;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LockOverlayService extends Service {
    // Janela de exceção temporária pro bloqueio (ver GuardianPrefs.setTaskSubmissionAllowedUntil):
    // tempo suficiente pra abrir a câmera do sistema, tirar a foto e voltar sem ser
    // barrado de volta pra essa tela no meio do caminho.
    private static final long TASK_SUBMISSION_WINDOW_MS = 3 * 60 * 1000L;

    private WindowManager windowManager;
    private View overlayView;
    private TextView titleView;
    private TextView subtitleView;
    private EditText pinInputView;
    private LinearLayout taskListContainer;
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
            // Só o bloqueio "geral" (Pausa Geral/tarefas/tempo esgotado) permite o PIN
            // de emergência — um app bloqueado individualmente pelo pai continua
            // bloqueado mesmo com o PIN certo (ver showOverlay em ParentalAccessibilityService).
            boolean allowPinUnlock = intent.getBooleanExtra("allowPinUnlock", false);
            // Só quando o motivo do bloqueio é especificamente "tarefas pendentes".
            boolean allowTaskSubmission = intent.getBooleanExtra("allowTaskSubmission", false);
            showOverlay(
                title != null ? title : "🔒 DISPOSITIVO BLOQUEADO",
                message != null ? message : "Pausa Geral Ativa ou Tempo Limite Esgotado.\nFale com seus pais para liberar o uso.",
                allowPinUnlock,
                allowTaskSubmission
            );
        } else if (intent != null && "HIDE_OVERLAY".equals(intent.getAction())) {
            hideOverlay();
        }
        return START_STICKY;
    }

    private void showOverlay(String titleText, String subtitleText, boolean allowPinUnlock, boolean allowTaskSubmission) {
        try {
            boolean showPinField = allowPinUnlock && GuardianPrefs.INSTANCE.hasUnlockPinConfigured(this);
            boolean showTaskList = allowTaskSubmission && !GuardianPrefs.INSTANCE.parsedTodayTasks(this).isEmpty();

            if (isShowing && overlayView != null) {
                if (titleView != null) titleView.setText(titleText);
                if (subtitleView != null) subtitleView.setText(subtitleText);
                // O tipo de bloqueio pode ter mudado (ex: de "app bloqueado" pra "tempo
                // esgotado") desde a última vez que essa sobreposição foi mostrada — em
                // vez de tentar adicionar/remover só os campos extras, recria a view inteira.
                boolean pinFieldAlreadyShown = pinInputView != null;
                boolean taskListAlreadyShown = taskListContainer != null;
                if (pinFieldAlreadyShown == showPinField && taskListAlreadyShown == showTaskList) return;
                hideOverlay();
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

            if (showTaskList) {
                taskListContainer = buildTaskListView();
                layout.addView(taskListContainer);
            } else {
                taskListContainer = null;
            }

            if (showPinField) {
                pinInputView = new EditText(this);
                pinInputView.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                pinInputView.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(6) });
                pinInputView.setHint("PIN dos pais");
                pinInputView.setTextColor(Color.WHITE);
                pinInputView.setHintTextColor(Color.parseColor("#64748b"));
                pinInputView.setGravity(Gravity.CENTER);
                pinInputView.setTextSize(20);
                pinInputView.setBackgroundColor(Color.parseColor("#1e293b"));
                pinInputView.setPadding(24, 20, 24, 20);
                LinearLayout.LayoutParams pinParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                );
                pinParams.width = 320;
                pinParams.topMargin = 40;
                pinInputView.setLayoutParams(pinParams);
                layout.addView(pinInputView);

                Button unlockButton = new Button(this);
                unlockButton.setText("Desbloquear");
                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                );
                btnParams.topMargin = 16;
                unlockButton.setLayoutParams(btnParams);
                unlockButton.setOnClickListener(v -> attemptPinUnlock());
                layout.addView(unlockButton);
            } else {
                pinInputView = null;
            }

            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_PHONE;
            }

            // Sem FLAG_NOT_FOCUSABLE: o campo de PIN precisa conseguir focar e abrir o
            // teclado. SOFT_INPUT_ADJUST_RESIZE evita que o teclado cubra o campo.
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
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

            windowManager.addView(layout, params);
            overlayView = layout;
            isShowing = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Lista das tarefas de hoje (GuardianPrefs.parsedTodayTasks — mesma fonte que já
     * alimenta o widget da Home) com um botão de enviar foto pra quem ainda pode
     * (pending/rejected). A Home nativa fica coberta por essa sobreposição durante o
     * bloqueio, então sem isso a criança não tem como ver nem cumprir as tarefas.
     */
    private LinearLayout buildTaskListView() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(340, LinearLayout.LayoutParams.WRAP_CONTENT);
        containerParams.topMargin = 24;
        container.setLayoutParams(containerParams);

        for (GuardianPrefs.TaskItem task : GuardianPrefs.INSTANCE.parsedTodayTasks(this)) {
            container.addView(buildTaskRow(task));
        }
        return container;
    }

    private View buildTaskRow(GuardianPrefs.TaskItem task) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.parseColor("#1e293b"));
        row.setPadding(20, 16, 20, 16);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = 10;
        row.setLayoutParams(rowParams);

        TextView iconView = new TextView(this);
        iconView.setText(task.getIcon());
        iconView.setTextSize(22);
        iconView.setPadding(0, 0, 16, 0);
        row.addView(iconView);

        LinearLayout infoColumn = new LinearLayout(this);
        infoColumn.setOrientation(LinearLayout.VERTICAL);
        infoColumn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView taskTitleView = new TextView(this);
        taskTitleView.setText(task.getTitle());
        taskTitleView.setTextColor(Color.WHITE);
        taskTitleView.setTextSize(15);
        infoColumn.addView(taskTitleView);

        TextView rewardView = new TextView(this);
        rewardView.setText("+" + task.getRewardMinutes() + " min");
        rewardView.setTextColor(Color.parseColor("#64748b"));
        rewardView.setTextSize(12);
        infoColumn.addView(rewardView);

        row.addView(infoColumn);

        // Mesma semântica de tappable do TaskCardAdapter (widget da Home): só
        // pending/rejected podem (re)enviar foto; submitted/approved só mostram status.
        String status = task.getStatus();
        boolean tappable = "pending".equals(status) || "rejected".equals(status);
        if (tappable) {
            Button sendPhotoButton = new Button(this);
            sendPhotoButton.setText("📸 Enviar Foto");
            sendPhotoButton.setTextSize(12);
            sendPhotoButton.setOnClickListener(v -> startTaskCameraFlow(task.getId()));
            row.addView(sendPhotoButton);
        } else {
            TextView statusView = new TextView(this);
            statusView.setText("approved".equals(status) ? "✅ Aprovada" : "⏳ Aguardando aprovação");
            statusView.setTextColor(Color.parseColor("#94a3b8"));
            statusView.setTextSize(12);
            row.addView(statusView);
        }

        return row;
    }

    /**
     * Abre a janela de exceção temporária (pra não barrar o app de câmera do sistema
     * de volta pra essa tela) e manda a Home nativa disparar a câmera pra essa tarefa
     * específica — LauncherHomeActivity já tem toda a lógica de captura/envio pronta
     * (launchTaskCamera/submitTaskPhoto, usada normalmente pelo widget da Home), essa
     * extra só aciona o mesmo fluxo sem precisar duplicar nada.
     */
    private void startTaskCameraFlow(String taskId) {
        GuardianPrefs.INSTANCE.setTaskSubmissionAllowedUntil(this, System.currentTimeMillis() + TASK_SUBMISSION_WINDOW_MS);
        hideOverlay();
        Intent intent = new Intent(this, LauncherHomeActivity.class);
        intent.putExtra("openTaskCameraForId", taskId);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    /**
     * Confere o PIN digitado contra o hash sincronizado do backend (GuardianPrefs,
     * gravado pelo poll de regras — ver ParentalAccessibilityService.pollRulesSync).
     * A checagem é 100% local: nenhuma chamada de rede acontece aqui, por isso
     * funciona mesmo com o aparelho totalmente offline.
     */
    private void attemptPinUnlock() {
        if (pinInputView == null) return;
        String entered = pinInputView.getText().toString().trim();
        String expectedHash = GuardianPrefs.INSTANCE.unlockPinHash(this);
        if (expectedHash == null || expectedHash.isEmpty()) return;

        if (expectedHash.equals(sha256Hex(entered))) {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            GuardianPrefs.INSTANCE.setPinOverrideDate(this, today);
            // Avisa o backend assim que a rede voltar (ver ackPinUnlock no poll de
            // regras) — pra o painel do pai não continuar mostrando "Pausa Geral
            // Ativa" quando o aparelho já foi liberado de verdade.
            GuardianPrefs.INSTANCE.setPendingPinUnlockAck(this, true);
            Toast.makeText(this, "Desbloqueado! Liberado até o fim do dia.", Toast.LENGTH_LONG).show();
            hideOverlay();
        } else {
            Toast.makeText(this, "PIN incorreto.", Toast.LENGTH_SHORT).show();
            pinInputView.setText("");
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
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
            pinInputView = null;
            taskListContainer = null;
            isShowing = false;
        }
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        super.onDestroy();
    }
}
