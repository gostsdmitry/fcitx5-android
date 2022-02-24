package me.rocka.fcitx5test.keyboard

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rocka.fcitx5test.data.Prefs
import me.rocka.fcitx5test.core.CapabilityFlags
import me.rocka.fcitx5test.core.Fcitx
import me.rocka.fcitx5test.core.FcitxEvent
import me.rocka.fcitx5test.service.FcitxDaemonManager
import me.rocka.fcitx5test.utils.inputConnection
import splitties.bitflags.hasFlag
import timber.log.Timber

class FcitxInputMethodService : LifecycleInputMethodService() {

    private lateinit var inputView: InputView
    private lateinit var fcitx: Fcitx
    private var eventHandlerJob: Job? = null

    var editorInfo: EditorInfo? = null

    private var keyRepeatingJobs = hashMapOf<String, Job>()

    // `-1` means invalid, or don't know yet
    private var selectionStart = -1
    private var composingTextStart = -1
    private var composingText = ""
    private var fcitxCursor = -1

    override fun onCreate() {
        FcitxDaemonManager.bindFcitxDaemon(javaClass.name, this) {
            fcitx = getFcitxDaemon().fcitx
        }
        super.onCreate()
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.CommitStringEvent -> {
                inputConnection?.commitText(event.data, 1)
            }
            is FcitxEvent.KeyEvent -> event.data.let {
                if (Character.isISOControl(it.code)) {
                    when (it.code) {
                        '\b'.code -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        '\r'.code -> handleReturnKey()
                        else -> Timber.w("Handled unknown ISO Control key event: $it")
                    }
                } else {
                    sendKeyChar(Char(it.code))
                }
            }
            is FcitxEvent.PreeditEvent -> event.data.let {
                updateComposingTextWithCursor(it.clientPreedit, it.cursor)
            }
            else -> {
            }
        }
        inputView.handleFcitxEvent(event)
    }

    private fun handleReturnKey() {
        if (editorInfo == null || editorInfo!!.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            return
        }
        editorInfo!!.run {
            if (actionLabel?.isNotEmpty() == true) {
                actionId
            } else {
                imeOptions and EditorInfo.IME_MASK_ACTION
            }.let { inputConnection?.performEditorAction(it) }
        }
    }

    fun startRepeating(key: String) {
        if (keyRepeatingJobs.containsKey(key)) {
            return
        }
        keyRepeatingJobs[key] = lifecycleScope.launch {
            while (true) {
                fcitx.sendKey(key)
                delay(60L)
            }
        }
    }

    fun cancelRepeating(key: String) {
        keyRepeatingJobs.run {
            get(key)?.cancel()
            remove(key)
        }
    }

    private fun cancelRepeatingAll() {
        keyRepeatingJobs.forEach { cancelRepeating(it.key) }
    }

    override fun onCreateInputView(): View {
        if (eventHandlerJob == null)
            eventHandlerJob = fcitx
                .eventFlow
                .onEach { handleFcitxEvent(it) }
                .launchIn(lifecycleScope)
        inputView = InputView(this, fcitx)
        return inputView
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        inputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        editorInfo = attribute
        lifecycleScope.launch {
            fcitx.setCapFlags(CapabilityFlags.fromEditorInfo(editorInfo))
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        lifecycleScope.launch {
            if (restarting) {
                // when input restarts in the same editor, unfocus it to clear previous state
                fcitx.focus(false)
            }
            fcitx.focus()
        }
        inputView.onShow()
    }

    // FIXME: cursor flicker
    // because setComposingText(text, cursor) can only put cursor at end of composing,
    // sometimes onUpdateCursorAnchorInfo would receive event with wrong cursor position.
    // those events need to be filtered.
    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo?) {
        if (info == null) return
        selectionStart = info.selectionStart
        composingTextStart = info.composingTextStart
        Timber.d("AnchorInfo: selStart=$selectionStart cmpStart=$composingTextStart")
        val composing = info.composingText ?: return
        // check if cursor inside composing text
        if ((composingTextStart <= selectionStart) &&
            (selectionStart <= composingTextStart + composing.length)
        ) {
            if (!Prefs.getInstance().ignoreSystemCursor) {
                val position = selectionStart - composingTextStart
                // move fcitx cursor when:
                // - cursor position changed
                // - cursor position in composing text range; when user long press backspace key,
                //   onUpdateCursorAnchorInfo can be left behind, thus position is invalid.
                if ((position != fcitxCursor) && (position <= composingText.length)) {
                    lifecycleScope.launch {
                        fcitx.moveCursor(position)
                    }
                }
            }
        } else {
            // cursor outside composing range, finish composing as-is
            inputConnection?.finishComposingText()
            // `fcitx.reset()` here would commit preedit after new cursor position
            // since we have `ClientUnfocusCommit`, focus out and in would do the trick
            lifecycleScope.launch {
                fcitx.focus(false)
                fcitx.focus()
            }
        }
    }

    // because of https://android.googlesource.com/platform/frameworks/base.git/+/refs/tags/android-11.0.0_r45/core/java/android/view/inputmethod/BaseInputConnection.java#851
    // it's not possible to set cursor inside composing text
    private fun updateComposingTextWithCursor(text: String, cursor: Int) {
        fcitxCursor = cursor
        inputConnection?.run {
            if (text != composingText) {
                composingText = text
                // set composing text AND put cursor at end of composing
                setComposingText(text, 1)
                if (cursor == text.length) {
                    // cursor already at end of composing, skip cursor reposition
                    return
                }
            }
            if (Prefs.getInstance().ignoreSystemCursor || (cursor < 0)) return
            // when user starts typing and there is no composing text, composingTextStart would be -1
            val p = cursor + composingTextStart
            Timber.d("TextWithCursor: p=$p composingStart=$composingTextStart")
            if (p != selectionStart) {
                Timber.d("TextWithCursor: move cursor $p")
                selectionStart = p
                setSelection(p, p)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelRepeatingAll()
        inputConnection?.finishComposingText()
        lifecycleScope.launch {
            fcitx.focus(false)
        }
    }

    override fun onFinishInput() {
        inputConnection?.requestCursorUpdates(0)
        editorInfo = null
        lifecycleScope.launch {
            fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.d("onUnbind")
        if (this::fcitx.isInitialized && fcitx.isReady)
            runBlocking {
                fcitx.save()
            }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        FcitxDaemonManager.unbind(javaClass.name)
        eventHandlerJob?.cancel()
        eventHandlerJob = null
        super.onDestroy()
    }

    companion object {
        val isBoundToFcitxDaemon: Boolean
            get() = FcitxDaemonManager.hasConnection(FcitxInputMethodService::javaClass.name)
    }
}