package proxy.exceptions;

public class TooLongHandshakeMessage extends Socks5ProtocolException {
    public TooLongHandshakeMessage(String message) {
        super(message);
    }
}
