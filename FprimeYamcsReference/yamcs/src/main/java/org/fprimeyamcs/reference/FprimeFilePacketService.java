package org.fprimeyamcs.reference;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.AbstractYamcsService;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.buckets.Bucket;
import org.yamcs.buckets.BucketManager;
import org.yamcs.buckets.ObjectProperties;
import org.yamcs.tctm.ccsds.error.CrcCciitCalculator;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Handles {@code Fw::FilePacket} file transfer to and from F´ over the
 * {@code FW_PACKET_FILE} (APID 3) channel.
 *
 * <p><b>Downlink</b>: subscribes to a TM stream (default {@code tm_realtime}),
 * filters for the file APID, demuxes by Fw::FilePacket type, reassembles the
 * file, validates the CFDP modular checksum, and writes the complete file
 * into an incoming bucket.
 *
 * <p><b>Uplink (v0 — bucket-watch, direct UDP)</b>: on a scheduled interval
 * polls an outgoing bucket for new objects. For each object, reads the bytes,
 * generates the {@code Fw::FilePacket} Start/Data/End sequence, wraps each in
 * a CCSDS space packet on the file APID, wraps each space packet in a CCSDS
 * TC Type-BD transfer frame with a CRC16-CCITT FECF trailer, and sends the
 * frames directly via UDP to the F´ binary's TC port — bypassing YAMCS's own
 * TC data link. After a successful send, deletes the object from the bucket.
 *
 * <p>This is a v0 deliberate simplification. A future step will route uplink
 * through a proper YAMCS command so it inherits the command-history /
 * authorization / verifier pipeline. See the "Vision" section of
 * {@code docs/file-transfer-integration.md} (options U1/U2/U3).
 *
 * <p>v0 scope limitations:
 * <ul>
 *   <li>One in-flight downlink transfer at a time
 *   <li>One uplink at a time (polling is serial)
 *   <li>No retransmit on either side
 *   <li>Uplink bypasses YAMCS command history; audit lives in service logs
 * </ul>
 *
 * <p>Wire format reference: {@code lib/fprime/Fw/FilePacket/FilePacket.hpp}
 * for the Fw::FilePacket layout, and
 * {@code lib/fprime/Svc/Ccsds/TcDeframer/TcDeframer.cpp} for the TC frame
 * layout. Cross-validated against F´'s C++ encoder by the Python codec at
 * {@code tools/fprime_filepacket/} (L1 oracle) and against the real F´
 * binary by {@code tools/l3_fake_spacecraft.py} and
 * {@code tools/l3_uplink_harness.py}.
 *
 * <p>Configured under {@code services:} in {@code yamcs.fprime-project.yaml}:
 * <pre>
 *   - class: org.fprimeyamcs.reference.FprimeFilePacketService
 *     args:
 *       inStream: tm_realtime          # default
 *       bucket: fprimeFilesIn          # incoming bucket
 *       outBucket: fprimeFilesOut      # outgoing bucket (uplink queue)
 *       fileApid: 3                    # default; FW_PACKET_FILE
 *       fprimeHost: 127.0.0.1          # F´ TC address
 *       fprimeTcPort: 50001            # F´ TC port
 *       uplinkIntervalMs: 2000         # bucket poll interval
 *       uplinkChunkSize: 128           # bytes per Fw::FilePacket DataPacket
 *       spacecraftId: 68               # must match F´ ComCfg
 *       vcId: 1
 * </pre>
 */
public class FprimeFilePacketService extends AbstractYamcsService implements StreamSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(FprimeFilePacketService.class);

    // CCSDS Space Packet primary header is always 6 bytes.
    private static final int CCSDS_PRIMARY_HEADER_LEN = 6;

    // F´ ComCfg::Apid::FW_PACKET_FILE — see lib/fprime/default/config/ComCfg.fpp.
    private static final int DEFAULT_FILE_APID = 3;

    // F´ FwPacketDescriptorType is U16. After the CCSDS primary header, the
    // payload starts with this descriptor, which equals FW_PACKET_FILE for
    // file packets. See FileDownlink.cpp:357-359.
    private static final int FW_PACKET_DESCRIPTOR_LEN = 2;
    private static final int FW_PACKET_FILE_DESCRIPTOR = 0x0003;

    // Fw::FilePacket::Header is U8 type + U32 sequenceIndex.
    private static final int FILE_PACKET_HEADER_LEN = 5;

    // Fw::FilePacket::Type enum values from FilePacket.hpp:40.
    private static final int T_START = 0;
    private static final int T_DATA = 1;
    private static final int T_END = 2;
    private static final int T_CANCEL = 3;

    // Configuration — downlink
    private String inStreamName;
    private String bucketName;
    private int fileApid;

    // Configuration — uplink
    private String outBucketName;
    private String fprimeHost;
    private int fprimeTcPort;
    private long uplinkIntervalMs;
    private int uplinkChunkSize;
    private int spacecraftId;
    private int vcId;

    // Runtime — downlink
    private Stream inStream;
    private Bucket bucket;

    // Runtime — uplink
    private Bucket outBucket;
    private DatagramSocket uplinkSocket;
    private InetAddress fprimeAddr;
    private ScheduledExecutorService scheduler;
    private final CrcCciitCalculator crc = new CrcCciitCalculator();
    // Space packet sequence counter for uplink. 14 bits, wraps.
    private int uplinkSpSeq = 0;
    // Objects we've already attempted to upload this run. In v0 we delete
    // successful uploads from the bucket so we never see them again; this
    // set prevents re-uploading failed ones in a tight loop.
    private final Set<String> uplinkFailed = new HashSet<>();

    // In-flight downlink transfer state. v0 supports one transfer at a time.
    // null means idle.
    private Transfer inflight;

    private static final class Transfer {
        final String sourcePath;
        final String destinationPath;
        final byte[] reassemblyBuffer;
        final int declaredSize;
        int bytesReceived;
        int lastSeqIndex;

        Transfer(String src, String dst, int size, int startSeq) {
            this.sourcePath = src;
            this.destinationPath = dst;
            this.declaredSize = size;
            this.reassemblyBuffer = new byte[size];
            this.bytesReceived = 0;
            this.lastSeqIndex = startSeq;
        }
    }

    // ----------------------------------------------------------------------
    // Spec / configuration
    // ----------------------------------------------------------------------

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();
        spec.addOption("inStream", OptionType.STRING).withDefault("tm_realtime");
        spec.addOption("bucket", OptionType.STRING).withDefault("fprimeFilesIn");
        spec.addOption("outBucket", OptionType.STRING).withDefault("fprimeFilesOut");
        spec.addOption("fileApid", OptionType.INTEGER).withDefault(DEFAULT_FILE_APID);
        spec.addOption("fprimeHost", OptionType.STRING).withDefault("127.0.0.1");
        spec.addOption("fprimeTcPort", OptionType.INTEGER).withDefault(50001);
        spec.addOption("uplinkIntervalMs", OptionType.INTEGER).withDefault(2000);
        spec.addOption("uplinkChunkSize", OptionType.INTEGER).withDefault(128);
        spec.addOption("spacecraftId", OptionType.INTEGER).withDefault(68);
        spec.addOption("vcId", OptionType.INTEGER).withDefault(1);
        return spec;
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        this.inStreamName = config.getString("inStream", "tm_realtime");
        this.bucketName = config.getString("bucket", "fprimeFilesIn");
        this.outBucketName = config.getString("outBucket", "fprimeFilesOut");
        this.fileApid = config.getInt("fileApid", DEFAULT_FILE_APID);
        this.fprimeHost = config.getString("fprimeHost", "127.0.0.1");
        this.fprimeTcPort = config.getInt("fprimeTcPort", 50001);
        this.uplinkIntervalMs = config.getLong("uplinkIntervalMs", 2000L);
        this.uplinkChunkSize = config.getInt("uplinkChunkSize", 128);
        this.spacecraftId = config.getInt("spacecraftId", 68);
        this.vcId = config.getInt("vcId", 1);

        LOG.info("FprimeFilePacketService init: inStream={} bucket={} outBucket={} fileApid={}"
                + " fprime={}:{} uplinkInterval={}ms chunk={}B scid={} vcid={}",
                inStreamName, bucketName, outBucketName, fileApid, fprimeHost, fprimeTcPort,
                uplinkIntervalMs, uplinkChunkSize, spacecraftId, vcId);
    }

    // ----------------------------------------------------------------------
    // Service lifecycle
    // ----------------------------------------------------------------------

    @Override
    protected void doStart() {
        try {
            // --- Downlink setup ---
            YarchDatabaseInstance yarch = YarchDatabase.getInstance(yamcsInstance);
            this.inStream = yarch.getStream(inStreamName);
            if (this.inStream == null) {
                notifyFailed(new IllegalStateException("Stream not found: " + inStreamName));
                return;
            }

            BucketManager bm = YamcsServer.getServer().getBucketManager();
            this.bucket = getOrCreateBucket(bm, bucketName);
            this.outBucket = getOrCreateBucket(bm, outBucketName);

            this.inStream.addSubscriber(this);

            // --- Uplink setup ---
            this.fprimeAddr = InetAddress.getByName(fprimeHost);
            this.uplinkSocket = new DatagramSocket();
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FprimeFilePacketService-uplink");
                t.setDaemon(true);
                return t;
            });
            this.scheduler.scheduleWithFixedDelay(
                    this::pollOutgoingBucket,
                    uplinkIntervalMs,
                    uplinkIntervalMs,
                    TimeUnit.MILLISECONDS);

            LOG.info("FprimeFilePacketService started: subscribed to {}, "
                    + "polling {} every {} ms, uplink to {}:{}",
                    inStreamName, outBucketName, uplinkIntervalMs, fprimeHost, fprimeTcPort);
            notifyStarted();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (uplinkSocket != null) {
            uplinkSocket.close();
        }
        if (inStream != null) {
            inStream.removeSubscriber(this);
        }
        notifyStopped();
    }

    private Bucket getOrCreateBucket(BucketManager bm, String name) throws Exception {
        Bucket b = bm.getBucket(name);
        if (b == null) {
            LOG.info("Bucket {} not found, creating", name);
            b = bm.createBucket(name);
        }
        return b;
    }

    // ----------------------------------------------------------------------
    // StreamSubscriber: called for every packet on the TM stream
    // ----------------------------------------------------------------------

    @Override
    public void onTuple(Stream stream, Tuple tuple) {
        // Each tm_realtime tuple has a "packet" column with the raw CCSDS bytes.
        Object packetCol = tuple.getColumn("packet");
        if (!(packetCol instanceof byte[])) {
            return;
        }
        byte[] bytes = (byte[]) packetCol;
        if (bytes.length < CCSDS_PRIMARY_HEADER_LEN + FW_PACKET_DESCRIPTOR_LEN + FILE_PACKET_HEADER_LEN) {
            return;  // Too short to be a file packet; some other APID.
        }

        // Extract APID from CCSDS primary header (bits 0-10 of bytes 0..1).
        int packetId = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        int apid = packetId & 0x07FF;
        if (apid != fileApid) {
            return;  // Not a file packet.
        }

        // After the CCSDS primary header, the F´ ComPacket descriptor (U16 BE).
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        int descriptor = bb.getShort(CCSDS_PRIMARY_HEADER_LEN) & 0xFFFF;
        if (descriptor != FW_PACKET_FILE_DESCRIPTOR) {
            LOG.warn("Got APID {} but unexpected ComPacket descriptor 0x{}",
                    apid, Integer.toHexString(descriptor));
            return;
        }

        int innerStart = CCSDS_PRIMARY_HEADER_LEN + FW_PACKET_DESCRIPTOR_LEN;
        int type = bytes[innerStart] & 0xFF;
        int seqIndex = bb.getInt(innerStart + 1);
        int payloadStart = innerStart + FILE_PACKET_HEADER_LEN;

        try {
            switch (type) {
                case T_START:
                    handleStart(bytes, payloadStart, seqIndex);
                    break;
                case T_DATA:
                    handleData(bytes, payloadStart, seqIndex);
                    break;
                case T_END:
                    handleEnd(bytes, payloadStart, seqIndex);
                    break;
                case T_CANCEL:
                    handleCancel(seqIndex);
                    break;
                default:
                    LOG.warn("Unknown FilePacket type {} at seq {}", type, seqIndex);
            }
        } catch (Exception e) {
            LOG.error("Error processing FilePacket type={} seq={}", type, seqIndex, e);
            inflight = null;
        }
    }

    @Override
    public void streamClosed(Stream stream) {
        LOG.info("Stream {} closed", stream.getName());
    }

    // ----------------------------------------------------------------------
    // FilePacket handlers
    // ----------------------------------------------------------------------

    private void handleStart(byte[] bytes, int offset, int seqIndex) {
        if (inflight != null) {
            LOG.warn("Got T_START while transfer in progress; dropping previous");
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        int fileSize = bb.getInt(offset);
        int srcLen = bytes[offset + 4] & 0xFF;
        String src = new String(bytes, offset + 5, srcLen, StandardCharsets.US_ASCII);
        int dstLenOffset = offset + 5 + srcLen;
        int dstLen = bytes[dstLenOffset] & 0xFF;
        String dst = new String(bytes, dstLenOffset + 1, dstLen, StandardCharsets.US_ASCII);

        LOG.info("File transfer START: seq={} size={} src={} dst={}",
                seqIndex, fileSize, src, dst);
        inflight = new Transfer(src, dst, fileSize, seqIndex);
    }

    private void handleData(byte[] bytes, int offset, int seqIndex) {
        if (inflight == null) {
            LOG.warn("Got T_DATA seq={} with no in-flight transfer; dropping", seqIndex);
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        int byteOffset = bb.getInt(offset);
        int dataSize = bb.getShort(offset + 4) & 0xFFFF;
        int dataStart = offset + 6;

        if (byteOffset + dataSize > inflight.declaredSize) {
            LOG.error("DATA packet would overflow file: byteOffset={} dataSize={} declared={}",
                    byteOffset, dataSize, inflight.declaredSize);
            inflight = null;
            return;
        }

        System.arraycopy(bytes, dataStart, inflight.reassemblyBuffer, byteOffset, dataSize);
        inflight.bytesReceived += dataSize;
        inflight.lastSeqIndex = seqIndex;
    }

    private void handleEnd(byte[] bytes, int offset, int seqIndex) {
        if (inflight == null) {
            LOG.warn("Got T_END seq={} with no in-flight transfer", seqIndex);
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        int receivedChecksum = bb.getInt(offset);
        int computed = cfdpModularChecksum(inflight.reassemblyBuffer);

        if (computed != receivedChecksum) {
            LOG.error("Checksum mismatch on transfer {}: received=0x{} computed=0x{}",
                    inflight.destinationPath,
                    Integer.toHexString(receivedChecksum),
                    Integer.toHexString(computed));
            inflight = null;
            return;
        }

        // Strip leading "/" from the destination so the bucket sees a relative key.
        String objectName = inflight.destinationPath.startsWith("/")
                ? inflight.destinationPath.substring(1)
                : inflight.destinationPath;

        try {
            // putObjectAsync returns a CompletableFuture<Void>; block on it so
            // we can log a single COMPLETE/FAILED line per transfer instead of
            // racing the next packet on the stream.
            bucket.putObjectAsync(objectName, "application/octet-stream",
                    Map.of(), inflight.reassemblyBuffer).join();
            LOG.info("File transfer COMPLETE: {} ({} bytes) -> bucket {}",
                    objectName, inflight.bytesReceived, bucketName);
        } catch (Exception e) {
            LOG.error("Failed to store file in bucket", e);
        } finally {
            inflight = null;
        }
    }

    private void handleCancel(int seqIndex) {
        if (inflight != null) {
            LOG.warn("File transfer CANCELLED at seq {} (was: {})",
                    seqIndex, inflight.destinationPath);
            inflight = null;
        } else {
            LOG.warn("Got T_CANCEL seq={} with no in-flight transfer", seqIndex);
        }
    }

    // ----------------------------------------------------------------------
    // CFDP modular checksum (CCSDS 727.0-B § 4.1.2)
    //
    // Direct port of lib/fprime/CFDP/Checksum/Checksum.cpp::update.
    // Cross-validated against F´'s C++ via tools/fprime_filepacket/oracle/.
    // ----------------------------------------------------------------------

    static int cfdpModularChecksum(byte[] data) {
        int csum = 0;
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            csum += b << (8 * (3 - (i % 4)));
        }
        return csum;
    }

    // ----------------------------------------------------------------------
    // UPLINK — bucket watcher, frame builder, UDP sender
    //
    // This half mirrors tools/l3_uplink_harness.py, which was validated
    // bit-for-bit against the real F´ binary. Any change to the wire
    // format should be kept in sync with that harness.
    // ----------------------------------------------------------------------

    /**
     * Scheduled poll of the outgoing bucket. For each object present,
     * upload it to F´ as a file and delete it on success.
     */
    private void pollOutgoingBucket() {
        try {
            List<ObjectProperties> objects = outBucket.listObjectsAsync().join();
            for (ObjectProperties obj : objects) {
                String name = obj.name();
                if (uplinkFailed.contains(name)) {
                    continue;
                }
                try {
                    uplinkObject(name);
                    outBucket.deleteObjectAsync(name).join();
                    LOG.info("Uplink COMPLETE: {} deleted from bucket {}", name, outBucketName);
                } catch (Exception e) {
                    LOG.error("Uplink FAILED for {}: leaving in bucket, will not retry", name, e);
                    uplinkFailed.add(name);
                }
            }
        } catch (Exception e) {
            LOG.error("Error polling outgoing bucket {}", outBucketName, e);
        }
    }

    /**
     * Upload one bucket object to F´ as a sequence of
     * {@code Fw::FilePacket} Start + Data + End packets, each wrapped in
     * a CCSDS space packet, each wrapped in a CCSDS TC transfer frame,
     * each sent as a single UDP datagram.
     *
     * <p>The bucket object's name is used as the F´ destination path
     * (relative to F´'s working directory). v0 does not yet support a
     * separate source path, so the source path is set to the object
     * name as well.
     */
    private void uplinkObject(String objectName) throws Exception {
        byte[] content = outBucket.getObjectAsync(objectName).join();
        if (content == null) {
            throw new IllegalStateException("Object " + objectName + " vanished");
        }
        LOG.info("Uplink START: {} ({} bytes) -> {}:{}",
                objectName, content.length, fprimeHost, fprimeTcPort);

        int seq = 0;
        // Start packet
        sendFilePacket(buildStartPacket(seq, content.length, objectName, objectName));

        // Data×N
        for (int offset = 0; offset < content.length; offset += uplinkChunkSize) {
            int len = Math.min(uplinkChunkSize, content.length - offset);
            seq++;
            sendFilePacket(buildDataPacket(seq, offset, content, offset, len));
        }

        // End
        seq++;
        sendFilePacket(buildEndPacket(seq, cfdpModularChecksum(content)));
    }

    /**
     * Wrap a raw {@code Fw::FilePacket} byte sequence (which already
     * includes the 2-byte ComPacket descriptor prefix) in a CCSDS space
     * packet, wrap that in a CCSDS TC transfer frame, and send it.
     */
    private void sendFilePacket(byte[] innerWithDescriptor) throws Exception {
        byte[] spacePacket = buildSpacePacket(innerWithDescriptor, uplinkSpSeq++);
        byte[] frame = buildTcFrame(spacePacket);
        uplinkSocket.send(new DatagramPacket(frame, frame.length, fprimeAddr, fprimeTcPort));
        // Slow the stream down so F´'s frame accumulator doesn't drop
        // back-to-back datagrams. Mirrors the Python harness's 20 ms
        // inter-frame delay.
        try {
            Thread.sleep(20);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        }
    }

    // ----------------------------------------------------------------------
    // Fw::FilePacket encoders (mirrors tools/fprime_filepacket/codec.py)
    // ----------------------------------------------------------------------

    private static byte[] buildStartPacket(int seq, int fileSize, String srcPath, String dstPath) {
        byte[] src = srcPath.getBytes(StandardCharsets.US_ASCII);
        byte[] dst = dstPath.getBytes(StandardCharsets.US_ASCII);
        if (src.length > 255 || dst.length > 255) {
            throw new IllegalArgumentException("Path too long");
        }
        // [descriptor U16=3][type U8=0][seq U32][fileSize U32][srcLen U8][src][dstLen U8][dst]
        ByteBuffer bb = ByteBuffer.allocate(2 + 5 + 4 + 1 + src.length + 1 + dst.length);
        bb.putShort((short) FW_PACKET_FILE_DESCRIPTOR);
        bb.put((byte) T_START);
        bb.putInt(seq);
        bb.putInt(fileSize);
        bb.put((byte) src.length).put(src);
        bb.put((byte) dst.length).put(dst);
        return bb.array();
    }

    private static byte[] buildDataPacket(int seq, int byteOffset, byte[] source, int srcOff, int len) {
        // [descriptor U16=3][type U8=1][seq U32][byteOffset U32][dataSize U16][data]
        ByteBuffer bb = ByteBuffer.allocate(2 + 5 + 4 + 2 + len);
        bb.putShort((short) FW_PACKET_FILE_DESCRIPTOR);
        bb.put((byte) T_DATA);
        bb.putInt(seq);
        bb.putInt(byteOffset);
        bb.putShort((short) len);
        bb.put(source, srcOff, len);
        return bb.array();
    }

    private static byte[] buildEndPacket(int seq, int checksum) {
        // [descriptor U16=3][type U8=2][seq U32][checksum U32]
        ByteBuffer bb = ByteBuffer.allocate(2 + 5 + 4);
        bb.putShort((short) FW_PACKET_FILE_DESCRIPTOR);
        bb.put((byte) T_END);
        bb.putInt(seq);
        bb.putInt(checksum);
        return bb.array();
    }

    // ----------------------------------------------------------------------
    // CCSDS space packet builder (type=TC, APID=file)
    // ----------------------------------------------------------------------

    private byte[] buildSpacePacket(byte[] payload, int seqCount) {
        int dataLenField = payload.length - 1;  // CCSDS convention
        ByteBuffer bb = ByteBuffer.allocate(CCSDS_PRIMARY_HEADER_LEN + payload.length);
        // Word 0: 3b version(0) | 1b type(1 = TC) | 1b secHdr(0) | 11b APID
        int packetId = (0 << 13) | (1 << 12) | (0 << 11) | (fileApid & 0x07FF);
        bb.putShort((short) packetId);
        // Word 1: 2b seqFlags (0b11 = standalone) | 14b seqCount
        int seqCtrl = (0b11 << 14) | (seqCount & 0x3FFF);
        bb.putShort((short) seqCtrl);
        // Word 2: 16b data length
        bb.putShort((short) dataLenField);
        bb.put(payload);
        return bb.array();
    }

    // ----------------------------------------------------------------------
    // CCSDS TC Type-BD transfer frame builder
    //
    // Format (from lib/fprime/Svc/Ccsds/TcDeframer/TcDeframer.cpp:36-49):
    //   2b  version = 00
    //   1b  bypass flag = 1  (Type-B — no FARM sequence checking)
    //   1b  ctrl cmd flag = 0  (Type-D data frame)
    //   2b  spare = 00
    //   10b spacecraft id
    //   6b  virtual channel id
    //   10b frame length (total bytes - 1)
    //   8b  frame sequence number (unused for Type-B, 0)
    //   variable data field
    //   16b FECF = CRC16-CCITT over [header + data field]
    // ----------------------------------------------------------------------

    private static final int TC_HEADER_LEN = 5;
    private static final int TC_TRAILER_LEN = 2;

    private byte[] buildTcFrame(byte[] dataField) {
        int total = TC_HEADER_LEN + dataField.length + TC_TRAILER_LEN;
        int lengthField = total - 1;  // "frame length is bytes minus 1"

        byte[] frame = new byte[total];
        // Word 0: ver(2)=00 | byp(1)=1 | ctrl(1)=0 | spare(2)=00 | scid(10)
        int word0 = (0 << 14) | (1 << 13) | (0 << 12) | (0 << 10) | (spacecraftId & 0x03FF);
        frame[0] = (byte) ((word0 >> 8) & 0xFF);
        frame[1] = (byte) (word0 & 0xFF);
        // Word 1: vcid(6) | length(10)
        int word1 = ((vcId & 0x3F) << 10) | (lengthField & 0x03FF);
        frame[2] = (byte) ((word1 >> 8) & 0xFF);
        frame[3] = (byte) (word1 & 0xFF);
        // Word 2: seq (unused for Type-B)
        frame[4] = 0;
        // Data field
        System.arraycopy(dataField, 0, frame, TC_HEADER_LEN, dataField.length);
        // FECF: CRC16-CCITT over header + data
        int fecf = crc.compute(frame, 0, TC_HEADER_LEN + dataField.length);
        frame[total - 2] = (byte) ((fecf >> 8) & 0xFF);
        frame[total - 1] = (byte) (fecf & 0xFF);
        return frame;
    }
}
