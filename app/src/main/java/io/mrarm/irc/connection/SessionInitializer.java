package io.mrarm.irc.connection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import io.mrarm.irc.BuildConfig;
import io.mrarm.irc.DCCManager;
import io.mrarm.irc.NotificationManager;
import io.mrarm.irc.R;
import io.mrarm.irc.chatlib.dto.RoomMessageId;
import io.mrarm.irc.chatlib.irc.IRCConnection;
import io.mrarm.irc.chatlib.irc.ServerConnectionData;
import io.mrarm.irc.chatlib.irc.cap.SASLCapability;
import io.mrarm.irc.chatlib.irc.cap.SASLOptions;
import io.mrarm.irc.chatlib.irc.filters.ZNCPlaybackMessageFilter;
import io.mrarm.irc.chatlib.irc.handlers.MessageCommandHandler;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.message.DefaultMessageBus;
import io.mrarm.irc.message.DefaultMessagePipeline;
import io.mrarm.irc.message.MessageBus;
import io.mrarm.irc.message.MessagePipeline;
import io.mrarm.irc.message.MessagePipelineContext;
import io.mrarm.irc.storage.MessageStorageRepository;
import io.mrarm.irc.util.IgnoreListMessageFilter;

public class SessionInitializer {
    private final Context context;
    private static final String TAG = "SESSION INITIALIZER";

    public SessionInitializer(Context context) {
        this.context = context;
    }

    public void attach(
            IRCConnection connection,
            ServerConnectionSession session,
            ServerConfigData config,
            SASLOptions saslOptions
    ) {
        MessageStorageRepository repo = MessageStorageRepository.getInstance(context);

        ServerConnectionData serverConnectionData = connection.getServerConnectionData();

        MessageBus bus = new DefaultMessageBus();
        MessagePipelineContext pipelineContext = new MessagePipelineContext(config.uuid, repo);

        MessagePipeline pipeline = new DefaultMessagePipeline(pipelineContext, bus);

        serverConnectionData.setServerUUID(config.uuid);
        serverConnectionData.setMessageStorageRepository(repo);
        serverConnectionData.setMessageBus(bus);
        serverConnectionData.setMessageSink(pipeline);
        serverConnectionData.setMessageIdParser(new RoomMessageId.Parser());


        bus.subscribe(null, (channel, message, messageId) -> {

            // Ignore ZNC playback / history replays
            if (message.isPlayback())
                return;

            NotificationManager.getInstance()
                    .processMessage(
                            context.getApplicationContext(),
                            session,
                            channel,
                            message,
                            messageId
                    );
        });


        serverConnectionData.getMessageFilterList().addMessageFilter(new IgnoreListMessageFilter(config));

        if (saslOptions != null) {
            serverConnectionData.getCapabilityManager().registerCapability(new SASLCapability(saslOptions));
        }

        serverConnectionData.getMessageFilterList().addMessageFilter(new ZNCPlaybackMessageFilter(serverConnectionData));

        MessageCommandHandler handler = serverConnectionData.getCommandHandlerList().getHandler(MessageCommandHandler.class);

        DCCManager dccManager = DCCManager.getInstance(context);
        handler.setDCCServerManager(dccManager.getServer());
        handler.setDCCClientManager(dccManager.createClient(session));
        handler.setCtcpVersionReply(
                context.getString(R.string.app_name),
                BuildConfig.VERSION_NAME,
                "Android"
        );

        Handler mainHandler = new Handler(Looper.getMainLooper());
        connection.addDisconnectListener(
                (conn, reason) -> {
                    // Fires on the socket read thread. Post to the main thread so that
                    // InfoChangeListeners and reconnect scheduling always run on main.
                    Log.d(TAG, "attach(): disconnection listener invoked. Posting notifyDisconnected to main thread.");
                    mainHandler.post(session::notifyDisconnected);
                }
        );
    }
}
