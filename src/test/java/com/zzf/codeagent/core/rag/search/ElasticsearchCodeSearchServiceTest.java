package com.zzf.codeagent.core.rag.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ElasticsearchCodeSearchServiceTest {

    @Test
    public void testEmbeddingCacheAvoidsDuplicateCalls() {
        CountingEmbeddingService embeddingService = new CountingEmbeddingService();
        FakeHttpClient http = FakeHttpClient.single(200, bodyWithHit());
        ElasticsearchCodeSearchService service = new ElasticsearchCodeSearchService(http, URI.create("http://localhost:9200"), "idx", new ObjectMapper(), embeddingService);

        CodeSearchResponse first = service.search(new CodeSearchQuery("UserService", 3, 200));
        CodeSearchResponse second = service.search(new CodeSearchQuery("UserService", 3, 200));

        assertFalse(first.getHits().isEmpty());
        assertEquals(1, embeddingService.calls.get());
        assertFalse(second.getHits().isEmpty());
        assertEquals(1, embeddingService.calls.get());
    }

    @Test
    public void testRetryOnFailureThenSuccess() {
        CountingEmbeddingService embeddingService = new CountingEmbeddingService();
        FakeHttpClient http = new FakeHttpClient(
                new FakeHttpResponse(500, "{\"hits\":{\"hits\":[]}}"),
                new FakeHttpResponse(200, bodyWithHit())
        );
        ElasticsearchCodeSearchService service = new ElasticsearchCodeSearchService(http, URI.create("http://localhost:9200"), "idx", new ObjectMapper(), embeddingService);

        CodeSearchResponse resp = service.search(new CodeSearchQuery("UserService", 3, 200));

        assertFalse(resp.getHits().isEmpty());
        assertEquals(2, http.calls.get());
    }

    private static String bodyWithHit() {
        return "{\"hits\":{\"hits\":[{\"_source\":{\"filePath\":\"src/UserService.java\",\"symbolKind\":\"class\",\"symbolName\":\"UserService\",\"startLine\":1,\"endLine\":2,\"content\":\"class UserService {}\"}}]}}";
    }

    private static final class CountingEmbeddingService implements EmbeddingService {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            return new float[]{0.1f, 0.2f, 0.3f};
        }
    }

    private static final class FakeHttpClient extends HttpClient {
        private final FakeHttpResponse[] responses;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeHttpClient(FakeHttpResponse... responses) {
            this.responses = responses;
        }

        private static FakeHttpClient single(int status, String body) {
            return new FakeHttpClient(new FakeHttpResponse(status, body));
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            int idx = Math.min(calls.getAndIncrement(), responses.length - 1);
            FakeHttpResponse resp = responses[idx];
            return resp.cast(responseBodyHandler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private static final class FakeHttpResponse implements HttpResponse<String> {
        private final int status;
        private final String body;

        private FakeHttpResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Collections.emptyMap(), (a, b) -> true);
        }

        @Override
        public URI uri() {
            return URI.create("http://localhost");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        private <T> HttpResponse<T> cast(HttpResponse.BodyHandler<T> handler) {
            return new HttpResponse<T>() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpRequest request() {
                    return null;
                }

                @Override
                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(java.util.Collections.emptyMap(), (a, b) -> true);
                }

                @Override
                public T body() {
                    HttpResponse.BodySubscriber<T> subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                        @Override
                        public int statusCode() {
                            return status;
                        }

                        @Override
                        public HttpHeaders headers() {
                            return headers();
                        }

                        @Override
                        public HttpClient.Version version() {
                            return HttpClient.Version.HTTP_1_1;
                        }
                    });
                    subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                    subscriber.onComplete();
                    return subscriber.getBody().toCompletableFuture().join();
                }

                @Override
                public URI uri() {
                    return URI.create("http://localhost");
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }

                @Override
                public Optional<javax.net.ssl.SSLSession> sslSession() {
                    return Optional.empty();
                }
            };
        }
    }
}
