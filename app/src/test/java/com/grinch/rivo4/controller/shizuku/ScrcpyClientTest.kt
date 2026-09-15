package com.grinch.rivo4.controller.shizuku

import org.junit.Assert.*
import org.junit.Test
import java.io.*

/** Synthetic scrcpy 4.0 byte fixtures only; no real call audio. */
class ScrcpyClientTest {
    private class Listener : ScrcpyClient.AudioPacketListener {
        var codec: ScrcpyAudioCodec? = null
        val packets = mutableListOf<ScrcpyClient.AudioPacket>()
        var error: String? = null
        var endings = 0
        var writeFailure = false
        override fun onMetadataReceived(codec: ScrcpyAudioCodec) { this.codec = codec }
        override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
            if (writeFailure) throw IOException("Synthetic output failure")
            packets.add(packet)
        }
        override fun onStreamEnd(error: String?) { this.error = error; endings++ }
    }
    private fun fixture(codec: Int = ScrcpyAudioCodec.AAC.codecFourCC, block: DataOutputStream.() -> Unit = {}): ByteArray =
        ByteArrayOutputStream().apply { DataOutputStream(this).apply { writeInt(codec); block() } }.toByteArray()
    private fun DataOutputStream.packet(flags: Long, bytes: ByteArray) { writeLong(flags);writeInt(bytes.size);write(bytes) }
    private fun parse(bytes: ByteArray, listener: Listener = Listener(), codec: ScrcpyAudioCodec = ScrcpyAudioCodec.AAC): Listener {
        ScrcpyClient(ByteArrayInputStream(bytes),codec,listener).use { it.start() };return listener
    }
    @Test fun headerConfigAndFirstAudioPacket() {
        val l=parse(fixture { packet(1L shl 62,byteArrayOf(0x11,0x90.toByte()));packet((1L shl 61) or 1234L,byteArrayOf(1,2,3)) })
        assertEquals(ScrcpyAudioCodec.AAC,l.codec);assertEquals(2,l.packets.size)
        assertTrue(l.packets[0].isConfigPacket);assertFalse(l.packets[1].isConfigPacket)
        assertEquals(1234L,l.packets[1].pts);assertArrayEquals(byteArrayOf(1,2,3),l.packets[1].data)
        assertNotNull(l.error) // EOF is unexpected until the owner has requested stop.
    }
    @Test fun invalidFourCc() { val l=parse(fixture(42));assertNull(l.codec);assertTrue(l.packets.isEmpty());assertNotNull(l.error) }
    @Test fun wrongRequestedCodecRejected() { val l=parse(fixture(ScrcpyAudioCodec.OPUS.codecFourCC));assertNull(l.codec);assertNotNull(l.error) }
    @Test fun firstPacketMustBeConfig() { val l=parse(fixture { packet(0,byteArrayOf(1)) });assertTrue(l.packets.isEmpty());assertNotNull(l.error) }
    @Test fun emptyPipe() { val l=parse(byteArrayOf());assertNull(l.codec);assertNotNull(l.error) }
    @Test fun truncatedFourCc() { val l=parse(byteArrayOf(0,1));assertNull(l.codec);assertNotNull(l.error) }
    @Test fun truncatedFrameHeader() { val l=parse(fixture { writeLong(1L shl 62) });assertTrue(l.packets.isEmpty());assertNotNull(l.error) }
    @Test fun truncatedPayload() { val l=parse(fixture { writeLong(1L shl 62);writeInt(5);writeByte(1) });assertTrue(l.packets.isEmpty());assertNotNull(l.error) }
    @Test fun configAloneIsNotAudio() { val l=parse(fixture { packet(1L shl 62,byteArrayOf(0x11,0x90.toByte())) });assertTrue(l.packets.all { it.isConfigPacket });assertNotNull(l.error) }
    @Test fun sessionPacketRejected() { val l=parse(fixture { packet((1L shl 63) or (1L shl 62),byteArrayOf(1)) });assertTrue(l.packets.isEmpty());assertNotNull(l.error) }
    @Test fun invalidSizesRejectedWithoutAllocation() {
        for (size in listOf(0,-1,1024*1024+1,Int.MAX_VALUE)) {
            val l=parse(fixture { writeLong(1L shl 62);writeInt(size) });assertTrue(l.packets.isEmpty());assertNotNull(l.error)
        }
    }
    @Test fun writeFailurePropagatesToStreamEnd() {
        val l=Listener().apply { writeFailure=true };parse(fixture { packet(1L shl 62,byteArrayOf(1,2)) },l)
        assertNotNull(l.error);assertEquals(1,l.endings)
    }
    @Test fun underlyingReadFailure() {
        val listener=Listener()
        val input=object:InputStream() { override fun read():Int=throw IOException("synthetic socket failure") }
        ScrcpyClient(input,ScrcpyAudioCodec.AAC,listener).use { it.start() };assertNotNull(listener.error)
    }
    @Test fun inputClosedAndRepeatedCloseSafe() {
        var closed=false
        val input=object:ByteArrayInputStream(fixture()) { override fun close() { closed=true;super.close() } }
        val client=ScrcpyClient(input,ScrcpyAudioCodec.AAC,Listener());client.start();assertTrue(closed);client.close();client.close()
    }
    @Test fun stopBeforeReaderStartCannotRestart() {
        val listener=Listener();val client=ScrcpyClient(ByteArrayInputStream(fixture()),ScrcpyAudioCodec.AAC,listener)
        client.close();client.start();assertNull(listener.codec);assertEquals(0,listener.endings)
    }
    @Test fun codecsMatchBaselineContainersAndDefaults() {
        assertEquals(".ogg",ScrcpyAudioCodec.OPUS.containerExtension);assertEquals(16000,ScrcpyAudioCodec.OPUS.defaultBitRate)
        assertEquals(".m4a",ScrcpyAudioCodec.AAC.containerExtension);assertEquals(32000,ScrcpyAudioCodec.AAC.defaultBitRate)
        assertEquals(0x6f707573,ScrcpyAudioCodec.OPUS.codecFourCC);assertEquals(0x00616163,ScrcpyAudioCodec.AAC.codecFourCC)
    }
    @Test fun pinnedArgsAndSocketIdentifiers() {
        repeat(100) {
            val id=ScrcpyConfig.getRandomSocketName();assertEquals(8,id.length);assertTrue(id.toLong(16)<Int.MAX_VALUE)
            val args=ScrcpyConfig.buildServerArgs(id,ScrcpyAudioSource.VOICE_CALL,ScrcpyAudioCodec.OPUS,16000)
            assertEquals("4.0",args.first());assertTrue(args.containsAll(listOf("audio_source=voice-call","audio_codec=opus","audio_bit_rate=16000","send_dummy_byte=false","send_frame_meta=true","send_stream_meta=true","tunnel_forward=false")))
        }
    }
}
