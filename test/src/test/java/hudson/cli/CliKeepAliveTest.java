/*
 * The MIT License
 *
 * Copyright 2026 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.cli;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression test for issue #26862: a long-running CLI command over the {@code -http} full-duplex
 * transport previously had no traffic on the server &rarr; client (download) connection while it
 * was running, so a reverse proxy could close that idle connection and abort the command. The
 * server now sends periodic keep-alive frames on the download connection while the command runs.
 */
@WithJenkins
class CliKeepAliveTest {

    private JenkinsRule j;
    private int originalInterval;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        originalInterval = CLIAction.KEEP_ALIVE_INTERVAL;
        CLIAction.KEEP_ALIVE_INTERVAL = 200;
    }

    @AfterEach
    void tearDown() {
        CLIAction.KEEP_ALIVE_INTERVAL = originalInterval;
    }

    @Test
    void serverSendsKeepAliveDuringLongCommand() throws Exception {
        AtomicInteger noops = new AtomicInteger();

        FullDuplexHttpStream streams = new FullDuplexHttpStream(j.getURL(), "cli?remoting=false", null);
        class Client extends PlainCLIProtocol.ClientSide {
            private int exit = -1;

            Client() {
                super(new PlainCLIProtocol.FramedOutput(streams.getOutputStream()));
            }

            @Override
            protected synchronized void onExit(int code) {
                exit = code;
                notifyAll();
            }

            @Override
            protected void onStdout(byte[] chunk) {}

            @Override
            protected void onStderr(byte[] chunk) {}

            @Override
            protected void onNoop() {
                noops.incrementAndGet();
            }

            @Override
            protected void handleClose() {}

            synchronized int awaitExit() throws InterruptedException {
                while (exit == -1) {
                    wait();
                }
                return exit;
            }
        }

        Client client = new Client();
        client.sendArg(SleepCommand.NAME);
        client.sendEncoding(Charset.defaultCharset().name());
        client.sendLocale(Locale.getDefault().toString());
        client.sendStart();
        InputStream is = streams.getInputStream();
        if (is.read() != 0) { // cf. FullDuplexHttpService
            throw new IOException("expected to see initial zero byte");
        }
        new PlainCLIProtocol.FramedReader(client, is).start();

        assertThat(client.awaitExit(), is(0));
        assertThat("server should have sent keep-alive NOOP frames during the long-running command",
                noops.get(), greaterThan(0));
    }

    /**
     * Test-only command that runs for several keep-alive intervals.
     */
    @TestExtension
    public static class SleepCommand extends CLICommand {

        static final String NAME = "test-sleep-keepalive";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getShortDescription() {
            return "Sleeps for several keep-alive intervals (for testing).";
        }

        @Override
        protected int run() throws Exception {
            Thread.sleep(TimeUnit.SECONDS.toMillis(2));
            return 0;
        }
    }
}
