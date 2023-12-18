package proxy;

public enum ClientState {
    GREETING,
    CONNECTING,
    WAIT_SERVER,
    WAIT_DNS,
    CONNECTED
}