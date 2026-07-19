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
package com.moez.QKSMS.feature.compose

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.moez.QKSMS.R
import com.moez.QKSMS.common.Navigator
import com.moez.QKSMS.common.base.QkThemedActivity
import com.moez.QKSMS.common.util.DateFormatter
import com.moez.QKSMS.common.util.extensions.autoScrollToStart
import com.moez.QKSMS.common.util.extensions.dismissKeyboard
import com.moez.QKSMS.common.util.extensions.hideKeyboard
import com.moez.QKSMS.common.util.extensions.resolveThemeColor
import com.moez.QKSMS.common.util.extensions.scrapViews
import com.moez.QKSMS.common.util.extensions.setBackgroundTint
import com.moez.QKSMS.common.util.extensions.setTint
import com.moez.QKSMS.common.util.extensions.setVisible
import com.moez.QKSMS.common.util.extensions.showKeyboard
import timber.log.Timber
import com.moez.QKSMS.feature.compose.editing.ChipsAdapter
import com.moez.QKSMS.feature.contacts.ContactsActivity
import com.moez.QKSMS.model.Attachment
import com.moez.QKSMS.model.Recipient
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.compose_activity.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.collections.HashMap

class ComposeActivity : QkThemedActivity(), ComposeView {

    companion object {
        private const val SelectContactRequestCode = 0
        private const val TakePhotoRequestCode = 1
        private const val AttachPhotoRequestCode = 2
        private const val AttachContactRequestCode = 3

        private const val CameraDestinationKey = "camera_destination"
    }

    @Inject lateinit var attachmentAdapter: AttachmentAdapter
    @Inject lateinit var chipsAdapter: ChipsAdapter
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var messageAdapter: MessagesAdapter
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    override val activityVisibleIntent: Subject<Boolean> = PublishSubject.create()
    override val chipsSelectedIntent: Subject<HashMap<String, String?>> = PublishSubject.create()
    override val chipDeletedIntent: Subject<Recipient> by lazy { chipsAdapter.chipDeleted }
    override val menuReadyIntent: Observable<Unit> = menu.map { Unit }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val sendAsGroupIntent by lazy { sendAsGroupBackground.clicks() }
    override val messageClickIntent: Subject<Long> by lazy { messageAdapter.clicks }
    override val messagePartClickIntent: Subject<Long> by lazy { messageAdapter.partClicks }
    override val messagesSelectedIntent by lazy { messageAdapter.selectionChanges }
    override val cancelSendingIntent: Subject<Long> by lazy { messageAdapter.cancelSending }
    override val attachmentDeletedIntent: Subject<Attachment> by lazy { attachmentAdapter.attachmentDeleted }
    override val textChangedIntent by lazy { message.textChanges() }
    override val attachIntent by lazy { Observable.merge(attach.clicks(), attachingBackground.clicks()) }
    override val cameraIntent by lazy { Observable.merge(camera.clicks(), cameraLabel.clicks()) }
    override val galleryIntent by lazy { Observable.merge(gallery.clicks(), galleryLabel.clicks()) }
    override val scheduleIntent by lazy { Observable.merge(schedule.clicks(), scheduleLabel.clicks()) }
    override val attachContactIntent by lazy { Observable.merge(contact.clicks(), contactLabel.clicks()) }
    override val attachmentSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val contactSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val inputContentIntent by lazy { message.inputContentSelected }
    override val scheduleSelectedIntent: Subject<Long> = PublishSubject.create()
    override val changeSimIntent by lazy { sim.clicks() }
    override val scheduleCancelIntent by lazy { scheduledCancel.clicks() }
    override val sendIntent by lazy { send.clicks() }
    override val viewQksmsPlusIntent: Subject<Unit> = PublishSubject.create()
    override val backPressedIntent: Subject<Unit> = PublishSubject.create()

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[ComposeViewModel::class.java] }

    private var cameraDestination: Uri? = null
    private var lastState: ComposeState? = null
    private var initialFocusDone = false
    private var lastThreadId = 0L
    private val hasCamera by lazy {
        try {
            val cameraManager = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cameraManager.cameraIdList.isNotEmpty()
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to get camera list")
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.compose_activity)
        showBackButton(true)
        viewModel.bindView(this)

        contentView.layoutTransition = android.animation.LayoutTransition().apply {
            disableTransitionType(android.animation.LayoutTransition.CHANGING)
            disableTransitionType(android.animation.LayoutTransition.CHANGE_APPEARING)
            disableTransitionType(android.animation.LayoutTransition.CHANGE_DISAPPEARING)
        }

        chipsAdapter.view = chips

        chips.itemAnimator = null
        chips.layoutManager = FlexboxLayoutManager(this)

        messageAdapter.autoScrollToStart(messageList)
        messageAdapter.emptyView = messagesEmpty

        messageList.setHasFixedSize(true)
        messageList.adapter = messageAdapter

        attachments.adapter = attachmentAdapter

        message.supportsInputContent = true
        message.privateImeOptions = "KC_IME_RSK_LABEL:${getString(R.string.sk_details)},KC_IME_RSK_ENABLE_MIN_LENGTH:0"

        theme
                .doOnNext { loading.setTint(it.theme) }
                .doOnNext { attach.setBackgroundTint(it.theme) }
                .doOnNext { attach.setTint(it.textPrimary) }
                .doOnNext { messageAdapter.theme = it }
                .autoDisposable(scope())
                .subscribe()

        window.callback = ComposeWindowCallback(window.callback, this)

        message.setOnFocusChangeListener { _, _ ->
            refreshSoftkeys()
        }

        // These theme attributes don't apply themselves on API 21
        if (Build.VERSION.SDK_INT <= 22) {
            messageBackground.setBackgroundTint(resolveThemeColor(R.attr.bubbleColor))
        }

        // Prevent D-pad focus from getting stuck on menu labels or background
        listOf(cameraLabel, galleryLabel, scheduleLabel, contactLabel, attachingBackground).forEach { 
            it.isFocusable = false 
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisibleIntent.onNext(true)
    }

    override fun onPause() {
        super.onPause()
        activityVisibleIntent.onNext(false)
    }

    override fun render(state: ComposeState) {
        lastState = state
        if (state.hasError) {
            finish()
            return
        }

        if (!initialFocusDone && state.threadId > 0 && !state.loading) {
            initialFocusDone = true
            message.post {
                if (!message.hasFocus()) {
                    message.requestFocus()
                    showKeyboard()
                }
            }
        }
        lastThreadId = state.threadId

        threadId.onNext(state.threadId)

        title = when {
            state.selectedMessages > 0 -> getString(R.string.compose_title_selected, state.selectedMessages)
            state.query.isNotEmpty() -> state.query
            else -> state.conversationtitle
        }

        toolbarSubtitle.setVisible(state.query.isNotEmpty())
        toolbarSubtitle.text = getString(R.string.compose_subtitle_results, state.searchSelectionPosition,
                state.searchResults)

        toolbarTitle.setVisible(!state.editingMode)
        chips.setVisible(state.editingMode)
        composeBar.setVisible(!state.loading)

        // Don't set the adapters unless needed
        if (state.editingMode && chips.adapter == null) chips.adapter = chipsAdapter

        toolbar.menu.findItem(R.id.add)?.isVisible = state.editingMode
        toolbar.menu.findItem(R.id.info)?.isVisible = !state.editingMode && state.selectedMessages == 0
                && state.query.isEmpty()
        toolbar.menu.findItem(R.id.copy)?.isVisible = !state.editingMode && state.selectedMessages > 0
        toolbar.menu.findItem(R.id.details)?.isVisible = !state.editingMode && state.selectedMessages == 1
        toolbar.menu.findItem(R.id.delete)?.isVisible = !state.editingMode && state.selectedMessages > 0
        toolbar.menu.findItem(R.id.forward)?.isVisible = !state.editingMode && state.selectedMessages == 1
        toolbar.menu.findItem(R.id.previous)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()
        toolbar.menu.findItem(R.id.next)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()
        toolbar.menu.findItem(R.id.clear)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()

        chipsAdapter.data = state.selectedChips

        loading.setVisible(state.loading)

        sendAsGroup.setVisible(state.editingMode && state.selectedChips.size >= 2)
        sendAsGroupSwitch.isChecked = state.sendAsGroup

        messageList.setVisible(!state.editingMode || state.sendAsGroup || state.selectedChips.size == 1)
        messageAdapter.data = state.messages
        messageAdapter.highlight = state.searchSelectionId

        scheduledGroup.isVisible = state.scheduled != 0L
        scheduledTime.text = dateFormatter.getScheduledTimestamp(state.scheduled)

        attachments.setVisible(state.attachments.isNotEmpty())
        attachmentAdapter.data = state.attachments

        val wasAttaching = attaching.visibility == View.VISIBLE
        if (wasAttaching != state.attaching) {
            attach.animate().rotation(if (state.attaching) 135f else 0f).start()
            attaching.isVisible = state.attaching
        }

        // Manage camera option visibility manually since it was removed from the attaching Group
        val showCamera = hasCamera && state.attaching
        camera.isVisible = showCamera
        cameraLabel.isVisible = showCamera
        
        if (state.attaching) {
            // Build the focusable list dynamically (camera may be hidden)
            val options = listOfNotNull(
                if (hasCamera) camera else null,
                gallery,
                schedule,
                contact
            ) // ordered bottom-to-top as they appear on screen

            // Wire up/down navigation to cycle only within the menu
            for (i in options.indices) {
                val view = options[i]
                val above = options.getOrNull(i + 1) ?: attach
                val below = options.getOrNull(i - 1) ?: attach
                view.nextFocusUpId   = above.id
                view.nextFocusDownId = below.id
                view.nextFocusLeftId = view.id   // block left escape
                view.nextFocusRightId = view.id  // block right escape
            }
            // attach (X) cycles back into the bottom-most option
            attach.nextFocusUpId   = options.first().id
            attach.nextFocusDownId = options.last().id
            attach.nextFocusLeftId = attach.id
            attach.nextFocusRightId = attach.id
        } else {
            // Reset all next-focus overrides
            listOf(camera, gallery, schedule, contact, attach).forEach { v ->
                v.nextFocusUpId   = View.NO_ID
                v.nextFocusDownId = View.NO_ID
                v.nextFocusLeftId = View.NO_ID
                v.nextFocusRightId = View.NO_ID
            }
        }
        
        if (state.attaching && !wasAttaching) {
            // Disable focus on background elements to trap D-pad in the menu
            message.isFocusable = false
            message.isFocusableInTouchMode = false
            send.isFocusable = false
            sim.isFocusable = false
            messageList.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS

            // Focus the bottom-most visible option
            (if (hasCamera) camera else gallery).requestFocus()
        } else if (!state.attaching && wasAttaching) {
            // Restore focusability when menu closes
            message.isFocusable = true
            message.isFocusableInTouchMode = true
            send.isFocusable = true
            sim.isFocusable = true
            messageList.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS

            message.post {
                message.requestFocus()
                showKeyboard()
            }
        }

        counter.text = state.remaining
        counter.setVisible(counter.text.isNotBlank())

        sim.setVisible(state.subscription != null)
        sim.contentDescription = getString(R.string.compose_sim_cd, state.subscription?.displayName)
        simIndex.text = state.subscription?.simSlotIndex?.plus(1)?.toString()

        send.isEnabled = state.canSend
        send.imageAlpha = if (state.canSend) 255 else 128

        refreshSoftkeys()
    }

    override fun clearSelection() = messageAdapter.clearSelection()

    override fun showDetails(details: String) {
        AlertDialog.Builder(this)
                .setTitle(R.string.compose_details_title)
                .setMessage(details)
                .setCancelable(true)
                .show()
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(this)
    }

    override fun requestStoragePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
    }

    override fun requestSmsPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS), 0)
    }

    override fun requestDatePicker() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this, DatePickerDialog.OnDateSetListener { _, year, month, day ->
            TimePickerDialog(this, TimePickerDialog.OnTimeSetListener { _, hour, minute ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                scheduleSelectedIntent.onNext(calendar.timeInMillis)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), DateFormat.is24HourFormat(this))
                    .show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()

        // On some devices, the keyboard can cover the date picker
        message.hideKeyboard()
    }

    override fun requestContact() {
        val intent = Intent(Intent.ACTION_PICK)
                .setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

        startActivityForResult(Intent.createChooser(intent, null), AttachContactRequestCode)
    }

    override fun showContacts(sharing: Boolean, chips: List<Recipient>) {
        message.hideKeyboard()
        val serialized = HashMap(chips.associate { chip -> chip.address to chip.contact?.lookupKey })
        val intent = Intent(this, ContactsActivity::class.java)
                .putExtra(ContactsActivity.SharingKey, sharing)
                .putExtra(ContactsActivity.ChipsKey, serialized)
        startActivityForResult(intent, SelectContactRequestCode)
    }

    override fun themeChanged() {
        messageList.scrapViews()
    }

    override fun showKeyboard() {
        message.postDelayed({
            message.showKeyboard()
        }, 200)
    }

    override fun requestCamera() {
        cameraDestination = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                .let { timestamp -> ContentValues().apply { put(MediaStore.Images.Media.TITLE, timestamp) } }
                .let { cv -> contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, cameraDestination)
        startActivityForResult(Intent.createChooser(intent, null), TakePhotoRequestCode)
    }

    override fun requestGallery() {
        val intent = Intent(Intent.ACTION_PICK)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setType("image/*")
        startActivityForResult(Intent.createChooser(intent, null), AttachPhotoRequestCode)
    }

    override fun setDraft(draft: String) {
        message.setText(draft)
        message.setSelection(draft.length)
    }

    override fun scrollToMessage(id: Long) {
        messageAdapter.data?.second
                ?.indexOfLast { message -> message.id == id }
                ?.takeIf { position -> position != -1 }
                ?.let(messageList::scrollToPosition)
    }

    override fun showQksmsPlusSnackbar(message: Int) {
        Snackbar.make(contentView, message, Snackbar.LENGTH_LONG).run {
            setAction(R.string.button_more) { viewQksmsPlusIntent.onNext(Unit) }
            setActionTextColor(colors.theme().theme)
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.compose, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun getColoredMenuItems(): List<Int> {
        return super.getColoredMenuItems()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == SelectContactRequestCode -> {
                chipsSelectedIntent.onNext(data?.getSerializableExtra(ContactsActivity.ChipsKey)
                        ?.let { serializable -> serializable as? HashMap<String, String?> }
                        ?: hashMapOf())
            }
            requestCode == TakePhotoRequestCode && resultCode == Activity.RESULT_OK -> {
                cameraDestination?.let(attachmentSelectedIntent::onNext)
            }
            requestCode == AttachPhotoRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.clipData?.itemCount
                        ?.let { count -> 0 until count }
                        ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                        ?.forEach(attachmentSelectedIntent::onNext)
                        ?: data?.data?.let(attachmentSelectedIntent::onNext)
            }
            requestCode == AttachContactRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.data?.let(contactSelectedIntent::onNext)
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(CameraDestinationKey, cameraDestination)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        cameraDestination = savedInstanceState?.getParcelable(CameraDestinationKey)
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                val focusedChild = messageList.focusedChild
                if (focusedChild != null) {
                    val isDown = keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    
                    val childLoc = IntArray(2)
                    focusedChild.getLocationOnScreen(childLoc)
                    val childTop = childLoc[1]
                    val childBottom = childTop + focusedChild.height
                    
                    val recyclerLoc = IntArray(2)
                    messageList.getLocationOnScreen(recyclerLoc)
                    val recyclerTop = recyclerLoc[1]
                    val recyclerBottom = recyclerTop + messageList.height

                    if (isDown && childBottom > recyclerBottom && messageList.canScrollVertically(1)) {
                        messageList.scrollBy(0, messageList.height / 3)
                        return true
                    }

                    if (!isDown && childTop < recyclerTop && messageList.canScrollVertically(-1)) {
                        messageList.scrollBy(0, -(messageList.height / 3))
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() = backPressedIntent.onNext(Unit)

    override fun onCsk() {
        val state = lastState ?: return
        val focus = window.currentFocus
        if (state.selectedMessages > 0) {
            window.currentFocus?.performClick()
        } else if (focus != null && focus != messageList && focus.isClickable && focus != message) {
            focus.performClick()
        } else if (message.hasFocus()) {
            if (send.isEnabled) {
                send.performClick()
            }
        } else {
            message.requestFocus()
            showKeyboard()
        }
    }

    override fun onSk1() {
        val state = lastState ?: return
        if (state.selectedMessages > 0) {
            // Do nothing, the left softkey is blank during selection mode
        } else if (!message.hasFocus()) {
            attach.performClick()
        }
    }

    override fun onSk2() {
        val state = lastState ?: return
        if (state.selectedMessages > 0) {
            toolbar.showOverflowMenu()
        } else {
            optionsItemIntent.onNext(R.id.info)
        }
    }

    override fun refreshSoftkeys() {
        if (!kyoceraHelper.isReady()) return
        val state = lastState ?: return

        when {
            state.selectedMessages > 0 -> kyoceraHelper.apply(
                com.moez.QKSMS.common.util.KyoceraSoftkeyHelper.PRIORITY_LIST,
                getString(R.string.sk_select),
                "",
                getString(R.string.sk_options)
            )
            state.attaching -> kyoceraHelper.apply(
                com.moez.QKSMS.common.util.KyoceraSoftkeyHelper.PRIORITY_LIST,
                getString(R.string.sk_select),
                "",
                ""
            )
            message.hasFocus() -> kyoceraHelper.apply(
                com.moez.QKSMS.common.util.KyoceraSoftkeyHelper.PRIORITY_TEXT,
                getString(R.string.sk_ok),
                "",
                getString(R.string.sk_details)
            )
            else -> kyoceraHelper.apply(
                com.moez.QKSMS.common.util.KyoceraSoftkeyHelper.PRIORITY_LIST,
                getString(R.string.sk_ok),
                "",
                getString(R.string.sk_details)
            )
        }
    }
}
