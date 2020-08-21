package com.tencent.bkrepo.repository.controller

import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.service.util.ResponseBuilder
import com.tencent.bkrepo.repository.api.MetadataClient
import com.tencent.bkrepo.repository.pojo.metadata.MetadataDeleteRequest
import com.tencent.bkrepo.repository.pojo.metadata.MetadataSaveRequest
import com.tencent.bkrepo.repository.service.MetadataService
import org.springframework.web.bind.annotation.RestController

/**
 * 元数据服务接口实现类
 */
@RestController
class MetadataController(
    private val metadataService: MetadataService
) : MetadataClient {

    override fun query(projectId: String, repoName: String, fullPath: String): Response<Map<String, String>> {
        return ResponseBuilder.success(metadataService.query(projectId, repoName, fullPath))
    }

    override fun save(metadataSaveRequest: MetadataSaveRequest): Response<Void> {
        metadataService.save(metadataSaveRequest)
        return ResponseBuilder.success()
    }

    override fun delete(metadataDeleteRequest: MetadataDeleteRequest): Response<Void> {
        metadataService.delete(metadataDeleteRequest)
        return ResponseBuilder.success()
    }
}