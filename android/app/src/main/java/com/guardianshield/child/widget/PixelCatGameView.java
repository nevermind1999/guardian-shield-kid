package com.guardianshield.child.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Minijogo "Alimente o Gatinho" — distração positiva pra criança brincar enquanto espera
 * o bloqueio passar (ver o cartão quadrado em LockOverlayService). Peixinhos caem do topo
 * em X aleatório; arrastar o dedo move o gato pra pegar o peixe quando ele chega perto.
 * Sem "game over" nem penalidade por errar um peixe — é só uma distração, não mais uma
 * fonte de frustração durante um bloqueio que já é chato o suficiente.
 *
 * A cada 30 peixes comidos o gato "estoura" (confete com as cores dele mesmo) — só um
 * efeito de comemoração, reinicia a contagem e a "gordura" (ver chubbyFactor) SEM trocar de
 * gatinho: a cor/identidade (catColorIndex) agora é fixa por instância, escolhida de fora
 * via setCatIndex (ver LockOverlayService.buildCatSelectorRow) — um dos CAT_COUNT gatinhos
 * da paleta FUR_PALETTE, cada um com progresso próprio persistido em
 * GuardianPrefs.CatState/loadCatState/saveCatState, pra dar pra cuidar de cada um
 * separadamente em vez de um só gato mudando de cor sozinho. O gato fica visivelmente mais
 * rechonchudo conforme come, suavizado quadro a quadro (nunca um salto brusco a cada peixe).
 *
 * Além de "Alimentar" (modo padrão, FEED), existem mais três ações — trocadas pelo
 * seletor de chips em LockOverlayService.buildCatModeSelector():
 *  - "Brincar" (PLAY): em vez de peixe caindo, um novelo de lã quica pela tela; arrastar
 *    o gato até ele "queima" gordura (reduz fishEatenThisCat, que é a mesma variável que
 *    controla o quanto o gato está rechonchudo — assim emagrece suavizado, do mesmo jeito
 *    que engorda). Brincar também cansa e suja o gato (ver barrinhas abaixo).
 *  - "Carinho" (PET): tocar no gato (sem arrastar) solta coraçõezinhos subindo e deixa os
 *    olhos "^ ^" por um instante, além de subir a barrinha de carinho.
 *  - "Banho" (BATH): gotinhas caem como o peixe; arrastar o gato até elas sobe a barrinha
 *    de limpeza.
 *
 * Quatro barrinhas de status (0-100, expostas via StatsListener pra LockOverlayService
 * desenhar fora do canvas): energia e limpeza CAEM enquanto o modo é "Brincar" (brincar
 * cansa e suja) e sobem ao alimentar/tomar banho; diversão sobe enquanto brinca e decai
 * aos poucos quando não brinca; carinho sobe a cada toque no modo "Carinho" e também
 * decai aos poucos com o tempo — assim as quatro ações têm um propósito recorrente, não
 * só a de alimentar.
 *
 * O gato é desenhado em blocos (retângulos) numa grade lógica — dá o visual "pixelado"/
 * 8-bit sem precisar de nenhum arquivo de imagem. Peixe/novelo/coração/gota usam emoji via
 * Canvas.drawText, mais simples e confiável que desenhar outro sprite à mão.
 *
 * Animação via Choreographer (sincronizado com o vsync da tela) + movimento baseado no
 * tempo real decorrido entre frames — não num "tick" de tamanho fixo. Um Handler.
 * postDelayed de intervalo fixo (jeito antigo) faz o movimento engasgar quando o sistema
 * atrasa um ciclo (comum em aparelho de entrada, tipo o Galaxy A06): o incremento por
 * ciclo continua o mesmo, então o atraso vira uma pausa visível seguida de um "pulo".
 * Multiplicando a velocidade pelo tempo de verdade que passou, o movimento fica suave
 * mesmo quando um frame demora mais que o normal.
 */
public class PixelCatGameView extends View {

    private static final int GRID = 12; // grade lógica do gato (12x12 "pixels", antes de engordar)
    private static final long FISH_SPAWN_MS = 1700L;
    private static final float FISH_FALL_SPEED_PER_SEC = 0.3f; // fração da altura por segundo — ritmo calmo de propósito
    private static final float CATCH_X_RADIUS = 0.17f; // "alcance" horizontal do gato, em fração da largura
    private static final float CAT_SIZE_FRACTION = 0.34f; // altura do gato, em fração do menor lado do widget
    private static final float CAT_BOTTOM_MARGIN_FRACTION = 0.05f;
    private static final int MAX_FISH_PER_CAT = 30; // ao chegar aqui, o gato "estoura" e troca de cor
    private static final long EXPLODE_DURATION_MS = 550L;
    private static final long RESPAWN_POP_DURATION_MS = 420L;
    private static final int PARTICLE_COUNT = 16;

    private static final float TOY_SPEED_PER_SEC = 0.42f; // velocidade do novelo no modo "Brincar"
    private static final float TOY_GRAVITY_PER_SEC = 0.75f;
    private static final float CATCH_TOY_X_RADIUS = 0.19f;
    private static final int TOY_BURN_PER_CATCH = 3; // quanto "emagrece" (reduz fishEatenThisCat) por toque no novelo
    private static final long PET_HAPPY_DURATION_MS = 900L;
    private static final float HEART_RISE_SPEED_PER_SEC = 0.22f;
    private static final long HEART_LIFETIME_MS = 1100L;

    private static final long DROPLET_SPAWN_MS = 1500L;
    private static final float DROPLET_FALL_SPEED_PER_SEC = 0.32f;
    private static final float HYGIENE_GAIN_PER_DROPLET = 11f;

    // Ritmo das 4 barrinhas (0-100) — só o suficiente pra cada ação ter um motivo de
    // existir de novo depois de um tempo, sem virar contagem regressiva estressante.
    private static final float ENERGY_DRAIN_PER_SEC_PLAYING = 1.1f; // ~90s de brincadeira contínua esvazia
    private static final float HYGIENE_DRAIN_PER_SEC_PLAYING = 0.9f;
    private static final float ENERGY_GAIN_PER_FISH = 9f;
    private static final float FUN_GAIN_PER_SEC_PLAYING = 1.6f;
    private static final float FUN_DECAY_PER_SEC_IDLE = 0.35f; // só fora do modo Brincar
    private static final float AFFECTION_DECAY_PER_SEC = 0.3f;
    private static final float AFFECTION_GAIN_PER_PET = 9f;

    /** Ação atual escolhida no seletor de chips (ver LockOverlayService.buildCatModeSelector). */
    public enum GameMode { FEED, PLAY, PET, BATH }

    /** Recebe as 4 barrinhas de status a cada ~100ms (throttle — ver advance()), pra
     * LockOverlayService atualizar as barras fora do canvas sem precisar reconstruir view. */
    public interface StatsListener {
        void onStatsChanged(float energy, float hygiene, float fun, float affection);
    }

    // Paleta de pelagens — cicla pra próxima cada vez que um gato "nasce" (nunca repete a
    // cor do gato anterior). Cada entrada é [pelagem, barriga] pra manter contraste.
    private static final int[][] FUR_PALETTE = {
        { Color.parseColor("#F4A460"), Color.parseColor("#FFF3E0") }, // laranja (original)
        { Color.parseColor("#9E9E9E"), Color.parseColor("#F5F5F5") }, // cinza
        { Color.parseColor("#6D4C33"), Color.parseColor("#E8D9C4") }, // marrom
        { Color.parseColor("#3A3A3A"), Color.parseColor("#B8B8B8") }, // preto/chumbo
        { Color.parseColor("#F2E6D8"), Color.parseColor("#FFFFFF") }  // creme claro
    };

    /** Quantos gatinhos existem no total (um por entrada de FUR_PALETTE) — usado por
     * LockOverlayService.buildCatSelectorRow pra desenhar um chip por gatinho. */
    public static final int CAT_COUNT = FUR_PALETTE.length;

    /** Cor da pelagem de um gatinho pelo índice (mesmo índice usado em setCatIndex/
     * GuardianPrefs.loadCatState) — pra pintar o chip dele no seletor sem duplicar a paleta. */
    public static int furColorFor(int catIndex) {
        return FUR_PALETTE[safeIndex(catIndex)][0];
    }

    /** Math.floorMod só existe a partir da API 24, e o minSdk daqui é 23 — este é o mesmo
     * resultado (nunca negativo) escrito na mão, pra funcionar em qualquer versão. */
    private static int safeIndex(int catIndex) {
        int m = catIndex % FUR_PALETTE.length;
        return m < 0 ? m + FUR_PALETTE.length : m;
    }

    private enum CatState { NORMAL, EXPLODING, RESPAWNING }

    private final Random random = new Random();
    private final Paint blockPaint = new Paint();
    private final Paint fishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static class Fish {
        float x; // fração da largura, 0..1
        float y; // fração da altura, 0..1
    }

    private static class Particle {
        float x, y;   // fração da largura/altura
        float vx, vy; // fração por segundo
        int color;
    }

    /** Novelo de lã do modo "Brincar" — quica pela tela em vez de cair reto como o peixe. */
    private static class Toy {
        float x, y;
        float vx, vy;
    }

    /** Coraçãozinho do modo "Carinho" — sobe e desaparece sozinho. */
    private static class Heart {
        float x, y;
        long bornAt;
    }

    /** Gotinha do modo "Banho" — cai reto igual ao peixe. */
    private static class Droplet {
        float x, y;
    }

    private final List<Fish> fishes = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Heart> hearts = new ArrayList<>();
    private final List<Droplet> droplets = new ArrayList<>();
    private Toy toy;

    private GameMode mode = GameMode.FEED;
    private long petHappyUntil = 0L;
    private long lastDropletSpawnAt = 0L;

    private StatsListener statsListener;
    private float statsNotifyAccumulator = 0f;
    private float energy = 100f;
    private float hygiene = 100f;
    private float fun = 100f;
    private float affection = 100f;

    private float catX = 0.5f; // fração da largura — posição horizontal do centro do gato
    private int fishEatenThisCat = 0;
    private int catColorIndex = 0;
    private long lastSpawnAt = 0L;
    private float catBounce = 0f; // 0..1 — "pulinho" ao pegar um peixe, decai sozinho
    private float displayedChubby = 0f; // 0..1, suavizado — nunca pula direto pro valor alvo
    private boolean blinking = false;
    private long nextBlinkAt = 0L;
    private boolean running = false;

    private CatState catState = CatState.NORMAL;
    private long stateStartedAt = 0L;

    private long lastFrameTimeNanos = 0L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            if (lastFrameTimeNanos == 0L) lastFrameTimeNanos = frameTimeNanos;
            float deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f;
            // Teto de segurança: se o processo ficou preso um tempão (troca de tela, GC
            // grande etc.), não deixa tudo "saltar" de uma vez quando volta.
            if (deltaSeconds > 0.1f) deltaSeconds = 0.1f;
            lastFrameTimeNanos = frameTimeNanos;

            advance(deltaSeconds);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public PixelCatGameView(Context context) {
        super(context);
        setClickable(true);
        scheduleNextBlink();
    }

    /** Liga o laço do jogo — chamado quando a sobreposição entra na tela. */
    public void start() {
        if (running) return;
        running = true;
        lastFrameTimeNanos = 0L;
        lastSpawnAt = System.currentTimeMillis();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    /** Desliga o laço do jogo — chamado ao esconder/trocar a sobreposição, pra não deixar
     * nada rodando em segundo plano. */
    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Rede de segurança: se a view sair da árvore sem alguém chamar stop() antes
        // (ex: contentLayout.removeAllViews() no seletor de duração), para sozinha.
        stop();
    }

    /** Troca a ação atual (chamado pelos chips em LockOverlayService). Cada ação usa seu
     * próprio objeto em cena (peixe/novelo/gota), então ao trocar limpa o que não pertence
     * à nova ação — sem misturar peixe caindo com novelo quicando, por exemplo. */
    public void setMode(GameMode newMode) {
        if (mode == newMode) return;
        mode = newMode;
        fishes.clear();
        droplets.clear();
        toy = null;
        if (mode == GameMode.PLAY) spawnToy();
    }

    /** Registra quem recebe as barrinhas de status a cada ~100ms; chamado uma vez logo ao
     * criar a view (ver LockOverlayService.buildCatGameCard) pra já pintar os valores
     * iniciais antes do primeiro frame do jogo. */
    public void setStatsListener(StatsListener listener) {
        this.statsListener = listener;
        if (listener != null) listener.onStatsChanged(energy, hygiene, fun, affection);
    }

    /** Define qual gatinho (cor da paleta) esta view representa — chamado ao criar o jogo
     * e a cada troca no seletor (ver LockOverlayService.buildCatSelectorRow). Só a
     * identidade visual; os stats em si vêm de loadState logo em seguida. */
    public void setCatIndex(int catIndex) {
        this.catColorIndex = safeIndex(catIndex);
    }

    /** Carrega o progresso de um gatinho específico (ver GuardianPrefs.loadCatState) —
     * chamado ao abrir o jogo e a cada troca no seletor. Limpa peixe/novelo/gota/coração
     * em voo e volta pro modo "Alimentar": o que estava caindo era do gatinho anterior, não
     * faz sentido continuar em cena depois da troca. displayedChubby pula direto pro valor
     * salvo (sem a suavização de costume) porque aqui é uma troca de gatinho, não o gato
     * atual engordando aos poucos. */
    public void loadState(float energyVal, float hygieneVal, float funVal, float affectionVal, int fullness) {
        energy = energyVal;
        hygiene = hygieneVal;
        fun = funVal;
        affection = affectionVal;
        fishEatenThisCat = fullness;
        displayedChubby = fishEatenThisCat / (float) MAX_FISH_PER_CAT;
        catState = CatState.NORMAL;
        mode = GameMode.FEED;
        fishes.clear();
        droplets.clear();
        hearts.clear();
        particles.clear();
        toy = null;
        catBounce = 0f;
        if (statsListener != null) statsListener.onStatsChanged(energy, hygiene, fun, affection);
        invalidate();
    }

    /** Snapshot do progresso atual pra persistir (ver LockOverlayService.saveActiveCatState
     * / GuardianPrefs.saveCatState) — chamado ao trocar de gatinho e ao fechar a sobreposição. */
    public float getEnergy() { return energy; }
    public float getHygiene() { return hygiene; }
    public float getFun() { return fun; }
    public float getAffection() { return affection; }
    public int getFullness() { return fishEatenThisCat; }

    private void spawnToy() {
        Toy t = new Toy();
        t.x = 0.3f + random.nextFloat() * 0.4f;
        t.y = 0.1f;
        t.vx = random.nextBoolean() ? TOY_SPEED_PER_SEC : -TOY_SPEED_PER_SEC;
        t.vy = 0f;
        toy = t;
    }

    private void scheduleNextBlink() {
        nextBlinkAt = System.currentTimeMillis() + 1800 + random.nextInt(2200);
    }

    /** Topo do gato, em fração da altura do widget — usado tanto pro desenho quanto pra
     * decidir quando um peixe "chegou perto o suficiente" de ser pego (ver advance()). */
    private float catTopFraction() {
        int h = getHeight();
        if (h == 0) return 0.6f;
        float catHeightFraction = Math.min((float) getWidth(), (float) h) * CAT_SIZE_FRACTION / h;
        return 1f - CAT_BOTTOM_MARGIN_FRACTION - catHeightFraction;
    }

    private void advance(float deltaSeconds) {
        long now = System.currentTimeMillis();

        if (catState == CatState.EXPLODING) {
            for (Particle p : particles) {
                p.x += p.vx * deltaSeconds;
                p.y += p.vy * deltaSeconds;
                p.vy += 0.6f * deltaSeconds; // gravidade leve — confete cai no fim da festa
            }
            if (now - stateStartedAt >= EXPLODE_DURATION_MS) {
                particles.clear();
                // Não troca mais catColorIndex aqui — a cor agora é a identidade fixa do
                // gatinho (ver setCatIndex), só a "gordura" dele reinicia ao encher.
                fishEatenThisCat = 0;
                displayedChubby = 0f;
                catState = CatState.RESPAWNING;
                stateStartedAt = now;
            }
            return; // sem peixe caindo/pontuando durante a explosão
        }

        if (catState == CatState.RESPAWNING) {
            if (now - stateStartedAt >= RESPAWN_POP_DURATION_MS) {
                catState = CatState.NORMAL;
            }
        }

        if (mode == GameMode.FEED) {
            if (now - lastSpawnAt >= FISH_SPAWN_MS) {
                lastSpawnAt = now;
                Fish f = new Fish();
                f.x = 0.15f + random.nextFloat() * 0.7f;
                f.y = -0.06f;
                fishes.add(f);
            }

            float catchY = catTopFraction() + 0.05f;
            for (int i = fishes.size() - 1; i >= 0; i--) {
                Fish f = fishes.get(i);
                f.y += FISH_FALL_SPEED_PER_SEC * deltaSeconds;
                if (f.y >= catchY && Math.abs(f.x - catX) < CATCH_X_RADIUS) {
                    fishes.remove(i);
                    catBounce = 1f;
                    fishEatenThisCat++;
                    energy = Math.min(100f, energy + ENERGY_GAIN_PER_FISH);
                    if (fishEatenThisCat >= MAX_FISH_PER_CAT) {
                        triggerExplosion();
                    }
                } else if (f.y > 1.08f) {
                    fishes.remove(i); // caiu sem ser pego — sem penalidade, só some
                }
            }
        } else if (mode == GameMode.PLAY) {
            advanceToy(deltaSeconds);
            // Brincar cansa e suja o gato — precisa comer e tomar banho depois.
            energy = Math.max(0f, energy - ENERGY_DRAIN_PER_SEC_PLAYING * deltaSeconds);
            hygiene = Math.max(0f, hygiene - HYGIENE_DRAIN_PER_SEC_PLAYING * deltaSeconds);
            fun = Math.min(100f, fun + FUN_GAIN_PER_SEC_PLAYING * deltaSeconds);
        } else if (mode == GameMode.BATH) {
            advanceBath(deltaSeconds);
        }

        if (mode != GameMode.PLAY) {
            // Diversão só cai fora do modo Brincar — dá um motivo pra voltar a brincar de
            // vez em quando, sem descontar nada enquanto já está brincando.
            fun = Math.max(0f, fun - FUN_DECAY_PER_SEC_IDLE * deltaSeconds);
        }
        // Carinho decai sempre, em qualquer ação — precisa de toque de vez em quando.
        affection = Math.max(0f, affection - AFFECTION_DECAY_PER_SEC * deltaSeconds);

        // Corações do "Carinho" sobem e somem sozinhos, em qualquer ação — assim um
        // carinho feito bem antes de trocar de aba ainda termina de subir na tela.
        for (int i = hearts.size() - 1; i >= 0; i--) {
            Heart hrt = hearts.get(i);
            hrt.y -= HEART_RISE_SPEED_PER_SEC * deltaSeconds;
            if (now - hrt.bornAt >= HEART_LIFETIME_MS) hearts.remove(i);
        }

        if (catBounce > 0f) catBounce = Math.max(0f, catBounce - 2.5f * deltaSeconds);

        // Suaviza a "gordura" em direção ao alvo (peixes comidos / máximo) em vez de
        // saltar pro valor novo assim que um peixe é pego — é isso que faz o gato parecer
        // ir engordando aos poucos, não em degraus.
        float targetChubby = fishEatenThisCat / (float) MAX_FISH_PER_CAT;
        displayedChubby += (targetChubby - displayedChubby) * Math.min(1f, deltaSeconds * 4.5f);

        if (now >= nextBlinkAt) {
            blinking = true;
            mainHandler.postDelayed(() -> blinking = false, 140);
            scheduleNextBlink();
        }

        statsNotifyAccumulator += deltaSeconds;
        if (statsListener != null && statsNotifyAccumulator >= 0.1f) {
            statsNotifyAccumulator = 0f;
            statsListener.onStatsChanged(energy, hygiene, fun, affection);
        }
    }

    /** Modo "Banho": gotinha cai reto igual ao peixe (mesmo ritmo/alcance); arrastar o
     * gato até ela sobe a barrinha de limpeza. */
    private void advanceBath(float deltaSeconds) {
        long now = System.currentTimeMillis();
        if (now - lastDropletSpawnAt >= DROPLET_SPAWN_MS) {
            lastDropletSpawnAt = now;
            Droplet d = new Droplet();
            d.x = 0.15f + random.nextFloat() * 0.7f;
            d.y = -0.06f;
            droplets.add(d);
        }

        float catchY = catTopFraction() + 0.05f;
        for (int i = droplets.size() - 1; i >= 0; i--) {
            Droplet d = droplets.get(i);
            d.y += DROPLET_FALL_SPEED_PER_SEC * deltaSeconds;
            if (d.y >= catchY && Math.abs(d.x - catX) < CATCH_X_RADIUS) {
                droplets.remove(i);
                catBounce = 1f;
                hygiene = Math.min(100f, hygiene + HYGIENE_GAIN_PER_DROPLET);
            } else if (d.y > 1.08f) {
                droplets.remove(i);
            }
        }
    }

    /** Modo "Brincar": o novelo quica pelas paredes do widget (sem cair reto igual peixe);
     * arrastar o gato até ele "queima" gordura em vez de engordar. Sem gordura pra perder
     * (fishEatenThisCat já em 0), o toque no novelo ainda dá um pulinho de brincadeira,
     * só não reduz mais nada. */
    private void advanceToy(float deltaSeconds) {
        if (toy == null) {
            spawnToy();
            return;
        }
        toy.x += toy.vx * deltaSeconds;
        toy.y += toy.vy * deltaSeconds;
        toy.vy += TOY_GRAVITY_PER_SEC * deltaSeconds;

        if (toy.x < 0.14f) { toy.x = 0.14f; toy.vx = Math.abs(toy.vx); }
        if (toy.x > 0.86f) { toy.x = 0.86f; toy.vx = -Math.abs(toy.vx); }
        if (toy.y < 0.08f) { toy.y = 0.08f; toy.vy = Math.abs(toy.vy) * 0.6f; }

        float floorY = catTopFraction() + 0.05f;
        if (toy.y >= floorY) {
            if (Math.abs(toy.x - catX) < CATCH_TOY_X_RADIUS) {
                // pegou o novelo — "queima" gordura e relança o novelo pro alto
                catBounce = 1f;
                fishEatenThisCat = Math.max(0, fishEatenThisCat - TOY_BURN_PER_CATCH);
                toy.vy = -(0.55f + random.nextFloat() * 0.3f);
                toy.vx = random.nextBoolean() ? TOY_SPEED_PER_SEC : -TOY_SPEED_PER_SEC;
            } else {
                // bateu no chão longe do gato — quica de volta sozinho, sem penalidade
                toy.y = floorY;
                toy.vy = -Math.abs(toy.vy) * 0.75f;
            }
        }
    }

    private void triggerExplosion() {
        catState = CatState.EXPLODING;
        stateStartedAt = System.currentTimeMillis();
        int[] furColors = FUR_PALETTE[catColorIndex];
        float centerX = catX;
        float centerY = catTopFraction() + (CAT_SIZE_FRACTION * 0.5f);
        particles.clear();
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            p.x = centerX;
            p.y = centerY;
            float angle = (float) (random.nextFloat() * Math.PI * 2);
            float speed = 0.35f + random.nextFloat() * 0.5f;
            p.vx = (float) Math.cos(angle) * speed;
            p.vy = (float) Math.sin(angle) * speed;
            p.color = random.nextBoolean() ? furColors[0] : furColors[1];
            particles.add(p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (mode == GameMode.PET) {
            // "Carinho" não move o gato — só reage ao toque, uma ação por dedo-na-tela.
            if (action == MotionEvent.ACTION_DOWN) {
                triggerPet();
                return true;
            }
            return super.onTouchEvent(event);
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            int w = getWidth();
            if (w > 0) {
                catX = clamp(event.getX() / w, 0.12f, 0.88f);
                invalidate();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    /** "Carinho": solta uns coraçõezinhos por cima do gato e deixa ele com bochecha rosada
     * (ver drawCat) por um tempinho — reação puramente fofa, não mexe em pontuação. */
    private void triggerPet() {
        petHappyUntil = System.currentTimeMillis() + PET_HAPPY_DURATION_MS;
        catBounce = Math.max(catBounce, 0.6f);
        affection = Math.min(100f, affection + AFFECTION_GAIN_PER_PET);
        float centerY = catTopFraction();
        for (int i = 0; i < 3; i++) {
            Heart hrt = new Heart();
            hrt.x = clamp(catX + (random.nextFloat() - 0.5f) * 0.24f, 0.05f, 0.95f);
            hrt.y = centerY - 0.02f - random.nextFloat() * 0.06f;
            hrt.bornAt = System.currentTimeMillis();
            hearts.add(hrt);
        }
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        if (catState == CatState.EXPLODING) {
            drawParticles(canvas, w, h);
            return; // nada de peixe/placar durante a explosão — só o confete
        }

        fishPaint.setTextSize(h * 0.085f);
        fishPaint.setTextAlign(Paint.Align.CENTER);
        for (Fish f : fishes) {
            canvas.drawText("🐟", f.x * w, f.y * h, fishPaint);
        }
        if (toy != null) {
            fishPaint.setTextSize(h * 0.095f);
            canvas.drawText("🧶", toy.x * w, toy.y * h, fishPaint);
        }
        fishPaint.setTextSize(h * 0.085f);
        for (Droplet d : droplets) {
            canvas.drawText("💧", d.x * w, d.y * h, fishPaint);
        }

        long nowDraw = System.currentTimeMillis();
        fishPaint.setTextSize(h * 0.08f);
        for (Heart hrt : hearts) {
            float age = Math.min(1f, (nowDraw - hrt.bornAt) / (float) HEART_LIFETIME_MS);
            fishPaint.setAlpha(Math.round(255 * (1f - age)));
            canvas.drawText("❤️", hrt.x * w, hrt.y * h, fishPaint);
        }
        fishPaint.setAlpha(255);

        if (mode == GameMode.FEED || mode == GameMode.PLAY) {
            scorePaint.setColor(Color.argb(235, 255, 255, 255));
            scorePaint.setTextSize(h * 0.062f);
            scorePaint.setTextAlign(Paint.Align.LEFT);
            scorePaint.setFakeBoldText(true);
            String label = mode == GameMode.PLAY ? ("🔥 " + fishEatenThisCat) : ("🐟×" + fishEatenThisCat);
            canvas.drawText(label, w * 0.06f, h * 0.11f, scorePaint);
        }

        float popScale = 1f;
        if (catState == CatState.RESPAWNING) {
            float progress = Math.min(1f, (System.currentTimeMillis() - stateStartedAt) / (float) RESPAWN_POP_DURATION_MS);
            // Passa um pouco de 1.0 antes de assentar (efeito "pop" bem simples, sem
            // precisar de interpolador de verdade aqui).
            popScale = progress < 0.7f
                ? (progress / 0.7f) * 1.15f
                : 1.15f - (progress - 0.7f) / 0.3f * 0.15f;
        }
        drawCat(canvas, w, h, popScale);
    }

    private void drawParticles(Canvas canvas, int w, int h) {
        float elapsed = System.currentTimeMillis() - stateStartedAt;
        float alpha = Math.max(0f, 1f - elapsed / EXPLODE_DURATION_MS);
        float size = Math.min(w, h) * 0.06f;
        for (Particle p : particles) {
            blockPaint.setColor(p.color);
            blockPaint.setAlpha(Math.round(alpha * 255));
            canvas.drawRect(p.x * w - size / 2f, p.y * h - size / 2f, p.x * w + size / 2f, p.y * h + size / 2f, blockPaint);
        }
        blockPaint.setAlpha(255);
    }

    private void drawCat(Canvas canvas, int w, int h, float popScale) {
        float unit = Math.min(w, h) * CAT_SIZE_FRACTION / GRID * popScale;
        float bounceOffset = -catBounce * unit * 1.6f;
        float baseX = catX * w - (GRID * unit) / 2f;
        float baseY = catTopFraction() * h + bounceOffset;

        int[] furColors = FUR_PALETTE[catColorIndex];
        int furColor = furColors[0];
        int bellyColor = furColors[1];
        int eyeColor = Color.parseColor("#2B2118");
        int noseColor = Color.parseColor("#E88AA0");

        // "Gordura": alarga a cabeça/corpo simetricamente conforme displayedChubby (0..1)
        // — até +3 unidades de largura no máximo. Orelhas/olhos/nariz/rabo são calculados
        // relativos às bordas da cabeça/corpo, então acompanham o alargamento sozinhos em
        // vez de ficarem "descolados" do contorno.
        float extraW = displayedChubby * 3f;
        float headLeft = 1f - extraW / 2f;
        float headWidth = 10f + extraW;
        float bodyLeft = 2f - extraW / 2f;
        float bodyWidth = 8f + extraW;

        // orelhas (relativas às bordas da cabeça)
        drawBlock(canvas, baseX, baseY, unit, headLeft + 1, 0, 2, 2, furColor);
        drawBlock(canvas, baseX, baseY, unit, headLeft + headWidth - 3, 0, 2, 2, furColor);
        // cabeça
        drawBlock(canvas, baseX, baseY, unit, headLeft, 2, headWidth, 5, furColor);
        // corpo
        drawBlock(canvas, baseX, baseY, unit, bodyLeft, 7, bodyWidth, 5, furColor);
        // barriga (centralizada no corpo, também alarga um pouco)
        drawBlock(canvas, baseX, baseY, unit, bodyLeft + bodyWidth / 2f - (2f + extraW / 2f), 8, 4f + extraW, 4, bellyColor);
        // rabo (dois blocos formando uma curva simples, relativo à borda direita do corpo)
        drawBlock(canvas, baseX, baseY, unit, bodyLeft + bodyWidth, 6, 2, 3, furColor);
        drawBlock(canvas, baseX, baseY, unit, bodyLeft + bodyWidth + 1, 9, 2, 1, furColor);
        // olhos — fecham ao piscar (relativos às bordas da cabeça)
        int eyeHeight = blinking ? 1 : 2;
        int eyeRow = blinking ? 4 : 3;
        drawBlock(canvas, baseX, baseY, unit, headLeft + 2, eyeRow, 1, eyeHeight, eyeColor);
        drawBlock(canvas, baseX, baseY, unit, headLeft + headWidth - 3, eyeRow, 1, eyeHeight, eyeColor);
        // nariz (centralizado na cabeça)
        drawBlock(canvas, baseX, baseY, unit, headLeft + headWidth / 2f - 1, 5, 2, 1, noseColor);

        // bochecha rosada — só aparece um tempinho depois de um carinho (ver triggerPet())
        if (System.currentTimeMillis() < petHappyUntil) {
            int blushColor = Color.parseColor("#F7A8B8");
            drawBlock(canvas, baseX, baseY, unit, headLeft + 0.5f, 4.5f, 1.5f, 1, blushColor);
            drawBlock(canvas, baseX, baseY, unit, headLeft + headWidth - 2f, 4.5f, 1.5f, 1, blushColor);
        }
    }

    /** Desenha um bloco de (col,row) até (col+wCells,row+hCells) na grade lógica, já em pixels reais. */
    private void drawBlock(Canvas canvas, float baseX, float baseY, float unit, float col, float row, float wCells, float hCells, int color) {
        blockPaint.setColor(color);
        blockPaint.setAlpha(255);
        canvas.drawRect(
            baseX + col * unit,
            baseY + row * unit,
            baseX + (col + wCells) * unit,
            baseY + (row + hCells) * unit,
            blockPaint
        );
    }
}
