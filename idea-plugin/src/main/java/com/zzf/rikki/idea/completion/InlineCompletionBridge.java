package com.zzf.rikki.idea.completion;

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement;
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement;
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion;
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion;
import com.intellij.openapi.util.UserDataHolderBase;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function3;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.FlowCollector;

import java.util.concurrent.CancellationException;

final class InlineCompletionBridge {
    private InlineCompletionBridge() {
    }

    static InlineCompletionSuggestion emptySuggestion() {
        return InlineCompletionSuggestion.Empty.INSTANCE;
    }

    static InlineCompletionSuggestion singleSuggestion(String text) {
        return InlineCompletionSingleSuggestion.Companion.build(
                new UserDataHolderBase(),
                new Function3<FlowCollector<? super InlineCompletionElement>, UserDataHolderBase, Continuation<? super Unit>, Object>() {
                    @Override
                    public Object invoke(
                            FlowCollector<? super InlineCompletionElement> collector,
                            UserDataHolderBase data,
                            Continuation<? super Unit> continuation
                    ) {
                        return collector.emit(new InlineCompletionGrayTextElement(text), continuation);
                    }
                }
        );
    }

    static void sleepWithCancellation(Continuation<?> continuation, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            ensureActive(continuation);
            long remainingMillis = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            try {
                Thread.sleep(Math.min(25L, remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Inline completion interrupted");
            }
        }
        ensureActive(continuation);
    }

    static void ensureActive(Continuation<?> continuation) {
        if (continuation == null) {
            return;
        }
        Job job = continuation.getContext().get(Job.Key);
        if (job != null && !job.isActive()) {
            throw new CancellationException("Inline completion cancelled");
        }
    }
}
