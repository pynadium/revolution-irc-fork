package io.mrarm.irc.protocol;

public interface ResponseCallback<T> {

    void onResponse(T response);

}
