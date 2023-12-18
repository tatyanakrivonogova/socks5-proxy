package exceptions;

public class UnsupportedAddressType extends Socks5ProtocolException {
    public UnsupportedAddressType(String message) {
        super(message);
    }
}
