package proxy;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public class Attachment {
    State state;
    int port;
    InetAddress ip;
    public ByteBuffer inputBuffer;
    public ByteBuffer outputBuffer;
    public SelectionKey connectedKey;
}