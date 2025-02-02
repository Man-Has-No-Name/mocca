package com.paypal.mocca.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.paypal.mocca.client.sample.AsyncSampleClient;
import com.paypal.mocca.client.sample.SampleClient;
import feign.Feign;
import feign.RetryableException;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class MoccaClientBuilderTest {

    @Test(expectedExceptions = SocketTimeoutException.class)
    void readTimeoutTest() throws Exception {
        final WireMockServer server = new WireMockServer(options().dynamicPort());

        final int readTimeout = 10;

        server.stubFor(post(urlEqualTo("/read/graphql")).willReturn(
            aResponse()
                .withHeader("Content-Type","application/json;charset=UTF-8")
                .withBody("{ \"data\": {}}")
                .withChunkedDribbleDelay(5, 5 * readTimeout)));

        server.start();
        final String serverUrl = "http://localhost:" + server.port() + "/read";

        try {
            MoccaClient.Builder.sync(serverUrl)
                .readTimeout(readTimeout)
                .build(SampleClient.class)
                .getOneSample("foo: \"boo\", bar: \"far\"")
                .getFoo();
        } catch (final RetryableException re) {
            throw (Exception)re.getCause();
        } finally {
            server.stop();
        }
    }

    @Test
    void customExecutorServiceTest() throws Exception {
        class BadExecService extends AbstractExecutorService {
            private boolean wasUsed = false;

            public boolean wasUsed() {
                return wasUsed;
            }

            @Override
            public void execute(Runnable command) {
                wasUsed = true;
                command.run();
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return null;
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return false;
            }
        }

        final BadExecService badExecService = new BadExecService();
        final MoccaHttpClient moccaJavaHttpClient = new MoccaDefaultHttpClient();
        final MoccaExecutorHttpClient executorHttpClient = new MoccaExecutorHttpClient(moccaJavaHttpClient, badExecService);

        try {
            MoccaClient.Builder.async("http://localhost:8080")
                    .client(executorHttpClient)
                    .build(AsyncSampleClient.class)
                    .getOneSample("foo: \"boo\", bar: \"far\"")
                    .get();
        } catch (final ExecutionException ee) {
            assertTrue(badExecService.wasUsed());
        }
    }

    @Test
    void resiliency() throws Exception {
        // Resiliency is added by passing in a custom feign builder.
        // This test will just verify that is leveraged.
        final UnsupportedOperationException err = new UnsupportedOperationException("I'm not working today!");
        class BadBuilder extends Feign.Builder {
            @Override
            public Feign build() {
                throw err;
            }
        }

        class BadResilience extends MoccaResiliency {
            public BadResilience() {
                super(new BadBuilder());
            }
        }

        try {
            MoccaClient.Builder.sync("http://localhost:8080")
                .resiliency(new BadResilience())
                .build(SampleClient.class);
            fail("Expected an exception to be the thrown.");
        } catch (final UnsupportedOperationException e) {
            assertEquals(e, err, "An expected error indicates custom `Feign.Builder` used.");
        }
    }

    //@Test
    void connectTimeoutTest() throws Exception {
        // TBD:(
    }

    @Test
    public void capabilitiesRegistration() {
        class MyCap extends MoccaCapability {
            public MyCap() {
                super(new PoorFeignCapability());
            }
        }
        try {
            MoccaClient.Builder.sync("http://foo")
                .addCapability(new MyCap())
                .build(SampleClient.class);
            fail("Expected an exception caused by MyCap.");
        } catch (final Exception e) {
            assertEquals(
                ((InvocationTargetException)e.getCause()).getTargetException().getMessage(),
                PoorFeignCapability.errorMessage
            );
        }
    }
}



