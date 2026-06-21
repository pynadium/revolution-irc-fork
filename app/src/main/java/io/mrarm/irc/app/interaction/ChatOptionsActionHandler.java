package io.mrarm.irc.app.interaction;

import io.mrarm.irc.R;

public class ChatOptionsActionHandler {

    public interface Host {
        boolean isChatScreen();
        void showJoinChannelDialog();
        void showUserSearchDialog();
        void partCurrentChannel();
        void closeAllPrivateMessages();
        void pickFileForDccSend();
        void openMembersDrawer();
        void openIgnoreList();
        void disconnect();
        void disconnectAndClose();
        void reconnect();
        void showFormatBar();
        void openDccTransfers();
        void openSettings();
        void requestExit();
    }

    private final Host host;

    public ChatOptionsActionHandler(Host host) {
        this.host = host;
    }

    public boolean handle(int itemId) {

        // Keep same behavior: if not handled, let Activity fall back to super
        if (itemId == R.id.action_join_channel) {
            host.showJoinChannelDialog();
            return true;
        } else if (itemId == R.id.action_message_user) {
            host.showUserSearchDialog();
            return true;
        } else if (itemId == R.id.action_part_channel) {
            host.partCurrentChannel();
            return true;
        } else if (itemId == R.id.action_close_all_pms) {
            host.closeAllPrivateMessages();
            return true;
        } else if (itemId == R.id.action_dcc_send) {
            host.pickFileForDccSend();
            return true;
        } else if (itemId == R.id.action_members) {
            host.openMembersDrawer();
            return true;
        } else if (itemId == R.id.action_ignore_list) {
            host.openIgnoreList();
            return true;
        } else if (itemId == R.id.action_disconnect) {
            host.disconnect();
            return true;
        } else if (itemId == R.id.action_disconnect_and_close || itemId == R.id.action_close) {
            host.disconnectAndClose();
            return true;
        } else if (itemId == R.id.action_reconnect) {
            host.reconnect();
            return true;
        } else if (itemId == R.id.action_format) {
            host.showFormatBar();
            return true;
        } else if (itemId == R.id.action_dcc_transfers) {
            host.openDccTransfers();
            return true;
        } else if (itemId == R.id.action_settings) {
            host.openSettings();
            return true;
        } else if (itemId == R.id.action_exit) {
            host.requestExit();
            return true;
        }
        return false;
    }
}

