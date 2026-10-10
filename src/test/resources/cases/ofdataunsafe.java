// expect: NC2-ofData
package t;

import org.teavm.interop.Address;

class OfDataUnsafe {
    @Unsafe("a large buffer: ofData avoids a copy; bytes is read after the call, so the GC keeps it in place")
    static int allowed() {
        var bytes = new byte[1 << 20];
        N.os(Address.ofData(bytes));
        return bytes[0];
    }

    static int stillRejected() {
        var bytes = new byte[16];
        N.os(Address.ofData(bytes));
        return bytes[0];
    }
}
