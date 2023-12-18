package proxy;

public final class ProtocolParams {
    public static final byte SUPPORTED_VERSION = 0x05;
//    public static final byte NO_AUTH = 0x00;
    public static final byte ERROR_CODE = (byte) 0xFF;
    public static final byte[] CONNECTING_REPLY_TEMPLATE = new byte[] { 0x05, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
    public static final byte COMMAND_CODE = 0x01;
    public static final byte ADDR_TYPE_IPV4 = 0x01;
    public static final byte ADDR_TYPE_HOST = 0x03;
    public static final byte ADDR_TYPE_IPV6 = 0x04;

}
