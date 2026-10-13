package com.guardianshield.child

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Área da tela inicial dividida em células (colunas x linhas), como nos launchers do
 * Android. Cada filho ocupa um retângulo de células (ver [Cell]): ícones 1x1, widgets
 * (relógio, status, tarefas) maiores.
 *
 * Toque e segure (detectado aqui, não nos filhos):
 * - num ícone: chama [Listener.onIconLongPress] (a Activity mostra o menu) e, se o dedo
 *   andar, o ícone passa a seguir o dedo; ao soltar, [Listener.onIconDropped] decide
 *   (célula vazia = move, outro ícone = pasta).
 * - num widget: entra no modo de edição — moldura com alças; arrastar o meio move,
 *   arrastar uma alça redimensiona, sempre encaixando nas células. Tocar fora termina e
 *   chama [Listener.onWidgetChanged].
 * - num espaço vazio: [Listener.onEmptyLongPress].
 */
class HomeWorkspace @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    data class Cell(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun overlaps(o: Cell) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
        fun contains(cx: Int, cy: Int) = cx >= x && cx < x + w && cy >= y && cy < y + h
    }

    class LayoutParams(var cell: Cell, val key: String, val isWidget: Boolean, val minW: Int = 1, val minH: Int = 1) :
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    interface Listener {
        fun onIconLongPress(view: View, key: String)
        fun onIconDragStarted(key: String)
        /** [targetKey] = ícone que estava na célula de destino (null = vazia). Devolve true
         * se aceitou (a Activity regrava o layout); false = o ícone volta pro lugar. */
        fun onIconDropped(key: String, cellX: Int, cellY: Int, targetKey: String?): Boolean
        fun onWidgetEditStarted(key: String)
        fun onWidgetChanged(key: String, cell: Cell)
        fun onEmptyLongPress()
        fun onLayoutGridChanged(columns: Int, rows: Int)
    }

    var listener: Listener? = null

    var columns = 4
        set(value) {
            if (field != value) { field = value; requestLayout() }
        }
    /** 0 = ainda não mediu (não dá pra encaixar nada antes disso). */
    var rows = 0
        private set

    private val density = resources.displayMetrics.density
    private val minCellHeightPx = 78 * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    val cellWidth: Float get() = if (columns > 0) width.toFloat() / columns else 0f
    val cellHeight: Float get() = if (rows > 0) height.toFloat() / rows else 0f

    // --- estado do gesto ---
    private var downX = 0f
    private var downY = 0f
    private var downChild: View? = null
    private var longPressFired = false
    private var draggingIcon: View? = null
    private var dragHoverCell: Pair<Int, Int>? = null

    /** Widget em edição (moldura com alças) — null fora do modo de edição. */
    var editingWidget: View? = null
        private set
    private var editMode = EDIT_NONE
    private var editStartCell: Cell? = null

    val isBusy: Boolean get() = longPressFired || draggingIcon != null || editingWidget != null

    private val longPressRunnable = Runnable { fireLongPress() }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0xFFFF6B00.toInt()
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    fun cellOf(view: View): Cell? = (view.layoutParams as? LayoutParams)?.cell

    fun keyOf(view: View): String? = (view.layoutParams as? LayoutParams)?.key

    fun childForKey(key: String): View? =
        (0 until childCount).map { getChildAt(it) }.firstOrNull { keyOf(it) == key }

    /** Primeira célula livre (de cima pra baixo, esquerda pra direita), ignorando [ignore]. */
    fun firstFreeCell(occupied: List<Cell>): Pair<Int, Int>? {
        for (y in 0 until rows) for (x in 0 until columns) {
            val c = Cell(x, y, 1, 1)
            if (occupied.none { it.overlaps(c) }) return x to y
        }
        return null
    }

    // ============================== medida/posição ==============================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val newRows = max(4, (h / minCellHeightPx).toInt())
        if (newRows != rows) {
            rows = newRows
            post { listener?.onLayoutGridChanged(columns, rows) }
        }
        val cw = w.toFloat() / columns
        val ch = h.toFloat() / rows
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            val cw2 = (cw * lp.cell.w).roundToInt()
            val ch2 = (ch * lp.cell.h).roundToInt()
            child.measure(MeasureSpec.makeMeasureSpec(cw2, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch2, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellWidth
        val ch = cellHeight
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            val left = (lp.cell.x * cw).roundToInt()
            val top = (lp.cell.y * ch).roundToInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(Cell(0, 0, 1, 1), "", false)

    // ============================== toque ==============================

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                longPressFired = false
                if (editingWidget != null) {
                    beginEditGesture(ev.x, ev.y)
                    return true
                }
                downChild = childAt(ev.x, ev.y)
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressFired) return true
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) removeCallbacks(longPressRunnable)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                // Toque e segure já disparou: não deixa o filho receber o UP (seria um
                // "clique" abrindo o app depois do menu).
                if (longPressFired) return true
            }
        }
        return longPressFired
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Nenhum filho consumiu o toque (fundo, ou parte não clicável de um widget):
                // ainda pode ser toque e segure — do que estiver embaixo do dedo.
                if (editingWidget == null) {
                    downX = ev.x; downY = ev.y
                    downChild = childAt(ev.x, ev.y)
                    longPressFired = false
                    removeCallbacks(longPressRunnable)
                    postDelayed(longPressRunnable, longPressMs)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (editingWidget != null) {
                    if (editMode == EDIT_NONE && (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) && longPressFired) {
                        editMode = EDIT_MOVE
                    }
                    updateEditGesture(ev.x, ev.y)
                    return true
                }
                val moved = abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop
                if (!longPressFired && moved) removeCallbacks(longPressRunnable)
                val icon = downChild
                if (longPressFired && icon != null && draggingIcon == null && moved) startIconDrag(icon)
                draggingIcon?.let { updateIconDrag(it, ev.x, ev.y) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                draggingIcon?.let { finishIconDrag(it, ev.x, ev.y) }
                if (editingWidget != null) endEditGesture()
                longPressFired = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                draggingIcon?.let { cancelIconDrag(it) }
                if (editingWidget != null) endEditGesture()
                longPressFired = false
                return true
            }
        }
        return true
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Um filho rolando (a lista de tarefas, de lado) pegou o gesto: não é toque e segure.
        if (disallowIntercept) removeCallbacks(longPressRunnable)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun childAt(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (c.visibility == VISIBLE && x >= c.left && x < c.right && y >= c.top && y < c.bottom) return c
        }
        return null
    }

    private fun fireLongPress() {
        longPressFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val child = downChild
        val lp = child?.layoutParams as? LayoutParams
        when {
            child == null || lp == null -> {
                longPressFired = false
                listener?.onEmptyLongPress()
            }
            lp.isWidget -> startWidgetEdit(child, armMove = true)
            else -> listener?.onIconLongPress(child, lp.key)
        }
        // Cancela o "pressionado" do filho (ripple, botão) — o gesto agora é nosso.
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        child?.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // --- arrastar ícone ---

    private fun startIconDrag(icon: View) {
        draggingIcon = icon
        icon.bringToFront()
        icon.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.9f).setDuration(120).start()
        listener?.onIconDragStarted(keyOf(icon) ?: "")
    }

    private fun updateIconDrag(icon: View, x: Float, y: Float) {
        icon.translationX = x - downX
        icon.translationY = y - downY
        val cx = ((icon.left + icon.width / 2f + icon.translationX) / cellWidth).toInt().coerceIn(0, columns - 1)
        val cy = ((icon.top + icon.height / 2f + icon.translationY) / cellHeight).toInt().coerceIn(0, rows - 1)
        if (dragHoverCell != cx to cy) {
            dragHoverCell = cx to cy
            invalidate()
        }
    }

    private fun finishIconDrag(icon: View, x: Float, y: Float) {
        updateIconDrag(icon, x, y)
        val target = dragHoverCell
        val key = keyOf(icon)
        val ok = if (target != null && key != null) {
            val occupant = (0 until childCount).map { getChildAt(it) }
                .firstOrNull { it !== icon && cellOf(it)?.contains(target.first, target.second) == true }
            when {
                occupant == null -> listener?.onIconDropped(key, target.first, target.second, null) == true
                (occupant.layoutParams as LayoutParams).isWidget -> false
                else -> listener?.onIconDropped(key, target.first, target.second, keyOf(occupant)) == true
            }
        } else false
        settleIcon(icon, animateBack = !ok)
    }

    private fun cancelIconDrag(icon: View) = settleIcon(icon, animateBack = true)

    private fun settleIcon(icon: View, animateBack: Boolean) {
        draggingIcon = null
        dragHoverCell = null
        invalidate()
        if (animateBack) {
            icon.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
        } else {
            icon.translationX = 0f; icon.translationY = 0f
            icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
        }
    }

    // --- editar widget ---

    fun startWidgetEdit(widget: View, armMove: Boolean = false) {
        editingWidget = widget
        editStartCell = cellOf(widget)
        editMode = if (armMove) EDIT_NONE else EDIT_NONE
        listener?.onWidgetEditStarted(keyOf(widget) ?: "")
        invalidate()
    }

    fun stopWidgetEdit() {
        val widget = editingWidget ?: return
        editingWidget = null
        editMode = EDIT_NONE
        invalidate()
        val lp = widget.layoutParams as LayoutParams
        if (lp.cell != editStartCell) listener?.onWidgetChanged(lp.key, lp.cell)
        editStartCell = null
    }

    private var gestureStartCell: Cell? = null

    private fun beginEditGesture(x: Float, y: Float) {
        val widget = editingWidget ?: return
        val r = frameRect(widget)
        val hit = 22 * density
        editMode = when {
            abs(x - r.left) < hit && abs(y - r.centerY()) < hit * 1.5f -> EDIT_LEFT
            abs(x - r.right) < hit && abs(y - r.centerY()) < hit * 1.5f -> EDIT_RIGHT
            abs(y - r.top) < hit && abs(x - r.centerX()) < hit * 1.5f -> EDIT_TOP
            abs(y - r.bottom) < hit && abs(x - r.centerX()) < hit * 1.5f -> EDIT_BOTTOM
            r.contains(x, y) -> EDIT_MOVE
            else -> EDIT_OUTSIDE
        }
        gestureStartCell = cellOf(widget)
        downX = x; downY = y
    }

    private fun updateEditGesture(x: Float, y: Float) {
        val widget = editingWidget ?: return
        val lp = widget.layoutParams as LayoutParams
        val start = gestureStartCell ?: lp.cell.also { gestureStartCell = it }
        val dxCells = ((x - downX) / cellWidth).roundToInt()
        val dyCells = ((y - downY) / cellHeight).roundToInt()
        val proposed = when (editMode) {
            EDIT_MOVE -> start.copy(
                x = (start.x + dxCells).coerceIn(0, columns - start.w),
                y = (start.y + dyCells).coerceIn(0, rows - start.h)
            )
            EDIT_LEFT -> {
                val nx = (start.x + dxCells).coerceIn(0, start.x + start.w - lp.minW)
                start.copy(x = nx, w = start.w + (start.x - nx))
            }
            EDIT_RIGHT -> start.copy(w = (start.w + dxCells).coerceIn(lp.minW, columns - start.x))
            EDIT_TOP -> {
                val ny = (start.y + dyCells).coerceIn(0, start.y + start.h - lp.minH)
                start.copy(y = ny, h = start.h + (start.y - ny))
            }
            EDIT_BOTTOM -> start.copy(h = (start.h + dyCells).coerceIn(lp.minH, rows - start.y))
            else -> return
        }
        // Não deixa cobrir outro widget; ícones por baixo são reposicionados ao terminar.
        val hitsWidget = (0 until childCount).map { getChildAt(it) }.any {
            it !== widget && (it.layoutParams as? LayoutParams)?.isWidget == true &&
                it.visibility != GONE && cellOf(it)?.overlaps(proposed) == true
        }
        if (!hitsWidget && proposed != lp.cell) {
            lp.cell = proposed
            requestLayout()
            invalidate()
        }
    }

    private fun endEditGesture() {
        if (editMode == EDIT_OUTSIDE) {
            stopWidgetEdit()
        } else if (editMode != EDIT_NONE) {
            // Fim de um arrasto/redimensionamento: salva já, mas continua editando (dá pra
            // ajustar de novo); tocar fora encerra.
            val widget = editingWidget
            val lp = widget?.layoutParams as? LayoutParams
            if (lp != null && lp.cell != gestureStartCell) listener?.onWidgetChanged(lp.key, lp.cell)
            editStartCell = lp?.cell
        }
        editMode = EDIT_NONE
        gestureStartCell = null
    }

    private fun frameRect(widget: View): RectF {
        val inset = 4 * density
        return RectF(widget.left + inset, widget.top + inset, widget.right - inset, widget.bottom - inset)
    }

    // ============================== desenho ==============================

    override fun dispatchDraw(canvas: Canvas) {
        val editing = editingWidget
        if (draggingIcon != null || editing != null) {
            // Pontinhos nas junções das células: mostram a grade enquanto arruma.
            for (y in 1 until rows) for (x in 1 until columns) {
                canvas.drawCircle(x * cellWidth, y * cellHeight, 2 * density, gridDotPaint)
            }
        }
        dragHoverCell?.let { (cx, cy) ->
            val pad = 6 * density
            canvas.drawRoundRect(
                RectF(cx * cellWidth + pad, cy * cellHeight + pad, (cx + 1) * cellWidth - pad, (cy + 1) * cellHeight - pad),
                16 * density, 16 * density, hoverPaint
            )
        }
        super.dispatchDraw(canvas)
        if (editing != null) {
            val r = frameRect(editing)
            canvas.drawRoundRect(r, 20 * density, 20 * density, framePaint)
            val radius = 9 * density
            listOf(r.left to r.centerY(), r.right to r.centerY(), r.centerX() to r.top, r.centerX() to r.bottom).forEach { (hx, hy) ->
                canvas.drawCircle(hx, hy, radius, handlePaint)
                canvas.drawCircle(hx, hy, radius, handleRingPaint)
            }
        }
    }

    companion object {
        private const val EDIT_NONE = 0
        private const val EDIT_MOVE = 1
        private const val EDIT_LEFT = 2
        private const val EDIT_RIGHT = 3
        private const val EDIT_TOP = 4
        private const val EDIT_BOTTOM = 5
        private const val EDIT_OUTSIDE = 6

        @Suppress("unused") private fun clampF(v: Float, a: Float, b: Float) = min(max(v, a), b)
    }
}
