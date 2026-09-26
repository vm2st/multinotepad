package com.vm2st.notepad

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout

enum class AppTheme(
    val preferenceValue: String,
    @StringRes val labelRes: Int,
    @StyleRes val styleRes: Int,
    @StyleRes val popupStyleRes: Int
) {
    LIGHT(
        "light",
        R.string.theme_light,
        R.style.Theme_MultiNotepad_Light,
        R.style.ThemeOverlay_MultiNotepad_Popup_Light
    ),
    DARK(
        "dark",
        R.string.theme_dark,
        R.style.Theme_MultiNotepad_Dark,
        R.style.ThemeOverlay_MultiNotepad_Popup_Dark
    ),
    BURGUNDY(
        "burgundy",
        R.string.theme_burgundy,
        R.style.Theme_MultiNotepad_Burgundy,
        R.style.ThemeOverlay_MultiNotepad_Popup_Burgundy
    );

    companion object {
        fun from(value: String?): AppTheme =
            entries.firstOrNull { it.preferenceValue == value } ?: LIGHT
    }
}

internal object AppThemeStore {
    private const val PREFERENCES = "multinotepad_preferences"
    private const val THEME_KEY = "app_theme"

    fun current(activity: AppCompatActivity): AppTheme =
        AppTheme.from(
            activity.getSharedPreferences(PREFERENCES, AppCompatActivity.MODE_PRIVATE)
                .getString(THEME_KEY, AppTheme.LIGHT.preferenceValue)
        )

    @SuppressLint("ApplySharedPref")
    fun save(activity: AppCompatActivity, theme: AppTheme) {
        activity.getSharedPreferences(PREFERENCES, AppCompatActivity.MODE_PRIVATE)
            .edit()
            .putString(THEME_KEY, theme.preferenceValue)
            .commit()
    }
}

abstract class ThemedActivity : AppCompatActivity() {
    private lateinit var themedRoot: View
    private lateinit var themedToolbar: MaterialToolbar

    protected val selectedTheme: AppTheme
        get() = AppThemeStore.current(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(selectedTheme.styleRes)
        super.onCreate(savedInstanceState)
    }

    protected fun setupThemedScreen(
        root: View,
        toolbar: MaterialToolbar,
        showBackButton: Boolean
    ) {
        themedRoot = root
        themedToolbar = toolbar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsets(root)

        val lightSystemBars = selectedTheme != AppTheme.DARK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }

        if (showBackButton) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.setNavigationOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        toolbar.setPopupTheme(selectedTheme.popupStyleRes)
        updateThemeMenu(toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            handleThemeMenuItem(item)
        }
    }

    private fun applySystemBarInsets(root: View) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun updateThemeMenu(toolbar: MaterialToolbar) {
        val theme = selectedTheme
        toolbar.menu.setGroupCheckable(R.id.theme_group, true, true)
        toolbar.menu.findItem(R.id.action_theme).title =
            getString(R.string.theme_menu_with_value, getString(theme.labelRes))

        listOf(
            R.id.theme_light to AppTheme.LIGHT,
            R.id.theme_dark to AppTheme.DARK,
            R.id.theme_burgundy to AppTheme.BURGUNDY
        ).forEach { (itemId, itemTheme) ->
            toolbar.menu.findItem(itemId).apply {
                isCheckable = true
                isChecked = itemTheme == theme
                title = if (itemTheme == theme) {
                    getString(R.string.theme_selected, getString(itemTheme.labelRes))
                } else {
                    getString(itemTheme.labelRes)
                }
            }
        }
    }

    private fun handleThemeMenuItem(item: MenuItem): Boolean {
        val theme = when (item.itemId) {
            R.id.theme_light -> AppTheme.LIGHT
            R.id.theme_dark -> AppTheme.DARK
            R.id.theme_burgundy -> AppTheme.BURGUNDY
            else -> return false
        }
        if (theme != selectedTheme) {
            AppThemeStore.save(this, theme)
            applyThemeWithoutRecreating(theme)
        }
        return true
    }

    private fun applyThemeWithoutRecreating(appTheme: AppTheme) {
        theme.applyStyle(appTheme.styleRes, true)

        val surface = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
        val surfaceVariant =
            resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        val onSurface = resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant =
            resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)
        val onPrimary = resolveThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val outline = resolveThemeColor(com.google.android.material.R.attr.colorOutline)

        window.statusBarColor = surface
        window.navigationBarColor = surface
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val lightBars = appTheme != AppTheme.DARK
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }

        themedRoot.setBackgroundColor(surface)
        tintViewTree(
            themedRoot,
            surface,
            surfaceVariant,
            onSurface,
            onSurfaceVariant,
            primary,
            onPrimary,
            outline
        )
        themedToolbar.setPopupTheme(appTheme.popupStyleRes)
        updateThemeMenu(themedToolbar)
    }

    private fun tintViewTree(
        view: View,
        surface: Int,
        surfaceVariant: Int,
        onSurface: Int,
        onSurfaceVariant: Int,
        primary: Int,
        onPrimary: Int,
        outline: Int
    ) {
        when (view) {
            is MaterialToolbar -> {
                view.setBackgroundColor(surface)
                view.setTitleTextColor(onSurface)
                view.navigationIcon?.let { icon ->
                    DrawableCompat.setTint(icon, onSurface)
                }
                view.overflowIcon?.let { icon ->
                    DrawableCompat.setTint(icon, onSurface)
                }
            }

            is MaterialButton -> tintButton(view, surface, primary, onPrimary)

            is TextInputLayout -> {
                view.boxBackgroundColor = Color.TRANSPARENT
                view.setBoxStrokeColorStateList(
                    ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_focused),
                            intArrayOf()
                        ),
                        intArrayOf(primary, outline)
                    )
                )
                view.defaultHintTextColor = ColorStateList.valueOf(onSurfaceVariant)
            }

            is EditText -> {
                view.setTextColor(onSurface)
                view.setHintTextColor(onSurfaceVariant)
                view.backgroundTintList = null
            }

            is TextView -> {
                when {
                    view.id == R.id.tvStatus -> Unit
                    view.id == R.id.tvAccessCode || view.id == R.id.tvTelegramLink ->
                        view.setTextColor(primary)
                    view.tag == TAG_SECONDARY || view.id == R.id.tvClientCount ->
                        view.setTextColor(onSurfaceVariant)
                    else -> view.setTextColor(onSurface)
                }
            }

            is MaterialCardView -> {
                val cardColor = if (view.tag == TAG_SURFACE_CARD) surface else surfaceVariant
                view.setCardBackgroundColor(cardColor)
                view.strokeColor = outline
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                tintViewTree(
                    view.getChildAt(index),
                    surface,
                    surfaceVariant,
                    onSurface,
                    onSurfaceVariant,
                    primary,
                    onPrimary,
                    outline
                )
            }
        }
    }

    private fun tintButton(
        button: MaterialButton,
        surface: Int,
        primary: Int,
        onPrimary: Int
    ) {
        when (button.id) {
            R.id.btnHost -> {
                button.backgroundTintList = ColorStateList.valueOf(primary)
                button.setTextColor(onPrimary)
            }

            else -> {
                button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                button.strokeColor = ColorStateList.valueOf(primary)
                button.setTextColor(primary)
            }
        }
    }

    private fun resolveThemeColor(@AttrRes attribute: Int): Int {
        val value = TypedValue()
        check(theme.resolveAttribute(attribute, value, true)) {
            "Theme color is missing"
        }
        return if (value.resourceId != 0) {
            ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    private companion object {
        const val TAG_SECONDARY = "theme_secondary"
        const val TAG_SURFACE_CARD = "theme_surface_card"
    }
}