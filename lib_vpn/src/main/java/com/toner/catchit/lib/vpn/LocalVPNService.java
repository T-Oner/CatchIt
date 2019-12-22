import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.toner.catchit.lib.vpn.R;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

public class LocalVPNService extends VpnService {
    private static final String TAG = "LocalVPNService";
    private ParcelFileDescriptor vpnInterface = null;
    private static final String VPN_ADDRESS = "10.0.0.2"; // Only IPv4 support for now
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything
    public static final int MUTE_SIZE = 2560;
    private ParcelFileDescriptor mInterface;
    private String mParameters;

    private boolean run(InetSocketAddress server) throws Exception {
        DatagramChannel tunnel = null;
        boolean connected = false;
        try {
            // Create a DatagramChannel as the VPN tunnel.
            tunnel = DatagramChannel.open();

            // Protect the tunnel before connecting to avoid loopback.
            if (!protect(tunnel.socket())) {
                throw new IllegalStateException("Cannot protect the tunnel");
            }

            // Connect to the server.
            tunnel.connect(server);

            // For simplicity, we use the same thread for both reading and
            // writing. Here we put the tunnel into non-blocking mode.
            tunnel.configureBlocking(false);

            // Authenticate and configure the virtual network interface.
            handshake(tunnel);

            // Now we are connected. Set the flag and show the message.
            connected = true;

            // Packets to be sent are queued in this input stream.
            FileInputStream in = new FileInputStream(mInterface.getFileDescriptor());

            // Packets received need to be written to this output stream.
            FileOutputStream out = new FileOutputStream(mInterface.getFileDescriptor());

            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(32767);

            // We use a timer to determine the status of the tunnel. It
            // works on both sides. A positive value means sending, and
            // any other means receiving. We start with receiving.
            int timer = 0;

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Assume that we did not make any progress in this iteration.
                boolean idle = true;

                // Read the outgoing packet from the input stream.
                int length = in.read(packet.array());
                if (length > 0) {
                    // Write the outgoing packet to the tunnel.
                    packet.limit(length);
                    tunnel.write(packet);
                    packet.clear();

                    // There might be more outgoing packets.
                    idle = false;

                    // If we were receiving, switch to sending.
                    if (timer < 1) {
                        timer = 1;
                    }
                }

                // Read the incoming packet from the tunnel.
                length = tunnel.read(packet);
                if (length > 0) {
                    // Ignore control messages, which start with zero.
                    if (packet.get(0) != 0) {
                        // Write the incoming packet to the output stream.
                        out.write(packet.array(), 0, length);
                    }
                    packet.clear();

                    // There might be more incoming packets.
                    idle = false;

                    // If we were sending, switch to receiving.
                    if (timer > 0) {
                        timer = 0;
                    }
                }

                // If we are idle or waiting for the network, sleep for a
                // fraction of time to avoid busy looping.
                if (idle) {
                    Thread.sleep(100);

                    // Increase the timer. This is inaccurate but good enough,
                    // since everything is operated in non-blocking mode.
                    timer += (timer > 0) ? 100 : -100;

                    // We are receiving for a long time but not sending.
                    if (timer < -15000) {
                        // Send empty control messages.
                        packet.put((byte) 0).limit(1);
                        for (int i = 0; i < 3; ++i) {
                            packet.position(0);
                            tunnel.write(packet);
                        }
                        packet.clear();

                        // Switch to sending.
                        timer = 1;
                    }

                    // We are sending for a long time but not receiving.
                    if (timer > 20000) {
                        throw new IllegalStateException("Timed out");
                    }
                }
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "Got " + e.toString());
        } finally {
            try {
                tunnel.close();
            } catch (Exception e) {
                // ignore
            }
        }
        return connected;
    }

    private void handshake(DatagramChannel tunnel) throws Exception {
        // To build a secured tunnel, we should perform mutual authentication
        // and exchange session keys for encryption. To keep things simple in
        // this demo, we just send the shared secret in plaintext and wait
        // for the server to send the parameters.

        // Allocate the buffer for handshaking.
        ByteBuffer packet = ByteBuffer.allocate(1024);

        // Control messages always start with zero.
        packet.put((byte) 0).put((byte) 0).flip();

        // Send the secret several times in case of packet loss.
        for (int i = 0; i < 3; ++i) {
            packet.position(0);
            tunnel.write(packet);
        }
        packet.clear();

        // Wait for the parameters within a limited time.
        for (int i = 0; i < 50; ++i) {
            Thread.sleep(100);

            // Normally we should not receive random packets.
            int length = tunnel.read(packet);
            if (length > 0 && packet.get(0) == 0) {
                configure(new String(packet.array(), 1, length - 1).trim());
                return;
            }
        }
        throw new IllegalStateException("Timed out");
    }

    private void configure(String parameters) throws Exception {
        // If the old interface has exactly the same parameters, use it!
        if (mInterface != null) {
            Log.i(TAG, "Using the previous interface");
            return;
        }

        // Configure a builder while parsing the parameters.
        Builder builder = new Builder();
        for (String parameter : parameters.split(" ")) {
            String[] fields = parameter.split(",");
            try {
                switch (fields[0].charAt(0)) {
                    case 'm':
                        builder.setMtu(Short.parseShort(fields[1]));
                        break;
                    case 'a':
                        builder.addAddress(fields[1], Integer.parseInt(fields[2]));
                        break;
                    case 'r':
                        builder.addRoute(fields[1], Integer.parseInt(fields[2]));
                        break;
                    case 'd':
                        builder.addDnsServer(fields[1]);
                        break;
                    case 's':
                        builder.addSearchDomain(fields[1]);
                        break;
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Bad parameter: " + parameter);
            }
        }

        // Close the old interface since the parameters have been changed.
        try {
            mInterface.close();
        } catch (Exception e) {
            // ignore
        }

        // Create a new interface using the builder and save the parameters.
        mInterface = builder.setSession(VPN_ADDRESS)
                .establish();
        mParameters = parameters;
        Log.i(TAG, "New interface: " + parameters);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate: ");
//        setupVPN();
//        FileDescriptor fileDescriptor = vpnInterface.getFileDescriptor();
//        final FileInputStream inputChannel = new FileInputStream(fileDescriptor);
//        final FileOutputStream outputChannel = new FileOutputStream(fileDescriptor);
//        final byte[] mPacket = new byte[MUTE_SIZE];
//        final IPHeader mIPHeader = new IPHeader(mPacket, 0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LocalVPNService.this.run(new InetSocketAddress("10.0.0.2", 0));
                } catch (Exception e) {
                    e.printStackTrace();
                }
//                while (true) {
//                    try {
//                        int read = inputChannel.read(mPacket);
//                        if (read > 0) {
//                            if (mIPHeader.getProtocol() == IPHeader.TCP) {
//                            }
//                            byte[] bytes = "123".getBytes();
//                            int destinationIP = mIPHeader.getDestinationIP();
//                            int sourceIP = mIPHeader.getSourceIP();
//                            mIPHeader.setSourceIP(destinationIP);
//                            mIPHeader.setDestinationIP(sourceIP);
//                            outputChannel.write(mPacket, 0, read);
//                        }
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }
            }
        }).start();

    }

    private void setupVPN() {
        if (vpnInterface == null) {
            Builder builder = new Builder();
            builder.addAddress(VPN_ADDRESS, 32);
            builder.addRoute(VPN_ROUTE, 0);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    builder.addAllowedApplication("com.leidian.testwebview");
                }
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            vpnInterface = builder.setSession(getString(R.string.app_name)).setConfigureIntent(null).establish();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
}
