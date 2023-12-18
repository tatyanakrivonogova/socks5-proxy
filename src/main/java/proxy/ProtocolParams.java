package proxy;

public final class ProtocolParams {
    public static final byte VERSION = 0x05;
    public static final byte NO_AUTH = 0x00;
    public static final byte NOTHING_SUPPORTED = (byte) 0xFF;
    public static final byte COMMAND_CODE = 0x01;

    public static final byte[] AUTH_NO_AUTH_REPLY = new byte[] { VERSION, NO_AUTH };

    public static final byte[] ERROR_REPLY = new byte[] { VERSION, NOTHING_SUPPORTED };

    public static final byte ADDR_TYPE_IPV4 = 0x01;
    public static final byte ADDR_TYPE_HOST = 0x03;
    public static final byte ADDR_TYPE_IPV6 = 0x04;

}
