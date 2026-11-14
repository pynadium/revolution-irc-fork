package io.mrarm.irc.protocol.user;


public interface UserNickChangeListener {

    void onNickChanged(UserInfo userInfo, String fromNick, String toNick);

}
