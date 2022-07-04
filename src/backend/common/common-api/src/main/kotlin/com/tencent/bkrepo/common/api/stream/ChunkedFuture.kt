package com.tencent.bkrepo.common.api.stream

import java.io.InputStream
import java.util.concurrent.Future

interface ChunkedFuture<V> : Future<V> {

    fun getInputStream(v: V): InputStream
}