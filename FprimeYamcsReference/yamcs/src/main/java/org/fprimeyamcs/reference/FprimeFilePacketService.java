package org.fprimeyamcs.reference;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.YConfiguration;
import org.yamcs.Processor;
import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.buckets.Bucket;
import org.yamcs.buckets.BucketManager;
import org.yamcs.commanding.CommandingManager;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.filetransfer.AbstractFileTransferService;
import org.yamcs.filetransfer.FileTransfer;
import org.yamcs.filetransfer.FileTransferFilter;
import org.yamcs.filetransfer.InvalidRequestException;
import org.yamcs.filetransfer.TransferMonitor;
import org.yamcs.filetransfer.TransferOptions;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.protobuf.EntityInfo;
import org.yamcs.protobuf.FileTransferCapabilities;
import org.yamcs.protobuf.TransferDirection;
import org.yamcs.protobuf.TransferState;
import org.yamcs.security.User;
import org.yamcs.xtce.MetaCommand;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.ccsds.TcPacketHandler;
import org.yamcs.tctm.ccsds.error.CrcCciitCalculator;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Handles {@code Fw::FilePacket} file transfer to and from F´ over the
 * {@code FW_PACKET_FILE} (APID 3) channel, integrating with YAMCS's native
 * {@link org.yamcs.filetransfer.FileTransferService} interface.
 *
 * <p>Because this service implements {@code FileTransferService}, it is
 * auto-discovered by YAMCS's built-in {@code FileTransferApi} REST endpoints
 * (see {@code /api/filetransfer/{instance}/services}) and appears in the
 * stock {@code yamcs-web} File Transfer UI alongside any other configured
 * file transfer providers (e.g., CFDP). Operators trigger uplinks from the
 * same UI they'd use for CFDP transfers — no custom REST client, no XTCE
 * command hand-rolling.
 *
 * <p><b>Downlink</b>: subscribes to a TM stream (default {@code tm_realtime}),
 * filters for the file APID, demuxes by Fw::FilePacket type, reassembles the
 * file, validates the CFDP modular checksum, and writes the complete file
 * into an incoming bucket. Downlink is driven by the spacecraft (via F´'s
 * {@code FileDownlink.SendFile} command issued from YAMCS) — the
 * {@link #startDownload} entry point on this service is not yet implemented.
 *
 * <p><b>Uplink</b>: exposed through
 * {@link #startUpload(String, Bucket, String, String, String, TransferOptions)}
 * — called by YAMCS when an operator clicks "Upload" in the web UI. The
 * service reads the specified bucket object, generates an
 * {@code Fw::FilePacket} Start/Data×N/End sequence, wraps each in a CCSDS
 * space packet on the file APID, and hands each packet to the YAMCS-
 * configured TC data link's {@code TcPacketHandler.sendCommand()} API, which
 * runs the command postprocessor, frames in CCSDS TC, and emits via the
 * link's configured transport. If the link isn't available, a direct-UDP
 * fallback path is used.
 *
 * <p>v0 scope limitations:
 * <ul>
 *   <li>One in-flight downlink transfer at a time
 *   <li>Uplinks run serially on a single executor thread
 *   <li>No retransmit on either side (F´ {@code FilePacket} is
 *       fire-and-forget; this service reflects that)
 *   <li>No pause / resume / cancel (protocol doesn't support it)
 *   <li>{@link #startDownload} not yet implemented
 *   <li>No remote file listing
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
 *       fileApid: 3                    # default; FW_PACKET_FILE
 *       uplinkLink: UDP_TC_OUT.vc1     # YAMCS TC link to route through
 *       fprimeHost: 127.0.0.1          # direct-UDP fallback only
 *       fprimeTcPort: 50001            # direct-UDP fallback only
 *       uplinkChunkSize: 128           # bytes per Fw::FilePacket DataPacket
 *       spacecraftId: 68               # must match F´ ComCfg
 *       vcId: 1
 * </pre>
 */
public class FprimeFilePacketService extends AbstractFileTransferService implements StreamSubscriber {

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
    private String uplinkLinkName;  // null/empty => direct UDP fallback
    private String fprimeHost;
    private int fprimeTcPort;
    private int uplinkChunkSize;
    private int spacecraftId;
    private int vcId;

    // Configuration — downlink
    private String fileDownlinkCommandName;
    private String sourceFileNameArg;
    private String destFileNameArg;

    // Runtime — downlink
    private Stream inStream;
    private Bucket bucket;

    // Runtime — uplink
    // The two uplink transports. Exactly one is active per run depending on
    // whether uplinkLinkName resolves to a TcPacketHandler at doStart time.
    // Step 2 path: route through the YAMCS-configured TC data link.
    private TcPacketHandler uplinkLink;
    // Step 1 path: open our own UDP socket directly to F´. Only used if
    // the YAMCS link is unavailable (e.g. tests, misconfiguration).
    private DatagramSocket uplinkSocket;
    private InetAddress fprimeAddr;
    private final CrcCciitCalculator crc = new CrcCciitCalculator();
    // Space packet sequence counter. Only used on the direct-UDP fallback
    // path; on the YAMCS-link path the FprimeCommandPostprocessor fills it.
    private int uplinkSpSeq = 0;

    // Entity ids for the FileTransferService interface. Values are
    // arbitrary — YAMCS only requires them to be unique within the
    // respective local/remote sets.
    private static final long GROUND_ENTITY_ID = 1L;
    private static final long SPACECRAFT_ENTITY_ID = 2L;

    // FileTransferService runtime state.
    private ExecutorService uplinkExecutor;
    private final AtomicLong transferIdSeq = new AtomicLong(1);
    private final Map<Long, FprimeFileTransfer> transfers = new ConcurrentHashMap<>();
    private final List<TransferMonitor> transferMonitors = new CopyOnWriteArrayList<>();

    // Downlink routing: pending download transfers are keyed by the
    // destination path F´ will emit in the FileDownlink Start packet
    // (i.e. the bucket object name we asked F´ to use). When the Start
    // arrives, handleStart() looks up the pending transfer by that path
    // and attaches it to the reassembly so progress / completion flow
    // back to the REST/UI layer.
    private final Map<String, FprimeFileTransfer> pendingDownloadsByPath = new ConcurrentHashMap<>();

    // Resolved at doStart for downlink command synthesis.
    private Processor processor;
    private CommandingManager commandingManager;
    private MetaCommand fileDownlinkCommand;  // may be null if not in MDB
    private User systemUser;

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
        // Optional API-level transfer — non-null when this downlink was
        // triggered by a startDownload() call, letting handleData/handleEnd
        // push progress and completion state back to the REST/UI layer.
        FprimeFileTransfer apiTransfer;

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
        // Step 2 default: route uplink through the YAMCS-configured TC
        // data link (inherits postprocessor, link stats, transport swap).
        // Set to empty string to force the direct-UDP fallback.
        spec.addOption("uplinkLink", OptionType.STRING).withDefault("UDP_TC_OUT.vc1");
        spec.addOption("fprimeHost", OptionType.STRING).withDefault("127.0.0.1");
        spec.addOption("fprimeTcPort", OptionType.INTEGER).withDefault(50001);
        spec.addOption("uplinkChunkSize", OptionType.INTEGER).withDefault(128);
        spec.addOption("spacecraftId", OptionType.INTEGER).withDefault(68);
        spec.addOption("vcId", OptionType.INTEGER).withDefault(1);
        // Downlink: qualified name of the F´ command that triggers a
        // FileDownlink on the spacecraft, plus the names of its source
        // and destination path arguments.
        spec.addOption("fileDownlinkCommand", OptionType.STRING).withDefault(
                "/FprimeYamcsReference|YamcsDeployment/FileHandling|fileDownlink|SendFile");
        spec.addOption("sourceFileNameArg", OptionType.STRING).withDefault(
                "FileHandling|fileDownlink|SendFile|sourceFileName");
        spec.addOption("destFileNameArg", OptionType.STRING).withDefault(
                "FileHandling|fileDownlink|SendFile|destFileName");
        return spec;
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        this.inStreamName = config.getString("inStream", "tm_realtime");
        this.bucketName = config.getString("bucket", "fprimeFilesIn");
        this.fileApid = config.getInt("fileApid", DEFAULT_FILE_APID);
        this.uplinkLinkName = config.getString("uplinkLink", "UDP_TC_OUT.vc1");
        this.fprimeHost = config.getString("fprimeHost", "127.0.0.1");
        this.fprimeTcPort = config.getInt("fprimeTcPort", 50001);
        this.uplinkChunkSize = config.getInt("uplinkChunkSize", 128);
        this.spacecraftId = config.getInt("spacecraftId", 68);
        this.vcId = config.getInt("vcId", 1);
        this.fileDownlinkCommandName = config.getString("fileDownlinkCommand",
                "/FprimeYamcsReference|YamcsDeployment/FileHandling|fileDownlink|SendFile");
        this.sourceFileNameArg = config.getString("sourceFileNameArg",
                "FileHandling|fileDownlink|SendFile|sourceFileName");
        this.destFileNameArg = config.getString("destFileNameArg",
                "FileHandling|fileDownlink|SendFile|destFileName");

        LOG.info("FprimeFilePacketService init: inStream={} bucket={} fileApid={}"
                + " uplinkLink={} fprime={}:{} chunk={}B scid={} vcid={}",
                inStreamName, bucketName, fileApid, uplinkLinkName,
                fprimeHost, fprimeTcPort, uplinkChunkSize, spacecraftId, vcId);
    }

    // ----------------------------------------------------------------------
    // FileTransferService: capabilities, entities
    // ----------------------------------------------------------------------

    @Override
    protected void addCapabilities(FileTransferCapabilities.Builder b) {
        b.setUpload(true)          // operators can push files to F´
         .setDownload(true)        // operators can pull files from F´ (by
                                   // synthesizing an F´ FileDownlink.SendFile
                                   // command under the hood)
         .setRemotePath(true)      // paths on either side are arbitrary
         .setFileList(false)       // no remote file listing
         .setHasTransferType(false)
         .setPauseResume(false);
    }

    @Override
    public List<EntityInfo> getLocalEntities() {
        return List.of(EntityInfo.newBuilder()
                .setId(GROUND_ENTITY_ID)
                .setName("ground")
                .build());
    }

    @Override
    public List<EntityInfo> getRemoteEntities() {
        return List.of(EntityInfo.newBuilder()
                .setId(SPACECRAFT_ENTITY_ID)
                .setName("spacecraft")
                .build());
    }

    // FileListingService (parent interface) — we don't support remote
    // file listing. Capabilities.fileList is false, so the YAMCS UI will
    // not surface listing actions, but the interface still requires these
    // methods to be implemented.
    @Override
    public void saveFileList(org.yamcs.protobuf.ListFilesResponse listing) {
        // no-op
    }

    @Override
    public void fetchFileList(String localEntity, String remoteEntity,
                              String remotePath, Map<String, Object> options) {
        // no-op
    }

    @Override
    public org.yamcs.protobuf.ListFilesResponse getFileList(String localEntity,
            String remoteEntity, String remotePath, Map<String, Object> options) {
        return null;
    }

    @Override
    public void registerRemoteFileListMonitor(
            org.yamcs.filetransfer.RemoteFileListMonitor monitor) {
        // no-op
    }

    @Override
    public void unregisterRemoteFileListMonitor(
            org.yamcs.filetransfer.RemoteFileListMonitor monitor) {
        // no-op
    }

    @Override
    public void notifyRemoteFileListMonitors(
            org.yamcs.protobuf.ListFilesResponse listing) {
        // no-op
    }

    @Override
    public java.util.Set<org.yamcs.filetransfer.RemoteFileListMonitor>
            getRemoteFileListMonitors() {
        return java.util.Collections.emptySet();
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

            this.inStream.addSubscriber(this);

            // --- Uplink transport resolution ---
            // Prefer routing via the YAMCS-configured TC data link. Fall
            // back to a direct UDP socket if the link can't be resolved
            // as a TcPacketHandler.
            if (uplinkLinkName != null && !uplinkLinkName.isEmpty()) {
                YamcsServerInstance instance = YamcsServer.getServer().getInstance(yamcsInstance);
                Link link = instance.getLinkManager().getLink(uplinkLinkName);
                if (link instanceof TcPacketHandler) {
                    this.uplinkLink = (TcPacketHandler) link;
                    LOG.info("Uplink will route through YAMCS link {} (TcPacketHandler)",
                            uplinkLinkName);
                } else if (link == null) {
                    LOG.warn("Uplink link {} not found; falling back to direct UDP",
                            uplinkLinkName);
                } else {
                    LOG.warn("Uplink link {} is {} (not TcPacketHandler); falling back to direct UDP",
                            uplinkLinkName, link.getClass().getSimpleName());
                }
            }
            if (this.uplinkLink == null) {
                this.fprimeAddr = InetAddress.getByName(fprimeHost);
                this.uplinkSocket = new DatagramSocket();
                LOG.info("Uplink will send via direct UDP to {}:{}", fprimeHost, fprimeTcPort);
            }

            // Uplink worker: single-threaded so transfers serialize and
            // the space packet sequence counter stays monotonic.
            this.uplinkExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FprimeFilePacketService-uplink");
                t.setDaemon(true);
                return t;
            });

            // --- Downlink command synthesis setup ---
            YamcsServerInstance ysi = YamcsServer.getServer().getInstance(yamcsInstance);
            this.processor = ysi.getFirstProcessor();
            if (processor != null) {
                this.commandingManager = processor.getCommandingManager();
                this.fileDownlinkCommand = processor.getMdb().getMetaCommand(fileDownlinkCommandName);
                this.systemUser = YamcsServer.getServer().getSecurityStore().getSystemUser();
                if (fileDownlinkCommand == null) {
                    LOG.warn("File downlink command '{}' not found in MDB; "
                            + "startDownload() will fail with InvalidRequestException",
                            fileDownlinkCommandName);
                } else {
                    LOG.info("Downlink trigger resolved: {} via processor {}",
                            fileDownlinkCommandName, processor.getName());
                }
            } else {
                LOG.warn("No processor available; downlink will be disabled");
            }

            LOG.info("FprimeFilePacketService started: subscribed to {}, "
                    + "ready for file transfers via YAMCS FileTransferService API",
                    inStreamName);
            notifyStarted();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        if (uplinkExecutor != null) {
            uplinkExecutor.shutdownNow();
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
            failInflight("superseded by new T_START");
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

        // Link an API-level transfer record if one is present. Two cases:
        //   (a) This downlink was requested via startDownload(), so a
        //       transfer is already waiting in pendingDownloadsByPath.
        //   (b) This is an unsolicited downlink (command stack / REST
        //       triggered FileDownlink.SendFile directly). We create a
        //       fresh transfer on the fly so it still appears in the
        //       File Transfer UI alongside operator-initiated downlinks.
        FprimeFileTransfer api = pendingDownloadsByPath.remove(dst);
        if (api == null) {
            long id = transferIdSeq.getAndIncrement();
            api = new FprimeFileTransfer(id, bucketName, dst, src,
                    fileSize, TransferDirection.DOWNLOAD);
            api.setStartTime(System.currentTimeMillis());
            transfers.put(id, api);
            LOG.info("Unsolicited downlink; created transfer record id={}", id);
        } else {
            // Update the totalSize now that we know it from the Start packet.
            api.setTotalSize(fileSize);
        }
        api.setState(TransferState.RUNNING);
        inflight.apiTransfer = api;
        notifyStateChanged(api);
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
            failInflight("overflow in DATA packet");
            return;
        }

        System.arraycopy(bytes, dataStart, inflight.reassemblyBuffer, byteOffset, dataSize);
        inflight.bytesReceived += dataSize;
        inflight.lastSeqIndex = seqIndex;

        if (inflight.apiTransfer != null) {
            inflight.apiTransfer.setTransferredSize(inflight.bytesReceived);
            notifyStateChanged(inflight.apiTransfer);
        }
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
            failInflight(String.format(
                    "checksum mismatch: expected 0x%08x got 0x%08x",
                    receivedChecksum, computed));
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
            if (inflight.apiTransfer != null) {
                inflight.apiTransfer.setTransferredSize(inflight.bytesReceived);
                inflight.apiTransfer.setState(TransferState.COMPLETED);
                notifyStateChanged(inflight.apiTransfer);
            }
        } catch (Exception e) {
            LOG.error("Failed to store file in bucket", e);
            failInflight("bucket write failed: " + e.getMessage());
            return;
        } finally {
            inflight = null;
        }
    }

    private void handleCancel(int seqIndex) {
        if (inflight != null) {
            LOG.warn("File transfer CANCELLED at seq {} (was: {})",
                    seqIndex, inflight.destinationPath);
            failInflight("cancelled by spacecraft");
        } else {
            LOG.warn("Got T_CANCEL seq={} with no in-flight transfer", seqIndex);
        }
    }

    /**
     * Mark the in-flight downlink as failed, push the failure to its API
     * transfer if linked, and clear {@link #inflight}. Used by every error
     * path in the downlink state machine.
     */
    private void failInflight(String reason) {
        if (inflight == null) {
            return;
        }
        if (inflight.apiTransfer != null) {
            inflight.apiTransfer.setFailureReason(reason);
            inflight.apiTransfer.setState(TransferState.FAILED);
            notifyStateChanged(inflight.apiTransfer);
        }
        inflight = null;
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
    // FileTransferService: upload / download / transfer queries
    // ----------------------------------------------------------------------

    @Override
    public FileTransfer startUpload(String sourceEntity, Bucket sourceBucket,
                                    String objectName, String destinationEntity,
                                    String remotePath, TransferOptions options)
            throws IOException {
        // Parameter order per YAMCS FileTransferApi.createTransfer bytecode:
        //   (sourceEntityName, bucket, objectName, destEntityName, remotePath, options)
        // destinationEntity is the remote entity name ("spacecraft"); the
        // remotePath string is the actual path on the spacecraft where the
        // file should land. Don't confuse them — an earlier pass did, and
        // F´ ended up with a file literally named "spacecraft".
        if (sourceBucket == null) {
            throw new InvalidRequestException("sourceBucket is required");
        }
        if (objectName == null || objectName.isEmpty()) {
            throw new InvalidRequestException("objectName is required");
        }
        // Fetch the bytes synchronously so we can reject early (and
        // populate totalSize) if the object is missing. The actual
        // transmission runs on the uplink executor.
        byte[] content = sourceBucket.getObjectAsync(objectName).join();
        if (content == null) {
            throw new InvalidRequestException(
                    "No such object '" + objectName + "' in bucket " + sourceBucket.getName());
        }

        String dest = (remotePath == null || remotePath.isEmpty())
                ? objectName : remotePath;

        FprimeFileTransfer transfer = new FprimeFileTransfer(
                transferIdSeq.getAndIncrement(),
                sourceBucket.getName(),
                objectName,
                dest,
                content.length,
                TransferDirection.UPLOAD);
        transfers.put(transfer.getId(), transfer);
        notifyStateChanged(transfer);

        uplinkExecutor.submit(() -> runUplink(transfer, content));
        return transfer;
    }

    @Override
    public FileTransfer startDownload(String sourceEntity, String sourcePath,
                                      String destEntity, Bucket destBucket,
                                      String destPath, TransferOptions options)
            throws IOException {
        // Parameter order per YAMCS FileTransferApi.createTransfer bytecode
        // matches startUpload with sourcePath/remotePath rather than
        // a bucket object name.
        //   sourceEntity : remote entity name ("spacecraft")
        //   sourcePath   : path on the spacecraft to fetch (e.g. "README.md")
        //   destEntity   : local entity name ("ground")
        //   destBucket   : bucket to deposit the received file in
        //   destPath     : bucket object name to store it under
        //
        // v0 implementation: synthesize an F´ FileDownlink.SendFile command
        // with (sourceFileName=sourcePath, destFileName=destPath) and send
        // it through the configured processor's commanding manager. F´ will
        // emit Fw::FilePacket frames; our existing handleStart/handleData/
        // handleEnd pipeline reassembles them and writes to `destBucket`.
        // We cross-reference the two halves by `destPath` — the same string
        // appears in the Start packet's destinationPath field.
        if (fileDownlinkCommand == null) {
            throw new InvalidRequestException("Downlink command '"
                    + fileDownlinkCommandName + "' not found in MDB");
        }
        if (sourcePath == null || sourcePath.isEmpty()) {
            throw new InvalidRequestException("sourcePath (file on spacecraft) is required");
        }
        if (destBucket == null) {
            throw new InvalidRequestException("destBucket is required");
        }
        if (destPath == null || destPath.isEmpty()) {
            // Default to the basename of the source path, so operators can
            // leave the destination blank in the UI.
            destPath = sourcePath.contains("/")
                    ? sourcePath.substring(sourcePath.lastIndexOf('/') + 1)
                    : sourcePath;
        }

        long id = transferIdSeq.getAndIncrement();
        FprimeFileTransfer transfer = new FprimeFileTransfer(
                id,
                destBucket.getName(),
                destPath,           // bucket object name when it lands
                sourcePath,         // path on F´ we requested
                -1,                 // total size unknown until Start packet arrives
                TransferDirection.DOWNLOAD);
        transfer.setStartTime(System.currentTimeMillis());
        transfers.put(id, transfer);
        pendingDownloadsByPath.put(destPath, transfer);
        notifyStateChanged(transfer);

        // Build and dispatch the F´ SendFile command on behalf of the user.
        try {
            Map<String, Object> args = new java.util.HashMap<>();
            args.put(sourceFileNameArg, sourcePath);
            args.put(destFileNameArg, destPath);
            PreparedCommand pc = commandingManager.buildCommand(
                    fileDownlinkCommand, args,
                    "FprimeFilePacketService",
                    (int) (id & 0x7FFFFFFF),
                    systemUser);
            commandingManager.sendCommand(systemUser, pc);
            LOG.info("Downlink START: id={} source={} (on F´) -> bucket {}/{}",
                    id, sourcePath, destBucket.getName(), destPath);
        } catch (Exception e) {
            // Command dispatch failed — mark the transfer failed and
            // clean up the pending map.
            LOG.error("Failed to dispatch FileDownlink command for transfer {}", id, e);
            pendingDownloadsByPath.remove(destPath, transfer);
            transfer.setFailureReason("command dispatch: " + e.getMessage());
            transfer.setState(TransferState.FAILED);
            notifyStateChanged(transfer);
            throw new IOException("Failed to dispatch downlink command", e);
        }

        return transfer;
    }

    @Override
    public List<FileTransfer> getTransfers(FileTransferFilter filter) {
        List<FileTransfer> all = new ArrayList<>(transfers.values());
        if (filter == null) {
            return all;
        }
        List<FileTransfer> result = new ArrayList<>();
        for (FileTransfer ft : all) {
            if (filter.direction != null && ft.getDirection() != filter.direction) {
                continue;
            }
            if (filter.states != null && !filter.states.isEmpty()
                    && !filter.states.contains(ft.getTransferState())) {
                continue;
            }
            if (filter.localEntityId != null
                    && !filter.localEntityId.equals(ft.getLocalEntityId())) {
                continue;
            }
            if (filter.remoteEntityId != null
                    && !filter.remoteEntityId.equals(ft.getRemoteEntityId())) {
                continue;
            }
            result.add(ft);
        }
        if (filter.limit > 0 && result.size() > filter.limit) {
            result = result.subList(0, filter.limit);
        }
        return result;
    }

    @Override
    public FileTransfer getFileTransfer(long id) {
        return transfers.get(id);
    }

    @Override
    public void pause(FileTransfer transfer) {
        throw new UnsupportedOperationException("Pause not supported by Fw::FilePacket");
    }

    @Override
    public void resume(FileTransfer transfer) {
        throw new UnsupportedOperationException("Resume not supported by Fw::FilePacket");
    }

    @Override
    public void cancel(FileTransfer transfer) {
        throw new UnsupportedOperationException(
                "Cancel not supported; Fw::FilePacket transfers are fire-and-forget");
    }

    @Override
    public void registerTransferMonitor(TransferMonitor monitor) {
        transferMonitors.add(monitor);
    }

    @Override
    public void unregisterTransferMonitor(TransferMonitor monitor) {
        transferMonitors.remove(monitor);
    }

    private void notifyStateChanged(FprimeFileTransfer transfer) {
        for (TransferMonitor m : transferMonitors) {
            try {
                m.stateChanged(transfer);
            } catch (Exception e) {
                LOG.warn("Transfer monitor threw", e);
            }
        }
    }

    // ----------------------------------------------------------------------
    // Uplink state machine (runs on uplinkExecutor)
    // ----------------------------------------------------------------------

    private void runUplink(FprimeFileTransfer transfer, byte[] content) {
        try {
            transfer.setStartTime(System.currentTimeMillis());
            LOG.info("Uplink START: id={} bucket={} object={} -> {} ({} bytes)",
                    transfer.getId(), transfer.getBucketName(), transfer.getObjectName(),
                    transfer.getRemotePath(), content.length);

            int seq = 0;
            // Start packet
            sendFilePacket(buildStartPacket(seq, content.length,
                    transfer.getObjectName(), transfer.getRemotePath()));

            // Data×N — update transferredSize after each chunk so the UI
            // progress bar animates.
            for (int offset = 0; offset < content.length; offset += uplinkChunkSize) {
                int len = Math.min(uplinkChunkSize, content.length - offset);
                seq++;
                sendFilePacket(buildDataPacket(seq, offset, content, offset, len));
                transfer.setTransferredSize(offset + len);
                notifyStateChanged(transfer);
            }

            // End
            seq++;
            sendFilePacket(buildEndPacket(seq, cfdpModularChecksum(content)));

            transfer.setTransferredSize(content.length);
            transfer.setState(TransferState.COMPLETED);
            LOG.info("Uplink COMPLETE: id={} object={} ({} bytes)",
                    transfer.getId(), transfer.getObjectName(), content.length);
        } catch (Exception e) {
            LOG.error("Uplink FAILED: id={} object={}",
                    transfer.getId(), transfer.getObjectName(), e);
            transfer.setFailureReason(e.getMessage() != null ? e.getMessage() : e.toString());
            transfer.setState(TransferState.FAILED);
        } finally {
            notifyStateChanged(transfer);
        }
    }

    // ----------------------------------------------------------------------
    // FprimeFileTransfer — in-memory transfer state
    // ----------------------------------------------------------------------

    private static final class FprimeFileTransfer implements FileTransfer {
        private final long id;
        private final String bucketName;
        private final String objectName;
        private final String remotePath;
        private volatile long totalSize;
        private final TransferDirection direction;
        private final long creationTime = System.currentTimeMillis();

        private volatile long startTime;
        private volatile long transferredSize;
        private volatile TransferState state = TransferState.RUNNING;
        private volatile String failureReason;

        FprimeFileTransfer(long id, String bucketName, String objectName,
                           String remotePath, long totalSize, TransferDirection direction) {
            this.id = id;
            this.bucketName = bucketName;
            this.objectName = objectName;
            this.remotePath = remotePath;
            this.totalSize = totalSize;
            this.direction = direction;
        }

        @Override public long getId() { return id; }
        @Override public String getBucketName() { return bucketName; }
        @Override public String getObjectName() { return objectName; }
        @Override public String getRemotePath() { return remotePath; }
        @Override public Long getLocalEntityId() { return GROUND_ENTITY_ID; }
        @Override public Long getRemoteEntityId() { return SPACECRAFT_ENTITY_ID; }
        @Override public TransferDirection getDirection() { return direction; }
        @Override public long getTotalSize() { return totalSize; }
        @Override public long getTransferredSize() { return transferredSize; }
        @Override public TransferState getTransferState() { return state; }
        @Override public boolean isReliable() { return false; }  // F´ FilePacket is fire-and-forget
        @Override public String getFailuredReason() { return failureReason; }
        @Override public long getCreationTime() { return creationTime; }
        @Override public long getStartTime() { return startTime; }
        @Override public String getTransferType() { return "FwFilePacket"; }
        @Override public boolean pausable() { return false; }
        @Override public boolean cancellable() { return false; }

        void setStartTime(long t) { this.startTime = t; }
        void setTransferredSize(long n) { this.transferredSize = n; }
        void setTotalSize(long n) { this.totalSize = n; }
        void setState(TransferState s) { this.state = s; }
        void setFailureReason(String r) { this.failureReason = r; }
    }

    /**
     * Wrap a raw {@code Fw::FilePacket} byte sequence (which already
     * includes the 2-byte ComPacket descriptor prefix) in a CCSDS space
     * packet on the file APID, then hand it to the configured uplink
     * path (YAMCS TC data link or direct UDP fallback).
     */
    private void sendFilePacket(byte[] innerWithDescriptor) throws Exception {
        byte[] spacePacket = buildSpacePacket(innerWithDescriptor, uplinkSpSeq++);
        if (uplinkLink != null) {
            // Step-2 path: hand the packet to YAMCS's TC link. The link
            // will run the FprimeCommandPostprocessor (which patches the
            // CCSDS packet length and sequence count in place), wrap the
            // result in a TC Type-BD frame with CRC16 FECF, and send via
            // its configured UDP socket.
            //
            // The binary we provide is already a complete CCSDS space
            // packet with APID=fileApid, so F´'s fprimeRouter will
            // dispatch it to FileUplink, not CmdDispatcher.
            //
            // We MUST give the PreparedCommand a populated CommandId — the
            // plain byte[] constructor leaves it null, and the TC link's
            // MasterChannelFrameMultiplexer calls getGenerationTime() on
            // the queued command and NPEs otherwise. The command name
            // field is a free-form string as far as sendCommand() is
            // concerned; it only becomes dictionary-validated if the
            // command goes through PreparedCommand.fromTuple(), which we
            // bypass by calling sendCommand() directly.
            CommandId cmdId = CommandId.newBuilder()
                    .setGenerationTime(System.currentTimeMillis())
                    .setOrigin("FprimeFilePacketService")
                    .setSequenceNumber(uplinkSpSeq)
                    .setCommandName("FprimeFilePacketService/uplinkFilePacket")
                    .build();
            PreparedCommand pc = new PreparedCommand(cmdId);
            pc.setBinary(spacePacket);
            uplinkLink.sendCommand(pc);
        } else {
            // Step-1 fallback: build the TC frame ourselves and send
            // directly via UDP.
            byte[] frame = buildTcFrame(spacePacket);
            uplinkSocket.send(new DatagramPacket(frame, frame.length, fprimeAddr, fprimeTcPort));
        }
        // Give F´'s frame accumulator a moment to drain between frames.
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
