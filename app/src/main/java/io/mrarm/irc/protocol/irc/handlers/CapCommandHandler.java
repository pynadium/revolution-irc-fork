package io.mrarm.irc.protocol.irc.handlers;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.mrarm.irc.protocol.irc.CommandDisconnectHandler;
import io.mrarm.irc.protocol.irc.CommandHandler;
import io.mrarm.irc.protocol.irc.InvalidMessageException;
import io.mrarm.irc.protocol.irc.MessagePrefix;
import io.mrarm.irc.protocol.irc.ServerConnectionData;
import io.mrarm.irc.protocol.irc.cap.CapabilityEntryPair;

public class CapCommandHandler implements CommandDisconnectHandler {

    private List<CapabilityEntryPair> lsEntries;
    private List<String> ackEntries;

    @Override
    public Object[] getHandledCommands() {
        return new Object[] { "CAP" };
    }

    @Override
    public void handle(ServerConnectionData connection, MessagePrefix sender, String command,
                       List<String> params, Map<String, String> tags)
            throws InvalidMessageException {

        Log.i("CAP_HANDLER", "handle() called, command=" + command + ", params=" + params);

        int baseSubcmdI = 1;
        boolean expectMore = false;

        String subcmd = CommandHandler.getParamWithCheck(params, baseSubcmdI);
        Log.i("CAP_HANDLER", "Parsed subcmd=" + subcmd);

        String maybeStar = CommandHandler.getParamOrNull(params, baseSubcmdI + 1);
        if ("*".equals(maybeStar)) {
            Log.i("CAP_HANDLER", "Found continuation marker '*'");
            expectMore = true;
            ++baseSubcmdI;
        } else {
            Log.i("CAP_HANDLER", "No continuation marker, maybeStar=" + maybeStar);
        }

        if (subcmd.equals("LS")) {
            Log.i("CAP_HANDLER", "Handling LS");

            if (lsEntries == null) {
                Log.i("CAP_HANDLER", "Initializing lsEntries");
                lsEntries = new ArrayList<>();
            }

            String[] caps = CommandHandler
                    .getParamWithCheck(params, baseSubcmdI + 1)
                    .split(" ");

            for (String s : caps) {
                Log.i("CAP_HANDLER", "LS capability entry=" + s);
                lsEntries.add(new CapabilityEntryPair(s));
            }

            if (!expectMore) {
                Log.i("CAP_HANDLER", "LS completed, sending list to CapabilityManager");
                connection.getCapabilityManager().onServerCapabilityList(lsEntries);
                lsEntries = null;
            } else {
                Log.i("CAP_HANDLER", "LS expects more chunks");
            }

        } else if (subcmd.equals("ACK")) {
            Log.i("CAP_HANDLER", "Handling ACK");

            if (ackEntries == null) {
                Log.i("CAP_HANDLER", "Initializing ackEntries");
                ackEntries = new ArrayList<>();
            }

            String[] caps = CommandHandler
                    .getParamWithCheck(params, baseSubcmdI + 1)
                    .split(" ");

            for (String s : caps) {
                Log.i("CAP_HANDLER", "ACK capability=" + s);
                ackEntries.add(s);
            }

            if (!expectMore) {
                Log.i("CAP_HANDLER", "ACK completed, notifying CapabilityManager");
                connection.getCapabilityManager().onCapabilitiesAck(ackEntries);
                ackEntries = null;
            } else {
                Log.i("CAP_HANDLER", "ACK expects more chunks");
            }

        } else if (subcmd.equals("NEW")) {
            Log.i("CAP_HANDLER", "Handling NEW");

            List<CapabilityEntryPair> entries = new ArrayList<>();

            String[] caps = CommandHandler
                    .getParamWithCheck(params, baseSubcmdI + 1)
                    .split(" ");

            for (String s : caps) {
                Log.i("CAP_HANDLER", "NEW capability=" + s);
                entries.add(new CapabilityEntryPair(s));
            }

            Log.i("CAP_HANDLER", "Notifying CapabilityManager of new capabilities");
            connection.getCapabilityManager().onNewServerCapabilitiesAvailable(entries);

        } else if (subcmd.equals("DEL")) {
            Log.i("CAP_HANDLER", "Handling DEL");

            List<String> entries = new ArrayList<>();
            String[] caps = CommandHandler
                    .getParamWithCheck(params, baseSubcmdI + 1)
                    .split(" ");

            for (String s : caps) {
                Log.i("CAP_HANDLER", "DEL capability=" + s);
            }

            Collections.addAll(entries, caps);
            Log.i("CAP_HANDLER", "Notifying CapabilityManager of removed capabilities");
            connection.getCapabilityManager().onServerCapabilitiesRemoved(entries);

        } else {
            Log.i("CAP_HANDLER", "Unknown subcommand, throwing exception: " + subcmd);
            throw new InvalidMessageException("Unknown subcommand: " + subcmd);
        }
    }


    @Override
    public void onDisconnected() {
        if (lsEntries != null)
            lsEntries = null;
        if (ackEntries != null)
            ackEntries = null;
    }
}
