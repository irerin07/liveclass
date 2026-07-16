package com.liveclass.notification.application;

public class ChannelNotSupportedException extends RuntimeException {

    public ChannelNotSupportedException() {
        super("IN_APP 알림만 읽음 처리할 수 있습니다");
    }
}
