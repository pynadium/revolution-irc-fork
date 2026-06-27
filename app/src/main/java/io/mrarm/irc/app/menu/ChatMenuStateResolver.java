package io.mrarm.irc.app.menu;

import io.mrarm.irc.R;
import io.mrarm.irc.chat.ChatFragment;
import io.mrarm.irc.config.ChatSettings;
import io.mrarm.irc.protocol.irc.ServerConnectionApi;

public class ChatMenuStateResolver {

    public ChatMenuState resolve(ChatFragment fragment, boolean membersVisible) {

        ChatMenuState state = new ChatMenuState();

        boolean connected = fragment.getConnectionInfo().isConnected();
        ServerConnectionApi api =
                (ServerConnectionApi) fragment.getConnectionInfo().getApiInstance();

        // Connection state
        state.showReconnect = !connected;
        state.showClose = !connected;
        state.showDisconnect = connected;
        state.showDisconnectAndClose = connected;

        // Members drawer
        state.showMembers = membersVisible;

        // Format
        state.showFormat =
                fragment.getSendMessageHelper().hasSendMessageTextSelection();

        // Part / Direct logic
        boolean inDirectChat = false;
        String channel = fragment.getCurrentChannel();

        if (channel == null) {
            state.showPart = false;

        } else if (!channel.isEmpty()
                && !api.getServerConnectionData()
                .getSupportList()
                .getSupportedChannelTypes()
                .contains(channel.charAt(0))) {

            state.showPart = true;
            state.partTitleRes = R.string.action_close_direct;
            inDirectChat = true;

        } else {
            state.showPart = true;
            state.partTitleRes = R.string.action_part_channel;
        }

        // DCC visibility
        state.showDccSend =
                ChatSettings.isDccSendVisible()
                        && connected
                        && inDirectChat;

        return state;
    }
}