package com.guardianshield.child.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.guardianshield.child.LauncherHomeActivity;
import com.guardianshield.child.R;
import com.guardianshield.child.util.GuardianPrefs;
import com.guardianshield.child.util.NetworkHelper;
import com.guardianshield.child.widget.PixelCatGameView;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Set;

/**
 * Tela de bloqueio em tela cheia (Pausa Geral / tarefas pendentes / tempo esgotado /
 * app bloqueado). Visual em "vidro fosco" (glassmorphism) sobre um fundo escuro com um
 * brilho gradiente na cor de destaque — sem depender de blur de verdade (RenderEffect só
 * existe a partir da API 31, e o minSdk daqui é 23), então o efeito é feito com painéis
 * semitransparentes + borda clara fina, técnica padrão de "glass card" que funciona em
 * qualquer versão. A cor de destaque NÃO é mais a fixa do tema da Home (GuardianPrefs.
 * themeColors) — é GuardianPrefs.currentLockAccentColors, que rotaciona sozinha a cada
 * vez que a tela liga de novo (ver ParentalAccessibilityService), só nessa tela.
 */
public class LockOverlayService extends Service {
    // Lido pelo ParentalAccessibilityService pra não mandar "mostra/esconde" à toa a cada
    // troca de janela (cada startService acorda este serviço na thread principal).
    public static volatile boolean showing = false;

    // Janela de exceção temporária pro bloqueio (ver GuardianPrefs.setTaskSubmissionAllowedUntil):
    // tempo suficiente pra abrir a câmera do sistema, tirar a foto e voltar sem ser
    // barrado de volta pra essa tela no meio do caminho.
    private static final long TASK_SUBMISSION_WINDOW_MS = 3 * 60 * 1000L;

    // Largura do "cartão" central (todos os blocos — sempre-disponíveis, tarefas, PIN — se
    // alinham nessa mesma largura, em vez de cada um ter seu próprio valor solto).
    private static final int CONTENT_WIDTH_DP = 300;
    // Largura real dos blocos dentro do popup: CONTENT_WIDTH_DP, ou menos em tela estreita.
    private int contentWidthDp = CONTENT_WIDTH_DP;
    private static final String BG_DARK = "#0c0a09";
    private static final String COLOR_WARM_MUTED = "#A8A29E"; // subtítulos, labels
    private static final String COLOR_WARM_FAINT = "#79716B"; // legendas bem discretas

    private WindowManager windowManager;
    private View overlayView;
    private TextView titleView;
    private TextView subtitleView;
    private ImageView heroIconView;
    private TextView eyebrowView;
    private EditText pinInputView;
    private LinearLayout taskListContainer;
    // Slot que troca entre o botão-fantasma "Usar PIN dos pais" e o cartão de PIN de
    // verdade (ver showPinAffordance/showPinEntryCard) — o PIN nunca fica exposto por
    // padrão, só depois de a criança/pai tocar de propósito nesse botão.
    private LinearLayout pinRevealSlot;
    private boolean pinUnlockAvailable = false;
    // Mesmo espírito do pinRevealSlot acima — botão-fantasma "⏱️ Pedir tempo extra" que
    // revela um cartão de verdade (motivo + presets de minutos) só ao tocar (ver
    // buildTimeRequestAffordance/showTimeRequestCard). Pedido explícito do usuário: pedir
    // tempo extra direto da tela de bloqueio, sem precisar abrir o app.
    private LinearLayout timeRequestSlot;
    private EditText timeRequestReasonInput;
    private boolean timeRequestSending = false;
    // Container raiz de tudo dentro do cartão — guardado como campo pra attemptPinUnlock
    // poder trocar o conteúdo inteiro pelo seletor de duração sem recriar a janela do zero.
    private LinearLayout contentLayout;
    // Popup central (por cima da Home desfocada) com duas "páginas": a principal (motivo,
    // tarefas, PIN…) e a do joguinho do gato — um botão troca entre elas.
    private FrameLayout cardView;
    private View mainPage;
    private View gamePage;
    private boolean backdropFromHome = false;
    // Minijogo "Alimente o Gatinho" — distração positiva enquanto a criança espera o
    // bloqueio passar (ver PixelCatGameView e buildCatGameCard). Guardado como campo só
    // pra poder chamar stop() explicitamente em hideOverlay (rede de segurança dupla com
    // o onDetachedFromWindow da própria view).
    private PixelCatGameView catGameView;
    // Seletor de ação do gatinho (Alimentar/Brincar/Carinho) — ver buildCatModeSelector().
    // Recriados do zero a cada showOverlay() junto com catGameView, então a lista é
    // esvaziada no início de buildCatModeSelector pra nunca acumular chip de uma
    // sobreposição anterior.
    private final java.util.List<TextView> catModeChips = new java.util.ArrayList<>();
    private TextView catCaptionView;
    // Seletor de QUAL gatinho cuidar (ver buildCatSelectorRow) — cada um dos CAT_COUNT
    // gatinhos da paleta tem progresso próprio persistido (GuardianPrefs.CatState).
    // activeCatIndex é o que está carregado em catGameView agora; salvo antes de trocar
    // (saveActiveCatState) pra nunca perder o progresso do gatinho que estava sendo cuidado.
    private final java.util.List<TextView> catAvatarChips = new java.util.ArrayList<>();
    private int activeCatIndex = 0;
    // Preenchimento das 4 barrinhas de status do gato (energia/limpeza/diversão/carinho)
    // — atualizados via scaleX (ver updateCatStatFill) a cada callback de
    // PixelCatGameView.StatsListener, sem precisar de requestLayout a cada frame.
    private View statEnergyFill;
    private View statHygieneFill;
    private View statFunFill;
    private View statAffectionFill;
    private static boolean isShowing = false;

    // Últimos parâmetros mostrados — guardados só pra REFRESH_ACCENT (tela ligou de novo)
    // conseguir reconstruir a MESMA sobreposição com a cor nova sorteada, sem precisar que
    // o motivo do bloqueio tenha mudado (ver onStartCommand e ParentalAccessibilityService).
    private String lastTitleText;
    private String lastSubtitleText;
    private boolean lastAllowPinUnlock;
    private boolean lastAllowTaskSubmission;
    private String lastIconType;

    // Foco de áudio "roubado" enquanto o bloqueio está visível — não toca nenhum som, só
    // faz o Android avisar qualquer app tocando mídia por baixo (vídeo, música, etc) que
    // perdeu o foco, o que faz esses players pausarem sozinhos.
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // API 26+
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        // Não faz nada com o foco em si (a sobreposição não toca áudio) — só existe pra
        // satisfazer a API, que exige um listener pra conceder o foco.
    };

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
            String iconType = intent.getStringExtra("iconType");
            showOverlay(
                title != null ? title : "🔒 DISPOSITIVO BLOQUEADO",
                message != null ? message : "Pausa Geral Ativa ou Tempo Limite Esgotado.\nFale com seus pais para liberar o uso.",
                allowPinUnlock,
                allowTaskSubmission,
                iconType != null ? iconType : "lock"
            );
        } else if (intent != null && "HIDE_OVERLAY".equals(intent.getAction())) {
            hideOverlay();
        } else if (intent != null && "REFRESH_ACCENT".equals(intent.getAction())) {
            // A tela ligou de novo (ver ParentalAccessibilityService) — atualiza só a cor
            // (fundo + ícone + selo), SEM recriar a janela/sobreposição inteira. Reconstruir
            // tudo (remover+recriar a janela, focar áudio de novo, perder o cartão de PIN
            // se estivesse aberto) a cada ACTION_SCREEN_ON travava a tela com frequência —
            // em alguns aparelhos esse evento dispara mais vezes do que "a tela realmente
            // apagou e ligou de novo" (AOD, sensor de proximidade etc.), então um rebuild
            // pesado repetido demais era exatamente a causa do travamento relatado.
            refreshAccentColorsInPlace();
        }
        return START_STICKY;
    }

    private void showOverlay(String titleText, String subtitleText, boolean allowPinUnlock, boolean allowTaskSubmission, String iconType) {
        try {
            boolean showPinField = allowPinUnlock && GuardianPrefs.INSTANCE.hasUnlockPinConfigured(this);
            boolean showTaskList = allowTaskSubmission && !GuardianPrefs.INSTANCE.parsedTodayTasks(this).isEmpty();

            lastTitleText = titleText;
            lastSubtitleText = subtitleText;
            lastAllowPinUnlock = allowPinUnlock;
            lastAllowTaskSubmission = allowTaskSubmission;
            lastIconType = iconType;

            if (isShowing && overlayView != null) {
                if (titleView != null) titleView.setText(titleText);
                if (subtitleView != null) subtitleView.setText(subtitleText);
                // O tipo de bloqueio pode ter mudado (ex: de "app bloqueado" pra "tempo
                // esgotado") desde a última vez que essa sobreposição foi mostrada — em
                // vez de tentar adicionar/remover só os campos extras, recria a view inteira.
                boolean taskListAlreadyShown = taskListContainer != null;
                if (pinUnlockAvailable == showPinField && taskListAlreadyShown == showTaskList) return;
                hideOverlay();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.e("GuardianShield", "Não é possível exibir o bloqueio: permissão SYSTEM_ALERT_WINDOW não concedida.");
                return;
            }

            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            pinUnlockAvailable = showPinField;

            // Raiz: a Home desfocada (foto tirada pela própria Home, ver HomeSnapshot) +
            // véu escuro, e o popup no meio. Sem a foto, fundo escuro sólido como antes.
            FrameLayout scrollRoot = new FrameLayout(this);
            scrollRoot.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            android.graphics.Bitmap homeShot = com.guardianshield.child.util.HomeSnapshot.capture();
            backdropFromHome = homeShot != null;
            if (homeShot != null) {
                ImageView backdrop = new ImageView(this);
                backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
                android.graphics.drawable.BitmapDrawable shotDrawable = new android.graphics.drawable.BitmapDrawable(getResources(), homeShot);
                shotDrawable.setFilterBitmap(true);
                backdrop.setImageDrawable(shotDrawable);
                scrollRoot.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                View veil = new View(this);
                veil.setBackgroundColor(Color.argb(110, 0, 0, 0));
                scrollRoot.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                scrollRoot.setBackground(buildRootBackground());
            }
            // Toque fora do popup não faz nada (e não chega na Home por baixo).
            scrollRoot.setClickable(true);

            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenWDp = Math.round(metrics.widthPixels / metrics.density);
            int cardWidthDp = Math.min(screenWDp - 32, 400);
            contentWidthDp = Math.min(CONTENT_WIDTH_DP, cardWidthDp - 36);
            int maxCardHeightPx = metrics.heightPixels - dp(120);

            cardView = new FrameLayout(this);
            cardView.setBackground(buildCardBackground());
            cardView.setClipToOutline(true);
            cardView.setElevation(dp(12));
            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dp(cardWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            scrollRoot.addView(cardView, cardParams);

            MaxHeightScrollView mainScroll = new MaxHeightScrollView(this, maxCardHeightPx);
            mainScroll.setVerticalScrollBarEnabled(false);
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER_HORIZONTAL);
            layout.setPadding(dp(18), dp(26), dp(18), dp(20));
            mainScroll.addView(layout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            cardView.addView(mainScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mainPage = mainScroll;
            contentLayout = layout;

            MaxHeightScrollView gameScroll = new MaxHeightScrollView(this, maxCardHeightPx);
            gameScroll.setVerticalScrollBarEnabled(false);
            LinearLayout gameLayout = new LinearLayout(this);
            gameLayout.setOrientation(LinearLayout.VERTICAL);
            gameLayout.setGravity(Gravity.CENTER_HORIZONTAL);
            gameLayout.setPadding(dp(18), dp(14), dp(18), dp(18));
            gameLayout.addView(buildGameHeader());
            gameLayout.addView(buildCatGameCard());
            gameScroll.addView(gameLayout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            gameScroll.setVisibility(View.GONE);
            cardView.addView(gameScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            gamePage = gameScroll;

            // --- Hero: círculo de vidro com ícone + selo pequeno + título + subtítulo,
            // direto sobre o fundo (sem cartão de vidro em volta — só os blocos abaixo
            // usam glass card). "Blocked" (app específico) usa cinza neutro em vez da cor
            // de destaque, de propósito: essa tela deve parecer mais "factual/calma" que
            // um bloqueio geral (Pausa Geral etc), que é "urgente" e usa a cor cheia. ---
            boolean neutralAccent = "blocked".equals(iconType);
            int heroDrawableRes = heroDrawableForIconType(iconType);
            int heroTint = neutralAccent ? Color.parseColor(COLOR_WARM_MUTED) : accentColors()[0];

            layout.addView(buildHeroIconCircle(heroDrawableRes, heroTint));

            eyebrowView = new TextView(this);
            eyebrowView.setText(eyebrowLabelForIconType(iconType, showTaskList));
            eyebrowView.setTextColor(neutralAccent ? Color.parseColor(COLOR_WARM_MUTED) : withAlpha(blendOpaque(accentColors()[0], Color.WHITE, 0.65f), 255));
            eyebrowView.setTextSize(11);
            eyebrowView.setLetterSpacing(0.14f);
            eyebrowView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams eyebrowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            eyebrowParams.bottomMargin = dp(10);
            eyebrowView.setLayoutParams(eyebrowParams);
            layout.addView(eyebrowView);

            titleView = new TextView(this);
            titleView.setText(titleText);
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(25);
            titleView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
            titleView.setLayoutParams(titleParams);
            layout.addView(titleView);

            subtitleView = new TextView(this);
            subtitleView.setText(subtitleText);
            subtitleView.setTextColor(Color.parseColor(COLOR_WARM_MUTED));
            subtitleView.setTextSize(14.5f);
            subtitleView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = dp(9);
            subtitleView.setLayoutParams(subtitleParams);
            layout.addView(subtitleView);

            // Apps "sempre disponíveis": ESSENCIAL, não cosmético — sem isso, um app
            // marcado como sempre-disponível fica sim liberado assim que está em 1º
            // plano, mas a criança não tem como CHEGAR até esse ícone (a sobreposição
            // cobre a Home inteira). Só nos bloqueios "gerais" (allowPinUnlock).
            if (allowPinUnlock) {
                LinearLayout alwaysAvailableRow = buildAlwaysAvailableRow(GuardianPrefs.INSTANCE.alwaysAvailablePackages(this));
                if (alwaysAvailableRow != null) {
                    layout.addView(alwaysAvailableRow);
                }
            }

            if (showTaskList) {
                taskListContainer = buildTaskListView();
                layout.addView(taskListContainer);
            } else {
                taskListContainer = null;
            }

            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(22)));
            layout.addView(spacer);

            // Botão de emergência: aparece em QUALQUER bloqueio desde que o pai tenha
            // cadastrado um telefone. Abre o discador já preenchido (ACTION_DIAL) — não
            // precisa da permissão perigosa CALL_PHONE, o toque final de discar continua
            // sendo de quem está com o aparelho na mão.
            String emergencyPhone = GuardianPrefs.INSTANCE.emergencyPhone(this);
            if (emergencyPhone != null) {
                layout.addView(buildEmergencyButton(emergencyPhone));
            }

            // Pedir tempo extra: só nos bloqueios GERAIS (Pausa Geral/horário/tarefas/
            // tempo esgotado, mesmo escopo do PIN — allowPinUnlock) — um app bloqueado
            // individualmente pelo pai não é liberado por uma concessão de tempo (ver
            // GuardianPrefs.isParentTimeGrantActive), então oferecer o botão ali seria
            // enganoso.
            if (allowPinUnlock) {
                timeRequestSlot = new LinearLayout(this);
                timeRequestSlot.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams timeRequestSlotParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
                timeRequestSlotParams.topMargin = dp(10);
                timeRequestSlot.setLayoutParams(timeRequestSlotParams);
                layout.addView(timeRequestSlot);
                buildTimeRequestAffordance();
            } else {
                timeRequestSlot = null;
                timeRequestReasonInput = null;
            }

            if (showPinField) {
                pinRevealSlot = new LinearLayout(this);
                pinRevealSlot.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
                slotParams.topMargin = dp(12);
                pinRevealSlot.setLayoutParams(slotParams);
                layout.addView(pinRevealSlot);
                showPinAffordance();
            } else {
                pinRevealSlot = null;
                pinInputView = null;
            }

            // Minijogo numa "página" própria do popup — aqui só o botão que leva até ele,
            // por último, pra não competir com os controles de verdade.
            layout.addView(buildGameButton());

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
            // ADJUST_PAN em vez de ADJUST_RESIZE: essa janela é adicionada direto pelo
            // WindowManager (não é uma Activity/Dialog normal), e nesse tipo de janela o
            // redimensionamento de ADJUST_RESIZE nem sempre acontece de verdade em alguns
            // fabricantes (o campo de PIN ficava atrás do teclado mesmo pedindo resize) —
            // ADJUST_PAN só desloca o conteúdo pra cima até o campo focado ficar visível,
            // sem depender do sistema encolher a janela.
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
            // Cobre a tela inteira, inclusive atrás das barras de status e navegação — o fundo
            // desfocado não pode deixar uma faixa da Home nítida em cima e embaixo.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.setFitInsetsTypes(0);
            }
            // Fundo (Home desfocada) por baixo das barras de status e navegação também.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.setFitInsetsTypes(0);
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }

            windowManager.addView(scrollRoot, params);
            overlayView = scrollRoot;
            isShowing = true;
            showing = true;
            // Entra suave: o fundo aparece e o popup cresce um pouco, como um diálogo.
            scrollRoot.setAlpha(0f);
            scrollRoot.animate().alpha(1f).setDuration(180).start();
            cardView.setScaleX(0.92f);
            cardView.setScaleY(0.92f);
            cardView.animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            stealAudioFocusToPauseMedia();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int heroDrawableForIconType(String iconType) {
        if ("tasks".equals(iconType)) return R.drawable.ic_clipboard_hero;
        if ("blocked".equals(iconType)) return R.drawable.ic_block_hero;
        return R.drawable.ic_lock_hero;
    }

    private String eyebrowLabelForIconType(String iconType, boolean showTaskList) {
        if ("blocked".equals(iconType)) return "🔒 Aplicativo bloqueado";
        if ("tasks".equals(iconType) || showTaskList) return "📋 Tarefa pendente";
        if ("settings".equals(iconType)) return "🔧 Configurações protegidas";
        return "🔒 Dispositivo bloqueado";
    }

    /** Círculo de vidro com o ícone vetorial (lock/clipboard/block) tintado na cor passada. */
    private View buildHeroIconCircle(int drawableRes, int tintColor) {
        LinearLayout circle = new LinearLayout(this);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setColor(Color.argb(31, 255, 255, 255));
        circleBg.setCornerRadius(dp(26));
        circleBg.setStroke(dp(1), Color.argb(41, 255, 255, 255));
        circle.setBackground(circleBg);
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(dp(80), dp(80));
        circleParams.bottomMargin = dp(20);
        circle.setLayoutParams(circleParams);

        heroIconView = new ImageView(this);
        heroIconView.setImageResource(drawableRes);
        heroIconView.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
        heroIconView.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        circle.addView(heroIconView);
        return circle;
    }

    private Button buildEmergencyButton(String emergencyPhone) {
        Button emergencyButton = new Button(this);
        emergencyButton.setText("🚨 Ligar para emergência");
        emergencyButton.setTextColor(Color.WHITE);
        emergencyButton.setAllCaps(false);
        emergencyButton.setTextSize(14.5f);
        GradientDrawable emergencyBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{ Color.parseColor("#F43F5E"), Color.parseColor("#E11D48") });
        emergencyBg.setCornerRadius(dp(18));
        emergencyButton.setBackground(emergencyBg);
        emergencyButton.setPadding(dp(16), dp(15), dp(16), dp(15));
        LinearLayout.LayoutParams emergencyParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
        emergencyButton.setLayoutParams(emergencyParams);
        emergencyButton.setOnClickListener(v -> callEmergencyNumber(emergencyPhone));
        return emergencyButton;
    }

    /**
     * Botão-fantasma discreto "⏱️ Pedir tempo extra" — mesmo espírito do "🔑 Usar PIN dos
     * pais" (showPinAffordance): nada de formulário exposto por padrão, só ao tocar aqui é
     * que showTimeRequestCard() revela o cartão de verdade (motivo + presets de minutos).
     */
    private void buildTimeRequestAffordance() {
        if (timeRequestSlot == null) return;
        timeRequestSlot.removeAllViews();
        timeRequestReasonInput = null;

        Button askButton = new Button(this);
        askButton.setText("⏱️ Pedir tempo extra");
        askButton.setTextColor(Color.argb(224, 255, 255, 255));
        askButton.setAllCaps(false);
        askButton.setTextSize(13);
        GradientDrawable ghostBg = new GradientDrawable();
        ghostBg.setColor(Color.argb(15, 255, 255, 255));
        ghostBg.setCornerRadius(dp(16));
        ghostBg.setStroke(dp(1), Color.argb(38, 255, 255, 255));
        askButton.setBackground(ghostBg);
        askButton.setPadding(dp(16), dp(13), dp(16), dp(13));
        askButton.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        askButton.setOnClickListener(v -> showTimeRequestCard());
        timeRequestSlot.addView(askButton);
    }

    /**
     * Revela o cartão de pedido de tempo extra de verdade: um campo de motivo (opcional) e
     * 3 botões de minutos prontos (+15/+30/+60) — tocar em qualquer um já ENVIA o pedido
     * (sem precisar de mais um passo de confirmação, pra ficar rápido de verdade "direto da
     * tela de bloqueio", pedido explícito do usuário). O pedido chega pro app do Pai
     * (GuardianShield Pai) pela mesma fila que ele já usa pra aprovar/negar — ver
     * POST /api/device/request-extra-time no backend.
     */
    private void showTimeRequestCard() {
        if (timeRequestSlot == null) return;
        timeRequestSlot.removeAllViews();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(buildGlassCard());
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText("Pedir tempo extra aos pais");
        label.setTextColor(Color.argb(224, 255, 255, 255));
        label.setTextSize(12.5f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.bottomMargin = dp(10);
        label.setLayoutParams(labelParams);
        card.addView(label);

        timeRequestReasonInput = new EditText(this);
        timeRequestReasonInput.setHint("Motivo (opcional)");
        timeRequestReasonInput.setTextColor(Color.WHITE);
        timeRequestReasonInput.setHintTextColor(Color.argb(110, 255, 255, 255));
        timeRequestReasonInput.setTextSize(14);
        timeRequestReasonInput.setSingleLine(true);
        timeRequestReasonInput.setBackground(buildInputFieldBackground());
        timeRequestReasonInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams reasonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        reasonParams.bottomMargin = dp(12);
        timeRequestReasonInput.setLayoutParams(reasonParams);
        card.addView(timeRequestReasonInput);

        LinearLayout presetsRow = new LinearLayout(this);
        presetsRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] presets = new int[]{ 15, 30, 60 };
        for (int i = 0; i < presets.length; i++) {
            int minutes = presets[i];
            Button presetButton = new Button(this);
            presetButton.setText("+" + minutes + "min");
            presetButton.setTextColor(Color.WHITE);
            presetButton.setAllCaps(false);
            presetButton.setTextSize(13.5f);
            presetButton.setBackground(buildAccentGradient(dp(14)));
            presetButton.setPadding(0, dp(12), 0, dp(12));
            LinearLayout.LayoutParams presetParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) presetParams.leftMargin = dp(8);
            presetButton.setLayoutParams(presetParams);
            presetButton.setOnClickListener(v -> sendTimeRequest(minutes));
            presetsRow.addView(presetButton);
        }
        card.addView(presetsRow);

        timeRequestSlot.addView(card);
    }

    /**
     * Envia o pedido pro backend (POST /api/device/request-extra-time, ver NetworkHelper) —
     * sempre em background (é IPC/rede pra fora, nunca pode travar a thread principal desta
     * tela de bloqueio). Enquanto está em voo, os 3 presets somem e um texto "Enviando..."
     * aparece no lugar, pra não deixar a criança tocar duas vezes achando que não funcionou.
     */
    private void sendTimeRequest(int minutes) {
        if (timeRequestSending || timeRequestSlot == null) return;
        timeRequestSending = true;
        String reason = timeRequestReasonInput != null ? timeRequestReasonInput.getText().toString().trim() : "";

        LinearLayout sendingCard = new LinearLayout(this);
        sendingCard.setOrientation(LinearLayout.VERTICAL);
        sendingCard.setGravity(Gravity.CENTER);
        sendingCard.setBackground(buildGlassCard());
        sendingCard.setPadding(dp(20), dp(18), dp(20), dp(18));
        sendingCard.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView sendingLabel = new TextView(this);
        sendingLabel.setText("Enviando pedido...");
        sendingLabel.setTextColor(Color.argb(224, 255, 255, 255));
        sendingLabel.setTextSize(13);
        sendingCard.addView(sendingLabel);
        timeRequestSlot.removeAllViews();
        timeRequestSlot.addView(sendingCard);

        Handler mainHandler = new Handler(Looper.getMainLooper());
        String finalReason = reason.isEmpty() ? "Pedido feito na tela de bloqueio" : reason;
        new Thread(() -> {
            boolean success;
            try {
                JSONObject body = new JSONObject();
                body.put("requestedMinutes", minutes);
                body.put("reason", finalReason);
                success = NetworkHelper.postJson(this, "/api/device/request-extra-time", body.toString());
            } catch (Exception e) {
                success = false;
            }
            boolean finalSuccess = success;
            mainHandler.post(() -> {
                timeRequestSending = false;
                if (timeRequestSlot == null) return; // sobreposição já fechou nesse meio tempo
                if (finalSuccess) {
                    Toast.makeText(this, "✅ Pedido enviado! Aguarde seus pais.", Toast.LENGTH_LONG).show();
                    showTimeRequestSentConfirmation();
                } else {
                    Toast.makeText(this, "Não foi possível enviar agora. Verifique a internet e tente de novo.", Toast.LENGTH_LONG).show();
                    showTimeRequestCard();
                }
            });
        }).start();
    }

    /** Substitui o cartão pelo aviso de "já enviado" — evita a criança mandar vários
     * pedidos seguidos sem querer; some sozinho na próxima vez que a sobreposição inteira
     * for reconstruída (novo motivo de bloqueio, tela ligou de novo, etc). */
    private void showTimeRequestSentConfirmation() {
        if (timeRequestSlot == null) return;
        timeRequestSlot.removeAllViews();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(buildGlassCard());
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        card.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView sentLabel = new TextView(this);
        sentLabel.setText("✅ Pedido enviado — aguardando seus pais");
        sentLabel.setTextColor(Color.argb(224, 255, 255, 255));
        sentLabel.setTextSize(13);
        sentLabel.setGravity(Gravity.CENTER);
        card.addView(sentLabel);
        timeRequestSlot.addView(card);
    }

    /** Fundo do popup: escuro quase opaco, com o brilho da cor de destaque no topo. */
    private GradientDrawable buildCardBackground() {
        int accent = accentColors()[0];
        int dark = Color.parseColor(BG_DARK);
        GradientDrawable bg = new GradientDrawable();
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setGradientRadius(dp(320));
        bg.setGradientCenter(0.5f, 0.0f);
        bg.setColors(new int[]{ withAlpha(blendOpaque(accent, dark, 0.38f), 245), withAlpha(dark, 242) });
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(1), Color.argb(40, 255, 255, 255));
        return bg;
    }

    /** Botão que troca o popup pra página do joguinho. */
    private View buildGameButton() {
        Button button = new Button(this);
        button.setText("🐱  Brincar com o gatinho");
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14.5f);
        button.setBackground(buildGlassCard());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(contentWidthDp), dp(50));
        params.topMargin = dp(14);
        button.setLayoutParams(params);
        button.setOnClickListener(v -> showGamePage(true));
        return button;
    }

    /** Cabeçalho da página do joguinho: voltar + título. */
    private View buildGameHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowParams);

        TextView back = new TextView(this);
        back.setText("‹  Voltar");
        back.setTextColor(Color.WHITE);
        back.setTextSize(15);
        back.setPadding(dp(4), dp(10), dp(14), dp(10));
        back.setOnClickListener(v -> showGamePage(false));
        row.addView(back);

        TextView title = new TextView(this);
        title.setText("Gatinhos");
        title.setTextColor(Color.parseColor(COLOR_WARM_MUTED));
        title.setTextSize(13);
        title.setGravity(Gravity.END);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** Troca a página do popup (principal <-> joguinho) com um fade rápido. O jogo só roda
     * enquanto a página dele está aberta. */
    private void showGamePage(boolean showGame) {
        if (mainPage == null || gamePage == null) return;
        View from = showGame ? mainPage : gamePage;
        View to = showGame ? gamePage : mainPage;
        if (to.getVisibility() == View.VISIBLE) return;
        if (showGame) {
            if (catGameView != null) catGameView.start();
        } else {
            saveActiveCatState();
            if (catGameView != null) catGameView.stop();
        }
        from.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            from.setVisibility(View.GONE);
            from.setAlpha(1f);
            to.setAlpha(0f);
            to.setVisibility(View.VISIBLE);
            to.animate().alpha(1f).setDuration(160).start();
        }).start();
    }

    /** ScrollView com altura máxima: o popup cresce com o conteúdo até esse limite e daí rola. */
    private static class MaxHeightScrollView extends ScrollView {
        private final int maxHeightPx;

        MaxHeightScrollView(Context context, int maxHeightPx) {
            super(context);
            this.maxHeightPx = maxHeightPx;
            setFillViewport(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST));
        }
    }

    /**
     * Cartão quadrado (mesma largura do resto, CONTENT_WIDTH_DP x CONTENT_WIDTH_DP) com o
     * minijogo "Alimente o Gatinho" — ver PixelCatGameView. Guarda a instância em
     * catGameView pra poder ligar/desligar o laço de jogo junto com a sobreposição
     * (start() em showOverlay depois de addView na janela, stop() em hideOverlay).
     */
    private LinearLayout buildCatGameCard() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapperParams.topMargin = dp(16);
        wrapper.setLayoutParams(wrapperParams);

        wrapper.addView(buildCatSelectorRow());

        LinearLayout card = new LinearLayout(this);
        card.setBackground(buildGlassCard());
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(contentWidthDp), dp(contentWidthDp)));

        activeCatIndex = GuardianPrefs.INSTANCE.selectedCatIndex(this);
        catGameView = new PixelCatGameView(this);
        catGameView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        catGameView.setCatIndex(activeCatIndex);
        applyCatState(GuardianPrefs.INSTANCE.loadCatState(this, activeCatIndex));
        card.addView(catGameView);
        wrapper.addView(card);

        wrapper.addView(buildCatStatsBar());
        catGameView.setStatsListener((energyVal, hygieneVal, funVal, affectionVal) -> {
            updateCatStatFill(statEnergyFill, energyVal);
            updateCatStatFill(statHygieneFill, hygieneVal);
            updateCatStatFill(statFunFill, funVal);
            updateCatStatFill(statAffectionFill, affectionVal);
        });

        wrapper.addView(buildCatModeSelector());

        TextView caption = new TextView(this);
        catCaptionView = caption;
        caption.setText(captionForCatMode(PixelCatGameView.GameMode.FEED));
        caption.setTextColor(Color.parseColor(COLOR_WARM_FAINT));
        caption.setTextSize(10.5f);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = dp(8);
        caption.setLayoutParams(captionParams);
        wrapper.addView(caption);

        return wrapper;
    }

    /** Aplica um GuardianPrefs.CatState carregado (já com o decaimento por tempo real
     * calculado, ver loadCatState) na view do jogo — só um repasse de tipos (Kotlin data
     * class -> parâmetros soltos do loadState do Java). */
    private void applyCatState(GuardianPrefs.CatState state) {
        catGameView.loadState(state.getEnergy(), state.getHygiene(), state.getJoy(), state.getAffection(), state.getFullness());
    }

    /** Salva o progresso do gatinho ATUALMENTE carregado em catGameView — chamado antes de
     * trocar pra outro gatinho no seletor e ao fechar a sobreposição (hideOverlay), pra
     * nunca perder o que a criança acabou de fazer com ele. Sem isso, cada minuto que se
     * passa sem salvar é minuto que loadCatState vai "descontar de novo" da próxima vez
     * (o decaimento é sempre calculado a partir do último valor salvo). */
    private void saveActiveCatState() {
        if (catGameView == null) return;
        GuardianPrefs.CatState state = new GuardianPrefs.CatState(
            catGameView.getEnergy(),
            catGameView.getHygiene(),
            catGameView.getFun(),
            catGameView.getAffection(),
            catGameView.getFullness()
        );
        GuardianPrefs.INSTANCE.saveCatState(this, activeCatIndex, state);
    }

    /** Fileira de CAT_COUNT avatares redondos (um por gatinho da paleta) acima do cartão do
     * jogo — tocar num troca qual gatinho está sendo cuidado agora (ver
     * PixelCatGameView.setCatIndex/loadState). O gatinho ativo ganha um anel na cor de
     * destaque; os outros mostram uma bolinha vermelha se algum dos 4 stats deles está
     * baixo (ver loadCatState — só leitura, não escreve nada), pra dar um sinal de "esse
     * aqui precisa de atenção" sem precisar entrar em cada um pra descobrir. */
    private LinearLayout buildCatSelectorRow() {
        catAvatarChips.clear();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

        for (int i = 0; i < PixelCatGameView.CAT_COUNT; i++) {
            row.addView(buildCatAvatarChip(i));
        }
        updateCatAvatarStyles();
        return row;
    }

    private static final int CAT_NEEDS_ATTENTION_THRESHOLD = 30;

    private TextView buildCatAvatarChip(int catIndex) {
        TextView chip = new TextView(this);
        chip.setText("🐾");
        chip.setTextSize(13f);
        chip.setGravity(Gravity.CENTER);
        int size = dp(28);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        chip.setLayoutParams(params);
        chip.setTag(catIndex);
        chip.setOnClickListener(v -> {
            if (catIndex == activeCatIndex || catGameView == null) return;
            saveActiveCatState();
            activeCatIndex = catIndex;
            GuardianPrefs.INSTANCE.setSelectedCatIndex(this, catIndex);
            catGameView.setCatIndex(catIndex);
            applyCatState(GuardianPrefs.INSTANCE.loadCatState(this, catIndex));
            // Volta o seletor de ação pro padrão (Alimentar) — loadState já limpou peixe/
            // novelo/gota em voo do gatinho anterior, o chip visual precisa acompanhar.
            if (!catModeChips.isEmpty()) updateCatModeChipStyles(PixelCatGameView.GameMode.FEED);
            if (catCaptionView != null) catCaptionView.setText(captionForCatMode(PixelCatGameView.GameMode.FEED));
            updateCatAvatarStyles();
        });
        catAvatarChips.add(chip);
        return chip;
    }

    private void updateCatAvatarStyles() {
        int accent = accentColors()[0];
        for (TextView chip : catAvatarChips) {
            int catIndex = (int) chip.getTag();
            int furColor = PixelCatGameView.furColorFor(catIndex);
            boolean active = catIndex == activeCatIndex;
            boolean needsAttention = !active && catNeedsAttention(catIndex);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(withAlpha(furColor, active ? 255 : 150));
            bg.setStroke(dp(active ? 2 : 1), active ? withAlpha(accent, 230) : withAlpha(Color.WHITE, 40));
            chip.setBackground(bg);
            // Bolinha vermelha simples: em vez de um segundo desenho por cima (mais View),
            // só troca o emoji por uma versão com "aviso" — mais simples e já bem visível
            // nesse tamanho pequeno de chip.
            chip.setText(needsAttention ? "❗" : "🐾");
        }
    }

    /** Só leitura (ver GuardianPrefs.loadCatState) — nunca marca esse gatinho como
     * "visitado agora", então checar todos os outros pro seletor não afeta o decaimento
     * de ninguém. */
    private boolean catNeedsAttention(int catIndex) {
        GuardianPrefs.CatState state = GuardianPrefs.INSTANCE.loadCatState(this, catIndex);
        return state.getEnergy() < CAT_NEEDS_ATTENTION_THRESHOLD
            || state.getHygiene() < CAT_NEEDS_ATTENTION_THRESHOLD
            || state.getJoy() < CAT_NEEDS_ATTENTION_THRESHOLD
            || state.getAffection() < CAT_NEEDS_ATTENTION_THRESHOLD;
    }

    /** Fileira de 4 "chips" redondos pra trocar a ação do gatinho — Alimentar (padrão),
     * Brincar (emagrece o gato, mas cansa e suja), Carinho (reação fofa) e Banho (limpa).
     * Feitos com TextView simples (não Button/MaterialButton) de propósito — ver a lição
     * de MaterialButton descentralizando ícone no botão "+Tempo" do app do pai; construir
     * na mão evita o mesmo problema aqui. */
    private LinearLayout buildCatModeSelector() {
        catModeChips.clear();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(10);
        row.setLayoutParams(rowParams);

        row.addView(buildCatModeChip("🐟", PixelCatGameView.GameMode.FEED));
        row.addView(buildCatModeChip("🧶", PixelCatGameView.GameMode.PLAY));
        row.addView(buildCatModeChip("✋", PixelCatGameView.GameMode.PET));
        row.addView(buildCatModeChip("🛁", PixelCatGameView.GameMode.BATH));

        updateCatModeChipStyles(PixelCatGameView.GameMode.FEED);
        return row;
    }

    /** Coluna com as 4 barrinhas de status do gato (energia/limpeza/diversão/carinho),
     * acima do seletor de ações — ver PixelCatGameView.StatsListener pra como os valores
     * chegam aqui em tempo real. */
    private LinearLayout buildCatStatsBar() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
        columnParams.topMargin = dp(12);
        column.setLayoutParams(columnParams);

        statEnergyFill = new View(this);
        statHygieneFill = new View(this);
        statFunFill = new View(this);
        statAffectionFill = new View(this);

        column.addView(buildCatStatRow("⚡", Color.parseColor("#FBBF24"), statEnergyFill));
        column.addView(buildCatStatRow("🫧", Color.parseColor("#38BDF8"), statHygieneFill));
        column.addView(buildCatStatRow("🎉", Color.parseColor("#F472B6"), statFunFill));
        column.addView(buildCatStatRow("❤️", Color.parseColor("#FB7185"), statAffectionFill));
        return column;
    }

    /** Uma barrinha: ícone + trilha translúcida + preenchimento colorido. O preenchimento
     * é sempre MATCH_PARENT com pivotX fixado em 0 (borda esquerda) e reduzido via
     * setScaleX — atualizar isso não pede requestLayout, então dá pra fazer a cada ~100ms
     * (ritmo do StatsListener) sem pesar na sobreposição. */
    private LinearLayout buildCatStatRow(String emoji, int color, View fillView) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(4);
        row.setLayoutParams(rowParams);

        TextView icon = new TextView(this);
        icon.setText(emoji);
        icon.setTextSize(10.5f);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT);
        icon.setLayoutParams(iconParams);
        row.addView(icon);

        FrameLayout track = new FrameLayout(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setShape(GradientDrawable.RECTANGLE);
        trackBg.setCornerRadius(dp(3));
        trackBg.setColor(withAlpha(Color.WHITE, 22));
        track.setBackground(trackBg);
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(0, dp(6));
        trackParams.weight = 1f;
        trackParams.leftMargin = dp(6);
        track.setLayoutParams(trackParams);

        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setShape(GradientDrawable.RECTANGLE);
        fillBg.setCornerRadius(dp(3));
        fillBg.setColor(color);
        fillView.setBackground(fillBg);
        fillView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        fillView.setPivotX(0f);
        fillView.setScaleX(1f);
        track.addView(fillView);

        row.addView(track);
        return row;
    }

    private void updateCatStatFill(View fillView, float value0to100) {
        if (fillView == null) return;
        float frac = Math.max(0f, Math.min(1f, value0to100 / 100f));
        fillView.setScaleX(frac);
    }

    private TextView buildCatModeChip(String emoji, PixelCatGameView.GameMode chipMode) {
        TextView chip = new TextView(this);
        chip.setText(emoji);
        chip.setTextSize(15f);
        chip.setGravity(Gravity.CENTER);
        int size = dp(32);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.leftMargin = dp(5);
        params.rightMargin = dp(5);
        chip.setLayoutParams(params);
        chip.setTag(chipMode);
        chip.setOnClickListener(v -> {
            if (catGameView != null) catGameView.setMode(chipMode);
            if (catCaptionView != null) catCaptionView.setText(captionForCatMode(chipMode));
            updateCatModeChipStyles(chipMode);
        });
        catModeChips.add(chip);
        return chip;
    }

    private void updateCatModeChipStyles(PixelCatGameView.GameMode active) {
        int accent = accentColors()[0];
        for (TextView chip : catModeChips) {
            PixelCatGameView.GameMode chipMode = (PixelCatGameView.GameMode) chip.getTag();
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            if (chipMode == active) {
                bg.setColor(withAlpha(accent, 70));
                bg.setStroke(dp(1), withAlpha(accent, 220));
            } else {
                bg.setColor(withAlpha(Color.WHITE, 18));
                bg.setStroke(dp(1), withAlpha(Color.WHITE, 30));
            }
            chip.setBackground(bg);
        }
    }

    private String captionForCatMode(PixelCatGameView.GameMode mode) {
        switch (mode) {
            case PLAY: return "🧶 brincar emagrece, mas cansa e suja o gatinho";
            case PET: return "✋ toque no gatinho pra fazer carinho";
            case BATH: return "🛁 arraste o gatinho até as gotinhas pra ficar limpinho";
            default: return "🐾 arraste o gatinho e pegue os peixinhos";
        }
    }

    /**
     * Pede foco de áudio "pra valer" (AUDIOFOCUS_GAIN, não transitório) sem tocar nada —
     * qualquer app com mídia rodando por baixo (YouTube, música, vídeo no navegador etc.)
     * recebe AUDIOFOCUS_LOSS e pausa sozinho, exatamente como quando outro app de mídia
     * assume o foco. Como o foco só é devolvido (releaseAudioFocus) quando a sobreposição
     * fecha — sem "reganhar" foco no meio — apps bem-comportados não retomam o play sozinhos.
     */
    private void stealAudioFocusToPauseMedia() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build();
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build();
                audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseAudioFocus() {
        try {
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                    audioFocusRequest = null;
                }
            } else {
                audioManager.abandonAudioFocus(audioFocusChangeListener);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Desenho: gradiente de fundo, cartões de vidro e botão de destaque, todos
    // reaproveitando GuardianPrefs.currentLockAccentColors (cor que ROTACIONA sozinha a
    // cada tela ligar — diferente da cor fixa que o pai escolhe pra Home). ---

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int[] accentColors() {
        kotlin.Pair<Integer, Integer> theme = GuardianPrefs.INSTANCE.currentLockAccentColors(this);
        return new int[]{ theme.getFirst(), theme.getSecond() };
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
        int accent = accentColors()[0];
        int dark = Color.parseColor(BG_DARK);
        int glow = blendOpaque(accent, dark, 0.4f);
        GradientDrawable bg = new GradientDrawable();
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setGradientRadius(dp(420));
        bg.setGradientCenter(0.5f, 0.2f);
        bg.setColors(new int[]{ glow, dark });
        return bg;
    }

    /**
     * Atualização LEVE de cor (ver "REFRESH_ACCENT" em onStartCommand) — só troca o
     * background do fundo + o tint do ícone/selo do topo, sem tocar em mais nada da
     * árvore de views (nenhum addView/removeView, nenhuma janela recriada). Não re-tinge
     * botões em degradê que já estejam na tela (desbloquear, enviar foto, seletor de
     * duração) — eles pegam a cor nova sozinhos na próxima vez que forem reconstruídos de
     * verdade (ex: o motivo do bloqueio mudar), o que é uma troca aceitável por não travar
     * a tela toda vez que ela liga de novo.
     */
    private void refreshAccentColorsInPlace() {
        if (!isShowing || overlayView == null) return;
        try {
            if (!backdropFromHome) overlayView.setBackground(buildRootBackground());
            if (cardView != null) cardView.setBackground(buildCardBackground());
            boolean neutralAccent = "blocked".equals(lastIconType);
            if (!neutralAccent) {
                int accent = accentColors()[0];
                if (heroIconView != null) heroIconView.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
                if (eyebrowView != null) eyebrowView.setTextColor(withAlpha(blendOpaque(accent, Color.WHITE, 0.65f), 255));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** "Vidro fosco": painel semitransparente com borda clara fina, sem precisar de blur de verdade. */
    private GradientDrawable buildGlassCard() {
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.argb(36, 255, 255, 255));
        card.setCornerRadius(dp(26));
        card.setStroke(dp(1), Color.argb(56, 255, 255, 255));
        return card;
    }

    /** Botão/painel em degradê definido (a cor de destaque ROTATIVA da tela de bloqueio). */
    private GradientDrawable buildAccentGradient(int cornerRadiusPx) {
        int[] theme = accentColors();
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR, theme);
        gradient.setCornerRadius(cornerRadiusPx);
        return gradient;
    }

    private GradientDrawable buildInputFieldBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(40, 255, 255, 255));
        bg.setCornerRadius(dp(14));
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
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
        containerParams.topMargin = dp(14);
        container.setLayoutParams(containerParams);

        TextView heading = new TextView(this);
        heading.setText("📋 Tarefas de hoje");
        heading.setTextColor(Color.parseColor(COLOR_WARM_FAINT));
        heading.setTextSize(11);
        heading.setLetterSpacing(0.08f);
        heading.setPadding(dp(2), 0, 0, dp(10));
        container.addView(heading);

        for (GuardianPrefs.TaskItem task : GuardianPrefs.INSTANCE.parsedTodayTasks(this)) {
            container.addView(buildTaskRow(task));
        }
        return container;
    }

    /**
     * Card de tarefa em DUAS linhas — ícone+título+recompensa em cima, status/botão de
     * enviar foto embaixo ocupando a largura toda.
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
        // Relógio pras tarefas que ainda não tiveram foto enviada (pending/rejected) — o
        // ícone escolhido na criação da tarefa (task.getIcon(), quase sempre ✅ por
        // padrão) ficava confuso ali, parecendo "já feita" antes mesmo de ser enviada.
        String status = task.getStatus();
        boolean notSubmittedYet = "pending".equals(status) || "rejected".equals(status);
        iconView.setText(notSubmittedYet ? "🕐" : task.getIcon());
        iconView.setTextSize(20);
        iconView.setPadding(0, 0, dp(10), 0);
        topLine.addView(iconView);

        TextView taskTitleView = new TextView(this);
        taskTitleView.setText(task.getTitle());
        taskTitleView.setTextColor(Color.WHITE);
        taskTitleView.setTextSize(15);
        taskTitleView.setMaxLines(2);
        taskTitleView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topLine.addView(taskTitleView);

        TextView rewardView = new TextView(this);
        rewardView.setText("+" + task.getRewardMinutes() + "min");
        rewardView.setTextColor(Color.parseColor(COLOR_WARM_MUTED));
        rewardView.setTextSize(12);
        rewardView.setBackground(buildInputFieldBackground());
        rewardView.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams rewardParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rewardParams.leftMargin = dp(8);
        rewardView.setLayoutParams(rewardParams);
        topLine.addView(rewardView);

        row.addView(topLine);

        boolean tappable = notSubmittedYet;
        if (tappable) {
            Button sendPhotoButton = new Button(this);
            sendPhotoButton.setText("📸 Enviar Foto");
            sendPhotoButton.setTextColor(Color.WHITE);
            sendPhotoButton.setAllCaps(false);
            sendPhotoButton.setTextSize(13);
            sendPhotoButton.setBackground(buildAccentGradient(dp(13)));
            LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sendParams.topMargin = dp(12);
            sendPhotoButton.setLayoutParams(sendParams);
            sendPhotoButton.setOnClickListener(v -> startTaskCameraFlow(task.getId()));
            row.addView(sendPhotoButton);
        } else {
            TextView statusView = new TextView(this);
            statusView.setText("approved".equals(status) ? "✅ Aprovada" : "⏳ Aguardando aprovação");
            statusView.setTextColor(Color.parseColor(COLOR_WARM_MUTED));
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
     * específica.
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
     * Botão-fantasma discreto "🔑 Usar PIN dos pais" — o PIN NUNCA fica exposto por padrão
     * (pedido explícito do usuário: nada de campo de PIN à mostra o tempo todo). Só ao
     * tocar aqui é que showPinEntryCard() revela o campo de verdade.
     */
    private void showPinAffordance() {
        if (pinRevealSlot == null) return;
        pinRevealSlot.removeAllViews();
        pinInputView = null;

        Button useButton = new Button(this);
        useButton.setText("🔑 Usar PIN dos pais");
        useButton.setTextColor(Color.argb(224, 255, 255, 255));
        useButton.setAllCaps(false);
        useButton.setTextSize(13);
        GradientDrawable ghostBg = new GradientDrawable();
        ghostBg.setColor(Color.argb(15, 255, 255, 255));
        ghostBg.setCornerRadius(dp(16));
        ghostBg.setStroke(dp(1), Color.argb(38, 255, 255, 255));
        useButton.setBackground(ghostBg);
        useButton.setPadding(dp(16), dp(13), dp(16), dp(13));
        useButton.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        useButton.setOnClickListener(v -> showPinEntryCard());
        pinRevealSlot.addView(useButton);
    }

    /**
     * Revela o cartão de PIN de verdade — campo funcional (EditText, letterSpacing largo
     * pra imitar as "caixinhas" separadas sem precisar de 6 views distintas) + botão
     * redondo com seta. Abre o teclado sozinho, já que a criança/pai tocou de propósito
     * pra chegar até aqui.
     */
    private void showPinEntryCard() {
        if (pinRevealSlot == null) return;
        pinRevealSlot.removeAllViews();

        LinearLayout pinCard = new LinearLayout(this);
        pinCard.setOrientation(LinearLayout.VERTICAL);
        pinCard.setBackground(buildGlassCard());
        pinCard.setPadding(dp(20), dp(20), dp(20), dp(18));
        pinCard.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText("PIN de emergência dos pais");
        label.setTextColor(Color.argb(224, 255, 255, 255));
        label.setTextSize(12.5f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.bottomMargin = dp(12);
        label.setLayoutParams(labelParams);
        pinCard.addView(label);

        LinearLayout pinRow = new LinearLayout(this);
        pinRow.setOrientation(LinearLayout.HORIZONTAL);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);

        pinInputView = new EditText(this);
        pinInputView.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInputView.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(6) });
        pinInputView.setHint("••••••");
        pinInputView.setTextColor(Color.WHITE);
        pinInputView.setHintTextColor(Color.argb(110, 255, 255, 255));
        pinInputView.setGravity(Gravity.CENTER);
        pinInputView.setTextSize(20);
        pinInputView.setLetterSpacing(0.4f);
        pinInputView.setBackground(buildInputFieldBackground());
        pinInputView.setPadding(dp(16), dp(14), dp(16), dp(14));
        pinInputView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pinRow.addView(pinInputView);

        ImageButton unlockButton = new ImageButton(this);
        unlockButton.setImageResource(R.drawable.ic_chevron_right);
        unlockButton.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        unlockButton.setScaleType(ImageView.ScaleType.CENTER);
        unlockButton.setBackground(buildAccentGradient(dp(14)));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        btnParams.leftMargin = dp(10);
        unlockButton.setLayoutParams(btnParams);
        unlockButton.setOnClickListener(v -> attemptPinUnlock());
        pinRow.addView(unlockButton);

        pinCard.addView(pinRow);

        TextView hint = new TextView(this);
        hint.setText("Não sabe o PIN? Peça pra seus pais liberarem pelo app do GuardianShield.");
        hint.setTextColor(Color.argb(179, 255, 255, 255));
        hint.setTextSize(11.5f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(12);
        hint.setLayoutParams(hintParams);
        pinCard.addView(hint);

        pinRevealSlot.addView(pinCard);

        // post(): espera o cartão terminar de entrar no layout antes de focar/rolar —
        // pedir foco na mesma passada do addView calculava a posição de ANTES do cartão
        // existir de verdade na árvore, então a rolagem abaixo não tinha o que rolar ainda.
        // post(): espera o cartão terminar de entrar no layout antes de focar — pedir
        // foco na mesma passada do addView ainda não tinha a posição real do campo. Com
        // SOFT_INPUT_ADJUST_PAN (ver criação da janela), o próprio sistema desloca a
        // janela pra cima até o campo focado ficar visível — não precisa mais de rolagem
        // manual aqui, que dependia do redimensionamento de ADJUST_RESIZE (não confiável
        // nessa janela) e era a causa do campo ficar atrás do teclado.
        pinCard.post(() -> {
            pinInputView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(pinInputView, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /**
     * Confere o PIN digitado contra o hash sincronizado do backend (GuardianPrefs,
     * gravado pelo poll de regras — ver ParentalAccessibilityService.pollRulesSync).
     * A checagem é 100% local: nenhuma chamada de rede acontece aqui, por isso
     * funciona mesmo com o aparelho totalmente offline. PIN certo não libera na hora
     * mais — pergunta por quanto tempo primeiro (ver showDurationPicker). PIN errado
     * volta pro botão-fantasma em vez de deixar o campo errado exposto na tela.
     */
    private void attemptPinUnlock() {
        if (pinInputView == null) return;
        String entered = pinInputView.getText().toString().trim();
        String expectedHash = GuardianPrefs.INSTANCE.unlockPinHash(this);
        if (expectedHash == null || expectedHash.isEmpty()) return;

        if (expectedHash.equals(sha256Hex(entered))) {
            showDurationPicker();
        } else {
            Toast.makeText(this, "PIN incorreto.", Toast.LENGTH_SHORT).show();
            pinInputView.setText("");
        }
    }

    /**
     * Troca o conteúdo do cartão (mesma janela, sem recriar) pelas opções de duração —
     * 10/20/30min, 1h ou um valor personalizado. Só aparece depois do PIN certo.
     */
    private void showDurationPicker() {
        if (contentLayout == null) return;
        contentLayout.removeAllViews();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(buildGlassCard());
        card.setPadding(dp(24), dp(28), dp(24), dp(26));
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView checkIcon = new ImageView(this);
        checkIcon.setImageResource(R.drawable.ic_check_hero);
        checkIcon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        LinearLayout checkCircle = new LinearLayout(this);
        checkCircle.setGravity(Gravity.CENTER);
        checkCircle.setBackground(buildAccentGradient(dp(20)));
        LinearLayout.LayoutParams checkCircleParams = new LinearLayout.LayoutParams(dp(60), dp(60));
        checkCircleParams.bottomMargin = dp(14);
        checkCircle.setLayoutParams(checkCircleParams);
        checkIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        checkCircle.addView(checkIcon);
        card.addView(checkCircle);

        TextView title = new TextView(this);
        title.setText("PIN correto!");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Por quanto tempo liberar o celular?");
        subtitle.setTextColor(Color.parseColor(COLOR_WARM_MUTED));
        subtitle.setTextSize(13.5f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(6);
        subtitleParams.bottomMargin = dp(20);
        subtitle.setLayoutParams(subtitleParams);
        card.addView(subtitle);

        int[] presetMinutes = { 10, 20, 30, 60 };
        String[] presetLabels = { "10 min", "20 min", "30 min", "1 hora" };
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row1Params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row1Params.bottomMargin = dp(10);
        row1.setLayoutParams(row1Params);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < presetMinutes.length; i++) {
            int minutes = presetMinutes[i];
            Button presetButton = new Button(this);
            presetButton.setText(presetLabels[i]);
            presetButton.setTextColor(Color.WHITE);
            presetButton.setAllCaps(false);
            presetButton.setTextSize(14);
            presetButton.setBackground(buildAccentGradient(dp(14)));
            LinearLayout.LayoutParams presetParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 1) presetParams.leftMargin = dp(8); else presetParams.rightMargin = dp(8);
            presetButton.setLayoutParams(presetParams);
            presetButton.setOnClickListener(v -> confirmPinOverride(minutes));
            (i < 2 ? row1 : row2).addView(presetButton);
        }
        card.addView(row1);
        card.addView(row2);

        Button customButton = new Button(this);
        customButton.setText("⏱ Personalizado");
        customButton.setTextColor(Color.WHITE);
        customButton.setAllCaps(false);
        customButton.setTextSize(14);
        customButton.setBackground(buildInputFieldBackground());
        LinearLayout.LayoutParams customParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        customParams.topMargin = dp(14);
        customButton.setLayoutParams(customParams);
        customButton.setOnClickListener(v -> showCustomDurationInput());
        card.addView(customButton);

        contentLayout.addView(card);
    }

    /** Campo numérico simples pra digitar quantos minutos, quando nenhum dos presets serve. */
    private void showCustomDurationInput() {
        if (contentLayout == null) return;
        contentLayout.removeAllViews();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(buildGlassCard());
        card.setPadding(dp(24), dp(28), dp(24), dp(28));
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("⏱ Duração personalizada");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp(16);
        title.setLayoutParams(titleParams);
        card.addView(title);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText minutesInput = new EditText(this);
        minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesInput.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(3) });
        minutesInput.setHint("minutos");
        minutesInput.setTextColor(Color.WHITE);
        minutesInput.setHintTextColor(Color.parseColor("#78716c"));
        minutesInput.setGravity(Gravity.CENTER);
        minutesInput.setTextSize(18);
        minutesInput.setBackground(buildInputFieldBackground());
        minutesInput.setPadding(dp(16), dp(14), dp(16), dp(14));
        minutesInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        inputRow.addView(minutesInput);

        Button confirmButton = new Button(this);
        confirmButton.setText("OK");
        confirmButton.setTextColor(Color.WHITE);
        confirmButton.setAllCaps(false);
        confirmButton.setBackground(buildAccentGradient(dp(14)));
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        confirmParams.leftMargin = dp(10);
        confirmButton.setMinWidth(dp(64));
        confirmButton.setLayoutParams(confirmParams);
        confirmButton.setOnClickListener(v -> {
            int minutes = 0;
            try {
                minutes = Integer.parseInt(minutesInput.getText().toString().trim());
            } catch (NumberFormatException e) {
                // campo vazio ou inválido — minutes fica 0, tratado abaixo
            }
            if (minutes > 0) {
                confirmPinOverride(minutes);
            } else {
                Toast.makeText(this, "Digite um número de minutos válido.", Toast.LENGTH_SHORT).show();
            }
        });
        inputRow.addView(confirmButton);

        card.addView(inputRow);
        contentLayout.addView(card);
    }

    /** Grava a liberação até agora+minutos e fecha a sobreposição — ver GuardianPrefs.setPinOverrideUntil. */
    private void confirmPinOverride(int minutes) {
        long untilMillis = System.currentTimeMillis() + minutes * 60_000L;
        GuardianPrefs.INSTANCE.setPinOverrideUntil(this, untilMillis);
        // Avisa o backend assim que a rede voltar (ver ackPinUnlock no poll de regras) —
        // pra o painel do pai não continuar mostrando "Pausa Geral Ativa" quando o
        // aparelho já foi liberado de verdade.
        GuardianPrefs.INSTANCE.setPendingPinUnlockAck(this, true);
        Toast.makeText(this, "Desbloqueado por " + formatDuration(minutes) + "!", Toast.LENGTH_LONG).show();
        hideOverlay();
    }

    private String formatDuration(int minutes) {
        if (minutes >= 60 && minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours + (hours == 1 ? " hora" : " horas");
        }
        return minutes + " min";
    }

    /**
     * Cartão com um ícone tocável por app sempre-disponível (rolável horizontalmente,
     * pro caso de o pai marcar vários) — resolve ícone/nome via PackageManager porque
     * essa tela não tem a lista pronta do AppRepository (roda num serviço, não numa
     * Activity com acesso fácil à Home). Retorna null se a lista estiver vazia ou se
     * nenhum dos pacotes resolver mais (ex: foram desinstalados).
     */
    private LinearLayout buildAlwaysAvailableRow(Set<String> packages) {
        if (packages.isEmpty()) return null;
        PackageManager pm = getPackageManager();

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(dp(contentWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapperParams.topMargin = dp(18);
        wrapper.setLayoutParams(wrapperParams);

        TextView heading = new TextView(this);
        heading.setText("✅ Sempre disponíveis");
        heading.setTextColor(Color.parseColor(COLOR_WARM_FAINT));
        heading.setTextSize(11);
        heading.setLetterSpacing(0.08f);
        heading.setPadding(dp(2), 0, 0, dp(10));
        wrapper.addView(heading);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);

        int addedCount = 0;
        for (String pkg : packages) {
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                Drawable icon = pm.getApplicationIcon(appInfo);
                CharSequence label = pm.getApplicationLabel(appInfo);

                LinearLayout iconCol = new LinearLayout(this);
                iconCol.setOrientation(LinearLayout.VERTICAL);
                iconCol.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT);
                colParams.rightMargin = dp(10);
                iconCol.setLayoutParams(colParams);

                GradientDrawable iconBg = new GradientDrawable();
                iconBg.setColor(Color.argb(31, 255, 255, 255));
                iconBg.setCornerRadius(dp(16));
                iconBg.setStroke(dp(1), Color.argb(36, 255, 255, 255));

                ImageView iconView = new ImageView(this);
                iconView.setImageDrawable(icon);
                iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
                iconView.setBackground(iconBg);
                iconView.setPadding(dp(7), dp(7), dp(7), dp(7));
                iconView.setClickable(true);
                iconView.setOnClickListener(v -> launchAlwaysAvailableApp(pkg));
                iconCol.addView(iconView);

                TextView labelView = new TextView(this);
                labelView.setText(label);
                labelView.setTextColor(Color.parseColor("#D6D3D1"));
                labelView.setTextSize(10.5f);
                labelView.setMaxLines(1);
                labelView.setEllipsize(TextUtils.TruncateAt.END);
                labelView.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT);
                labelParams.topMargin = dp(6);
                labelView.setLayoutParams(labelParams);
                iconCol.addView(labelView);

                iconRow.addView(iconCol);
                addedCount++;
            } catch (PackageManager.NameNotFoundException e) {
                // App desinstalado desde a última sincronização — ignora, sem crashar.
            }
        }
        if (addedCount == 0) return null;

        scroll.addView(iconRow);
        wrapper.addView(scroll);
        return wrapper;
    }

    /**
     * Abre o app sempre-disponível direto da tela de bloqueio e já fecha a sobreposição
     * na hora — o serviço de acessibilidade mantém ela fechada sozinho a partir daqui
     * (ver reevaluateBlockState), isso só evita um flash da tela de bloqueio até o
     * próximo ciclo dele.
     */
    private void launchAlwaysAvailableApp(String packageName) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            Toast.makeText(this, "Não foi possível abrir o aplicativo.", Toast.LENGTH_SHORT).show();
            return;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launchIntent);
        hideOverlay();
    }

    private void callEmergencyNumber(String phone) {
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir o discador.", Toast.LENGTH_SHORT).show();
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
            saveActiveCatState();
            if (catGameView != null) catGameView.stop();
            catGameView = null;
            catModeChips.clear();
            catAvatarChips.clear();
            catCaptionView = null;
            statEnergyFill = null;
            statHygieneFill = null;
            statFunFill = null;
            statAffectionFill = null;
            overlayView = null;
            titleView = null;
            subtitleView = null;
            heroIconView = null;
            eyebrowView = null;
            pinInputView = null;
            pinRevealSlot = null;
            pinUnlockAvailable = false;
            timeRequestSlot = null;
            timeRequestReasonInput = null;
            timeRequestSending = false;
            taskListContainer = null;
            contentLayout = null;
            cardView = null;
            mainPage = null;
            gamePage = null;
            backdropFromHome = false;
            isShowing = false;
            showing = false;
            releaseAudioFocus();
        }
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        super.onDestroy();
    }
}
