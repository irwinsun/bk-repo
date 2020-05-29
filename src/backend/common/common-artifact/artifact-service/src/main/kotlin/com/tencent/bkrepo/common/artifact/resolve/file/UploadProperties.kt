package com.tencent.bkrepo.common.artifact.resolve.file

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

@ConfigurationProperties("upload")
data class UploadProperties (
    var location: String = System.getProperty("java.io.tmpdir"),
    var maxFileSize: DataSize = DataSize.ofBytes(-1),
    var maxRequestSize: DataSize = DataSize.ofBytes(-1),
    var fileSizeThreshold: DataSize = DataSize.ofBytes(0),
    var isResolveLazily: Boolean = true
)