package com.example.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.example.network.StarlinkProtocol.field
import com.example.network.StarlinkProtocol.fields
import com.example.network.StarlinkProtocol.singleBytes
import com.example.network.StarlinkProtocol.string

class StarlinkCloudTest {
    private val router = "Router-fixture"
    private val status = field(3004, field(3, field(1, router.toByteArray())))
    private class Store : CloudSessionStore {
        var value: String? = null
        override fun read() = value
        override fun write(cookie: String) { value = cookie }
        override fun clear() { value = null }
    }
    private fun lan(reply: ByteArray = status) = object : RouterControlLink {
        override val localIps = setOf("192.168.1.20")
        override suspend fun exchange(payload: ByteArray): ByteArray {
            assertArrayEquals(StarlinkProtocol.request(StarlinkProtocol.Query.STATUS), payload)
            return reply
        }
    }
    private fun grpc(reply: ByteArray, code: Int = 0): CloudHttpReply {
        val trailer = StarlinkProtocol.frame("grpc-status: $code\r\n".toByteArray()).also { it[0] = 128.toByte() }
        return CloudHttpReply(200, StarlinkProtocol.frame(reply) + trailer, "application/grpc-web+proto")
    }
    private fun rejects(code: String? = null, action: suspend () -> Unit) = runBlocking {
        try { action(); fail("Unexpectedly accepted") } catch (e: Exception) {
            if (code != null) assertEquals(code, errorCode(e))
        }
    }
    @Test fun `authenticated gRPC gateway uses Starlink api2 endpoint`() {
        assertEquals("https://api2.starlink.com/SpaceX.API.Device.Device/Handle", CloudPolicy.HANDLE)
    }

    @Test fun `login policy rejects impersonation cleartext credentials ports and scripts`() {
        assertTrue(CloudPolicy.loginUrlAllowed(CloudPolicy.LOGIN))
        assertTrue(CloudPolicy.loginUrlAllowed("https://auth.starlink.com/path"))
        listOf("http://starlink.com", "https://starlink.com.evil.test", "https://evilstarlink.com", "https://user@starlink.com", "https://starlink.com:444", "javascript:alert(1)", "file:///data/data/session").forEach {
            assertFalse(it, CloudPolicy.loginUrlAllowed(it))
        }
    }
    @Test fun `access cookie alone is accepted as an authenticated session`() {
        assertTrue(CloudPolicy.hasLogin("Starlink.Com.Access.V1=access-only"))
        assertFalse(CloudPolicy.hasLogin("tracking=ignored"))
    }

    @Test fun `session diagnostics never expose cookie values`() {
        val diagnostic = CloudPolicy.sessionDiagnostics(
            listOf(
                CloudPolicy.LOGIN to "Starlink.Com.Sso=secret-sso",
                CloudPolicy.AUTH to "Starlink.Com.Access.V1=secret-access"
            )
        )
        assertTrue(diagnostic.contains("www.starlink.com[SSO=YES ACCESS=NO"))
        assertTrue(diagnostic.contains("api.starlink.com[SSO=NO ACCESS=YES"))
        assertFalse(diagnostic.contains("secret-sso"))
        assertFalse(diagnostic.contains("secret-access"))
    }

    @Test fun `only account session cookies are retained without header injection`() {
        assertEquals("Starlink.Com.Sso=test-session; Starlink.Com.Access.V1=new", CloudPolicy.cookies(
            "tracking=do-not-store; Starlink.Com.Sso=test-session; Starlink.Com.Access.V1=old",
            "Starlink.Com.Access.V1=new; Path=/; HttpOnly; Secure"))
        rejects { CloudPolicy.cookies("Starlink.Com.Sso=test\r\nInjected: yes") }
    }
    @Test fun `successful login verifies exact local router before saving and no mutation occurs`() = runBlocking {
        val store = Store(); var calls = 0
        val http = CloudHttp { url, cookie, body ->
            calls++
            assertTrue(cookie.contains("Starlink.Com.Sso=test-session"))
            if (url == CloudPolicy.AUTH) {
                assertNull(body)
                CloudHttpReply(200, "{}".toByteArray(), "application/json", listOf("Starlink.Com.Access.V1=fresh; Path=/"))
            } else {
                assertEquals(CloudPolicy.HANDLE, url)
                assertTrue(cookie.contains("Starlink.Com.Access.V1=fresh"))
                assertEquals(router, fields(body!!).string(13))
                assertNotNull(fields(body).singleBytes(1004))
                grpc(status)
            }
        }
        StarlinkCloud(store, http).connect("Starlink.Com.Sso=test-session", lan())
        assertEquals(2, calls)
        assertTrue(store.value!!.contains("Access.V1=fresh"))
    }
    @Test fun `link verification keeps blocking cloud and local work off the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val originalName = caller.name
        caller.name = "main"
        try {
            val store = object : CloudSessionStore {
                var value: String? = null
                override fun read(): String? = value
                override fun write(cookie: String) {
                    assertNotEquals("main", Thread.currentThread().name)
                    value = cookie
                }
                override fun clear() { value = null }
            }
            val http = CloudHttp { url, _, _ ->
                assertNotEquals("main", Thread.currentThread().name)
                if (url == CloudPolicy.AUTH) {
                    CloudHttpReply(200, "{}".toByteArray(), "application/json")
                } else {
                    assertEquals(CloudPolicy.HANDLE, url)
                    grpc(status)
                }
            }
            val local = object : RouterControlLink {
                override val localIps = setOf("192.168.1.20")
                override suspend fun exchange(payload: ByteArray): ByteArray {
                    assertNotEquals("main", Thread.currentThread().name)
                    assertArrayEquals(StarlinkProtocol.request(StarlinkProtocol.Query.STATUS), payload)
                    return status
                }
            }

            StarlinkCloud(store, http).connect("Starlink.Com.Sso=test-session", local)
            assertNotNull(store.value)
        } finally {
            caller.name = originalName
        }
    }

    @Test fun `account without router access cannot persist session`() {
        val store = Store()
        val http = CloudHttp { url, _, _ ->
            if (url == CloudPolicy.AUTH) CloudHttpReply(200, byteArrayOf(), "application/json") else grpc(byteArrayOf(), 7)
        }
        rejects("gRPC=7") { StarlinkCloud(store, http).connect("Starlink.Com.Sso=test-session", lan()) }
        assertNull(store.value)
    }
    @Test fun `wrong cloud router cannot persist session`() {
        val store = Store()
        val http = CloudHttp { url, _, _ ->
            if (url == CloudPolicy.AUTH) CloudHttpReply(200, byteArrayOf(), "application/json")
            else grpc(field(3004, field(3, field(1, "Router-other".toByteArray()))))
        }
        rejects("cloud_router_mismatch") { StarlinkCloud(store, http).connect("Starlink.Com.Sso=test-session", lan()) }
        assertNull(store.value)
    }
    @Test fun `expired session stops before gateway and raw response is never exposed`() {
        val store = Store().apply { value = "Starlink.Com.Sso=test-session" }; var calls = 0
        val http = CloudHttp { url, _, _ -> calls++; assertEquals(CloudPolicy.AUTH, url); CloudHttpReply(401, "PRIVATE_RESPONSE".toByteArray(), "application/json") }
        rejects("cloud_http_401") { StarlinkCloud(store, http).exchange(router, field(1004, byteArrayOf())) }
        assertEquals(1, calls)
        assertFalse(controlError(IllegalStateException("cloud_http_401")).contains("PRIVATE_RESPONSE"))
    }
    @Test fun `redirect is a failure not an authenticated followup`() {
        val store = Store(); var calls = 0
        val http = CloudHttp { _, _, _ -> calls++; CloudHttpReply(302, byteArrayOf(), "text/html") }
        rejects("cloud_http_302") { StarlinkCloud(store, http).connect("Starlink.Com.Sso=test-session", lan()) }
        assertEquals(1, calls); assertNull(store.value)
    }
    @Test fun `allowlist prohibits caller target injection and unsupported router commands`() {
        rejects { CloudPolicy.target("ut-dish", field(1004, byteArrayOf())) }
        rejects { CloudPolicy.target(router, field(1011, byteArrayOf())) }
        rejects { CloudPolicy.target(router, field(13, "Router-other".toByteArray()) + field(1004, byteArrayOf())) }
    }
    @Test fun `changing local router invalidates authenticated link`() = runBlocking {
        var localStatus = status
        val local = object : RouterControlLink {
            override val localIps = setOf("192.168.1.20")
            override suspend fun exchange(payload: ByteArray) = localStatus
        }
        val store = Store().apply { value = "Starlink.Com.Sso=test-session" }; var gateway = 0
        val cloud = StarlinkCloud(store, CloudHttp { url, _, _ ->
            if (url == CloudPolicy.AUTH) CloudHttpReply(200, byteArrayOf(), "application/json") else { gateway++; grpc(status) }
        })
        val link = AuthenticatedRouterLink(local, cloud)
        link.exchange(field(1004, byteArrayOf()))
        localStatus = field(3004, field(3, field(1, "Router-other".toByteArray())))
        rejects("cloud_router_mismatch") { link.exchange(field(3001, byteArrayOf())) }
        assertEquals(1, gateway)
    }
}
