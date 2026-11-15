package com.kbhatiya999.jarvis.core

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal object B64 {
    private val url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    fun url(b: ByteArray) = url.encode(b)
    fun unurl(s: String) = url.decode(s)
    fun std(b: ByteArray) = Base64.Default.encode(b)
    fun unstd(s: String) = Base64.Default.decode(s)
}
