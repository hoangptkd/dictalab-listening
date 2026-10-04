package com.dictalab.service;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancel oversized provider responses before buffering them in memory. */
final class BoundedProviderBody {
    private BoundedProviderBody() {}
    static HttpResponse.BodySubscriber<byte[]> bytes(int maximum) {
        var delegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long size;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
            public void onNext(List<ByteBuffer> chunks) {
                size += chunks.stream().mapToLong(ByteBuffer::remaining).sum();
                if (size > maximum) {
                    subscription.cancel(); delegate.onError(new IllegalStateException("Provider response too large"));
                } else delegate.onNext(chunks);
            }
            public void onError(Throwable error) { delegate.onError(error); }
            public void onComplete() { delegate.onComplete(); }
        };
    }
}
