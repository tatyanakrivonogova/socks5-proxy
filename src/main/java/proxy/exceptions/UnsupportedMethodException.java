package proxy.exceptions;

public class UnsupportedMethodException extends Socks5ProtocolException {
    public UnsupportedMethodException(String message) {
        super(message);
    }
}