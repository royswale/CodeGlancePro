package com.nasller.codeglance.panel

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diff.LineStatusMarkerDrawUtil
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.CustomFoldRegionImpl
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ex.LocalRange
import com.intellij.reference.SoftReference
import com.intellij.ui.scale.ScaleContext
import com.nasller.codeglance.listener.GlanceListener
import com.nasller.codeglance.listener.HideScrollBarListener
import com.nasller.codeglance.panel.scroll.ScrollBar
import com.nasller.codeglance.render.Minimap
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.function.Function

class GlancePanel(project: Project, textEditor: TextEditor) : AbstractGlancePanel(project,textEditor){
    private var mapRef = MinimapCache { MinimapRef(Minimap(this,scrollState)) }
    private val glanceListener = GlanceListener(this)
    val hideScrollBarListener = HideScrollBarListener(this)
    val myPopHandler = CustomScrollBarPopup(this)
    init {
        Disposer.register(textEditor,this)
        editor.putUserData(CURRENT_GLANCE,this)
        scrollbar = ScrollBar(this)
        add(scrollbar)
        refresh()
    }

    fun addHideScrollBarListener(){
        if(config.hoveringToShowScrollBar){
            ApplicationManager.getApplication().invokeLater { isVisible = false }
            if(!config.hideOriginalScrollBar){
                editor.scrollPane.verticalScrollBar.addMouseListener(hideScrollBarListener)
                editor.scrollPane.verticalScrollBar.addMouseMotionListener(hideScrollBarListener)
            }else{
                myVcsPanel?.addMouseListener(hideScrollBarListener)
                myVcsPanel?.addMouseMotionListener(hideScrollBarListener)
            }
        }
    }

    fun removeHideScrollBarListener(){
        hideScrollBarListener.apply {
            if(!config.hideOriginalScrollBar){
                editor.scrollPane.verticalScrollBar.removeMouseListener(this)
                editor.scrollPane.verticalScrollBar.removeMouseMotionListener(this)
            }else{
                myVcsPanel?.removeMouseListener(this)
                myVcsPanel?.removeMouseMotionListener(this)
            }
            cancel()
            showHideOriginScrollBar(true)
        }
        isVisible = true
    }

    override fun updateImgTask() {
        try {
            mapRef.get(ScaleContext.create(this)).update()
        } finally {
            renderLock.release()
            if (renderLock.dirty) {
                renderLock.clean()
                updateImage()
            }
        }
        repaint()
    }

    override fun paintVcs(g: Graphics2D,notPaint:Boolean) {
        if(notPaint) return
        val foldRegions = editor.foldingModel.allFoldRegions.filter { fold -> fold !is CustomFoldRegionImpl && !fold.isExpanded &&
                fold.startOffset >= 0 && fold.endOffset >= 0 }
        val srcOver = if(config.hideOriginalScrollBar) srcOver else srcOver0_4
        trackerManager.getLineStatusTracker(editor.document)?.getRanges()?.apply {
            g.composite = srcOver
            forEach {
                if (it !is LocalRange || it.changelistId == changeListManager.defaultChangeList.id) {
                    try {
                        g.color = LineStatusMarkerDrawUtil.getGutterColor(it.type, editor)
                        val documentLine = getDocumentRenderLine(it.line1, it.line2)
                        var visualLine1 = EditorUtil.logicalToVisualLine(editor, it.line1)
                        var visualLine2 = EditorUtil.logicalToVisualLine(editor, it.line2)
                        foldRegions.forEach { fold ->
                            if (editor.document.getLineNumber(fold.startOffset) <= it.line1 &&
                                it.line2 <= editor.document.getLineNumber(fold.endOffset)
                            ) visualLine2 = visualLine1 + 1
                        }
                        if (it.line1 != it.line2 && visualLine1 == visualLine2) {
                            val realLine = editor.visualToLogicalPosition(VisualPosition(visualLine1, 0)).line
                            visualLine1 += it.line1 - realLine
                            visualLine2 += it.line2 - realLine
                        }
                        val start = (visualLine1 + documentLine.first) * config.pixelsPerLine - scrollState.visibleStart
                        val end = (visualLine2 + documentLine.second) * config.pixelsPerLine - scrollState.visibleStart
                        if(start > 0 || end - start - config.pixelsPerLine > 0) {
                            g.fillRect(0, start, width, config.pixelsPerLine)
                            g.fillRect(0, start + config.pixelsPerLine, width, end - start - config.pixelsPerLine)
                        }
                    }catch (_:ConcurrentModificationException){}
                }
            }
        }
    }

    override fun paintSelection(g: Graphics2D, startByte: Int, endByte: Int) {
        val start = editor.offsetToVisualPosition(startByte)
        val end = editor.offsetToVisualPosition(endByte)
        val documentLine = getDocumentRenderLine(editor.document.getLineNumber(startByte),editor.document.getLineNumber(endByte))

        val sX = start.column
        val sY = (start.line + documentLine.first) * config.pixelsPerLine - scrollState.visibleStart
        val eX = end.column + 1
        val eY = (end.line + documentLine.second) * config.pixelsPerLine - scrollState.visibleStart

        g.composite = srcOver
        g.color = editor.colorsScheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
        if(sY > 0 || eY > 0) {
            // Single line is real easy
            if (start.line == end.line) {
                g.fillRect(sX, sY, eX - sX, config.pixelsPerLine)
            } else {
                // Draw the line leading in
                g.fillRect(sX, sY, width - sX, config.pixelsPerLine)
                // Then the line at the end
                g.fillRect(0, eY, eX, config.pixelsPerLine)
                if (eY + config.pixelsPerLine != sY) {
                    // And if there is anything in between, fill it in
                    g.fillRect(0, sY + config.pixelsPerLine, width, eY - sY - config.pixelsPerLine)
                }
            }
        }
    }

    override fun paintCaretPosition(g: Graphics2D): MutableList<VisualPosition> {
        g.composite = srcOver
        val allCarets = mutableListOf<VisualPosition>()
        editor.caretModel.allCarets.forEach{
            g.color = editor.colorsScheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            val documentLine = getDocumentRenderLine(it.logicalPosition.line,it.logicalPosition.line)
            val start = (it.visualPosition.line + documentLine.first) * config.pixelsPerLine - scrollState.visibleStart
            if(start > 0){
                g.fillRect(0, start, width, config.pixelsPerLine)
                allCarets.add(VisualPosition(start,it.visualPosition.column))
            }
        }
        return allCarets
    }

    override fun paintOtherHighlight(g: Graphics2D,allCarets:List<VisualPosition>) {
        val map = lazy{ hashMapOf<String,Int>() }
        editor.markupModel.processRangeHighlightersOverlappingWith(0, editor.document.textLength) {
            val key = (it.startOffset+it.endOffset).toString()
            val layer = map.value[key]
            it.getErrorStripeMarkColor(editor.colorsScheme)?.apply {
                if(layer == null || layer < it.layer){
                    drawMarkupLine(it,g,this,false,allCarets)
                    map.value[key] = it.layer
                }
            }
            return@processRangeHighlightersOverlappingWith true
        }
    }

    override fun paintErrorStripes(g: Graphics2D,allCarets:List<VisualPosition>) {
        editor.filteredDocumentMarkupModel.processRangeHighlightersOverlappingWith(0, editor.document.textLength) {
            HighlightInfo.fromRangeHighlighter(it) ?.let {info ->
                it.getErrorStripeMarkColor(editor.colorsScheme)?.apply {
                    drawMarkupLine(it, g,this,info.severity.myVal > minSeverity.myVal,allCarets)
                }
            }
            return@processRangeHighlightersOverlappingWith true
        }
    }

    private fun drawMarkupLine(it: RangeHighlighter, g: Graphics2D, color: Color, compensateLine:Boolean,allCarets:List<VisualPosition>){
        g.color = color
        g.composite = srcOver0_8
        val documentLine = getDocumentRenderLine(editor.offsetToLogicalPosition(it.startOffset).line, editor.offsetToLogicalPosition(it.endOffset).line)
        val start = editor.offsetToVisualPosition(it.startOffset)
        val end = editor.offsetToVisualPosition(it.endOffset)
        var sX = if (start.column > (width - minGap)) width - minGap else start.column
        val sY = (start.line + documentLine.first) * config.pixelsPerLine - scrollState.visibleStart
        var eX = if (start.column < (width - minGap)) end.column + 1 else width
        val eY = (end.line + documentLine.second) * config.pixelsPerLine - scrollState.visibleStart
        val collapsed = editor.foldingModel.isOffsetCollapsed(it.startOffset)
        if(sY > 0 || eY > 0){
            if (allCarets.any { it.line == sY }) {
                g.composite = srcOver
                g.color = g.color.brighter()
            }
            if (sY == eY && !collapsed) {
                if(compensateLine && eX - sX < minGap){
                    eX += minGap-(eX - sX)
                    if(eX > width) sX -= eX - width
                }
                g.fillRect(sX, sY, eX - sX, config.pixelsPerLine)
            } else if (collapsed) {
                g.fillRect(0, sY, width / 2, config.pixelsPerLine)
            } else {
                val notEqual = eY + config.pixelsPerLine != sY
                if (allCarets.any {it.line == eY || (notEqual && (it.line in sY + config.pixelsPerLine..eY))}) {
                    g.composite = srcOver
                    g.color = g.color.brighter()
                }
                g.fillRect(sX, sY, width - sX, config.pixelsPerLine)
                if (notEqual) g.fillRect(0, sY + config.pixelsPerLine, width, eY - sY - config.pixelsPerLine)
                g.fillRect(0, eY, eX, config.pixelsPerLine)
            }
        }
    }

    override fun getDrawImage() : BufferedImage?{
        return mapRef.get(ScaleContext.create(this)).let{
            if(!it.img.isInitialized()) {
                updateImage()
                null
            }else{
                it.img.value
            }
        }
    }

    override fun dispose() {
        mapRef.clear()
        glanceListener.dispose()
        removeHideScrollBarListener()
        super.dispose()
    }
}

private class MinimapRef(minimap: Minimap) : SoftReference<Minimap?>(minimap) {
    private var strongRef: Minimap?

    init {
        strongRef = minimap
    }

    override fun get(): Minimap? {
        val minimap = strongRef ?: super.get()
        // drop on first request
        strongRef = null
        return minimap
    }
}

private class MinimapCache(imageProvider: Function<in ScaleContext, MinimapRef>) : ScaleContext.Cache<MinimapRef?>(imageProvider) {
    fun get(ctx: ScaleContext): Minimap {
        val ref = getOrProvide(ctx)
        val image = SoftReference.dereference(ref)
        if (image != null) return image
        clear() // clear to recalculate the image
        return get(ctx) // first recalculated image will be non-null
    }
}