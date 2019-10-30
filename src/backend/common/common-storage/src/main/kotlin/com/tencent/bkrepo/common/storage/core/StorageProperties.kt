package com.tencent.bkrepo.common.storage.core

/**
 * 存储基本配置属性
 *
 * @author: carrypan
 * @date: 2019-09-25
 */
abstract class StorageProperties {

        open var clientCache: ClientCache = ClientCache()

        open lateinit var credentials: ClientCredentials

        class ClientCache {
                /**
                 * 客户端缓存开关
                 */
                var enabled: Boolean = false
                /**
                 * 客户端缓存池大小
                 */
                var size: Long = 0L
        }
}
