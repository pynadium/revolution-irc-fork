package io.mrarm.irc.protocol.dto;

import java.util.Date;

public class WhoisStatusMessageInfo extends StatusMessageInfo {

    private WhoisInfo whoisInfo;

    public WhoisStatusMessageInfo(String sender, Date date, WhoisInfo whoisInfo) {
        super(sender, date, MessageType.WHOIS, null);
        this.whoisInfo = whoisInfo;
    }

    public WhoisInfo getWhoisInfo() {
        return whoisInfo;
    }
}
