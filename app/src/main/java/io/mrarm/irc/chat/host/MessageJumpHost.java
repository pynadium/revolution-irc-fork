package io.mrarm.irc.chat.host;

public interface MessageJumpHost {
    Long getAndClearMessageJump(String channel);
}
