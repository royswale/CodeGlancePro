package com.nasller.codeglance.render

import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.CustomFoldRegionImpl
import com.intellij.openapi.editor.impl.view.IterationState
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.containers.ContainerUtil
import com.nasller.codeglance.panel.AbstractGlancePanel
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.max

/**
 * A rendered minimap of a document
 */
class Minimap(glancePanel: AbstractGlancePanel,private val scrollState: ScrollState){
	private val editor = glancePanel.editor
	private val config = glancePanel.config
	private var preBuffer:BufferedImage? = null
	var img = lazy { BufferedImage(config.width, scrollState.documentHeight + (100 * config.pixelsPerLine), BufferedImage.TYPE_4BYTE_ABGR) }

	fun update() {
		var curImg = img.value
		if(editor.document.lineCount <= 0) return
		if (curImg.height < scrollState.documentHeight || curImg.width < config.width) {
			// Create an image that is a bit bigger then the one we need, so we don't need to re-create it again soon.
			// Documents can get big, so rather than relative sizes lets just add a fixed amount on.
			preBuffer = img.value
			curImg = BufferedImage(config.width, scrollState.documentHeight + (100 * config.pixelsPerLine), BufferedImage.TYPE_4BYTE_ABGR)
		}
		// These are just to reduce allocations. Premature optimization???
		val scaleBuffer = FloatArray(4)
		val setColorRgba: (Color).() -> Unit = {
			scaleBuffer[0] = red.toFloat()
			scaleBuffer[1] = green.toFloat()
			scaleBuffer[2] = blue.toFloat()
			scaleBuffer[3] = alpha.toFloat()
		}

		val text = editor.document.immutableCharSequence
		val defaultColor = editor.colorsScheme.defaultForeground
		val line = editor.document.createLineIterator()
		val hlIter = editor.highlighter.createIterator(0)
		val softWrapEnable = editor.softWrapModel.isSoftWrappingEnabled

		var x = 0
		var y = 0
		var foldedLines = 0
		var softWrapLines = 0
		val moveCharIndex = { code: Int ->
			when (code) {
				ENTER -> {
					x = 0
					y += config.pixelsPerLine
				}
				TAB -> x += 4
				else -> x += 1
			}
		}
		val g = curImg.createGraphics()
		g.composite = AbstractGlancePanel.CLEAR
		g.fillRect(0, 0, curImg.width, curImg.height)
		loop@ while (!hlIter.atEnd()) {
			var offset = hlIter.start
			line.start(offset)
			y = (line.lineNumber + softWrapLines - foldedLines) * config.pixelsPerLine
			// Jump over folds
			val region = editor.foldingModel.getCollapsedRegionAtOffset(offset)?.let{
				if(it.startOffset >= 0 && it.endOffset >= 0 && it !is CustomFoldRegionImpl){
					foldedLines += editor.document.getLineNumber(it.endOffset) - editor.document.getLineNumber(it.startOffset)
					offset = it.endOffset
					it
				} else null
			}
			if (region != null && region.placeholderText.isNotEmpty()) {
				(editor.foldingModel.placeholderAttributes?.foregroundColor ?: defaultColor).apply(setColorRgba)
				StringUtil.replace(region.placeholderText, "\n", " ").toCharArray().forEach {
					val charCode = it.code
					moveCharIndex(charCode)
					curImg.renderImage(x, y, charCode, scaleBuffer)
				}
			} else {
				val color = try {
					hlIter.textAttributes.foregroundColor
				} catch (_: ConcurrentModificationException){ null }
				while (offset < hlIter.end) {
					// Watch out for tokens that extend past the document... bad plugins? see issue #138
					if (offset >= text.length) break@loop
					if (softWrapEnable) editor.softWrapModel.getSoftWrap(offset)?.let { softWrap ->
						softWrap.chars.forEach {
							val charCode = it.code
							moveCharIndex(charCode)
							if(charCode == ENTER) softWrapLines += 1
						}
					}
					val charCode = text[offset].code
					moveCharIndex(charCode)
					curImg.renderImage(x, y, charCode, scaleBuffer){
						(getHighlightColor(offset) ?: color ?: defaultColor).apply(setColorRgba)
					}
					++offset
				}
			}
			do // Skip to end of fold
				hlIter.advance()
			while (!hlIter.atEnd() && hlIter.start < offset)
		}
		g.dispose()
		preBuffer?.let {
			img = lazyOf(curImg)
			it.flush()
			null
		}.also { preBuffer = it }
	}

	private fun getHighlightColor(offset:Int):Color?{
		var color:Color? = null
		val list = mutableListOf<RangeHighlighterEx>()
		editor.filteredDocumentMarkupModel.processRangeHighlightersOverlappingWith(max(0, offset - 1),offset) {
			if (it.errorStripeTooltip != null && it.isValid && it.getTextAttributes (editor.colorsScheme) != TextAttributes.ERASE_MARKER) {
				list.add(it)
			}
			return@processRangeHighlightersOverlappingWith true
		}
		list.apply {
			if(size > 1) ContainerUtil.quickSort(this,IterationState.createByLayerThenByAttributesComparator(editor.colorsScheme))
		}.forEach{
			it.getTextAttributes(editor.colorsScheme)?.foregroundColor?.apply {
				color = this
				return@forEach
			}
		}
		return color
	}

	private fun BufferedImage.renderImage(x: Int, y: Int, char: Int, scaleBuffer: FloatArray,consumer: (()->Unit)? = null) {
		if (char !in 0..32 && x in 0 until width && 0 <= y && y + config.pixelsPerLine < height) {
			consumer?.invoke()
			if (config.clean) {
				renderClean(x, y, char, scaleBuffer)
			} else {
				renderAccurate(x, y, char, scaleBuffer)
			}
		}
	}

	private fun BufferedImage.renderClean(x: Int, y: Int, char: Int, buffer: FloatArray) {
		val weight = when (char) {
			in 33..126 -> 0.8f
			else -> 0.4f
		}
		when (config.pixelsPerLine) {
			1 -> // Can't show whitespace between lines anymore. This looks rather ugly...
				setPixel(x, y + 1, weight * 0.6f, buffer)
			2 -> {
				// Two lines we make the top line a little lighter to give the illusion of whitespace between lines.
				setPixel(x, y, weight * 0.3f, buffer)
				setPixel(x, y + 1, weight * 0.6f, buffer)
			}
			3 -> {
				// Three lines we make the top nearly empty, and fade the bottom a little too
				setPixel(x, y, weight * 0.1f, buffer)
				setPixel(x, y + 1, weight * 0.6f, buffer)
				setPixel(x, y + 2, weight * 0.6f, buffer)
			}
			4 -> {
				// Empty top line, Nice blend for everything else
				setPixel(x, y + 1, weight * 0.6f, buffer)
				setPixel(x, y + 2, weight * 0.6f, buffer)
				setPixel(x, y + 3, weight * 0.6f, buffer)
			}
		}
	}

	private fun BufferedImage.renderAccurate(x: Int, y: Int, char: Int, buffer: FloatArray) {
		val topWeight = getTopWeight(char)
		val bottomWeight = getBottomWeight(char)
		when (config.pixelsPerLine) {
			1 -> // Can't show whitespace between lines anymore. This looks rather ugly...
				setPixel(x, y + 1, ((topWeight + bottomWeight) / 2.0).toFloat(), buffer)
			2 -> {
				// Two lines we make the top line a little lighter to give the illusion of whitespace between lines.
				setPixel(x, y, topWeight * 0.5f, buffer)
				setPixel(x, y + 1, bottomWeight, buffer)
			}
			3 -> {
				// Three lines we make the top nearly empty, and fade the bottom a little too
				setPixel(x, y, topWeight * 0.3f, buffer)
				setPixel(x, y + 1, ((topWeight + bottomWeight) / 2.0).toFloat(), buffer)
				setPixel(x, y + 2, bottomWeight * 0.7f, buffer)
			}
			4 -> {
				// Empty top line, Nice blend for everything else
				setPixel(x, y + 1, topWeight, buffer)
				setPixel(x, y + 2, ((topWeight + bottomWeight) / 2.0).toFloat(), buffer)
				setPixel(x, y + 3, bottomWeight, buffer)
			}
		}
	}

	/**
	 * mask out the alpha component and set it to the given value.
	 * *
	 * @param alpha     alpha percent from 0-1.
	 */
	private fun BufferedImage.setPixel(x: Int, y: Int, alpha: Float, scaleBuffer: FloatArray) {
		if(alpha < 1) scaleBuffer[3] = alpha * 0xFF
		raster.setPixel(x, y, scaleBuffer)
	}

	private companion object{
		const val TAB = 9
		const val ENTER = 10
	}
}