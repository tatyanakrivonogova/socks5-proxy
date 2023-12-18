package proxy;

import exceptions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.SelectionKey;
import java.util.Arrays;

public class Handshake {
    public static void greeting(SelectionKey key) throws Socks5ProtocolException {
        Attachment attachment = (Attachment) key.attachment();

        int len = attachment.inputBuffer.position();
        if (len < 2) {
            System.out.println("Full client's greeting message wasn't received");
            return;
        }

        byte[] data = attachment.inputBuffer.array();

        if (data[0] != ProtocolParams.VERSION) {
            throw new UnsupportedSocksVersionException("Client's greeting message has wrong version, only SOCKS5 is supported");
        }

        byte suggestedMethodsNumber = data[1];
        if (suggestedMethodsNumber > len - 2) {
            System.out.println("Full client greeting message wasn't received");
            return;
        }
        if (suggestedMethodsNumber < len - 2) {
            throw new TooLongHandshakeMessage("Too long client's greeting message");
        }

        boolean isNoAuthMethodFound = false;
        for (int i = 0; i < suggestedMethodsNumber; i++) {
            byte method = data[i + 2];
            if (method == ProtocolParams.NO_AUTH) {
                isNoAuthMethodFound = true;
                break;
            }
        }

        if (!isNoAuthMethodFound) {
//            attachment.outputBuffer = attachment.inputBuffer;
//            attachment.outputBuffer.clear();
//            attachment.outputBuffer.put(proxy.ProtocolParams.NO_SUPPORTED_METHODS_REPLY).flip();
//            attachment.state = proxy.State.GREETING_WRITE;
//
//            key.interestOps(SelectionKey.OP_WRITE);

            throw new UnsupportedMethodException("Client's greeting message hasn't no auth method, only no auth method is supported");
        }
        System.out.println("Client's greeting message was parsed: " + data[0] + " " + data[1] + " " + data[2]);


        attachment.outputBuffer = attachment.inputBuffer;
        attachment.outputBuffer.clear();
        attachment.outputBuffer.put(ProtocolParams.AUTH_NO_AUTH_REPLY).flip();
        attachment.state = State.GREETING;

        key.interestOps(SelectionKey.OP_WRITE);
    }

    public static void connecting(SelectionKey clientKey) throws Socks5ProtocolException, IOException {
        Attachment attachment = (Attachment) clientKey.attachment();
        int length = attachment.inputBuffer.position();

        if (length < 4) {
            System.out.println("Full client's connecting message wasn't received");
            return;
        }

        byte[] data = attachment.inputBuffer.array();
        if (data[0] != ProtocolParams.VERSION) {
            throw new UnsupportedSocksVersionException("Client's connecting message has wrong version, only SOCKS5 is supported");
        }

        if (data[1] != ProtocolParams.COMMAND_CODE) {
            throw new UnsupportedCommandCode("0x02 and 0x03 commands are not supported, only 0x01 command code is supported");
        }

        if (data[3] == ProtocolParams.ADDR_TYPE_IPV4) {
            byte[] connectAddressBytes = new byte[] { data[4], data[5], data[6], data[7] };
            InetAddress connectAddress = InetAddress.getByAddress(connectAddressBytes);
            int connectPort = ((data[8] & 0xFF) << 8) + (data[9] & 0xFF);

            Attachment clientAttachment = (Attachment) clientKey.attachment();
            clientAttachment.ip = connectAddress;
            clientKey.attach(clientAttachment);
            //clientAttachment.port = connectPort;

            Proxy.registerServer(connectAddress, connectPort, clientKey);
            clientKey.interestOps(0);
        } else if (data[3] == ProtocolParams.ADDR_TYPE_IPV6) {
            throw new UnsupportedAddressType("IPv6 is not supported");
        } else if (data[3] == ProtocolParams.ADDR_TYPE_HOST) {
            byte hostLength = data[4];
            System.out.println("Host length: " + hostLength);
            int hostStart = 5;
            if (length < hostLength + hostStart + 2) {
                System.out.println("Full client's connecting message wasn't received");
                return;
            } else if (length > hostLength + hostStart + 2) {
                throw new TooLongHandshakeMessage("Too long client's connecting message");
            }

            String connectHost = new String(Arrays.copyOfRange(data, hostStart, hostStart + hostLength));
            int portPosition = hostStart + hostLength;
            int connectPort = ((data[portPosition] & 0xFF) << 8) + (data[portPosition + 1] & 0xFF);

            System.out.println("Resolving host " + connectHost + ":" + connectPort);

            clientKey.interestOps(0);
            ((Attachment) clientKey.attachment()).port = connectPort;
            Proxy.createHostResolveRequest(connectHost, connectPort, clientKey);
        }
        System.out.println("Client's connecting message was parsed");
    }
}
