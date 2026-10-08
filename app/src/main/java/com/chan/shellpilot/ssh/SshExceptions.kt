package com.chan.shellpilot.ssh

/** 服务器主机密钥从未见过：需要用户确认指纹后才能继续。 */
class UnknownHostKeyException(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
) : Exception("未知的主机密钥：$host:$port")

/** 服务器主机密钥与上次记录的不一致：可能遭受中间人攻击。 */
class HostKeyChangedException(
    val host: String,
    val port: Int,
    val keyType: String,
    val oldFingerprint: String,
    val newFingerprint: String,
) : Exception("主机密钥已变更：$host:$port")
