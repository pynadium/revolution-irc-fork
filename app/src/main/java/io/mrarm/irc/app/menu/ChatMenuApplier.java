package io.mrarm.irc.app.menu;

import android.view.Menu;
import android.view.MenuItem;

import io.mrarm.irc.R;

public class ChatMenuApplier {

    public boolean apply(Menu menu, ChatMenuState state) {

        boolean hasChanges = false;

        hasChanges |= setVisible(menu, R.id.action_reconnect, state.showReconnect);
        hasChanges |= setVisible(menu, R.id.action_close, state.showClose);
        hasChanges |= setVisible(menu, R.id.action_disconnect, state.showDisconnect);
        hasChanges |= setVisible(menu, R.id.action_disconnect_and_close, state.showDisconnectAndClose);
        hasChanges |= setVisible(menu, R.id.action_members, state.showMembers);
        hasChanges |= setVisible(menu, R.id.action_format, state.showFormat);
        hasChanges |= setVisible(menu, R.id.action_dcc_send, state.showDccSend);
        hasChanges |= setVisible(menu, R.id.action_clear_history, state.showClearHistory);

        MenuItem partItem = menu.findItem(R.id.action_part_channel);

        if (partItem.isVisible() != state.showPart) {
            partItem.setVisible(state.showPart);
            hasChanges = true;
        }

        if (state.showPart) {
            partItem.setTitle(state.partTitleRes);
        }

        return hasChanges;
    }

    private boolean setVisible(Menu menu, int id, boolean visible) {
        MenuItem item = menu.findItem(id);
        if (item.isVisible() != visible) {
            item.setVisible(visible);
            return true;
        }
        return false;
    }
}