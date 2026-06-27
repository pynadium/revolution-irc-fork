package io.mrarm.irc.chat.host;

import androidx.appcompat.widget.Toolbar;

import java.util.Date;
import java.util.List;

import io.mrarm.irc.connection.ServerConnectionSession;
import io.mrarm.irc.drawer.DrawerHelper;
import io.mrarm.irc.protocol.dto.NickWithPrefix;

public interface ChatActivityHost {
    Toolbar getToolbar();

    void addActionBarDrawerToggle(Toolbar toolbar);

    DrawerHelper getDrawerHelper();

    void setCurrentChannelInfo(ServerConnectionSession session, String topic, String setBy,
                               Date setOn, List<NickWithPrefix> members);
}
