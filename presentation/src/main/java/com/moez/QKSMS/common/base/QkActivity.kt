/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.common.base

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.moez.QKSMS.R
import com.moez.QKSMS.common.util.KyoceraSoftkeyHelper
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.toolbar.*

abstract class QkActivity : AppCompatActivity() {

    protected val menu: Subject<Menu> = BehaviorSubject.create()
    protected val kyoceraHelper = KyoceraSoftkeyHelper()

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onNewIntent(intent)
    }

    private var isCenterDown = false
    private var centerDownHandled = false
    private val longPressRunnable = Runnable {
        if (isCenterDown && !centerDownHandled) {
            centerDownHandled = true
            val focus = window.currentFocus
            if (focus != null && focus.isLongClickable) {
                focus.performLongClick()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!isCenterDown) {
                    isCenterDown = true
                    centerDownHandled = false
                    window.decorView.postDelayed(longPressRunnable, 500)
                }
                return true // Consume all DOWN events to prevent native Android repeats/clicks
            } else if (event.action == KeyEvent.ACTION_UP) {
                isCenterDown = false
                window.decorView.removeCallbacks(longPressRunnable)
                if (!centerDownHandled) {
                    // Short press - trigger our custom softkey logic
                    onCsk()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        kyoceraHelper.init(
            window,
            onCsk = { onCsk() },
            onSk1 = { onSk1() },
            onSk2 = { onSk2() }
        )
        refreshSoftkeys()
    }

    open fun onCsk() {
        window.currentFocus?.performClick()
    }

    open fun onSk1() {
        // Default: no-op
    }

    open fun onSk2() {
        openOptionsMenu()
    }

    open fun refreshSoftkeys() {
        if (kyoceraHelper.isReady()) {
            val cskLabel = getString(R.string.sk_select)
            val sk1Label = ""
            val sk2Label = "@SK_AUTO_MENU"
            kyoceraHelper.apply(KyoceraSoftkeyHelper.PRIORITY_LIST, cskLabel, sk1Label, sk2Label)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (kyoceraHelper.isReady()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount > 0) {
                        window.currentFocus?.performLongClick()
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        setSupportActionBar(toolbar)
        title = title // The title may have been set before layout inflation
    }

    override fun setTitle(titleId: Int) {
        title = getString(titleId)
    }

    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        toolbarTitle?.text = title
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val result = super.onCreateOptionsMenu(menu)
        if (menu != null) {
            this.menu.onNext(menu)
        }
        return result
    }

    protected open fun showBackButton(show: Boolean) {
        supportActionBar?.setDisplayHomeAsUpEnabled(show)
    }

}