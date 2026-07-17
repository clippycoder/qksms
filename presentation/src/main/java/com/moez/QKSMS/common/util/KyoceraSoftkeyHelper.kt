package com.moez.QKSMS.common.util

import android.app.Dialog
import android.view.Window
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class KyoceraSoftkeyHelper {

    companion object {
        const val INDEX_CSK = 0
        const val INDEX_SK1 = 1
        const val INDEX_SK2 = 2

        const val PRIORITY_DEFAULT = 0
        const val PRIORITY_FW = 1
        const val PRIORITY_LIST = 2
        const val PRIORITY_DIALOG = 3
        const val PRIORITY_TEXT = 4

        private const val GUIDE_CLASS = "jp.kyocera.kcfp.util.KCfpSoftkeyGuide"
        private const val LISTENER_CLASS = "jp.kyocera.kcfp.util.KCfpSoftkeyGuide\$SoftkeyEventListener"

        fun configureDialog(dialog: Dialog) {
            try {
                // Find positive and negative buttons
                val positiveBtn = (dialog as? androidx.appcompat.app.AlertDialog)?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    ?: (dialog as? android.app.AlertDialog)?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                val negativeBtn = (dialog as? androidx.appcompat.app.AlertDialog)?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                    ?: (dialog as? android.app.AlertDialog)?.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                
                val confirmText = positiveBtn?.text ?: ""
                val cancelText = negativeBtn?.text ?: ""
                
                if (confirmText.isNotEmpty() || cancelText.isNotEmpty()) {
                    val cls = Class.forName(GUIDE_CLASS)
                    val listenerCls = Class.forName(LISTENER_CLASS)
                    val getSoftkeyGuide = cls.getMethod("getSoftkeyGuide", Window::class.java)
                    val dGuide = getSoftkeyGuide.invoke(null, dialog.window) ?: return
                    
                    val setTxt = cls.getMethod("setText", Int::class.java, CharSequence::class.java)
                    val setEn = cls.getMethod("setEnabled", Int::class.java, Boolean::class.java)
                    val setPri = cls.getMethod("setPriority", Int::class.java, Int::class.java)
                    val inv = cls.getMethod("invalidate")
                    val setLst = cls.getMethod("setSoftkeyEventListener", Int::class.java, listenerCls)
                    
                    setPri.invoke(dGuide, 0, PRIORITY_DIALOG)
                    setPri.invoke(dGuide, 1, PRIORITY_DIALOG)
                    setPri.invoke(dGuide, 2, PRIORITY_DIALOG)
                    
                    if (confirmText.isNotEmpty()) {
                        setTxt.invoke(dGuide, INDEX_CSK, confirmText)
                        setEn.invoke(dGuide, INDEX_CSK, true)
                        
                        val handler = Proxy.newProxyInstance(
                            listenerCls.classLoader,
                            arrayOf(listenerCls),
                            object : InvocationHandler {
                                override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                                    if (method.name == "onSoftkeySelected") {
                                        positiveBtn?.performClick()
                                    }
                                    return null
                                }
                            }
                        )
                        setLst.invoke(dGuide, INDEX_CSK, handler)
                    } else {
                        setEn.invoke(dGuide, INDEX_CSK, false)
                    }
                    
                    setTxt.invoke(dGuide, INDEX_SK1, "")
                    setEn.invoke(dGuide, INDEX_SK1, false)
                    
                    if (cancelText.isNotEmpty()) {
                        setTxt.invoke(dGuide, INDEX_SK2, cancelText)
                        setEn.invoke(dGuide, INDEX_SK2, true)
                        
                        val handler = Proxy.newProxyInstance(
                            listenerCls.classLoader,
                            arrayOf(listenerCls),
                            object : InvocationHandler {
                                override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                                    if (method.name == "onSoftkeySelected") {
                                        negativeBtn?.performClick()
                                    }
                                    return null
                                }
                            }
                        )
                        setLst.invoke(dGuide, INDEX_SK2, handler)
                    } else {
                        setEn.invoke(dGuide, INDEX_SK2, false)
                    }
                    
                    inv.invoke(dGuide)
                }
            } catch (e: Exception) {
                // Ignored
            }
        }
    }

    private var guide: Any? = null
    private var setTextMethod: Method? = null
    private var setEnabledMethod: Method? = null
    private var setPriorityMethod: Method? = null
    private var invalidateMethod: Method? = null
    private var ready = false

    fun isReady(): Boolean = ready

    fun init(
        window: Window,
        onCsk: (() -> Unit)? = null,
        onSk1: (() -> Unit)? = null,
        onSk2: (() -> Unit)? = null
    ) {
        if (ready) return
        try {
            val cls = Class.forName(GUIDE_CLASS)
            val getSoftkeyGuide = cls.getMethod("getSoftkeyGuide", Window::class.java)
            guide = getSoftkeyGuide.invoke(null, window)
            if (guide == null) {
                ready = true // Stop retrying on non-Kyocera devices
                return
            }

            setTextMethod = cls.getMethod("setText", Int::class.java, CharSequence::class.java)
            setEnabledMethod = cls.getMethod("setEnabled", Int::class.java, Boolean::class.java)
            setPriorityMethod = cls.getMethod("setPriority", Int::class.java, Int::class.java)
            invalidateMethod = cls.getMethod("invalidate")

            wireListeners(cls, onCsk, onSk1, onSk2)
            ready = true
        } catch (e: Exception) {
            guide = null
            ready = false
        }
    }

    private var cachedPriority = -1
    private var cachedCsk = ""
    private var cachedSk1 = ""
    private var cachedSk2 = ""

    fun apply(priority: Int, csk: CharSequence, sk1: CharSequence, sk2: CharSequence) {
        val guideObj = guide ?: return
        val cskStr = csk.toString()
        val sk1Str = sk1.toString()
        val sk2Str = sk2.toString()

        // Skip if nothing changed — avoids resetting the Kyocera XT9 menu
        if (priority == cachedPriority && cskStr == cachedCsk && sk1Str == cachedSk1 && sk2Str == cachedSk2) {
            return
        }
        cachedPriority = priority
        cachedCsk = cskStr
        cachedSk1 = sk1Str
        cachedSk2 = sk2Str
        
        // CSK is disabled when empty or is @SK_AUTO_ENTER (system handles it)
        val cskEnabled = cskStr.isNotEmpty() && cskStr != "@SK_AUTO_ENTER"
        
        try {
            setPriorityMethod?.invoke(guideObj, 0, priority)
            setPriorityMethod?.invoke(guideObj, 1, priority)
            setPriorityMethod?.invoke(guideObj, 2, priority)
            
            setTextMethod?.invoke(guideObj, 0, csk)
            setTextMethod?.invoke(guideObj, 1, sk1)
            setTextMethod?.invoke(guideObj, 2, sk2)
            
            setEnabledMethod?.invoke(guideObj, 0, cskEnabled)
            setEnabledMethod?.invoke(guideObj, 1, sk1Str.isNotEmpty())
            setEnabledMethod?.invoke(guideObj, 2, sk2Str.isNotEmpty())
            
            invalidateMethod?.invoke(guideObj)
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun wireListeners(
        cls: Class<*>,
        onCsk: (() -> Unit)?,
        onSk1: (() -> Unit)?,
        onSk2: (() -> Unit)?
    ) {
        val guideObj = guide ?: return
        try {
            val listenerCls = Class.forName(LISTENER_CLASS)
            val setListener = cls.getMethod("setSoftkeyEventListener", Int::class.java, listenerCls)

            val handler = { action: (() -> Unit)? ->
                Proxy.newProxyInstance(
                    listenerCls.classLoader,
                    arrayOf(listenerCls),
                    object : InvocationHandler {
                        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                            if (method.name == "onSoftkeySelected") {
                                action?.invoke()
                            }
                            return null
                        }
                    }
                )
            }

            if (onCsk != null) {
                setListener.invoke(guideObj, INDEX_CSK, handler(onCsk))
            }
            if (onSk1 != null) {
                setListener.invoke(guideObj, INDEX_SK1, handler(onSk1))
            }
            if (onSk2 != null) {
                setListener.invoke(guideObj, INDEX_SK2, handler(onSk2))
            }
        } catch (e: Exception) {
            // Ignored
        }
    }
}
