package proxy.exceptions;

public class UnsupportedCommandCode extends Socks5ProtocolException {
    public UnsupportedCommandCode(String message) {
        super(message);
    }
}
