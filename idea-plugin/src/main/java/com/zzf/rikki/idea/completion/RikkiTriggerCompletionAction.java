package com.zzf.rikki.idea.completion;

import com.intellij.codeInsight.inline.completion.InlineCompletion;
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;

public class RikkiTriggerCompletionAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }
        var handler = InlineCompletion.INSTANCE.getHandlerOrNull(editor);
        if (handler == null) {
            return;
        }
        handler.invokeEvent(new InlineCompletionEvent.DirectCall(editor, editor.getCaretModel().getCurrentCaret(), event.getDataContext()));
    }

    @Override
    public void update(AnActionEvent event) {
        event.getPresentation().setEnabled(event.getData(CommonDataKeys.EDITOR) != null);
    }
}
