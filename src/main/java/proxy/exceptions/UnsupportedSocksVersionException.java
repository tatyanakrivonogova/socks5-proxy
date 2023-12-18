package proxy.exceptions;

public class UnsupportedSocksVersionException extends Socks5ProtocolException {
    public UnsupportedSocksVersionException(String message) {
        super(message);
    }
}
