package com.tencent.bkrepo.rpm.controller

import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.service.util.ResponseBuilder
import com.tencent.bkrepo.rpm.api.RpmWebResource
import com.tencent.bkrepo.rpm.artifact.RpmArtifactInfo
import com.tencent.bkrepo.rpm.servcie.RpmWebService
import org.springframework.web.bind.annotation.RestController

/**
 * rpm 仓库 非标准接口
 */
@RestController
class RpmResourceWebController(
    private val rpmWebService: RpmWebService
) : RpmWebResource {
    override fun deletePackage(rpmArtifactInfo: RpmArtifactInfo, packageKey: String): Response<Void> {
        rpmWebService.delete(rpmArtifactInfo, packageKey, null)
        return ResponseBuilder.success()
    }

    override fun deleteVersion(rpmArtifactInfo: RpmArtifactInfo, packageKey: String, version: String?): Response<Void> {
        rpmWebService.delete(rpmArtifactInfo, packageKey, version)
        return ResponseBuilder.success()
    }

    override fun artifactDetail(rpmArtifactInfo: RpmArtifactInfo, packageKey: String, version: String?): Response<Any?> {
        return ResponseBuilder.success(rpmWebService.artifactDetail(rpmArtifactInfo, packageKey, version))
    }
}
