package io.mrarm.irc.connection;

import android.content.Context;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import io.mrarm.irc.UserKeyManager;
import io.mrarm.irc.UserOverrideTrustManager;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.infrastructure.threading.DelayScheduler;
import io.mrarm.irc.protocol.irc.IRCConnectionRequest;
import io.mrarm.irc.protocol.irc.cap.SASLOptions;

public class ServerConnectionFactory {
    private final Context appContext;
    private final DelayScheduler reconnectScheduler;

    public ServerConnectionFactory(Context appContext,
                                   DelayScheduler reconnectScheduler) {
        this.appContext = appContext;
        this.reconnectScheduler = reconnectScheduler;
    }

    public ServerConnectionSession create(ServerConnectionManager manager,
                                          ServerConfigData data,
                                          List<String> joinChannels) {

        IRCConnectionRequest request = new IRCConnectionRequest();
        ReconnectPolicy reconnectPolicy = new ReconnectPolicy();

        request.setServerAddress(data.address, data.port);

        if (data.charset != null)
            request.setCharset(Charset.forName(data.charset));
        if (data.nicks != null && !data.nicks.isEmpty()) {
            for (String nick : data.nicks)
                request.addNick(nick);
        } else {
            for (String nick : AppSettings.getDefaultNicks())
                request.addNick(nick);
            if (request.getNickList() == null)
                throw new ServerConnectionManager.NickNotSetException();
        }
        if (data.user != null)
            request.setUser(data.user);
        else if (AppSettings.getDefaultUser() != null && !AppSettings.getDefaultUser().isEmpty())
            request.setUser(AppSettings.getDefaultUser());
        else
            request.setUser(request.getNickList().get(0));
        if (data.realname != null)
            request.setRealName(data.realname);
        else if (AppSettings.getDefaultRealname() != null && !AppSettings.getDefaultRealname().isEmpty())
            request.setRealName(AppSettings.getDefaultRealname());
        else
            request.setRealName(request.getNickList().get(0));

        if (data.pass != null)
            request.setServerPass(data.pass);

        SASLOptions saslOptions = null;
        UserKeyManager userKeyManager = null;
        if (data.authMode != null) {
            if (data.authMode.equals(ServerConfigData.AUTH_SASL) && data.authUser != null &&
                    data.authPass != null)
                saslOptions = SASLOptions.createPlainAuth(data.authUser, data.authPass);
            if (data.authMode.equals(ServerConfigData.AUTH_SASL_EXTERNAL)) {
                saslOptions = SASLOptions.createExternal();
                userKeyManager = new UserKeyManager(data.getAuthCert(), data.getAuthPrivateKey());
            }
        }

        if (data.ssl) {
            UserOverrideTrustManager sslHelper = new UserOverrideTrustManager(appContext, data.uuid);
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                KeyManager[] keyManagers = new KeyManager[0];
                if (userKeyManager != null)
                    keyManagers = new KeyManager[]{userKeyManager};
                sslContext.init(keyManagers, new TrustManager[]{sslHelper}, null);
                request.enableSSL(sslContext.getSocketFactory(), sslHelper);
            } catch (GeneralSecurityException e) {
                throw new RuntimeException(e);
            }
        }

        SessionInitializer sessionInitializer = new SessionInitializer(appContext);

        return new ServerConnectionSession(
                manager, sessionInitializer, data, request, saslOptions, joinChannels, reconnectScheduler, reconnectPolicy);
    }
}
