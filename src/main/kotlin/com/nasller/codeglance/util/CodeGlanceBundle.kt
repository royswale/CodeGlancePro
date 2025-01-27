// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.nasller.codeglance.util

import com.intellij.AbstractBundle
import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.util.*
import java.util.function.Supplier

const val BUNDLE: @NonNls String = "messages.CodeGlanceBundle"

object CodeGlanceBundle : AbstractBundle(BUNDLE) {
	private val adaptedControl = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

	private val adaptedBundle: AbstractBundle? by lazy {
		val dynamicLocale = DynamicBundle.getLocale()
		if (dynamicLocale.toLanguageTag() == Locale.ENGLISH.toLanguageTag()) {
			object : AbstractBundle(BUNDLE) {
				override fun findBundle(pathToBundle: String, loader: ClassLoader, control: ResourceBundle.Control): ResourceBundle {
					val dynamicBundle = ResourceBundle.getBundle(pathToBundle, dynamicLocale, loader, adaptedControl)
					return dynamicBundle ?: super.findBundle(pathToBundle, loader, control)
				}
			}
		} else null
	}

	override fun findBundle(pathToBundle: String, loader: ClassLoader, control: ResourceBundle.Control): ResourceBundle =
		DynamicBundle.getLocale().let { ResourceBundle.getBundle(pathToBundle, it, loader, control) }
			?: super.findBundle(pathToBundle, loader, control)

	fun getAdaptedMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
		return adaptedBundle?.getMessage(key, *params) ?: getMessage(key, *params)
	}

	fun getAdaptedLazyMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): Supplier<String> {
		return adaptedBundle?.getLazyMessage(key, *params) ?: getLazyMessage(key, *params)
	}
}

@Nls
fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any): String {
	return CodeGlanceBundle.getAdaptedMessage(key, *params)
}

fun messagePointer(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any): Supplier<String> {
	return CodeGlanceBundle.getAdaptedLazyMessage(key, *params)
}