// ======================================================================
// dump_vectors.cpp
//
// Cross-implementation oracle for the Python Fw::FilePacket codec at
// tools/fprime_filepacket/. Uses F´'s own Fw::FilePacket and CFDP::Checksum
// implementations to encode known inputs, then dumps the resulting bytes
// to disk so the Python codec can be tested against them.
//
// This program is part of the L1 testing layer described in
// docs/file-transfer-integration.md. If the Python decoder produces the
// same fields that this C++ encoder started with, the wire formats agree
// bit-for-bit and the Python codec is trustworthy as the L3 fake spacecraft.
//
// See tools/fprime_filepacket/oracle/build.sh for compile instructions.
// ======================================================================

#include <CFDP/Checksum/Checksum.hpp>
#include <Fw/Buffer/Buffer.hpp>
#include <Fw/FilePacket/FilePacket.hpp>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {

// Write a Buffer's bytes to a file. Returns 0 on success, nonzero on error.
int write_buffer(const std::string& path, const Fw::Buffer& buf) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) {
        std::fprintf(stderr, "ERROR: cannot open %s for writing\n", path.c_str());
        return 1;
    }
    const size_t n = std::fwrite(buf.getData(), 1, buf.getSize(), f);
    std::fclose(f);
    if (n != buf.getSize()) {
        std::fprintf(stderr, "ERROR: short write to %s\n", path.c_str());
        return 2;
    }
    std::printf("  wrote %s (%u bytes)\n",
                path.c_str(),
                static_cast<unsigned>(buf.getSize()));
    return 0;
}

// Helper: build a Fw::Buffer pointing into a stack/static byte array.
Fw::Buffer make_buffer(U8* storage, U32 capacity) {
    Fw::Buffer buf;
    buf.setData(storage);
    buf.setSize(capacity);
    return buf;
}

}  // namespace

int main(int argc, char** argv) {
    const std::string out_dir = (argc > 1) ? argv[1] : "vectors";
    std::printf("Dumping golden vectors to: %s/\n", out_dir.c_str());

    // ----------------------------------------------------------------
    // 1. StartPacket: seq=0, fileSize=100, src="a", dest="b"
    // ----------------------------------------------------------------
    {
        Fw::FilePacket::StartPacket start;
        start.initialize(/*fileSize=*/100, /*src=*/"a", /*dest=*/"b");

        U8 storage[64] = {0};
        Fw::Buffer buf = make_buffer(storage, sizeof(storage));
        if (start.toBuffer(buf) != Fw::FW_SERIALIZE_OK) {
            std::fprintf(stderr, "ERROR: StartPacket toBuffer failed\n");
            return 10;
        }
        if (write_buffer(out_dir + "/start_seq0_size100_a_b.bin", buf) != 0) return 11;
    }

    // ----------------------------------------------------------------
    // 2. DataPacket: seq=1, byteOffset=0, data="hello"
    // ----------------------------------------------------------------
    {
        const U8 payload[] = {'h', 'e', 'l', 'l', 'o'};
        Fw::FilePacket::DataPacket data;
        data.initialize(/*seq=*/1, /*byteOffset=*/0, /*dataSize=*/sizeof(payload), payload);

        U8 storage[64] = {0};
        Fw::Buffer buf = make_buffer(storage, sizeof(storage));
        if (data.toBuffer(buf) != Fw::FW_SERIALIZE_OK) {
            std::fprintf(stderr, "ERROR: DataPacket toBuffer failed\n");
            return 20;
        }
        if (write_buffer(out_dir + "/data_seq1_off0_hello.bin", buf) != 0) return 21;
    }

    // ----------------------------------------------------------------
    // 3. EndPacket: seq=2, with a CFDP checksum computed by F´ over "hello"
    // ----------------------------------------------------------------
    {
        const U8 file_bytes[] = {'h', 'e', 'l', 'l', 'o'};
        CFDP::Checksum cs;
        cs.update(file_bytes, /*offset=*/0, /*length=*/sizeof(file_bytes));
        std::printf("  CFDP checksum of \"hello\" = 0x%08x\n", cs.getValue());

        Fw::FilePacket::EndPacket end;
        end.initialize(/*seq=*/2, cs);

        U8 storage[32] = {0};
        Fw::Buffer buf = make_buffer(storage, sizeof(storage));
        if (end.toBuffer(buf) != Fw::FW_SERIALIZE_OK) {
            std::fprintf(stderr, "ERROR: EndPacket toBuffer failed\n");
            return 30;
        }
        if (write_buffer(out_dir + "/end_seq2_hello_checksum.bin", buf) != 0) return 31;
    }

    // ----------------------------------------------------------------
    // 4. CancelPacket: seq=3
    // ----------------------------------------------------------------
    {
        Fw::FilePacket::CancelPacket cancel;
        cancel.initialize(/*seq=*/3);

        U8 storage[16] = {0};
        Fw::Buffer buf = make_buffer(storage, sizeof(storage));
        if (cancel.toBuffer(buf) != Fw::FW_SERIALIZE_OK) {
            std::fprintf(stderr, "ERROR: CancelPacket toBuffer failed\n");
            return 40;
        }
        if (write_buffer(out_dir + "/cancel_seq3.bin", buf) != 0) return 41;
    }

    std::printf("OK\n");
    return 0;
}
