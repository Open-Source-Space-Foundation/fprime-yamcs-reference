package org.fprimeyamcs.reference;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;

/**
 * Reassembles {@code Fw::FilePacket} downlink streams from F´ into complete
 * files in a YAMCS bucket. This is the YAMCS-side endpoint for file transfer
 * over the F´ {@code FW_PACKET_FILE} (APID 3) channel.
 *
 * <p>v0 scope: one in-flight downlink transfer at a time, no retransmit, no
 * uplink. Cancel packets are logged and drop the in-flight transfer. See
 * {@code docs/file-transfer-integration.md} for the full design.
 *
 * <p>Wire format reference: {@code lib/fprime/Fw/FilePacket/FilePacket.hpp}.
 * Cross-validated against F´'s C++ encoder by the Python codec at
 * {@code tools/fprime_filepacket/} and its L1 oracle test.
 *
 * <p>Configured under {@code services:} in {@code yamcs.fprime-project.yaml}:
 * <pre>
 *   - class: org.fprimeyamcs.reference.FprimeFilePacketService
 *     args:
 *       inStream: tm_realtime          # default
 *       bucket: fprimeFilesIn          # default
 *       fileApid: 3                    # default; FW_PACKET_FILE
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

    // Configuration
    private String inStreamName;
    private String bucketName;
    private int fileApid;

    // Runtime
    private Stream inStream;
    private Bucket bucket;

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
        spec.addOption("fileApid", OptionType.INTEGER).withDefault(DEFAULT_FILE_APID);
        return spec;
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        this.inStreamName = config.getString("inStream", "tm_realtime");
        this.bucketName = config.getString("bucket", "fprimeFilesIn");
        this.fileApid = config.getInt("fileApid", DEFAULT_FILE_APID);

        LOG.info("FprimeFilePacketService init: inStream={} bucket={} fileApid={}",
                inStreamName, bucketName, fileApid);
    }

    // ----------------------------------------------------------------------
    // Service lifecycle
    // ----------------------------------------------------------------------

    @Override
    protected void doStart() {
        try {
            // Resolve the TM stream we'll subscribe to.
            YarchDatabase yarch = YarchDatabase.getInstance(yamcsInstance);
            this.inStream = yarch.getStream(inStreamName);
            if (this.inStream == null) {
                notifyFailed(new IllegalStateException(
                        "Stream not found: " + inStreamName));
                return;
            }

            // Resolve (and lazily create) the destination bucket.
            BucketManager bm = YamcsServer.getServer().getBucketManager();
            this.bucket = bm.getBucket(bucketName);
            if (this.bucket == null) {
                LOG.info("Bucket {} not found, creating", bucketName);
                this.bucket = bm.createBucket(bucketName);
            }

            this.inStream.addSubscriber(this);
            LOG.info("FprimeFilePacketService started, subscribed to {}", inStreamName);
            notifyStarted();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        if (inStream != null) {
            inStream.removeSubscriber(this);
        }
        notifyStopped();
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
            bucket.putObject(objectName, "application/octet-stream", null,
                    inflight.reassemblyBuffer);
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
}
