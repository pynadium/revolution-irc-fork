package io.mrarm.irc.chat.host;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ActionMode;

public interface ActionModeHost {
    ActionMode startSupportActionMode(@NonNull ActionMode.Callback callback);
}
