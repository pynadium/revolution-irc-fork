package io.mrarm.irc.protocol.irc.cap;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.irc.InvalidMessageException;
import io.mrarm.irc.protocol.irc.MessagePrefix;
import io.mrarm.irc.protocol.irc.ServerConnectionData;

public class ServerTimeCapability extends Capability {

    // java.util.logging keeps protocol/ free of Android imports; on Android it ends up in logcat
    private static final Logger LOG = Logger.getLogger("ServerTimeCapability");

    private SimpleDateFormat parser;
    // Some servers and bouncers send the timestamp without the milliseconds
    private SimpleDateFormat parserNoMillis;

    public ServerTimeCapability() {
        parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        parserNoMillis = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        parserNoMillis.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Override
    public String[] getNames() {
        return new String[]{"server-time", "znc.in/server-time-iso"};
    }

    @Override
    public String[] getHandledCommands() {
        return new String[0];
    }

    @Override
    public void processMessage(MessageInfo.Builder message, Map<String, String> tags) {
        String time = tags.get("time");
        if (time == null)
            return;
        try {
            message.setDate(parser.parse(time));
        } catch (ParseException e) {
            try {
                message.setDate(parserNoMillis.parse(time));
            } catch (ParseException e2) {
                // Unparsable timestamp: keep the message's own arrival time. This runs on the
                // socket thread, so throwing here would take the whole connection down.
                // Logged with the raw value so new server formats can be spotted and supported.
                LOG.warning("Unsupported server-time format, using arrival time: time=" + time);
            }
        }
    }

    @Override
    public void handle(ServerConnectionData connection, MessagePrefix sender, String command, List<String> params,
                       Map<String, String> tags) throws InvalidMessageException {
        // stub
    }

}
