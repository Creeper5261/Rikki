package com.zzf.rikki.idea.completion

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Manually triggers Rikki AI inline completion.
 * Default shortcut: Alt+\ (backslash) — does not conflict with IntelliJ's built-in keymap.
 */
class RikkiTriggerCompletionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val handler = InlineCompletion.getHandlerOrNull(editor) ?: return
        val caret = editor.caretModel.currentCaret
        val event = InlineCompletionEvent.DirectCall(editor, caret, e.dataContext)
        handler.invokeEvent(event)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
    }
}
