package io.mrarm.irc.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.infrastructure.threading.FakeDelayScheduler;
import io.mrarm.irc.infrastructure.threading.FakeSchedulerProvider;
import io.mrarm.irc.protocol.NickUnavailableException;
import io.mrarm.irc.protocol.ResponseCallback;
import io.mrarm.irc.protocol.ResponseErrorCallback;
import io.mrarm.irc.protocol.irc.IRCConnection;
import io.mrarm.irc.protocol.irc.IRCConnectionRequest;
import io.mrarm.irc.protocol.irc.cap.SASLOptions;

/**
 * Reconnect state machine of {@link ServerConnectionSession}, driven by a virtual clock.
 * <p>
 * No socket is opened: the session gets a {@link FakeIRCConnection} whose connect, quit and
 * disconnect only record the call, and the test plays the server by invoking the captured
 * callbacks. A socket that closes is simulated by calling {@code notifyDisconnected()}, which
 * is what the disconnect listener installed by {@link SessionInitializer} does.
 * <p>
 * Tests marked {@code @Ignore} describe the intended behaviour of known defects. They are
 * enabled when the reconnect state machine is fixed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ServerConnectionSessionReconnectTest {

    private static final int RECONNECT_DELAY_MS = 5000;
    private static final long CONNECTIVITY_GRACE_MS = 3000L;

    private FakeDelayScheduler mScheduler;
    private TestConnectionManager mManager;
    private RecordingReconnectPolicy mPolicy;
    private FakeIRCConnection mConnection;
    private ServerConfigData mConfig;
    private ServerConnectionSession mSession;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        resetSettingsHelper();
        SettingsHelper.getInstance(context);
        SettingsHelper.getPreferences().edit()
                .clear()
                .putBoolean(AppSettings.PREF_RECONNECT_ENABLED, true)
                .putBoolean(AppSettings.PREF_RECONNECT_WI_FI_ONLY, false)
                .putBoolean(AppSettings.PREF_RECONNECT_ON_CONNECTIVITY_CHANGE_ENABLED, true)
                .commit();

        mScheduler = new FakeDelayScheduler();
        mManager = new TestConnectionManager(context, new FakeSchedulerProvider(mScheduler));
        mPolicy = new RecordingReconnectPolicy();
        mConnection = new FakeIRCConnection();

        mConfig = new ServerConfigData();
        mConfig.uuid = UUID.randomUUID();
        mConfig.name = "test";
        mConfig.autojoinChannels = Collections.singletonList("#test");

        mSession = new ServerConnectionSession(mManager,
                new TestSessionInitializer(context, mConnection), mConfig,
                new IRCConnectionRequest(), null, new ArrayList<>(), mScheduler, mPolicy);
    }

    @After
    public void tearDown() throws Exception {
        resetSettingsHelper();
    }

    // --- current behaviour ---

    @Test
    public void unexpectedDisconnect_schedulesReconnectWithPolicyDelay() {
        connectAndRegister();

        mSession.notifyDisconnected();

        assertFalse(mSession.isConnected());
        assertEquals(Collections.singletonList(0), mPolicy.attempts);
        assertEquals(Collections.singletonList((long) RECONNECT_DELAY_MS), mScheduler.pendingDelays());

        mScheduler.advanceBy(RECONNECT_DELAY_MS);

        assertEquals(2, mConnection.connectCalls.get());
        assertTrue(mSession.isConnecting());
    }

    @Test
    public void repeatedConnectFailures_advanceBackoffOneStepEach() {
        mSession.connect();
        mConnection.fail(new IOException("Connection refused"));
        mScheduler.runDue();
        mScheduler.advanceBy(RECONNECT_DELAY_MS);
        mConnection.fail(new IOException("Connection refused"));
        mScheduler.runDue();

        assertEquals(2, mConnection.connectCalls.get());
        assertEquals(2, mPolicy.attempts.size());
        assertEquals(mPolicy.attempts.get(0) + 1, (int) mPolicy.attempts.get(1));
        assertEquals(1, mScheduler.pendingCount());
    }

    @Test
    public void successfulConnect_resetsBackoff() {
        mSession.connect();
        mConnection.fail(new IOException("Connection refused"));
        mScheduler.runDue();
        mScheduler.advanceBy(RECONNECT_DELAY_MS);
        mConnection.fail(new IOException("Connection refused"));
        mScheduler.runDue();
        mScheduler.advanceBy(RECONNECT_DELAY_MS);
        mConnection.succeed();
        mScheduler.runDue();
        assertTrue(mSession.isConnected());

        mSession.notifyDisconnected();

        assertEquals(0, (int) mPolicy.attempts.get(mPolicy.attempts.size() - 1));
    }

    @Test
    public void connectSuccess_joinsAutojoinChannels() {
        connectAndRegister();

        assertEquals(1, mConnection.joinRequests.size());
        assertTrue(mConnection.joinRequests.get(0).contains("#test"));
    }

    @Test
    public void userDisconnect_whileConnected_quitsAndNeverReconnects() {
        connectAndRegister();

        mSession.disconnect();

        assertEquals(1, mConnection.quitCalls.get());
        assertTrue(mSession.isDisconnecting());
        assertTrue(mSession.hasUserDisconnectRequest());

        mSession.notifyDisconnected();
        mScheduler.advanceBy(60_000L);

        assertFalse(mSession.isDisconnecting());
        assertFalse(mSession.isConnected());
        assertEquals(1, mManager.fullyDisconnectedCalls.get());
        assertTrue(mPolicy.attempts.isEmpty());
        assertEquals(1, mConnection.connectCalls.get());
    }

    @Test
    public void allNicksRejected_stopsReconnectAndExposesReason() {
        List<String> tried = Arrays.asList("alice", "alice_");
        mSession.connect();

        mConnection.fail(new NickUnavailableException("Nickname is already in use", tried));
        mScheduler.runDue();

        assertTrue(mSession.isNickExhausted());
        assertTrue(mSession.hasUserDisconnectRequest());
        assertEquals("Nickname is already in use", mSession.getNickExhaustedReason());
        assertEquals(tried, mSession.getNickExhaustedNicks());
        assertTrue(mPolicy.attempts.isEmpty());
        assertEquals(0, mScheduler.pendingCount());
    }

    @Test
    public void reconnectWithNick_clearsNickExhaustionAndConnects() {
        mSession.connect();
        mConnection.fail(new NickUnavailableException("Nickname is already in use",
                Collections.singletonList("alice")));
        mScheduler.runDue();

        mSession.reconnectWithNick("bob");

        assertFalse(mSession.isNickExhausted());
        assertFalse(mSession.hasUserDisconnectRequest());
        assertEquals(2, mConnection.connectCalls.get());
    }

    @Test
    public void connectivityLoss_disconnectsOnlyAfterGracePeriod() {
        connectAndRegister();

        mSession.notifyConnectivityChanged(false, false);

        assertEquals(Collections.singletonList(CONNECTIVITY_GRACE_MS), mScheduler.pendingDelays());
        assertEquals(0, mConnection.quitCalls.get());

        mScheduler.advanceBy(CONNECTIVITY_GRACE_MS);

        assertEquals(1, mConnection.quitCalls.get());
        assertTrue(mSession.isDisconnecting());
        assertFalse(mSession.hasUserDisconnectRequest());
    }

    @Test
    public void connectivityRestoredWithinGrace_keepsConnection() {
        connectAndRegister();

        mSession.notifyConnectivityChanged(false, false);
        mScheduler.advanceBy(1000L);
        mSession.notifyConnectivityChanged(true, true);
        mScheduler.advanceBy(60_000L);

        assertEquals(0, mConnection.quitCalls.get());
        assertTrue(mSession.isConnected());
        assertEquals(1, mConnection.connectCalls.get());
    }

    @Test
    public void connectivityRestored_whileReconnectPending_connectsImmediately() {
        mSession.connect();
        mConnection.fail(new IOException("Network is unreachable"));
        mScheduler.runDue();
        assertEquals(1, mScheduler.pendingCount());

        mSession.notifyConnectivityChanged(true, true);

        assertEquals(2, mConnection.connectCalls.get());
        assertEquals(0, mScheduler.pendingCount());
    }

    // --- known defects: intended behaviour ---

    @Ignore("S2: the handshake success callback ignores a disconnect requested during connect")
    @Test
    public void disconnectDuringConnect_lateHandshakeDoesNotMarkConnected() throws Exception {
        mSession.connect();

        mSession.disconnect();
        assertTrue("disconnect(true) never reached the connection",
                mConnection.awaitHardDisconnect());

        mConnection.succeed();
        mScheduler.runDue();

        assertFalse(mSession.isConnected());
        assertTrue(mConnection.joinRequests.isEmpty());
    }

    @Ignore("S3: connect() throws while a disconnect for reconnect is still in progress")
    @Test
    public void connectivityRestored_duringReconnectTeardown_doesNotThrowAndReconnects() {
        connectAndRegister();
        mSession.notifyConnectivityChanged(false, false);
        mScheduler.advanceBy(CONNECTIVITY_GRACE_MS);
        assertTrue(mSession.isDisconnecting());

        // IRCService.handleNetworkUpdate reaches connect() this way before the socket closes
        mSession.notifyConnectivityChanged(true, true);

        mSession.notifyDisconnected();
        mScheduler.advanceBy(60_000L);

        assertEquals(2, mConnection.connectCalls.get());
    }

    @Ignore("S5: a disconnect for reconnect is treated as final, so nothing reconnects")
    @Test
    public void connectivityLoss_reconnectsWhenReconnectOnConnectivityChangeIsOff() {
        SettingsHelper.getPreferences().edit()
                .putBoolean(AppSettings.PREF_RECONNECT_ON_CONNECTIVITY_CHANGE_ENABLED, false)
                .commit();
        connectAndRegister();

        mSession.notifyConnectivityChanged(false, false);
        mScheduler.advanceBy(CONNECTIVITY_GRACE_MS);
        mSession.notifyDisconnected();
        mSession.notifyConnectivityChanged(true, true);
        mScheduler.advanceBy(60_000L);

        assertEquals(2, mConnection.connectCalls.get());
        assertEquals(0, mManager.fullyDisconnectedCalls.get());
    }

    @Ignore("S7: error callback and disconnect listener both call notifyDisconnected")
    @Test
    public void failureDuringRegistration_advancesBackoffOnce() {
        mSession.connect();

        mConnection.fail(new IOException("Connection reset"));
        // the disconnect listener of SessionInitializer fires for the same socket
        mSession.notifyDisconnected();
        mScheduler.runDue();

        assertEquals(1, mPolicy.attempts.size());
        assertEquals(1, mScheduler.pendingCount());
    }

    // --- helpers ---

    private void connectAndRegister() {
        mSession.connect();
        assertTrue(mSession.isConnecting());
        mConnection.succeed();
        mScheduler.runDue();
        assertTrue(mSession.isConnected());
    }

    /** SettingsHelper caches its first Context; each Robolectric test has a new Application. */
    private static void resetSettingsHelper() throws Exception {
        Field f = SettingsHelper.class.getDeclaredField("mInstance");
        f.setAccessible(true);
        f.set(null, null);
    }

    static final class RecordingReconnectPolicy extends ReconnectPolicy {
        final List<Integer> attempts = new CopyOnWriteArrayList<>();

        @Override
        public int getReconnectDelay(int attemptNumber) {
            attempts.add(attemptNumber);
            return RECONNECT_DELAY_MS;
        }
    }

    static final class TestSessionInitializer extends SessionInitializer {
        private final IRCConnection mConnection;

        TestSessionInitializer(Context context, IRCConnection connection) {
            super(context);
            mConnection = connection;
        }

        @Override
        public IRCConnection createConnection() {
            return mConnection;
        }

        @Override
        public void attach(IRCConnection connection, ServerConnectionSession session,
                           ServerConfigData config, SASLOptions saslOptions) {
            // No pipeline, storage, DCC or notifications: the test drives disconnects itself.
        }
    }

    static final class TestConnectionManager extends ServerConnectionManager {
        final AtomicInteger fullyDisconnectedCalls = new AtomicInteger();

        TestConnectionManager(Context context, FakeSchedulerProvider provider) {
            super(context, provider);
        }

        @Override
        void notifyConnectionInfoChanged(ServerConnectionSession connection) {
        }

        @Override
        void notifyChannelListChanged(ServerConnectionSession connection, List<String> newChannels) {
        }

        @Override
        void saveAutoconnectListAsync() {
        }

        @Override
        void notifyConnectionFullyDisconnected(ServerConnectionSession connection) {
            fullyDisconnectedCalls.incrementAndGet();
        }
    }

    /** IRCConnection that opens no socket and lets the test complete or fail the handshake. */
    static final class FakeIRCConnection extends IRCConnection {
        final AtomicInteger connectCalls = new AtomicInteger();
        final AtomicInteger quitCalls = new AtomicInteger();
        final AtomicInteger queuedDisconnectCalls = new AtomicInteger();
        final List<List<String>> joinRequests = new CopyOnWriteArrayList<>();
        private final CountDownLatch mHardDisconnect = new CountDownLatch(1);
        private volatile ResponseCallback<Void> mConnectCallback;
        private volatile ResponseErrorCallback mConnectErrorCallback;

        @Override
        public Future<Void> connect(IRCConnectionRequest request, ResponseCallback<Void> callback,
                                    ResponseErrorCallback errorCallback) {
            connectCalls.incrementAndGet();
            mConnectCallback = callback;
            mConnectErrorCallback = errorCallback;
            return null;
        }

        /** The server finished registration (end of MOTD). */
        void succeed() {
            assertNotNull("connect() was not called", mConnectCallback);
            mConnectCallback.onResponse(null);
        }

        /** The connect or the handshake failed. */
        void fail(Exception e) {
            assertNotNull("connect() was not called", mConnectErrorCallback);
            mConnectErrorCallback.onError(e);
        }

        @Override
        public void disconnect(boolean cleanly) {
            mHardDisconnect.countDown();
        }

        boolean awaitHardDisconnect() throws InterruptedException {
            return mHardDisconnect.await(5, TimeUnit.SECONDS);
        }

        @Override
        public Future<Void> disconnect(ResponseCallback<Void> callback,
                                       ResponseErrorCallback errorCallback) {
            queuedDisconnectCalls.incrementAndGet();
            return null;
        }

        @Override
        public Future<Void> quit(String message, ResponseCallback<Void> callback,
                                 ResponseErrorCallback errorCallback) {
            quitCalls.incrementAndGet();
            return null;
        }

        @Override
        public Future<Void> joinChannels(List<String> channels, ResponseCallback<Void> callback,
                                         ResponseErrorCallback errorCallback) {
            joinRequests.add(new ArrayList<>(channels));
            return null;
        }
    }
}
