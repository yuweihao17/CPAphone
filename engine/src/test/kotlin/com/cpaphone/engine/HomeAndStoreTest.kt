package com.cpaphone.engine

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.model.StoreSyncSnapshot
import com.cpaphone.data.store.GitStoreAdapter
import com.cpaphone.data.store.S3ObjectStoreAdapter
import com.cpaphone.engine.home.HomeClusterClient
import com.cpaphone.engine.home.RespWireCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeAndStoreTest {

    @Test
    fun testRespWireCodecEncodingAndDecoding() {
        // 1. 测试命令编码
        val cmdBytes = RespWireCodec.encodeCommand(listOf("SET", "cpa_key", "value123"))
        val cmdStr = String(cmdBytes, Charsets.UTF_8)
        assertTrue(cmdStr.startsWith("*3\r\n"))
        assertTrue(cmdStr.contains("$3\r\nSET\r\n"))
        assertTrue(cmdStr.contains("$7\r\ncpa_key\r\n"))
        assertTrue(cmdStr.contains("$8\r\nvalue123\r\n"))

        // 2. 测试响应解码
        val simpleResp = RespWireCodec.decodeResponse("+OK\r\n")
        assertEquals("OK", (simpleResp as RespWireCodec.RespMessage.SimpleString).value)

        val intResp = RespWireCodec.decodeResponse(":42\r\n")
        assertEquals(42L, (intResp as RespWireCodec.RespMessage.IntegerNumber).value)

        val errResp = RespWireCodec.decodeResponse("-ERR unknown command\r\n")
        assertEquals("ERR unknown command", (errResp as RespWireCodec.RespMessage.Error).message)

        val bulkResp = RespWireCodec.decodeResponse("$6\r\nfoobar\r\n")
        assertEquals("foobar", (bulkResp as RespWireCodec.RespMessage.BulkString).value)
    }

    @Test
    fun testHomeClusterConcurrencyReleaseFlusher() {
        val client = HomeClusterClient()

        // 标记并发释放递增
        val frame1 = client.markReleaseDirty("cred-1", "gpt-4o")
        assertEquals(1L, frame1.releaseSeq)

        val frame2 = client.markReleaseDirty("cred-1", "gpt-4o")
        assertEquals(2L, frame2.releaseSeq)

        // 查询未决释放列表
        val pending1 = client.getPendingReleaseFrames()
        assertEquals(1, pending1.size)
        assertEquals(2L, pending1[0].releaseSeq)

        // ACK 序号 2
        client.ackRelease("cred-1", "gpt-4o", 2L)

        // 再次查询未决，应已完全归还清空
        val pending2 = client.getPendingReleaseFrames()
        assertTrue(pending2.isEmpty())
    }

    @Test
    fun testHomeInFlightSnapshotBinPacking() {
        val client = HomeClusterClient()

        val activeList = listOf(
            Pair("c1", "gpt-4o"),
            Pair("c1", "gpt-4o"),
            Pair("c2", "claude-3-5-sonnet")
        )

        val normalFrame = client.packInFlightSnapshot(activeList, maxGroups = 10)
        assertEquals("part", normalFrame.kind)
        assertEquals(2, normalFrame.aggregateGroupCount)
        assertEquals(2, normalFrame.aggregates.size)

        // 测试超过最大分组限制时降级为 overflow 帧
        val overflowFrame = client.packInFlightSnapshot(activeList, maxGroups = 1)
        assertEquals("overflow", overflowFrame.kind)
    }

    @Test
    fun testMultiStoreAdaptersSync() = runBlocking {
        val cred = AuthCredential(
            id = "test-store-1",
            alias = "S3-Synced-Cred",
            provider = ProviderType.CLAUDE
        )

        // S3 适配器同步验证
        val s3Adapter = S3ObjectStoreAdapter("https://s3.amazonaws.com", "cpa-bucket")
        assertTrue(s3Adapter.saveCredential(cred))
        val s3List = s3Adapter.listCredentials()
        assertEquals(1, s3List.size)
        assertEquals("test-store-1", s3List[0].id)

        // GitStore 适配器同步验证
        val gitAdapter = GitStoreAdapter("git@github.com:example/cpa-backup.git")
        val snapshot = StoreSyncSnapshot(credentials = listOf(cred))
        assertTrue(gitAdapter.pushSnapshot(snapshot))
        val pulled = gitAdapter.pullSnapshot()
        assertEquals(1, pulled.credentials.size)
        assertEquals("test-store-1", pulled.credentials[0].id)
    }
}
