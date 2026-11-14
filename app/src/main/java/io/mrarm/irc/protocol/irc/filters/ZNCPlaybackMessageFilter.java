package io.mrarm.irc.protocol.irc.filters;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.mrarm.irc.protocol.dto.BatchInfo;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.irc.MessageFilter;
import io.mrarm.irc.protocol.irc.ServerConnectionData;
import io.mrarm.irc.protocol.irc.cap.BatchCapability;

public class ZNCPlaybackMessageFilter implements MessageFilter, BatchCapability.BatchListener {

    private static final String ZNC_PLAYBACK_BATCH = "znc.in/playback";

    private final Map<String, List<MessageInfo>> channelData = new HashMap<>();
    private final ZNCPlaybackReconciler reconciler = new ZNCPlaybackReconciler();

    public ZNCPlaybackMessageFilter(ServerConnectionData connectionData) {
        connectionData.getCapabilityManager()
                .getCapability(BatchCapability.class)
                .addBatchListener(ZNC_PLAYBACK_BATCH, this);
    }

    @Override
    public boolean filter(ServerConnectionData connection, String channel, MessageInfo messageInfo) {
        if (messageInfo.getBatch() != null) {
            Log.i("ZNCPlaybackMessageFilter",messageInfo.getBatch().getType());
        }
        if (messageInfo.getBatch() != null && messageInfo.getBatch().getType().equals(ZNC_PLAYBACK_BATCH)) {
            // Store the message for processing it later
            if (!channelData.containsKey(channel))
                channelData.put(channel, new ArrayList<>());
            channelData.get(channel).add(messageInfo);
            return false;
        }
        return true;
    }

    @Override
    public void onBatchStart(ServerConnectionData connection, BatchInfo batch) {
        // stub
    }

    @Override
    public void onBatchEnd(ServerConnectionData connection, BatchInfo batch) {
        Map<String, List<MessageInfo>> snapshot = new HashMap<>();
        for (Map.Entry<String, List<MessageInfo>> entry : channelData.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        channelData.clear();
        reconciler.reconcile(connection, snapshot, this);
    }
}
