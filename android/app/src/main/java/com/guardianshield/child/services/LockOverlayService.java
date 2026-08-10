package com.guardianshield.child.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.guardianshield.child.LauncherHomeActivity;
import com.guardianshield.child.util.GuardianPrefs;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tela de bloqueio em tela cheia (Pausa Geral / tarefas pendentes / tempo esgotado /
 * app bloqueado). Visual em "vidro fosco" (glassmorphism) sobre um fundo escuro com
 * um brilho gradiente sutil na cor de destaque que o pai já escolheu pra Home (mesmas
 * cores de GuardianPrefs.themeColors, pra ficar consistente com o resto do app) —
 * sem depender de blur de verdade (RenderEffect só existe a partir da API 31, e o
 * minSdk daqui é 23), então o efeito é feito com painéis semitransparentes + borda
 * clara fina, técnica padrão de "glass card" que funciona em qualquer versão.
 */
public class LockOverlayService extends Service {
    // Janela de exceção temporária pro bloqueio (ver GuardianPrefs.setTaskSubmissionAllowedUntil):
    // tempo suficiente pra abrir a câmera do sistema, tirar a foto e voltar sem ser
    // barrado de volta pra essa tela no meio do caminho.
    private static final long TASK_SUBMISSION_WINDOW_MS = 3 * 60 * 1000L;

    // Largura do "cartão" central (todos os blocos — cabeçalho, tarefas, PIN — se
    // alinham nessa mesma largura, em vez de cada um ter seu próprio valor solto
    // como antes, que foi a causa do card de tarefa ficar espremido/quebrado).
    private static final int CONTENT_WIDTH_DP = 300;
    private static final String BG_DARK = "#0b1120";

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

            // ScrollView com fillViewport: se o conteúdo (cabeçalho + tarefas + PIN)
            // couber na tela, fica centralizado verticalmente como antes; se não couber
            // (várias tarefas, ou teclado aberto), passa a rolar em vez de cortar.
            ScrollView scrollRoot = new ScrollView(this);
            scrollRoot.setFillViewport(true);
            scrollRoot.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrollRoot.setBackground(buildRootBackground());

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            layout.setLayoutParams(new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            layout.setPadding(dp(24), dp(56), dp(24), dp(56));
            scrollRoot.addView(layout);

            // --- Cartão de cabeçalho: tarja gradiente de destaque + título + mensagem ---
            LinearLayout headerCard = new LinearLayout(this);
            headerCard.setOrientation(LinearLayout.VERTICAL);
            headerCard.setGravity(Gravity.CENTER);
            headerCard.setBackground(buildGlassCard());
            headerCard.setPadding(dp(24), dp(28), dp(24), dp(28));
            LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(dp(CONTENT_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT);
            headerCard.setLayoutParams(headerParams);

            View accentStrip = new View(this);
            accentStrip.setBackground(buildAccentGradient(dp(6)));
            LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(dp(56), dp(5));
            stripParams.bottomMargin = dp(18);
            accentStrip.setLayoutParams(stripParams);
            headerCard.addView(accentStrip);

            titleView = new TextView(this);
            titleView.setText(titleText);
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(22);
            titleView.setGravity(Gravity.CENTER);
            headerCard.addView(titleView);

            subtitleView = new TextView(this);
            subtitleView.setText(subtitleText);
            subtitleView.setTextColor(Color.parseColor("#94a3b8"));
            subtitleView.setTextSize(15);
            subtitleView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = dp(8);
            subtitleView.setLayoutParams(subtitleParams);
            headerCard.addView(subtitleView);

            layout.addView(headerCard);

            if (showTaskList) {
                taskListContainer = buildTaskListView();
                layout.addView(taskListContainer);
            } else {
                taskListContainer = null;
            }

            if (showPinField) {
                LinearLayout pinCard = new LinearLayout(this);
                pinCard.setOrientation(LinearLayout.VERTICAL);
                pinCard.setGravity(Gravity.CENTER);
                pinCard.setBackground(buildGlassCard());
                pinCard.setPadding(dp(20), dp(20), dp(20), dp(20));
                LinearLayout.LayoutParams pinCardParams = new LinearLayout.LayoutParams(dp(CONTENT_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT);
                pinCardParams.topMargin = dp(16);
                pinCard.setLayoutParams(pinCardParams);

                pinInputView = new EditText(this);
                pinInputView.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                pinInputView.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(6) });
                pinInputView.setHint("PIN dos pais");
                pinInputView.setTextColor(Color.WHITE);
                pinInputView.setHintTextColor(Color.parseColor("#64748b"));
                pinInputView.setGravity(Gravity.CENTER);
                pinInputView.setTextSize(20);
                pinInputView.setBackground(buildInputFieldBackground());
                pinInputView.setPadding(dp(16), dp(14), dp(16), dp(14));
                pinInputView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                pinCard.addView(pinInputView);

                Button unlockButton = new Button(this);
                unlockButton.setText("Desbloquear");
                unlockButton.setTextColor(Color.WHITE);
                unlockButton.setAllCaps(false);
                unlockButton.setBackground(buildAccentGradient(dp(14)));
                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                btnParams.topMargin = dp(14);
                unlockButton.setLayoutParams(btnParams);
                unlockButton.setOnClickListener(v -> attemptPinUnlock());
                pinCard.addView(unlockButton);

                layout.addView(pinCard);
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

            windowManager.addView(scrollRoot, params);
            overlayView = scrollRoot;
            isShowing = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Desenho: gradiente de fundo, cartões de vidro e botão de destaque, todos
    // reaproveitando a cor de tema que o pai já escolheu pra Home (GuardianPrefs.
    // themeColors) — a tela de bloqueio fica visualmente consistente com o resto do
    // app em vez de usar uma paleta nova e solta. ---

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * Mistura duas cores 100% opacas (sem canal alpha) — usado pro brilho do fundo em
     * vez de baixar o alpha da cor de destaque, porque alpha < 255 nessa camada deixa
     * a tela por trás da janela (Home, o que a criança estava usando) vazar por baixo
     * da sobreposição, prejudicando a leitura. O "vidro" fica só nos cards internos,
     * por cima desse fundo já sólido — nunca na janela inteira.
     */
    private int blendOpaque(int colorA, int colorB, float ratioOfA) {
        int r = Math.round(Color.red(colorA) * ratioOfA + Color.red(colorB) * (1 - ratioOfA));
        int g = Math.round(Color.green(colorA) * ratioOfA + Color.green(colorB) * (1 - ratioOfA));
        int b = Math.round(Color.blue(colorA) * ratioOfA + Color.blue(colorB) * (1 - ratioOfA));
        return Color.rgb(r, g, b);
    }

    /** Fundo 100% opaco (nunca deixa a tela por trás da janela aparecer) com um brilho na cor de destaque, no topo. */
    private GradientDrawable buildRootBackground() {
        int accent = GuardianPrefs.INSTANCE.themeColors(this).getFirst();
        int dark = Color.parseColor(BG_DARK);
        int glow = blendOpaque(accent, dark, 0.4f);
        GradientDrawable bg = new GradientDrawable();
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setGradientRadius(dp(420));
        bg.setGradientCenter(0.5f, 0.24f);
        bg.setColors(new int[]{ glow, dark });
        return bg;
    }

    /** "Vidro fosco": painel semitransparente com borda clara fina, sem precisar de blur de verdade. */
    private GradientDrawable buildGlassCard() {
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.argb(28, 255, 255, 255));
        card.setCornerRadius(dp(20));
        card.setStroke(dp(1), Color.argb(46, 255, 255, 255));
        return card;
    }

    /** Linha/botão em degradê definido (as duas cores de tema escolhidas pelo pai), o "destaque" da tela. */
    private GradientDrawable buildAccentGradient(int cornerRadiusPx) {
        kotlin.Pair<Integer, Integer> theme = GuardianPrefs.INSTANCE.themeColors(this);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{ theme.getFirst(), theme.getSecond() });
        gradient.setCornerRadius(cornerRadiusPx);
        return gradient;
    }

    private GradientDrawable buildInputFieldBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(40, 255, 255, 255));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.argb(60, 255, 255, 255));
        return bg;
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
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(dp(CONTENT_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT);
        containerParams.topMargin = dp(16);
        container.setLayoutParams(containerParams);

        TextView heading = new TextView(this);
        heading.setText("📋 Tarefas de hoje");
        heading.setTextColor(Color.parseColor("#94a3b8"));
        heading.setTextSize(13);
        heading.setPadding(dp(4), 0, 0, dp(8));
        container.addView(heading);

        for (GuardianPrefs.TaskItem task : GuardianPrefs.INSTANCE.parsedTodayTasks(this)) {
            container.addView(buildTaskRow(task));
        }
        return container;
    }

    /**
     * Card de tarefa em DUAS linhas — ícone+título+recompensa em cima, status/botão de
     * enviar foto embaixo ocupando a largura toda. Antes era tudo numa linha horizontal
     * só (ícone + título + um Button "Enviar Foto"), e o título ficava espremido pro
     * botão caber do lado, quebrando letra por letra.
     */
    private View buildTaskRow(GuardianPrefs.TaskItem task) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(buildGlassCard());
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

        LinearLayout topLine = new LinearLayout(this);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView iconView = new TextView(this);
        iconView.setText(task.getIcon());
        iconView.setTextSize(20);
        iconView.setPadding(0, 0, dp(10), 0);
        topLine.addView(iconView);

        TextView taskTitleView = new TextView(this);
        taskTitleView.setText(task.getTitle());
        taskTitleView.setTextColor(Color.WHITE);
        taskTitleView.setTextSize(15);
        taskTitleView.setMaxLines(2);
        // weight=1: o título recebe todo o espaço restante da linha (a recompensa ao
        // lado é só um selo curto) — essa era exatamente a largura que faltava antes.
        taskTitleView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topLine.addView(taskTitleView);

        TextView rewardView = new TextView(this);
        rewardView.setText("+" + task.getRewardMinutes() + "min");
        rewardView.setTextColor(Color.parseColor("#94a3b8"));
        rewardView.setTextSize(12);
        rewardView.setBackground(buildInputFieldBackground());
        rewardView.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams rewardParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rewardParams.leftMargin = dp(8);
        rewardView.setLayoutParams(rewardParams);
        topLine.addView(rewardView);

        row.addView(topLine);

        // Mesma semântica de tappable do TaskCardAdapter (widget da Home): só
        // pending/rejected podem (re)enviar foto; submitted/approved só mostram status.
        String status = task.getStatus();
        boolean tappable = "pending".equals(status) || "rejected".equals(status);
        if (tappable) {
            Button sendPhotoButton = new Button(this);
            sendPhotoButton.setText("📸 Enviar Foto");
            sendPhotoButton.setTextColor(Color.WHITE);
            sendPhotoButton.setAllCaps(false);
            sendPhotoButton.setTextSize(13);
            sendPhotoButton.setBackground(buildAccentGradient(dp(12)));
            LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sendParams.topMargin = dp(12);
            sendPhotoButton.setLayoutParams(sendParams);
            sendPhotoButton.setOnClickListener(v -> startTaskCameraFlow(task.getId()));
            row.addView(sendPhotoButton);
        } else {
            TextView statusView = new TextView(this);
            statusView.setText("approved".equals(status) ? "✅ Aprovada" : "⏳ Aguardando aprovação");
            statusView.setTextColor(Color.parseColor("#94a3b8"));
            statusView.setTextSize(12);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            statusParams.topMargin = dp(8);
            statusView.setLayoutParams(statusParams);
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
