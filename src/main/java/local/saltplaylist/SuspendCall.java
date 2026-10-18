package local.saltplaylist;

import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Invokes a resolved native suspend API; never hooks its suspend state machine. */
public final class SuspendCall {
    private SuspendCall() {}

    public static Object invoke(Method method, Object receiver, Job job, Object... arguments) throws Exception {
        JobKt.ensureActive(job);
        CompletableFuture<Object> result = new CompletableFuture<>();
        Continuation<Object> continuation = new Continuation<>() {
            @Override public CoroutineContext getContext() { return job; }
            @Override public void resumeWith(Object value) {
                try {
                    ResultKt.throwOnFailure(value);
                    result.complete(value);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            }
        };
        Object[] args = Arrays.copyOf(arguments, arguments.length + 1);
        args[arguments.length] = continuation;
        try {
            Object returned = method.invoke(receiver, args);
            if (returned != IntrinsicsKt.getCOROUTINE_SUSPENDED()) result.complete(returned);
            return result.get();
        } catch (InvocationTargetException failure) {
            throw unwrap(failure.getCause());
        } catch (ExecutionException failure) {
            throw unwrap(failure.getCause());
        } catch (InterruptedException interrupted) {
            job.cancel(new java.util.concurrent.CancellationException("Import worker interrupted"));
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static Exception unwrap(Throwable failure) {
        if (failure instanceof Error error) throw error;
        return failure instanceof Exception exception ? exception : new RuntimeException(failure);
    }
}
