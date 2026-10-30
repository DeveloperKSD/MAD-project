package `in`.yashd.bitchat.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class FileTransferEngineTest {
    private fun engine(): Pair<FileTransferEngine, java.io.File> {
        val dir = Files.createTempDirectory("transfer-test").toFile()
        return FileTransferEngine(dir) to dir
    }

    @Test fun completedOutgoingPayloadCanBeCopiedWithVerification() {
        val (engine, dir) = engine()
        val data = ByteArray(5000) { (it % 251).toByte() }
        val snap = engine.prepareOutgoing(data.inputStream(), TransferProfile.DIRECT_BLE)
        engine.start(snap.manifest.id)
        for (i in 0 until snap.manifest.totalChunks) engine.acknowledge(snap.manifest.id, i)
        engine.confirmRemoteCompletion(snap.manifest.id, snap.manifest.sha256)
        val out = ByteArrayOutputStream()
        engine.copyCompletedTo(snap.manifest.id, out)
        assertTrue(out.toByteArray().contentEquals(data))
        engine.close(); dir.deleteRecursively()
    }

    @Test fun nonCompletedTransfersCannotBeCopied() {
        val (engine, dir) = engine()
        val pending = engine.prepareOutgoing(ByteArray(100).inputStream(), TransferProfile.DIRECT_BLE)
        try {
            engine.copyCompletedTo(pending.manifest.id, ByteArrayOutputStream())
            fail("pending transfer must not be copyable")
        } catch (expected: IllegalStateException) { }
        engine.start(pending.manifest.id)
        try {
            engine.copyCompletedTo(pending.manifest.id, ByteArrayOutputStream())
            fail("in-flight transfer must not be copyable")
        } catch (expected: IllegalStateException) { }
        engine.close(); dir.deleteRecursively()
    }

    @Test fun tamperedOutgoingCompletionIsRejected() {
        val (engine, dir) = engine()
        val snap = engine.prepareOutgoing(ByteArray(100) { 7 }.inputStream(), TransferProfile.DIRECT_BLE)
        engine.start(snap.manifest.id)
        for (i in 0 until snap.manifest.totalChunks) engine.acknowledge(snap.manifest.id, i)
        try {
            engine.confirmRemoteCompletion(snap.manifest.id, "0".repeat(64))
            fail("wrong receiver hash must fail integrity")
        } catch (expected: TransferIntegrityException) { }
        engine.close(); dir.deleteRecursively()
    }
}
